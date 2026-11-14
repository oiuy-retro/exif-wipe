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

package com.oiuy.exifwipe.strip

import java.io.IOException

/** Thrown when an image cannot be stripped safely. The original file is never touched in that case. */
open class StripException(message: String) : IOException(message)

/** Thrown for image formats this app does not rewrite. */
class UnsupportedFormatException(val format: ImageFormat) :
    StripException("${format.label} images are not supported")

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)

internal fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

internal fun ByteArray.u32be(i: Int): Long =
    (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

internal fun ByteArray.u32le(i: Int): Long =
    u8(i).toLong() or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)

internal fun ByteArray.ascii(start: Int, length: Int): String =
    if (start < 0 || start + length > size) "" else String(this, start, length, Charsets.ISO_8859_1)

/** True if [prefix] appears at [start] and fits before [end]. */
internal fun ByteArray.hasPrefixAt(start: Int, end: Int, prefix: ByteArray): Boolean {
    if (start < 0 || start + prefix.size > end || end > size) return false
    for (k in prefix.indices) if (this[start + k] != prefix[k]) return false
    return true
}

internal fun ByteArray.indexOf(needle: ByteArray, from: Int = 0): Int {
    if (needle.isEmpty()) return from
    val first = needle[0]
    var i = from
    val last = size - needle.size
    while (i <= last) {
        if (this[i] == first) {
            var k = 1
            while (k < needle.size && this[i + k] == needle[k]) k++
            if (k == needle.size) return i
        }
        i++
    }
    return -1
}

internal fun latin1(s: String): ByteArray = s.toByteArray(Charsets.ISO_8859_1)
