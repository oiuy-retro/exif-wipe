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

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Where the original of a shared image lives and how sure we are that it is the right file. */
sealed interface Resolution {
    val shared: Uri
    val name: String

    /**
     * [target] is the file to overwrite.
     * [mediaStore] is true if [target] is a MediaStore item (needs an edit grant on Android 10+).
     * [mustMatch] is true if [target] was inferred and must be compared to the shared copy before
     * it may be changed.
     */
    data class Found(
        override val shared: Uri,
        override val name: String,
        val target: Uri,
        val mediaStore: Boolean,
        val mustMatch: Boolean,
    ) : Resolution

    /** The original may be findable after the user allows photo access. */
    data class NeedsReadPermission(override val shared: Uri, override val name: String) : Resolution

    data class Failed(override val shared: Uri, override val name: String, val reason: String) : Resolution
}

const val NOT_LOCAL_REASON =
    "The sharing app only passed a temporary copy, not the photo stored on this phone, so the original " +
        "cannot be changed. This happens with cloud-only photos and with apps that copy images before sharing. " +
        "Share it from your phone's gallery or file manager instead."

class TargetResolver(private val context: Context) {
    private val cr: ContentResolver = context.contentResolver

    fun resolve(shared: Uri): Resolution {
        val name = displayName(shared) ?: shared.lastPathSegment ?: "image"
        if (shared.scheme != ContentResolver.SCHEME_CONTENT) {
            return Resolution.Failed(shared, name, "Only content links can be changed (got ${shared.scheme}).")
        }

        // 1. A MediaStore item: this is the stored original.
        if (shared.authority == MediaStore.AUTHORITY) {
            val segments = shared.pathSegments
            val isItem = segments.size >= 3 && segments.last().all(Char::isDigit) &&
                segments.getOrNull(1) in setOf("images", "file", "downloads")
            if (isItem && segments.firstOrNull()?.startsWith("picker") != true) {
                return Resolution.Found(shared, name, shared, mediaStore = true, mustMatch = false)
            }
            // Photo picker link: the trailing id is usually the local MediaStore id.
            val id = segments.lastOrNull()?.toLongOrNull()
            if (id != null) {
                return Resolution.Found(shared, name, imageUri(id), mediaStore = true, mustMatch = true)
            }
        }

        // 2. Links that embed a MediaStore link (Google Photos does this for photos stored on the
        //    phone). Checked before any write grant: the sharing app's own link usually serves a
        //    cached copy, and writing to that copy would leave the original unchanged.
        embeddedMediaUri(shared)?.let {
            return Resolution.Found(shared, name, it, mediaStore = true, mustMatch = true)
        }

        // 3. Documents from Android's own storage providers.
        if (DocumentsContract.isDocumentUri(context, shared)) {
            val docId = DocumentsContract.getDocumentId(shared)
            if (shared.authority == MEDIA_DOCUMENTS && docId.startsWith("image:")) {
                docId.removePrefix("image:").toLongOrNull()?.let {
                    return Resolution.Found(shared, name, imageUri(it), mediaStore = true, mustMatch = false)
                }
            }
            if (shared.authority in LOCAL_STORAGE_DOCUMENTS && canWrite(shared)) {
                return Resolution.Found(shared, name, shared, mediaStore = false, mustMatch = false)
            }
        }

        // 4. Look the file up in MediaStore by name and size, then confirm it is byte-identical.
        //    Links from other apps are never written directly, even if the app allows writing,
        //    because they may point at a copy rather than the stored photo.
        if (!hasReadPermission(context)) return Resolution.NeedsReadPermission(shared, name)
        return lookUp(shared, name) ?: Resolution.Failed(shared, name, NOT_LOCAL_REASON)
    }

    private fun canWrite(uri: Uri): Boolean =
        context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    private fun lookUp(shared: Uri, name: String): Resolution? {
        val size = size(shared) ?: return null
        val candidates = ArrayList<Uri>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        cr.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.SIZE} = ?",
            arrayOf(name, size.toString()),
            null,
        )?.use { c -> while (c.moveToNext() && candidates.size < 20) candidates += imageUri(c.getLong(0)) }
        if (candidates.isEmpty()) return null
        val sharedHash = sha256(shared) ?: return null
        val match = candidates.firstOrNull { sha256(it)?.contentEquals(sharedHash) == true } ?: return null
        return Resolution.Found(shared, name, match, mediaStore = true, mustMatch = false)
    }

    /**
     * Confirms that an inferred [Resolution.Found.target] holds the same photo as the shared copy.
     * Needs read access to the target (an edit grant includes it). Byte-identical files always
     * match. A link that explicitly referenced the target also matches if the sharing app handed
     * over a re-saved copy with the same name and pixel size.
     */
    fun confirmMatch(found: Resolution.Found): Boolean {
        val a = sha256(found.shared) ?: return false
        val b = sha256(found.target) ?: return false
        if (a.contentEquals(b)) return true
        val targetName = queryString(found.target, MediaStore.MediaColumns.DISPLAY_NAME)
        val sharedName = displayName(found.shared)
        if (sharedName != null && targetName != null && sharedName != targetName) return false
        val da = dimensions(found.shared) ?: return false
        val db = dimensions(found.target) ?: return false
        return da == db
    }

    private fun imageUri(id: Long): Uri =
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(id.toString()).build()

    private fun embeddedMediaUri(uri: Uri): Uri? {
        val decoded = Uri.decode(uri.toString())
        val match = Regex("content://media/[A-Za-z0-9_\\-]+/(images/media|file)/\\d+").find(decoded, 1) ?: return null
        return Uri.parse(match.value)
    }

    fun displayName(uri: Uri): String? = queryString(uri, OpenableColumns.DISPLAY_NAME)

    private fun size(uri: Uri): Long? = try {
        cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun queryString(uri: Uri, column: String): String? = try {
        cr.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun dimensions(uri: Uri): Pair<Int, Int>? = try {
        cr.openInputStream(uri)?.use { s ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(s, null, o)
            if (o.outWidth > 0) o.outWidth to o.outHeight else null
        }
    } catch (_: Exception) {
        null
    }

    private fun sha256(uri: Uri): ByteArray? = try {
        cr.openInputStream(uri)?.use { digest(it) }
    } catch (_: SecurityException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun digest(s: InputStream): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest()
    }

    companion object {
        private const val MEDIA_DOCUMENTS = "com.android.providers.media.documents"
        private val LOCAL_STORAGE_DOCUMENTS = setOf(
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents",
        )

        fun readPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        fun hasReadPermission(context: Context): Boolean =
            readPermissions().any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
