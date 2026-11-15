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

import java.util.Locale

/**
 * Compares the file read back from storage with the bytes that were written.
 *
 * Android's media provider hides photo locations from apps that lack the "access media location"
 * permission: when such an app opens another app's photo for reading, the values of the EXIF GPS
 * tags are replaced with zeros as the file is read (the file itself is unchanged). A read-back
 * through such a read-only link therefore differs from the written bytes exactly inside the GPS
 * values. [compare] recognises that case so it can be told apart from a real write failure, and
 * [describe] explains any other difference without printing image or metadata contents.
 */
object ReadBack {
    sealed interface Result {
        data object Same : Result
        /** Differs only by zeros inside the EXIF GPS values (Android's location redaction). */
        data class OnlyLocationRedacted(val bytes: Int) : Result
        data class Different(val details: String) : Result
    }

    /**
     * Absolute byte ranges of the EXIF GPS tag values in [file], given the TIFF block [exif] that
     * was written into it. Empty if there is no GPS data.
     */
    fun gpsValueRanges(file: ByteArray, exif: ByteArray?, knownBase: Int? = null): List<IntRange> {
        if (exif == null || exif.size < 8) return emptyList()
        val base = knownBase ?: file.indexOf(exif)
        if (base < 0) return emptyList()
        val little = when (exif.ascii(0, 2)) { "II" -> true; "MM" -> false; else -> return emptyList() }
        fun u16(p: Int) = if (little) exif.u16le(p) else exif.u16be(p)
        fun u32(p: Int) = if (little) exif.u32le(p) else exif.u32be(p)
        fun entries(off: Long): List<Int>? {
            if (off < 8 || off + 2 > exif.size) return null
            val n = u16(off.toInt())
            if (off + 2 + 12L * n > exif.size) return null
            return (0 until n).map { off.toInt() + 2 + 12 * it }
        }
        val ifd0 = entries(u32(4)) ?: return emptyList()
        val gpsPointer = ifd0.firstOrNull { u16(it) == Tiff.TAG_GPS_IFD } ?: return emptyList()
        val gps = entries(u32(gpsPointer + 8)) ?: return emptyList()
        val ranges = ArrayList<IntRange>()
        for (e in gps) {
            val size = TiffType.size(u16(e + 2))
            val total = size.toLong() * u32(e + 4)
            if (size == 0 || total <= 0 || total > exif.size) continue
            val pos = if (total <= 4) (e + 8).toLong() else u32(e + 8)
            if (pos + total > exif.size) continue
            ranges += (base + pos.toInt()) until (base + (pos + total).toInt())
        }
        return ranges
    }

    /** GPS value ranges of the first EXIF block found in a JPEG, PNG or WebP file (for the original). */
    fun gpsValueRangesInFile(file: ByteArray): List<IntRange> {
        val limit = minOf(file.size, 512 * 1024)
        val head = file.copyOf(limit)
        val jpeg = head.indexOf(JpegStripper.EXIF_ID)
        val start = when {
            jpeg >= 0 -> jpeg + JpegStripper.EXIF_ID.size
            else -> head.indexOf("eXIf".toByteArray()).takeIf { it >= 0 }?.plus(4)
                ?: head.indexOf("EXIF".toByteArray()).takeIf { it >= 0 }?.plus(8)
                ?: return emptyList()
        }
        val end = minOf(file.size, start + 65_536)
        if (start + 8 > end) return emptyList()
        return gpsValueRanges(file, file.copyOfRange(start, end), start)
    }

    fun compare(expected: ByteArray, actual: ByteArray, redactable: List<IntRange>): Result {
        if (expected.contentEquals(actual)) return Result.Same
        if (expected.size == actual.size && redactable.isNotEmpty()) {
            var diffs = 0
            var onlyRedacted = true
            for (i in expected.indices) {
                if (expected[i] == actual[i]) continue
                diffs++
                if (actual[i] != 0.toByte() || redactable.none { i in it }) { onlyRedacted = false; break }
            }
            if (onlyRedacted) return Result.OnlyLocationRedacted(diffs)
        }
        return Result.Different(describe(expected, actual, redactable))
    }

