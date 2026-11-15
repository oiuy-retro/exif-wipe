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
import com.oiuy.exifwipe.strip.ReadBack
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
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
        // Read through a read-write link if possible: Android hides (zeroes) the GPS values of other
        // apps' photos only on read-only links, so this gives the real original. A read-only
        // fallback is used for links that cannot be opened for writing.
        val (original, originalPath) = readTarget(uri)
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
        val outputGps = ReadBack.gpsValueRanges(result.output, meta.exif)
        try {
            var opened = false
            try {
                openForOverwrite(uri).use { pfd ->
                    opened = true
                    val truncated = writeFully(pfd, result.output)
                    // First check through the descriptor that was written to.
                    readFd(pfd)?.let { check(result.output, it, outputGps, "same read-write descriptor", truncated) }
                }
                // Success is only reported if the stored file, opened again and read back from
                // storage, is exactly the new version and passes the safety check against the
                // planned metadata again.
                val (written, path) = readTarget(uri)
                val same = check(result.output, written, outputGps, "reopened ($path)", null)
                OutputVerifier.verify(result.format, if (same) written else result.output, meta)
            } catch (e: Exception) {
                if (!opened) throw e // nothing was written
                try {
                    openForOverwrite(uri).use { pfd -> writeFully(pfd, original) }
                    val (restored, path) = readTarget(uri)
                    val r = ReadBack.compare(original, restored, ReadBack.gpsValueRangesInFile(original))
                    if (r is ReadBack.Result.Different) throw IOException("restore mismatch via $path: ${r.details}")
                } catch (restoreError: Exception) {
                    val kept = File(context.getExternalFilesDir(null) ?: context.filesDir, "unrestored-${backup.name}")
                    backup.copyTo(kept, overwrite = true)
                    throw RestoreFailedException(
                        "Writing failed and the original could not be put back. A copy of the original was saved to $kept",
                    )
                }
                val note = if (originalPath != MODE_RW) " (the original was read through a read-only link, so Android may have hidden its location values)" else ""
                throw IOException("Writing failed (${e.message}); the original was restored$note", e)
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

    /**
     * Compares [actual] (read back through [path]) with [expected]. Returns true if identical,
     * false if the only difference is Android's location redaction (zeros in the GPS values, which
     * the file itself does not contain), and throws with a diagnostic description otherwise.
     */
    private fun check(expected: ByteArray, actual: ByteArray, gps: List<IntRange>, path: String, truncated: Boolean?): Boolean =
        when (val r = ReadBack.compare(expected, actual, gps)) {
            ReadBack.Result.Same -> true
            is ReadBack.Result.OnlyLocationRedacted -> false
            is ReadBack.Result.Different -> throw IOException(
                "The file read back after writing did not match [read path: $path" +
                    (if (truncated == false) "; explicit truncate not supported" else "") + "; ${r.details}]",
            )
        }

    /**
     * Reads the whole file, preferring a read-write link (see [wipe]). Returns the bytes and the
     * mode that was used.
     */
    private fun readTarget(uri: Uri): Pair<ByteArray, String> {
        var last: Exception? = null
        for (mode in listOf(MODE_RW, "r")) {
            try {
                val pfd = cr.openFileDescriptor(uri, mode) ?: continue
                pfd.use { fd ->
                    val bytes = readFd(fd) ?: return@use null
                    return bytes to mode
                }
            } catch (e: Exception) {
                if (e is IOException && e.message?.startsWith("File is too large") == true) throw e
                last = e
            }
        }
        // Last resort: a plain stream (some providers only serve pipes).
        val bytes = try {
            cr.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            throw last ?: e
        } ?: throw (last ?: FileNotFoundException("Cannot open $uri"))
        if (bytes.size > MAX_BYTES) throw IOException("File is too large")
        return bytes to "stream"
    }

    /** Reads the whole file behind [pfd] from offset 0 without moving or closing it; null if not readable. */
    private fun readFd(pfd: ParcelFileDescriptor): ByteArray? {
        val ch = try {
            FileInputStream(pfd.fileDescriptor).channel
        } catch (_: Exception) {
            return null
        }
        return try {
            val size = ch.size()
            if (size > MAX_BYTES) throw IOException("File is too large (${size / (1024 * 1024)} MB)")
            val buf = ByteBuffer.allocate(size.toInt())
            var pos = 0L
            while (buf.hasRemaining()) {
                val n = ch.read(buf, pos)
                if (n < 0) break
                pos += n
            }
            // A file that grew while reading would be cut off here; read the rest so a size change shows.
            val extra = ByteArrayOutputStream()
            val more = ByteBuffer.allocate(64 * 1024)
            while (true) {
                more.clear()
                val n = ch.read(more, pos)
                if (n <= 0) break
                extra.write(more.array(), 0, n)
                pos += n
            }
            val head = if (buf.position() == buf.capacity()) buf.array() else buf.array().copyOf(buf.position())
            if (extra.size() == 0) head else head + extra.toByteArray()
        } catch (e: IOException) {
            if (e.message?.startsWith("File is too large") == true) throw e
            null
        } catch (_: java.nio.channels.NonReadableChannelException) {
            null
        }
    }

    /**
     * Writes [bytes] from offset 0, then explicitly truncates the file to their length (some
     * storage layers do not honour the truncate flag of the open mode), flushes and syncs.
     * Returns whether the explicit truncation worked (null if the descriptor is not seekable).
     */
    private fun writeFully(pfd: ParcelFileDescriptor, bytes: ByteArray): Boolean? {
        val out = FileOutputStream(pfd.fileDescriptor)
        val ch = out.channel
        val seekable = try {
            ch.position(0)
            true
        } catch (_: IOException) {
            false
        }
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) ch.write(buf)
        var truncated: Boolean? = null
        if (seekable) {
            truncated = try {
                if (ch.size() > bytes.size) ch.truncate(bytes.size.toLong())
                ch.size() == bytes.size.toLong()
            } catch (_: IOException) {
                false
            }
        }
        out.flush()
        try {
            ch.force(true)
        } catch (_: IOException) {
        }
        try {
            pfd.fileDescriptor.sync()
        } catch (_: Exception) {
        }
        return truncated
    }

    private fun openForOverwrite(uri: Uri): ParcelFileDescriptor {
        val modes = listOf("rwt", "wt", MODE_RW)
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
        private const val MODE_RW = "rw"
    }
}
