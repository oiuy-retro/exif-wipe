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

import java.util.zip.CRC32

/**
 * Independent check run on every stripped file before it may replace the original.
 *
 * It re-parses the output from scratch and fails unless the file is well formed and contains only
 * the segments or chunks the strippers are allowed to keep. A second pass looks for well-known
 * metadata signatures anywhere in the file. The idea of a structural post-check plus a signature
 * scan comes from Scrambler (https://gitlab.com/juanitobananas/scrambler) by Juan García Basilio;
 * this implementation was written separately.
 */
object OutputVerifier {
    private val forbiddenSignatures = listOf(
        "http://ns.adobe.com/", // XMP
        "<x:xmpmeta", // XMP packet
        "Photoshop 3.0", // IPTC / Photoshop resources
    ).map(::latin1)

    fun verify(format: ImageFormat, b: ByteArray) {
        val problem = when (format) {
            ImageFormat.JPEG -> jpegProblem(b)
            ImageFormat.PNG -> pngProblem(b)
            ImageFormat.WEBP -> webpProblem(b)
            else -> "unsupported format"
        } ?: signatureProblem(b)
        if (problem != null) throw StripException("Safety check failed: $problem")
    }

    private fun signatureProblem(b: ByteArray): String? {
        for (sig in forbiddenSignatures) {
            if (b.indexOf(sig) >= 0) return "metadata signature \"${String(sig, Charsets.ISO_8859_1)}\" is still present"
        }
        return null
    }

    private fun jpegProblem(b: ByteArray): String? {
        if (b.size < 4 || b.u8(0) != 0xFF || b.u8(1) != 0xD8) return "missing JPEG start marker"
        var pos = 2
        var scans = 0
        var frames = 0
        while (true) {
            if (pos + 1 >= b.size || b.u8(pos) != 0xFF) return "malformed JPEG structure at byte $pos"
            while (pos + 1 < b.size && b.u8(pos + 1) == 0xFF) pos++
            if (pos + 1 >= b.size) return "JPEG truncated"
            val m = b.u8(pos + 1)
            pos += 2
            if (m == 0xD9) {
                if (pos != b.size) return "data after JPEG end marker"
                if (scans == 0 || frames == 0) return "JPEG has no image data"
                return null
            }
            if (m in 0xD0..0xD7 || m == 0x01) continue
            if (pos + 2 > b.size) return "JPEG truncated"
            val len = b.u16be(pos)
            val end = pos + len
            if (len < 2 || end > b.size) return "JPEG segment length invalid"
            val data = pos + 2
            when {
                m == 0xFE -> return "JPEG comment survived"
                m == 0xE1 -> if (!(len == JpegStripper.ORIENTATION_SEGMENT_SIZE - 2 &&
                        b.hasPrefixAt(data, end, JpegStripper.EXIF_ID) &&
                        Orientation.isMinimalTiff(b, data + 6, len - 8))
                ) return "EXIF block other than orientation survived"
                m == 0xE2 -> if (!b.hasPrefixAt(data, end, JpegStripper.ICC_ID)) return "APP2 segment other than ICC survived"
                m == 0xEE -> if (!b.hasPrefixAt(data, end, JpegStripper.ADOBE_ID)) return "APP14 segment other than Adobe survived"
                m in 0xE0..0xEF -> return "APP${m - 0xE0} segment survived"
                m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC -> frames++
            }
            pos = end
            if (m == 0xDA) {
                scans++
                while (true) {
                    if (pos + 1 >= b.size) return "JPEG scan not terminated"
                    if (b.u8(pos) == 0xFF) {
                        val n = b.u8(pos + 1)
                        if (n == 0x00 || n in 0xD0..0xD7) { pos += 2; continue }
                        break
                    }
                    pos++
                }
            }
        }
    }

    private fun pngProblem(b: ByteArray): String? {
        if (!b.hasPrefixAt(0, b.size, PngStripper.SIGNATURE)) return "missing PNG signature"
        var pos = 8
        var first = true
        while (pos + 12 <= b.size) {
            val len = b.u32be(pos)
            if (pos + 12 + len > b.size) return "PNG chunk length invalid"
            val name = b.ascii(pos + 4, 4)
            if (first && name != "IHDR") return "PNG does not start with IHDR"
            first = false
            val crc = CRC32().apply { update(b, pos + 4, 4 + len.toInt()) }.value
            if (crc != b.u32be(pos + 8 + len.toInt())) return "PNG chunk $name has a bad CRC"
            if (name == "eXIf") {
                if (!Orientation.isMinimalTiff(b, pos + 8, len.toInt())) return "eXIf other than orientation survived"
            } else if (name !in PngStripper.KEEP) {
                return "PNG chunk $name survived"
            }
            pos += 12 + len.toInt()
            if (name == "IEND") return if (pos == b.size) null else "data after PNG IEND"
        }
        return "PNG has no IEND"
    }

    private fun webpProblem(b: ByteArray): String? {
        val chunks = try {
            WebpStripper.parse(b)
        } catch (e: StripException) {
            return e.message
        }
        if (8L + b.u32le(4) != b.size.toLong()) return "WebP size field does not match the file"
        val first = chunks.first()
        if (first.fourcc == "VP8 " || first.fourcc == "VP8L") {
            return if (chunks.size == 1) null else "extra chunks in a simple WebP"
        }
        if (first.fourcc != "VP8X") return "unrecognised WebP layout"
        val flags = b.u8(first.dataStart)
        if (flags and WebpStripper.FLAG_XMP != 0) return "WebP XMP flag still set"
        var exif = false
        for (c in chunks) {
            when (c.fourcc) {
                "VP8X", "ICCP", "ANIM", "ANMF", "ALPH", "VP8 ", "VP8L" -> Unit
                "EXIF" -> {
                    if (!Orientation.isMinimalTiff(b, c.dataStart, c.size)) return "WebP EXIF other than orientation survived"
                    exif = true
                }
                else -> return "WebP chunk ${c.fourcc} survived"
            }
        }
        if (exif != (flags and WebpStripper.FLAG_EXIF != 0)) return "WebP EXIF flag inconsistent"
        return null
    }
}
