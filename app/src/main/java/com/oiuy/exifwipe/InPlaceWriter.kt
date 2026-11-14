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
import android.content.ContentValues
import android.os.Build
import android.provider.DocumentsContract
import com.oiuy.exifwipe.meta.FileNames
import com.oiuy.exifwipe.meta.IccMode
import com.oiuy.exifwipe.meta.MetadataPlanner
import com.oiuy.exifwipe.meta.WipeOptions
import com.oiuy.exifwipe.strip.MetadataStripper
import com.oiuy.exifwipe.strip.OutputMeta
import com.oiuy.exifwipe.strip.OutputVerifier
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class WipeOutcome { CLEANED, ALREADY_CLEAN }

/** Result of one photo: what happened, the new name if it was renamed, and a non-fatal warning. */
class WipeResult(val outcome: WipeOutcome, val newName: String? = null, val warning: String? = null)

/** Thrown when the stored original could not be restored after a failed write. */
class RestoreFailedException(message: String) : IOException(message)

/**
 * Overwrites one image with its metadata-free version.
 *
 * Order of operations, so a failure never leaves a damaged original:
 *  1. read the whole original into memory and rewrite its metadata there (see [WipeOptions]),
 *  2. check the new copy contains exactly the planned metadata and decodes to the same pixels,
 *  3. keep a backup of the original in the app's private storage,
 *  4. truncate and write the original, then read it back and compare,
 *  5. on any failure after truncation, write the backup back.
 * SecurityExceptions raised while opening the file (before anything is written) are passed on so
 * the caller can ask the user for permission. Renaming (if chosen) happens only after the new
 * contents were written and verified; a failed rename is reported as a warning.
 */
class InPlaceWriter(private val context: Context) {
    private val cr: ContentResolver = context.contentResolver

    private val random = SecureRandom()

    fun wipe(uri: Uri, options: WipeOptions = WipeOptions.DEFAULT): WipeResult {
        val original = readAll(uri)
        val planner = MetadataPlanner(options, random, thumbnailMaker = { thumbnail(original) })
        val result = MetadataStripper.process(original, options, planner)
        val meta = result.meta!!
        if (!result.changed) {
            if (meta.fileName == null) return WipeResult(WipeOutcome.ALREADY_CLEAN)
            return renameOnly(uri, meta.fileName)
        }
        // With the original colour profile kept, both files must decode to the same colours. With a
        // removed or replaced profile, colours are allowed to change, so the stored pixel values are
        // compared with no profile applied.
        val same = if (options.icc == IccMode.KEEP) samePixels(original, result.output)
        else samePixels(bare(original), bare(result.output))
        if (!same) {
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
                // new version and passes the safety check against the planned metadata again.
                val written = readAll(uri)
                if (!written.contentEquals(result.output)) throw IOException("The file read back after writing did not match")
                OutputVerifier.verify(result.format, written, meta)
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
        var newName: String? = null
        var warning: String? = null
        if (meta.fileName != null) {
            try {
                newName = rename(uri, meta.fileName)
            } catch (e: Exception) {
                warning = "Metadata was changed, but the file could not be renamed (${e.message ?: e.javaClass.simpleName})."
            }
        }
        rescan(uri)
        return WipeResult(WipeOutcome.CLEANED, newName, warning)
    }

    private fun renameOnly(uri: Uri, name: String): WipeResult = try {
        val n = rename(uri, name)
        rescan(uri)
        WipeResult(WipeOutcome.CLEANED, n)
    } catch (e: Exception) {
        WipeResult(WipeOutcome.ALREADY_CLEAN, warning = "The file could not be renamed (${e.message ?: e.javaClass.simpleName}).")
    }

    /** Same image without any metadata or colour profile, for comparing stored pixel values. */
    private fun bare(b: ByteArray): ByteArray = MetadataStripper.rewrite(b) { OutputMeta() }.output

    /** Small JPEG preview for a new EXIF thumbnail (160 px on the long side), or null. */
    private fun thumbnail(b: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 320) sample *= 2
        val full = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = 160f / maxOf(full.width, full.height)
        val small = Bitmap.createScaledBitmap(full, maxOf(1, (full.width * scale).toInt()), maxOf(1, (full.height * scale).toInt()), true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (small !== full) small.recycle()
        full.recycle()
        // Remove the JFIF header the encoder writes; a thumbnail carries no metadata of its own.
        return runCatching { com.oiuy.exifwipe.strip.JpegStripper.strip(out.toByteArray()) }.getOrNull()
    }

    /**
     * Renames the file to [wanted], or to another name of the same style if that one is taken.
     * Returns the name the file has afterwards.
     */
    @Suppress("DEPRECATION")
    private fun rename(uri: Uri, wanted: String): String {
        if (uri.authority == MediaStore.AUTHORITY) {
            if (Build.VERSION.SDK_INT >= 29) {
                val (relPath, current) = cr.query(
                    uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null,
                )?.use { c -> if (c.moveToFirst()) (c.getString(0) to c.getString(1)) else null }
                    ?: throw IOException("the photo was not found in the media library")
                val volume = MediaStore.getVolumeName(uri)
                val files = MediaStore.Files.getContentUri(volume)
                for (attempt in 0..20) {
                    val name = if (attempt == 0) wanted else FileNames.alternative(wanted, random, attempt)
                    if (name == current) return name
                    val taken = cr.query(
                        files, arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                        arrayOf(relPath ?: "", name), null,
                    )?.use { it.count > 0 } ?: false
                    if (taken) continue
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
                    if (cr.update(uri, values, null, null) < 1) throw IOException("the media library refused the new name")
                    return cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: name
                }
                throw IOException("no free name found")
            }
            val path = cr.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: throw IOException("file path unknown")
            val file = File(path)
            for (attempt in 0..20) {
                val name = if (attempt == 0) wanted else FileNames.alternative(wanted, random, attempt)
                if (name == file.name) return name
                val target = File(file.parentFile, name)
                if (target.exists()) continue
                if (!file.renameTo(target)) throw IOException("the file system refused the new name")
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DATA, target.absolutePath)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                }
                cr.update(uri, values, null, null)
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath, target.absolutePath), null, null)
                return name
            }
            throw IOException("no free name found")
        }
        if (DocumentsContract.isDocumentUri(context, uri)) {
            // Document providers pick their own name if this one is taken (usually "name (1).jpg").
            val newUri = DocumentsContract.renameDocument(cr, uri, wanted) ?: uri
            return cr.query(newUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: wanted
        }
        throw IOException("this kind of link cannot be renamed")
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
