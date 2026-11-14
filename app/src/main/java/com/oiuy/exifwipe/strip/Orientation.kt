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

/**
 * Reads the EXIF Orientation tag and builds a replacement EXIF block that holds nothing else.
 *
 * Phones usually save a portrait photo as landscape pixels plus an Orientation tag. Dropping the
 * tag would make such photos display sideways, and rotating the pixels would mean re-encoding or a
 * lossless-rotation library. Instead the stripped file keeps a 26-byte TIFF block whose only entry
 * is Orientation. It carries no location, camera, time, or other identifying data.
 */
object Orientation {
    private const val TAG_ORIENTATION = 0x0112
    private const val TYPE_SHORT = 3

    /** Orientation (1..8) from a TIFF block in [b] spanning [start] until [end], or null. */
    fun read(b: ByteArray, start: Int, end: Int): Int? {
        if (start < 0 || end > b.size || end - start < 8) return null
        val little = when (b.ascii(start, 2)) {
            "II" -> true
            "MM" -> false
            else -> return null
        }
        fun u16(p: Int) = if (little) b.u16le(p) else b.u16be(p)
        fun u32(p: Int) = if (little) b.u32le(p) else b.u32be(p)
        if (u16(start + 2) != 42) return null
        val ifdOffset = u32(start + 4)
        if (ifdOffset < 8 || start + ifdOffset + 2 > end) return null
        val ifd = (start + ifdOffset).toInt()
        val count = u16(ifd)
        for (i in 0 until count) {
            val e = ifd + 2 + 12 * i
            if (e + 12 > end) return null
            if (u16(e) == TAG_ORIENTATION) {
                if (u16(e + 2) != TYPE_SHORT) return null
                val value = u16(e + 8)
                return if (value in 1..8) value else null
            }
        }
        return null
    }

    /** True if [value] is an orientation that changes how the image is displayed. */
    fun needsTag(value: Int?): Boolean = value != null && value in 2..8

    /** Big-endian TIFF block with a single IFD0 entry: Orientation = [value]. */
    fun minimalTiff(value: Int): ByteArray {
        require(value in 1..8)
        return byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, // "MM", 42
            0x00, 0x00, 0x00, 0x08, // IFD0 at offset 8
            0x00, 0x01, // one entry
            0x01, 0x12, 0x00, 0x03, // tag 0x0112, type SHORT
            0x00, 0x00, 0x00, 0x01, // count 1
            0x00, value.toByte(), 0x00, 0x00, // value, left-justified
            0x00, 0x00, 0x00, 0x00, // no next IFD
        )
    }

    const val MINIMAL_TIFF_SIZE = 26

    /** True if the bytes are exactly a [minimalTiff] block for an orientation 1..8. */
    fun isMinimalTiff(b: ByteArray, start: Int, length: Int): Boolean {
        if (length != MINIMAL_TIFF_SIZE || start < 0 || start + length > b.size) return false
        val value = b.u8(start + 19)
        if (value !in 1..8) return false
        val expected = minimalTiff(value)
        for (k in expected.indices) if (b[start + k] != expected[k]) return false
        return true
    }
}
