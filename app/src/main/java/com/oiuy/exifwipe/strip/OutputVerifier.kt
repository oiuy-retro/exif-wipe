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
 * Independent check run on every rewritten file before it may replace the original.
 *
 * It re-parses the output from scratch and fails unless the file is well formed and contains only
 * the segments or chunks needed to decode the image plus exactly the metadata that was planned
 * ([OutputMeta]), byte for byte. Without a plan, the version 0.1.0 rules apply: at most an
 * Orientation-only EXIF block and a sanitized colour profile. A second pass looks for well-known
 * metadata signatures everywhere except inside the planned metadata blocks. The idea of a
 * structural post-check plus a signature scan comes from Scrambler
 * (https://gitlab.com/juanitobananas/scrambler) by Juan García Basilio; this implementation was
 * written separately.
 */
object OutputVerifier {
    private val forbiddenSignatures = listOf(
        "http://ns.adobe.com/", // XMP
        "<x:xmpmeta", // XMP packet
        "Photoshop 3.0", // IPTC / Photoshop resources
        "Hewlett-Packard", // copyright text of the common sRGB ICC profile
    ).map(::latin1)

    fun verify(format: ImageFormat, b: ByteArray, expected: OutputMeta? = null) {
        val scan = java.io.ByteArrayOutputStream(b.size)
        val problem = when (format) {
            ImageFormat.JPEG -> jpegProblem(b, expected, scan)
            ImageFormat.PNG -> pngProblem(b, expected, scan)
            ImageFormat.WEBP -> webpProblem(b, expected, scan)
            else -> "unsupported format"
        } ?: signatureProblem(scan.toByteArray())
        if (problem != null) throw StripException("Safety check failed: $problem")
    }

    private fun signatureProblem(b: ByteArray): String? {
        for (sig in forbiddenSignatures) {
            if (b.indexOf(sig) >= 0) return "metadata signature \"${String(sig, Charsets.ISO_8859_1)}\" is still present"
        }
        return null
    }

    /** Problem with an EXIF block, or null if it is the planned one (or minimal orientation without a plan). */
    private fun exifProblem(b: ByteArray, start: Int, length: Int, expected: OutputMeta?): String? {
        if (expected == null) {
            return if (Orientation.isMinimalTiff(b, start, length)) null else "EXIF block other than orientation survived"
        }
        val want = expected.exif ?: return "unplanned EXIF block"
        return if (same(b, start, length, want)) null else "EXIF block differs from the planned one"
    }

    private fun iccProblem(profile: ByteArray, expected: OutputMeta?): String? {
        IccSanitizer.problem(profile)?.let { return it }
        if (expected != null && !profile.contentEquals(expected.icc ?: return "unplanned colour profile")) {
            return "colour profile differs from the planned one"
        }
        return null
    }

    private fun same(b: ByteArray, start: Int, length: Int, want: ByteArray): Boolean {
        if (length != want.size || start + length > b.size) return false
        for (k in want.indices) if (b[start + k] != want[k]) return false
        return true
    }

    private fun jpegProblem(b: ByteArray, expected: OutputMeta?, scan: java.io.ByteArrayOutputStream): String? {
        if (b.size < 4 || b.u8(0) != 0xFF || b.u8(1) != 0xD8) return "missing JPEG start marker"
        scan.write(b, 0, 2)
        var pos = 2
        var scans = 0
        var frames = 0
        var exif = false
        var xmp = false
        var iptc = false
        val icc = ArrayList<Pair<Int, ByteArray>>()
        while (true) {
            if (pos + 1 >= b.size || b.u8(pos) != 0xFF) return "malformed JPEG structure at byte $pos"
            val segStart = pos
            while (pos + 1 < b.size && b.u8(pos + 1) == 0xFF) pos++
            if (pos + 1 >= b.size) return "JPEG truncated"
            val m = b.u8(pos + 1)
            pos += 2
            if (m == 0xD9) {
                scan.write(b, segStart, pos - segStart)
                if (pos != b.size && expected?.keepExtras != true) return "data after JPEG end marker"
                if (scans == 0 || frames == 0) return "JPEG has no image data"
                if (icc.isNotEmpty()) iccProblem(JpegStripper.assembleIcc(icc), expected)?.let { return it }
                else if (expected?.icc != null) return "planned colour profile missing"
                if (expected != null) {
                    if (expected.exif != null && !exif) return "planned EXIF block missing"
                    if (expected.xmp != null && !xmp) return "planned XMP missing"
                    if (expected.iptc != null && !iptc) return "planned IPTC missing"
                }
                return null
            }
            if (m in 0xD0..0xD7 || m == 0x01) { scan.write(b, segStart, pos - segStart); continue }
            if (pos + 2 > b.size) return "JPEG truncated"
            val len = b.u16be(pos)
            val end = pos + len
            if (len < 2 || end > b.size) return "JPEG segment length invalid"
            val data = pos + 2
            var exempt = false
            when {
                m == 0xFE -> return "JPEG comment survived"
                m == 0xE1 && b.hasPrefixAt(data, end, JpegStripper.EXIF_ID) -> {
                    if (exif) return "second EXIF block"
                    exifProblem(b, data + 6, len - 8, expected)?.let { return it }
                    exif = true
                    exempt = expected != null
                }
                m == 0xE1 && b.hasPrefixAt(data, end, JpegStripper.XMP_ID) -> {
                    val want = expected?.xmp ?: return "XMP survived"
                    if (xmp || !same(b, data + JpegStripper.XMP_ID.size, end - data - JpegStripper.XMP_ID.size, want)) return "XMP differs from the planned packet"
                    xmp = true
                    exempt = true
                }
                m == 0xE1 -> return "APP1 segment other than EXIF or XMP survived"
                m == 0xED -> {
                    val want = expected?.iptc ?: return "APP13 segment survived"
                    val payload = JpegStripper.PHOTOSHOP_ID + Iptc.toPhotoshop(want)
                    if (iptc || !same(b, data, len - 2, payload)) return "IPTC differs from the planned data"
                    iptc = true
                    exempt = true
                }
                m == 0xE2 && b.hasPrefixAt(data, end, JpegStripper.MPF_ID) -> {
                    if (expected?.keepExtras != true) return "MPF segment survived"
                    exempt = true
                }
                m == 0xE2 -> {
                    if (!b.hasPrefixAt(data, end, JpegStripper.ICC_ID) || end - data < JpegStripper.ICC_ID.size + 2) {
                        return "APP2 segment other than ICC survived"
                    }
                    icc += b.u8(data + JpegStripper.ICC_ID.size) to b.copyOfRange(data + JpegStripper.ICC_ID.size + 2, end)
                }
                m == 0xEE -> if (!b.hasPrefixAt(data, end, JpegStripper.ADOBE_ID)) return "APP14 segment other than Adobe survived"
                m in 0xE0..0xEF -> return "APP${m - 0xE0} segment survived"
                m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC -> frames++
            }
            if (!exempt) scan.write(b, segStart, end - segStart)
            pos = end
            if (m == 0xDA) {
                scans++
                val scanStart = pos
                while (true) {
                    if (pos + 1 >= b.size) return "JPEG scan not terminated"
                    if (b.u8(pos) == 0xFF) {
                        val n = b.u8(pos + 1)
                        if (n == 0x00 || n in 0xD0..0xD7) { pos += 2; continue }
                        break
                    }
                    pos++
                }
                scan.write(b, scanStart, pos - scanStart)
            }
        }
    }

    private fun pngProblem(b: ByteArray, expected: OutputMeta?, scan: java.io.ByteArrayOutputStream): String? {
        if (!b.hasPrefixAt(0, b.size, PngStripper.SIGNATURE)) return "missing PNG signature"
        var pos = 8
        var first = true
        var exif = false
        var xmp = false
        var time = false
        var sawIcc = false
        val text = ArrayList<Pair<String, ByteArray>>()
        while (pos + 12 <= b.size) {
            val len = b.u32be(pos)
            if (pos + 12 + len > b.size) return "PNG chunk length invalid"
            val n = len.toInt()
            val name = b.ascii(pos + 4, 4)
            if (first && name != "IHDR") return "PNG does not start with IHDR"
            first = false
            val crc = CRC32().apply { update(b, pos + 4, 4 + n) }.value
            if (crc != b.u32be(pos + 8 + n)) return "PNG chunk $name has a bad CRC"
            val d = pos + 8
            var exempt = false
            when {
                name == "iCCP" -> {
                    if (!b.hasPrefixAt(d, d + n, latin1(PngStripper.ICCP_NAME + "\u0000"))) return "iCCP profile name survived"
                    val profile = PngStripper.iccpProfile(b, d, n) ?: return "iCCP cannot be read"
                    iccProblem(profile, expected)?.let { return it }
                    sawIcc = true
                }
                name == "eXIf" -> {
                    if (exif) return "second eXIf chunk"
                    exifProblem(b, d, n, expected)?.let { return it }
                    exif = true
                    exempt = expected != null
                }
                name == "iTXt" && PngStripper.keyword(b.copyOfRange(d, d + n)) == PngStripper.XMP_KEYWORD -> {
                    val want = expected?.xmp ?: return "XMP survived"
                    if (xmp || !same(b, d, n, PngStripper.xmpItxt(want))) return "XMP differs from the planned packet"
                    xmp = true
                    exempt = true
                }
                name in PngStripper.TEXT -> {
                    val chunkData = b.copyOfRange(d, d + n)
                    if (expected == null || expected.pngText.none { it.first == name && it.second.contentEquals(chunkData) }) {
                        return "PNG text chunk survived"
                    }
                    text += name to chunkData
                    exempt = true
                }
                name == "tIME" -> {
                    if (expected?.pngTime == null || !same(b, d, n, expected.pngTime)) return "PNG tIME chunk survived"
                    time = true
                }
                name == "sRGB" && expected?.dropPngSrgb == true -> return "sRGB chunk next to a replaced profile"
                name !in PngStripper.KEEP -> return "PNG chunk $name survived"
            }
            if (!exempt) scan.write(b, pos, 12 + n)
            pos += 12 + n
            if (name == "IEND") {
                if (pos != b.size) return "data after PNG IEND"
                if (expected != null) {
                    if (expected.exif != null && !exif) return "planned EXIF block missing"
                    if (expected.xmp != null && !xmp) return "planned XMP missing"
                    if (expected.icc != null && !sawIcc) return "planned colour profile missing"
                    if (expected.pngTime != null && !time) return "planned tIME missing"
                    if (text.size != expected.pngText.size) return "planned text chunks missing"
                }
                return null
            }
        }
        return "PNG has no IEND"
    }

    private fun webpProblem(b: ByteArray, expected: OutputMeta?, scan: java.io.ByteArrayOutputStream): String? {
        val chunks = try {
            WebpStripper.parse(b)
        } catch (e: StripException) {
            return e.message
        }
        if (8L + b.u32le(4) != b.size.toLong()) return "WebP size field does not match the file"
        val first = chunks.first()
        if (first.fourcc == "VP8 " || first.fourcc == "VP8L") {
            if (expected != null && (expected.exif != null || expected.xmp != null || expected.icc != null)) return "planned metadata missing"
            scan.write(b, 0, b.size)
            return if (chunks.size == 1) null else "extra chunks in a simple WebP"
        }
        if (first.fourcc != "VP8X") return "unrecognised WebP layout"
        scan.write(b, 0, 12)
        val flags = b.u8(first.dataStart)
        var exif = false
        var xmp = false
        var icc = false
        for (c in chunks) {
            var exempt = false
            when (c.fourcc) {
                "ICCP" -> {
                    iccProblem(b.copyOfRange(c.dataStart, c.dataStart + c.size), expected)?.let { return it }
                    icc = true
                }
                "VP8X", "ANIM", "ANMF", "ALPH", "VP8 ", "VP8L" -> Unit
                "EXIF" -> {
                    exifProblem(b, c.dataStart, c.size, expected)?.let { return it }
                    exif = true
                    exempt = expected != null
                }
                "XMP " -> {
                    val want = expected?.xmp ?: return "WebP XMP survived"
                    if (xmp || !same(b, c.dataStart, c.size, want)) return "XMP differs from the planned packet"
                    xmp = true
                    exempt = true
                }
                else -> return "WebP chunk ${c.fourcc} survived"
            }
            if (!exempt) scan.write(b, c.start, c.end - c.start)
        }
        if (exif != (flags and WebpStripper.FLAG_EXIF != 0)) return "WebP EXIF flag inconsistent"
        if (xmp != (flags and WebpStripper.FLAG_XMP != 0)) return "WebP XMP flag inconsistent"
        if (icc != (flags and WebpStripper.FLAG_ICC != 0)) return "WebP ICC flag inconsistent"
        if (expected != null) {
            if (expected.exif != null && !exif) return "planned EXIF block missing"
            if (expected.xmp != null && !xmp) return "planned XMP missing"
            if (expected.icc != null && !icc) return "planned colour profile missing"
        }
        return null
    }
}
