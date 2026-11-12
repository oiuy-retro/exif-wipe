/*
 * Exif Wipe - removes metadata from photos in place on Android.
 * Copyright (C) 2026 oiuy-retro
 *
 * Adapted from the JpegScrambler and PngScrambler classes in Scrambled Exif
 * (app/src/main/java/com/jarsilio/android/scrambledeggsif/utils/ExifScrambler.kt, v1.8.3,
 * https://gitlab.com/juanitobananas/scrambled-exif):
 *   Copyright (c) 2019-2024 Juan García Basilio
 *   Copyright (c) 2019 Eric Cochran
 * Scrambled Exif is licensed under the GNU General Public License, version 3 or later.
 * Changes: ported from okio streams to in-memory byte arrays, kept the colour-related
 * segments/chunks needed to display the image identically, kept a minimal Orientation-only
 * EXIF block, walked every scan of progressive JPEGs, and made malformed input fail instead of
 * being skipped over.
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

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Removes metadata chunks from a PNG without touching the compressed image data.
 *
 * Only chunks needed to draw the image the same way are kept: the critical chunks, transparency,
 * colour space information, background colour, and the APNG animation chunks. Text (tEXt, zTXt,
 * iTXt, which also carry XMP), eXIf, tIME, pHYs and every unknown or private chunk are dropped, as
 * is anything after IEND. A non-normal EXIF Orientation is kept as a minimal eXIf chunk.
 */
object PngStripper {
    internal val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    internal val KEEP = setOf(
        "IHDR", "PLTE", "IDAT", "IEND",
        "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "cICP", "mDCV", "cLLI", "bKGD",
        "acTL", "fcTL", "fdAT",
    )

    private class Chunk(val name: String, val start: Int, val dataStart: Int, val dataLength: Int, val end: Int)

    fun strip(b: ByteArray): ByteArray {
        if (!b.hasPrefixAt(0, b.size, SIGNATURE)) throw StripException("Not a PNG file")

        val chunks = ArrayList<Chunk>()
        var pos = SIGNATURE.size
        var sawEnd = false
        while (pos < b.size) {
            if (pos + 12 > b.size) throw StripException("PNG chunk header is truncated")
            val length = b.u32be(pos)
            if (length > Int.MAX_VALUE || pos + 12 + length > b.size) throw StripException("PNG chunk runs past the end of the file")
            val name = b.ascii(pos + 4, 4)
            if (!name.all { it in 'a'..'z' || it in 'A'..'Z' }) throw StripException("Invalid PNG chunk name")
            val chunk = Chunk(name, pos, pos + 8, length.toInt(), pos + 12 + length.toInt())
            chunks += chunk
            pos = chunk.end
            if (name == "IEND") {
                sawEnd = true
                break
            }
        }
        if (!sawEnd) throw StripException("PNG has no IEND chunk (file may be truncated)")
        if (chunks.first().name != "IHDR") throw StripException("PNG does not start with IHDR")
        if (chunks.none { it.name == "IDAT" }) throw StripException("PNG contains no image data")

        var orientation: Int? = null
        for (c in chunks) {
            if (c.name == "eXIf") {
                var s = c.dataStart
                if (b.hasPrefixAt(s, s + c.dataLength, JpegStripper.EXIF_ID)) s += JpegStripper.EXIF_ID.size
                orientation = Orientation.read(b, s, c.dataStart + c.dataLength)
                break
            }
        }

        val out = ByteArrayOutputStream(b.size)
        out.write(SIGNATURE)
        var wroteOrientation = false
        for (c in chunks) {
            if (c.name !in KEEP) continue
            if (!crcMatches(b, c)) throw StripException("PNG ${c.name} chunk is corrupt (CRC mismatch)")
            if (c.name == "IDAT" && !wroteOrientation && Orientation.needsTag(orientation)) {
                out.write(chunk("eXIf", Orientation.minimalTiff(orientation!!)))
                wroteOrientation = true
            }
            out.write(b, c.start, c.end - c.start)
        }
        return out.toByteArray()
    }

    private fun crcMatches(b: ByteArray, c: Chunk): Boolean {
        val crc = CRC32()
        crc.update(b, c.start + 4, 4 + c.dataLength)
        return crc.value == b.u32be(c.dataStart + c.dataLength)
    }

    internal fun chunk(name: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(12 + data.size)
        val len = data.size
        out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        val typeAndData = latin1(name) + data
        out.write(typeAndData)
        val crc = CRC32().apply { update(typeAndData) }.value
        out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        return out.toByteArray()
    }
}