    /**
     * Lengths, first and last differing offsets, number of differing bytes, the file region of the
     * first difference and 8 bytes of context on each side. Inside EXIF/XMP/IPTC the context bytes
     * are left out, so no metadata values end up in error reports.
     */
    fun describe(expected: ByteArray, actual: ByteArray, redactable: List<IntRange> = emptyList()): String {
        val common = minOf(expected.size, actual.size)
        var first = -1
        var last = -1
        var count = 0
        for (i in 0 until common) if (expected[i] != actual[i]) { if (first < 0) first = i; last = i; count++ }
        val sb = StringBuilder()
        sb.append("expected ").append(expected.size).append(" bytes, read back ").append(actual.size).append(" bytes")
        if (first < 0) {
            sb.append("; the first ").append(common).append(" bytes are identical")
            if (actual.size > expected.size) sb.append(" (extra bytes at the end: the old file was not truncated)")
            else if (actual.size < expected.size) sb.append(" (the read-back is shorter: incomplete write or read)")
            return sb.toString()
        }
        sb.append("; ").append(count).append(" differing bytes in the common part, first at offset ").append(first)
            .append(", last at ").append(last)
        val region = region(expected, first)
        sb.append("; first difference in ").append(region)
        if (redactable.isNotEmpty()) {
            val inGps = (first..last).count { i -> expected[i] != actual[i] && redactable.any { i in it } }
            sb.append("; ").append(inGps).append(" of them inside GPS values")
            val zero = (first..last).count { i -> expected[i] != actual[i] && actual[i] == 0.toByte() }
            sb.append(", ").append(zero).append(" read back as zero")
        }
        if (!region.startsWith("metadata")) {
            val from = maxOf(0, first - 8)
            sb.append("; expected ").append(hex(expected, from, minOf(expected.size, first + 9)))
            sb.append(" / read ").append(hex(actual, from, minOf(actual.size, first + 9)))
            sb.append(" (from offset ").append(from).append(')')
        }
        return sb.toString()
    }

    /** Which part of a JPEG/PNG/WebP file holds [offset], for diagnostics. */
    fun region(b: ByteArray, offset: Int): String {
        if (b.size >= 2 && b.u8(0) == 0xFF && b.u8(1) == 0xD8) {
            var p = 2
            while (p + 4 <= b.size && b.u8(p) == 0xFF) {
                val m = b.u8(p + 1)
                if (m == 0xD9) return if (offset >= p + 2) "data after the end of the image" else "JPEG end marker"
                if (m == 0x01 || m in 0xD0..0xD7) { p += 2; continue }
                val len = b.u16be(p + 2)
                val end = p + 2 + len
                if (offset in p until end) {
                    val name = when (m) {
                        0xE1 -> if (b.hasPrefixAt(p + 4, end, JpegStripper.EXIF_ID)) "metadata (EXIF segment at $p, length $len)" else "metadata (APP1/XMP segment at $p, length $len)"
                        0xED -> "metadata (APP13/IPTC segment at $p)"
                        in 0xE0..0xEF -> "APP${m - 0xE0} segment at $p (length $len)"
                        0xDA -> "start of scan header at $p"
                        else -> "segment 0x%02X at %d".format(Locale.ROOT, m, p)
                    }
                    return name
                }
                if (m == 0xDA) return "compressed image data (scan starts at $p)"
                p = end
            }
            return "compressed image data"
        }
        if (b.size >= 8 && b.ascii(1, 3) == "PNG") {
            var p = 8
            while (p + 12 <= b.size) {
                val len = b.u32be(p).toInt()
                val type = b.ascii(p + 4, 4)
                val end = p + 12 + len
                if (offset in p until end) return if (type in setOf("eXIf", "iTXt", "tEXt", "zTXt")) "metadata (PNG $type chunk at $p)" else "PNG $type chunk at $p"
                if (len < 0 || end <= p) break
                p = end
            }
            return "PNG data"
        }
        if (b.size >= 12 && b.ascii(0, 4) == "RIFF") {
            var p = 12
            while (p + 8 <= b.size) {
                val type = b.ascii(p, 4)
                val len = b.u32le(p + 4).toInt()
                val end = p + 8 + len + (len and 1)
                if (offset in p until end) return if (type == "EXIF" || type == "XMP ") "metadata (WebP $type chunk at $p)" else "WebP $type chunk at $p"
                if (len < 0 || end <= p) break
                p = end
            }
            return "WebP data"
        }
        return "unknown format"
    }

    private fun hex(b: ByteArray, from: Int, to: Int): String =
        (from until to).joinToString("") { "%02x".format(Locale.ROOT, b.u8(it)) }
}
