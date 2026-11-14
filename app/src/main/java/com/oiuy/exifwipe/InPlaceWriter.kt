/*
 * Exif Wipe - removes metadata from photos in place on Android.
 * Copyright (C) 2026 oiuy-retro
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.oiuy.exifwipe

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.oiuy.exifwipe.strip.MetadataStripper
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class WipeOutcome { CLEANED, ALREADY_CLEAN }

/** Thrown when the stored original could not be restored after a failed write. */
class RestoreFailedException(message: String) : IOException(message)

/**
 * Overwrites one image with its metadata-free version.
 *
 * Order of operations, so a failure never leaves a damaged original:
 *  1. read the whole original into memory and strip it there,
 *  2. check the stripped copy is structurally clean and decodes to the same pixels,
 *  3. keep a backup of the original in the app's private storage,
 *  4. truncate and write the original, then read it back and compare,
 *  5. on any failure after truncation, write the backup back.
 * SecurityExceptions raised while opening the file (before anything is written) are passed on so
 * the caller can ask the user for permission.
 */
class InPlaceWriter(private val context: Context) {
    private val cr: ContentResolver = context.contentResolver

    fun wipe(uri: Uri): WipeOutcome {
        val original = readAll(uri)
        val result = MetadataStripper.strip(original)
        if (!result.changed) return WipeOutcome.ALREADY_CLEAN
        if (!samePixels(original, result.output)) {
            throw IOException("Safety check failed: the cleaned image would not look the same, so the original was left unchanged")
        }

        val backup = File(context.cacheDir, "backup-${UUID.randomUUID()}")
        backup.writeBytes(original)
        try {
            var opened = false
            try {
                openForOverwrite(uri).use { pfd ->
                    opened = true
                    FileOutputStream(pfd.fileDescriptor).use { out ->
                        out.write(result.output)
                        out.flush()
                        pfd.fileDescriptor.sync()
                    }
                }
                // Success is only reported if the stored file, read back from storage, is exactly the
                // cleaned version and stripping it again finds nothing left to remove.
                val written = readAll(uri)
                if (!written.contentEquals(result.output)) throw IOException("The file read back after writing did not match")
                if (MetadataStripper.strip(written).changed) throw IOException("Metadata was still found after writing")
            } catch (e: Exception) {
                if (!opened) throw e // nothing was written
                try {
                    openForOverwrite(uri).use { pfd ->
                        FileOutputStream(pfd.fileDescriptor).use { out ->
                            out.write(original)
                            out.flush()
                            pfd.fileDescriptor.sync()
                        }
                    }
                    if (!readAll(uri).contentEquals(original)) throw IOException("restore mismatch")
                } catch (restoreError: Exception) {
                    val kept = File(context.getExternalFilesDir(null) ?: context.filesDir, "unrestored-${backup.name}")
                    backup.copyTo(kept, overwrite = true)
                    throw RestoreFailedException(
                        "Writing failed and the original could not be put back. A copy of the original was saved to $kept",
                    )
                }
                throw IOException("Writing failed (${e.message}); the original was restored", e)
            }
        } finally {
            backup.delete()
        }
        rescan(uri)
        return WipeOutcome.CLEANED
    }

    private fun readAll(uri: Uri): ByteArray {
        val size = try {
            cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (_: Exception) {
            -1L
        }
        if (size > MAX_BYTES) throw IOException("File is too large (${size / (1024 * 1024)} MB)")
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw FileNotFoundException("Cannot open $uri")
        if (bytes.size > MAX_BYTES) throw IOException("File is too large")
        return bytes
    }

    private fun openForOverwrite(uri: Uri): ParcelFileDescriptor {
        val modes = listOf("rwt", "wt")
        var last: Exception? = null
        for (mode in modes) {
            try {
                return cr.openFileDescriptor(uri, mode) ?: throw FileNotFoundException("Provider returned no file")
            } catch (e: SecurityException) {
                throw e
            } catch (e: IllegalArgumentException) {
                last = e
            } catch (e: UnsupportedOperationException) {
                last = e
            } catch (e: FileNotFoundException) {
                last = e
            }
        }
        throw IOException("This file cannot be opened for writing (${last?.message})", last)
    }

    /**
     * Decodes both versions at a reduced size and requires identical pixels. Compressed image
     * data is copied unchanged and the rebuilt colour profile keeps the colour data, so any
     * difference means something went wrong.
     */
    private fun samePixels(a: ByteArray, b: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(a, 0, a.size, bounds)
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            // Convert both through their own colour profile to sRGB: the rebuilt profile must
            // produce exactly the same colours as the original one.
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val ba = BitmapFactory.decodeByteArray(a, 0, a.size, opts) ?: return false
        val bb = BitmapFactory.decodeByteArray(b, 0, b.size, opts) ?: run { ba.recycle(); return false }
        val same = ba.sameAs(bb)
        ba.recycle()
        bb.recycle()
        return same
    }

    /**
     * Asks the media scanner to re-read the file so MediaStore and gallery apps pick up the new
     * contents. Android 11+ also rescans on its own when the file is closed; this makes it happen
     * on every version and waits briefly for it to finish.
     */
    @Suppress("DEPRECATION")
    private fun rescan(uri: Uri) {
        try {
            val path = if (uri.authority == MediaStore.AUTHORITY) {
                cr.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
                }
            } else {
                null
            }
            if (path != null) {
                val done = CountDownLatch(1)
                MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ -> done.countDown() }
                done.await(5, TimeUnit.SECONDS)
            }
            cr.notifyChange(uri, null)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val MAX_BYTES = 200L * 1024 * 1024
    }
}
