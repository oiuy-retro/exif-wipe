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
 * Rewrites the metadata chunks of a PNG without touching the compressed image data.
 *
 * Only chunks needed to draw the image the same way are copied: the critical chunks, transparency,
 * colour space information, background colour, and the APNG animation chunks. Everything else is
 * collected into a [SourceMeta] (eXIf, XMP in iTXt, other text, tIME, iCCP) and the [Planner]
 * decides what is written back. pHYs and every unknown or private chunk are always dropped, as is
 * anything after IEND. [strip] uses the version 0.1.0 plan: a minimal eXIf chunk for a non-normal
 * orientation and the iCCP profile rebuilt by [IccSanitizer] under a neutral name.
 */
object PngStripper {
    internal val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    internal val KEEP = setOf(
        "IHDR", "PLTE", "IDAT", "IEND",
        "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "cICP", "mDCV", "cLLI", "bKGD",
        "acTL", "fcTL", "fdAT",
    )
    internal val TEXT = setOf("tEXt", "zTXt", "iTXt")
    internal const val XMP_KEYWORD = "XML:com.adobe.xmp"

    private class Chunk(val name: String, val start: Int, val dataStart: Int, val dataLength: Int, val end: Int)

    fun strip(b: ByteArray): ByteArray = rewrite(b) { OutputMeta.default(it) }.output

    fun rewrite(b: ByteArray, planner: Planner): Rewrite {
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
        val ihdr = chunks.first()
        if (ihdr.dataLength < 8) throw StripException("Invalid PNG IHDR chunk")

        fun data(c: Chunk) = b.copyOfRange(c.dataStart, c.dataStart + c.dataLength)
        var orientation: Int? = null
        var tiff: TiffData? = null
        var xmp: String? = null
        var icc: ByteArray? = null
        var time: ByteArray? = null
        val text = ArrayList<Pair<String, ByteArray>>()
        for (c in chunks) {
            when (c.name) {
                "eXIf" -> if (tiff == null && orientation == null) {
                    var s = c.dataStart
                    if (b.hasPrefixAt(s, s + c.dataLength, JpegStripper.EXIF_ID)) s += JpegStripper.EXIF_ID.size
                    orientation = Orientation.read(b, s, c.dataStart + c.dataLength)
                    tiff = Tiff.read(b, s, c.dataStart + c.dataLength)
                }
                "iCCP" -> if (icc == null) icc = iccpProfile(b, c.dataStart, c.dataLength)
                "tIME" -> if (crcMatches(b, c)) time = data(c)
                in TEXT -> if (crcMatches(b, c)) {
                    val d = data(c)
                    if (c.name == "iTXt" && keyword(d) == XMP_KEYWORD) {
                        if (xmp == null) xmp = itxtText(d)
                    } else {
                        text += c.name to d
                    }
                }
            }
        }
        val src = SourceMeta(
            ImageFormat.PNG, b.u32be(ihdr.dataStart).toInt(), b.u32be(ihdr.dataStart + 4).toInt(),
            tiff, orientation, xmp, null, icc, text, time,
        )
        val plan = planner.plan(src)

        val out = ByteArrayOutputStream(b.size + 4096)
        out.write(SIGNATURE)
        var wroteMeta = false
        var wroteIcc = plan.icc == null
        val iccChunk = plan.icc?.let { chunk("iCCP", iccpData(it)) }
        for (c in chunks) {
            if (c.name !in KEEP) continue
            if (!crcMatches(b, c)) throw StripException("PNG ${c.name} chunk is corrupt (CRC mismatch)")
            if (c.name == "iCCP") {
                if (!wroteIcc) { out.write(iccChunk!!); wroteIcc = true }
                continue
            }
            if (c.name == "sRGB" && plan.dropPngSrgb) continue
            // The profile must come before PLTE and IDAT; with no original position it goes right after IHDR.
            if (!wroteIcc && c.name != "IHDR" && (c.name == "PLTE" || c.name == "IDAT" || chunks.none { it.name == "iCCP" })) {
                out.write(iccChunk!!)
                wroteIcc = true
            }
            if (c.name == "IDAT" && !wroteMeta) {
                plan.exif?.let { out.write(chunk("eXIf", it)) }
                plan.xmp?.let { out.write(chunk("iTXt", xmpItxt(it))) }
                for ((name, d) in plan.pngText) out.write(chunk(name, d))
                plan.pngTime?.let { out.write(chunk("tIME", it)) }
                wroteMeta = true
            }
            out.write(b, c.start, c.end - c.start)
        }
        return Rewrite(out.toByteArray(), plan, src)
    }

    /** Keyword of a text chunk (the bytes before the first NUL). */
    internal fun keyword(d: ByteArray): String {
        val nul = d.indexOf(0.toByte()).let { if (it < 0) minOf(d.size, 79) else it }
        return String(d, 0, nul, Charsets.ISO_8859_1)
    }

    /** Text of an iTXt chunk, inflating it if compressed; null if malformed. */
    internal fun itxtText(d: ByteArray): String? {
        val k = d.indexOf(0.toByte())
        if (k < 0 || k + 3 > d.size) return null
        val compressed = d[k + 1].toInt() != 0
        var p = k + 3
        val lang = (p until d.size).firstOrNull { d[it].toInt() == 0 } ?: return null
        p = lang + 1
        val trans = (p until d.size).firstOrNull { d[it].toInt() == 0 } ?: return null
        p = trans + 1
        val raw = if (compressed) inflate(d, p, d.size - p) ?: return null else d.copyOfRange(p, d.size)
        return String(raw, Charsets.UTF_8)
    }

    internal fun xmpItxt(xmp: ByteArray): ByteArray = latin1(XMP_KEYWORD) + byteArrayOf(0, 0, 0, 0, 0) + xmp

    internal const val ICCP_NAME = "ICC Profile"

    /** iCCP chunk data for [profile] under a neutral name. */
    internal fun iccpData(profile: ByteArray): ByteArray = latin1(ICCP_NAME) + byteArrayOf(0, 0) + deflate(profile)

    /** Decompressed profile from iCCP chunk data, or null. */
    internal fun iccpProfile(b: ByteArray, start: Int, length: Int): ByteArray? {
        var nul = -1
        for (i in start until minOf(start + 80, start + length)) if (b[i].toInt() == 0) { nul = i; break }
        if (nul < 0 || nul + 2 > start + length || b[nul + 1].toInt() != 0) return null
        return inflate(b, nul + 2, start + length - nul - 2)
    }

    private fun inflate(b: ByteArray, off: Int, len: Int): ByteArray? {
        val inflater = java.util.zip.Inflater()
        return try {
            inflater.setInput(b, off, len)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buf, 0, n)
                if (out.size() > 16 * 1024 * 1024) return null
            }
            out.toByteArray()
        } catch (_: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = java.util.zip.Deflater(9)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
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
