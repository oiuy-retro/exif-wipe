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

/**
 * Removes metadata from a JPEG without touching the compressed image data.
 *
 * The file is walked segment by segment. Every APPn (0xE0-0xEF) and COM (0xFE) segment is
 * dropped (EXIF with maker notes and thumbnail, XMP and extended XMP, IPTC/Photoshop, MPF,
 * FlashPix, vendor segments), except the two that change how pixels are displayed:
 *  - APP2 "ICC_PROFILE": the colour profile. It is reassembled, rebuilt by [IccSanitizer] without
 *    its copyright, manufacturer and model fields, and written back in place of the original,
 *  - APP14 "Adobe": the colour-transform flag needed to decode some RGB/CMYK JPEGs.
 * Anything after the end-of-image marker (motion-photo video, gain maps, vendor trailers) is
 * dropped. If the original carried an EXIF Orientation other than "normal", a new EXIF block
 * holding only that tag is written so the photo still displays upright.
 */
object JpegStripper {
    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val APP14 = 0xEE
    private const val COM = 0xFE

    internal val EXIF_ID = latin1("Exif\u0000\u0000")
    internal val ICC_ID = latin1("ICC_PROFILE\u0000")
    internal val ADOBE_ID = latin1("Adobe")

    /** Size of the APP1 segment written by [orientationSegment], including marker and length. */
    internal const val ORIENTATION_SEGMENT_SIZE = 2 + 2 + 6 + Orientation.MINIMAL_TIFF_SIZE

    fun strip(b: ByteArray): ByteArray {
        if (b.size < 4 || b.u8(0) != MARKER || b.u8(1) != SOI) throw StripException("Not a JPEG file")

        val body = ByteArrayOutputStream(b.size)
        var orientation: Int? = null
        val iccChunks = ArrayList<Pair<Int, ByteArray>>() // (sequence number, data)
        var iccPosition = -1
        var sawScan = false
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
                if (m == APP1 && orientation == null && b.hasPrefixAt(dataStart, segEnd, EXIF_ID)) {
                    orientation = Orientation.read(b, dataStart + EXIF_ID.size, segEnd)
                }
                if (m == APP2 && b.hasPrefixAt(dataStart, segEnd, ICC_ID) && segEnd - dataStart >= ICC_ID.size + 2) {
                    if (iccPosition < 0) iccPosition = body.size()
                    val seq = b.u8(dataStart + ICC_ID.size)
                    iccChunks += seq to b.copyOfRange(dataStart + ICC_ID.size + 2, segEnd)
                } else if (m == APP14 && b.hasPrefixAt(dataStart, segEnd, ADOBE_ID)) {
                    body.write(b, markerPos, segEnd - markerPos)
                }
                pos = segEnd
                continue
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

        val out = ByteArrayOutputStream(body.size() + ORIENTATION_SEGMENT_SIZE + 2)
        out.write(MARKER)
        out.write(SOI)
        if (Orientation.needsTag(orientation)) out.write(orientationSegment(orientation!!))
        val bodyBytes = body.toByteArray()
        val icc = if (iccChunks.isEmpty()) null else IccSanitizer.sanitize(assembleIcc(iccChunks))
        if (icc == null) {
            out.write(bodyBytes)
        } else {
            out.write(bodyBytes, 0, iccPosition)
            out.write(iccSegments(icc))
            out.write(bodyBytes, iccPosition, bodyBytes.size - iccPosition)
        }
        return out.toByteArray()
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
    internal fun orientationSegment(value: Int): ByteArray {
        val tiff = Orientation.minimalTiff(value)
        val length = 2 + EXIF_ID.size + tiff.size
        return byteArrayOf(MARKER.toByte(), APP1.toByte(), (length shr 8).toByte(), length.toByte()) + EXIF_ID + tiff
    }
}
