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
 * EXIF block, walked every scan of progressive JPEGs, made malformed input fail instead of
 * being skipped over, and split reading and writing so the output metadata can be planned
 * (kept per group, replaced, or removed).
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

/**
 * Rewrites the metadata of a JPEG without touching the compressed image data.
 *
 * The file is walked segment by segment. Every APPn (0xE0-0xEF) and COM (0xFE) segment is
 * removed (EXIF with maker notes and thumbnail, XMP and extended XMP, IPTC/Photoshop, MPF,
 * FlashPix, vendor segments) and its content collected into a [SourceMeta]. APP14 "Adobe" is
 * kept because some RGB/CMYK JPEGs cannot be decoded without it. A [Planner] then decides what
 * the new file contains ([OutputMeta]); only that is written:
 *  - a freshly built EXIF block (APP1),
 *  - a filtered XMP packet (APP1) and IPTC block (APP13),
 *  - the colour profile (APP2 ICC_PROFILE), at the position of the original one,
 *  - MPF (APP2) and the data after the end-of-image marker, only if extras are kept.
 *
 * [strip] uses the version 0.1.0 plan: an Orientation-only EXIF block if the photo needs one, and
 * the colour profile rebuilt by [IccSanitizer].
 */
object JpegStripper {
    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val APP13 = 0xED
    private const val APP14 = 0xEE
    private const val COM = 0xFE

    internal val EXIF_ID = latin1("Exif\u0000\u0000")
    internal val XMP_ID = latin1("http://ns.adobe.com/xap/1.0/\u0000")
    internal val ICC_ID = latin1("ICC_PROFILE\u0000")
    internal val MPF_ID = latin1("MPF\u0000")
    internal val PHOTOSHOP_ID = latin1("Photoshop 3.0\u0000")
    internal val ADOBE_ID = latin1("Adobe")

    /** Size of the APP1 segment written by [orientationSegment], including marker and length. */
    internal const val ORIENTATION_SEGMENT_SIZE = 2 + 2 + 6 + Orientation.MINIMAL_TIFF_SIZE

    fun strip(b: ByteArray): ByteArray = rewrite(b) { OutputMeta.default(it) }.output

    fun rewrite(b: ByteArray, planner: Planner): Rewrite {
        if (b.size < 4 || b.u8(0) != MARKER || b.u8(1) != SOI) throw StripException("Not a JPEG file")

        val body = ByteArrayOutputStream(b.size)
        var tiff: TiffData? = null
        var orientation: Int? = null
        var sawExif = false
        var xmp: String? = null
        var iptc: ByteArray? = null
        var mpf: ByteArray? = null
        var mpfHeaderPos = -1
        val iccChunks = ArrayList<Pair<Int, ByteArray>>() // (sequence number, data)
        var iccPosition = -1
        var sawScan = false
        var width = 0
        var height = 0
        var pos = 2

        while (true) {
            if (pos + 1 >= b.size) throw StripException("JPEG ends before its end-of-image marker (file may be truncated)")
            if (b.u8(pos) != MARKER) throw StripException("Invalid JPEG: expected a marker at byte $pos")
            // Skip 0xFF fill bytes in front of a marker.
            while (pos + 1 < b.size && b.u8(pos + 1) == MARKER) pos++
            if (pos + 1 >= b.size) throw StripException("JPEG ends before its end-of-image marker (file may be truncated)")
            val m = b.u8(pos + 1)
            val markerPos = pos
            pos += 2

            when {
                m == EOI -> {
                    body.write(MARKER)
                    body.write(EOI)
                    break
                }
                m in 0xD0..0xD7 || m == 0x01 -> {
                    // Standalone markers without a length field.
                    body.write(b, markerPos, 2)
                    continue
                }
                m == SOI || m == 0x00 -> throw StripException("Invalid JPEG: unexpected marker 0x%02X".format(m))
            }

            if (pos + 2 > b.size) throw StripException("JPEG segment header is truncated")
            val length = b.u16be(pos)
            if (length < 2) throw StripException("Invalid JPEG: segment 0x%02X has length %d".format(m, length))
            val segEnd = pos + length
            if (segEnd > b.size) throw StripException("Invalid JPEG: segment 0x%02X runs past the end of the file".format(m))
            val dataStart = pos + 2

            if (m in 0xE0..0xEF || m == COM) {
                when {
                    m == APP1 && !sawExif && b.hasPrefixAt(dataStart, segEnd, EXIF_ID) -> {
                        sawExif = true
                        orientation = Orientation.read(b, dataStart + EXIF_ID.size, segEnd)
                        tiff = Tiff.read(b, dataStart + EXIF_ID.size, segEnd)
                    }
                    m == APP1 && xmp == null && b.hasPrefixAt(dataStart, segEnd, XMP_ID) ->
                        xmp = String(b, dataStart + XMP_ID.size, segEnd - dataStart - XMP_ID.size, Charsets.UTF_8)
                    m == APP13 && iptc == null && b.hasPrefixAt(dataStart, segEnd, PHOTOSHOP_ID) ->
                        iptc = Iptc.fromPhotoshop(b, dataStart + PHOTOSHOP_ID.size, segEnd)
                    m == APP2 && b.hasPrefixAt(dataStart, segEnd, ICC_ID) && segEnd - dataStart >= ICC_ID.size + 2 -> {
                        if (iccPosition < 0) iccPosition = body.size()
                        val seq = b.u8(dataStart + ICC_ID.size)
                        iccChunks += seq to b.copyOfRange(dataStart + ICC_ID.size + 2, segEnd)
                    }
                    m == APP2 && mpf == null && b.hasPrefixAt(dataStart, segEnd, MPF_ID) -> {
                        mpf = b.copyOfRange(dataStart + MPF_ID.size, segEnd)
                        mpfHeaderPos = dataStart + MPF_ID.size
                    }
                    m == APP14 && b.hasPrefixAt(dataStart, segEnd, ADOBE_ID) -> body.write(b, markerPos, segEnd - markerPos)
                }
                pos = segEnd
                continue
            }

            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC && length >= 7 && width == 0) {
                height = b.u16be(dataStart + 1)
                width = b.u16be(dataStart + 3)
            }

            body.write(b, markerPos, segEnd - markerPos)
            pos = segEnd

            if (m == SOS) {
                sawScan = true
                // Entropy-coded data: every 0xFF inside it is followed by 0x00 (byte stuffing) or a
                // restart marker 0xD0-0xD7. Any other marker ends the scan; progressive JPEGs then
                // continue with more tables and scans, which the outer loop handles.
                val scanStart = pos
                var i = pos
                while (true) {
                    if (i + 1 >= b.size) throw StripException("JPEG image data ends without an end-of-image marker (file may be truncated)")
                    if (b.u8(i) == MARKER) {
                        val next = b.u8(i + 1)
                        if (next == 0x00 || next in 0xD0..0xD7) {
                            i += 2
                            continue
                        }
                        break
                    }
                    i++
                }
                body.write(b, scanStart, i - scanStart)
                pos = i
            }
        }

        if (!sawScan) throw StripException("JPEG contains no image data")
        val mainEnd = pos // just after EOI
        val trailer = if (mainEnd < b.size) b.copyOfRange(mainEnd, b.size) else null

        val src = SourceMeta(
            ImageFormat.JPEG, width, height, tiff, orientation, xmp, iptc,
            if (iccChunks.isEmpty()) null else assembleIcc(iccChunks),
            hasExtras = mpf != null || trailer != null,
        )
        val plan = planner.plan(src)

        val bodyBytes = body.toByteArray()
        val out = ByteArrayOutputStream(bodyBytes.size + 4096 + (if (plan.keepExtras) trailer?.size ?: 0 else 0))
        out.write(MARKER)
        out.write(SOI)
        plan.exif?.let { out.write(segment(APP1, EXIF_ID + it)) }
        plan.xmp?.let { out.write(segment(APP1, XMP_ID + it)) }
        plan.iptc?.let { out.write(segment(APP13, PHOTOSHOP_ID + Iptc.toPhotoshop(it))) }
        // The colour profile goes where the original one was (or before all tables if there was none).
        val split = if (iccPosition >= 0) iccPosition else 0
        out.write(bodyBytes, 0, split)
        plan.icc?.let { out.write(iccSegments(it)) }
        var mpfAt = -1
        // An MPF index that cannot be parsed is dropped; the embedded images themselves are still kept.
        val keepMpf = plan.keepExtras && mpf != null && Mpf.relocate(mpf.copyOf(), 0, 0, 0, 0)
        if (keepMpf) {
            mpfAt = out.size()
            out.write(segment(APP2, MPF_ID + mpf!!))
        }
        out.write(bodyBytes, split, bodyBytes.size - split)
        val newMainEnd = out.size()
        if (plan.keepExtras && trailer != null) out.write(trailer)
        val result = out.toByteArray()
        if (keepMpf) {
            val headerAt = mpfAt + 4 + MPF_ID.size
            if (!Mpf.relocate(result, headerAt, newMainEnd, mainEnd - mpfHeaderPos, newMainEnd - headerAt)) {
                throw StripException("Could not update the MPF index of the embedded images")
            }
        }
        return Rewrite(result, plan, src)
    }

    private fun segment(marker: Int, data: ByteArray): ByteArray {
        val length = 2 + data.size
        if (length > 0xFFFF) throw StripException("Metadata block too large for a JPEG segment")
        return byteArrayOf(MARKER.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + data
    }

    /** Joins ICC chunks in sequence-number order (file order if the numbers are inconsistent). */
    internal fun assembleIcc(chunks: List<Pair<Int, ByteArray>>): ByteArray {
        val seqs = chunks.map { it.first }
        val ordered = if (seqs.toSet().size == seqs.size) chunks.sortedBy { it.first } else chunks
        return ordered.fold(ByteArray(0)) { acc, c -> acc + c.second }
    }

    /** Splits a profile into APP2 ICC_PROFILE segments. */
    internal fun iccSegments(profile: ByteArray): ByteArray {
        val maxData = 0xFFFF - 2 - ICC_ID.size - 2
        val parts = (profile.size + maxData - 1) / maxData
        val out = ByteArrayOutputStream(profile.size + parts * 18)
        for (i in 0 until parts) {
            val from = i * maxData
            val to = minOf(profile.size, from + maxData)
            val length = 2 + ICC_ID.size + 2 + (to - from)
            out.write(byteArrayOf(MARKER.toByte(), APP2.toByte(), (length shr 8).toByte(), length.toByte()))
            out.write(ICC_ID)
            out.write(i + 1)
            out.write(parts)
            out.write(profile, from, to - from)
        }
        return out.toByteArray()
    }

    /** APP1 segment containing only an EXIF Orientation tag. */
    internal fun orientationSegment(value: Int): ByteArray = segment(APP1, EXIF_ID + Orientation.minimalTiff(value))
}

/**
 * Multi-Picture Format index (CIPA DC-007). Offsets of the embedded images are counted from the
 * MPF header, so they change when segments before the end of the main image change size.
 */
internal object Mpf {
    private const val TAG_MP_ENTRY = 0xB002

    /**
     * Rewrites the MP entries in [b] in place. [headerAt] is the MPF TIFF header position,
     * [mainSize] the new size of the main image, [oldDistance]/[newDistance] the distance from the
     * MPF header to the end of the main image before and after rewriting.
     */
    fun relocate(b: ByteArray, headerAt: Int, mainSize: Int, oldDistance: Int, newDistance: Int): Boolean {
        val little = when (b.ascii(headerAt, 2)) { "II" -> true; "MM" -> false; else -> return false }
        fun u16(p: Int) = if (little) b.u16le(p) else b.u16be(p)
        fun u32(p: Int) = if (little) b.u32le(p) else b.u32be(p)
        fun put32(p: Int, v: Long) {
            for (k in 0 until 4) {
                val shift = if (little) 8 * k else 8 * (3 - k)
                b[p + k] = (v ushr shift).toByte()
            }
        }
        val ifd = headerAt + u32(headerAt + 4).toInt()
        if (ifd + 2 > b.size) return false
        val n = u16(ifd)
        for (i in 0 until n) {
            val e = ifd + 2 + 12 * i
            if (e + 12 > b.size) return false
            if (u16(e) != TAG_MP_ENTRY) continue
            val count = u32(e + 4).toInt()
            val start = headerAt + u32(e + 8).toInt()
            if (count % 16 != 0 || start + count > b.size) return false
            val delta = newDistance - oldDistance
            for (k in 0 until count / 16) {
                val r = start + 16 * k
                val offset = u32(r + 8)
                if (offset == 0L) put32(r + 4, mainSize.toLong()) else put32(r + 8, offset + delta)
            }
            return true
        }
        return false
    }
}
