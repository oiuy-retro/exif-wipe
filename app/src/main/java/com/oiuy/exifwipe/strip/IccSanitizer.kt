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

import java.io.ByteArrayOutputStream

/**
 * Rewrites an embedded ICC colour profile so it keeps only the data needed to reproduce colours.
 *
 * Camera and phone profiles carry identifying text: a copyright notice ("cprt", for example
 * "Copyright (c) 1998 Hewlett-Packard Company" in the common sRGB profile), a device manufacturer
 * and model description ("dmnd", "dmdd"), and header fields for the device manufacturer, model,
 * creator, platform, CMM, creation date and an MD5 profile ID. Photo viewers show these as
 * "Copyright" and "Manufacturer".
 *
 * The rebuilt profile has those header fields zeroed and only colour-relevant tags (white and
 * black point, primaries, tone curves, chromatic adaptation, lookup tables, luminance, CICP).
 * The description tag is kept only if it is a standard colour space name such as "Display P3"
 * or "sRGB IEC61966-2.1"; otherwise it is replaced with "Colour profile". The colour data itself
 * is copied unchanged, so colours stay the same.
 */
object IccSanitizer {
    private const val HEADER = 128

    /** Tags that describe colour and nothing else. */
    internal val COLOUR_TAGS = setOf(
        "wtpt", "bkpt", "rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC", "kTRC", "chad", "chrm",
        "A2B0", "A2B1", "A2B2", "B2A0", "B2A1", "B2A2", "D2B0", "D2B1", "D2B2", "D2B3",
        "B2D0", "B2D1", "B2D2", "B2D3", "gamt", "lumi", "cicp",
    )

    /** Header byte ranges (start, end exclusive) that can identify a device, vendor or tool. */
    private val IDENTIFYING_HEADER_RANGES = listOf(
        4 to 8, // preferred CMM
        24 to 36, // creation date and time
        40 to 44, // primary platform
        48 to 52, // device manufacturer
        52 to 56, // device model
        56 to 64, // device attributes
        80 to 84, // profile creator
        84 to 100, // profile ID (MD5); zero means "not computed"
        100 to 128, // reserved
    )

    private val STANDARD_NAMES = listOf(
        "srgb iec61966-2.1", "srgb iec61966-2-1", "srgb", "display p3", "p3", "dci-p3", "dci-p3 d65", "p3 d65",
        "adobe rgb (1998)", "prophoto rgb", "rec. 2020", "rec.2020", "itu-r bt.2020", "bt.2020", "bt.709",
        "rec. 709", "itu-r bt.709", "linear srgb", "scrgb", "generic gray gamma 2.2 profile", "gray gamma 2.2",
        "dot gain 20%", "display p3 gamut with srgb transfer", "dci-p3 d65 gamut with srgb transfer",
    )

    const val GENERIC_DESCRIPTION = "Colour profile"

    /** Returns a cleaned profile, or null if [p] is not a well-formed ICC profile. */
    fun sanitize(p: ByteArray): ByteArray? {
        if (p.size < HEADER + 4) return null
        val declared = p.u32be(0)
        if (declared < HEADER + 4 || declared > p.size) return null
        val size = declared.toInt()
        if (p.ascii(36, 4) != "acsp") return null
        val count = p.u32be(HEADER)
        if (count > 200 || HEADER + 4 + 12 * count > size) return null
        val major = p.u8(8)

        class Tag(val sig: String, val offset: Int, val length: Int)

        val tags = ArrayList<Tag>()
        for (i in 0 until count.toInt()) {
            val e = HEADER + 4 + 12 * i
            val off = p.u32be(e + 4)
            val len = p.u32be(e + 8)
            if (off + len > size || off < HEADER + 4 || len < 8) return null
            tags += Tag(p.ascii(e, 4), off.toInt(), len.toInt())
        }

        // Collect the tag data to keep, sharing data between tags that shared it before.
        val keptSigs = ArrayList<String>()
        val keptData = ArrayList<ByteArray>()
        val dataKeys = ArrayList<Pair<Int, Int>?>() // original (offset, length), null for new data
        for (t in tags) {
            if (t.sig in COLOUR_TAGS) {
                if (t.sig in keptSigs) continue
                keptSigs += t.sig
                keptData += p.copyOfRange(t.offset, t.offset + t.length)
                dataKeys += t.offset to t.length
            }
        }
        if (keptSigs.none { it != "wtpt" && it != "bkpt" && it != "lumi" && it != "cicp" && it != "chad" }) return null
        val description = tags.firstOrNull { it.sig == "desc" }
            ?.let { readDescription(p, it.offset, it.length) }
            ?.trim()
            ?.takeIf { d -> STANDARD_NAMES.any { it.equals(d, ignoreCase = true) } }
            ?: GENERIC_DESCRIPTION
        keptSigs.add(0, "desc")
        keptData.add(0, if (major >= 4) mluc(description) else textDescription(description))
        dataKeys.add(0, null)

        // Lay out: header, tag table, then data blocks aligned to 4 bytes.
        val n = keptSigs.size
        val offsets = IntArray(n)
        val body = ByteArrayOutputStream()
        val placed = HashMap<Pair<Int, Int>, Int>()
        var cursor = HEADER + 4 + 12 * n
        for (i in 0 until n) {
            val key = dataKeys[i]
            val existing = key?.let { placed[it] }
            if (existing != null) {
                offsets[i] = existing
                continue
            }
            offsets[i] = cursor
            key?.let { placed[it] = cursor }
            body.write(keptData[i])
            cursor += keptData[i].size
            while (cursor % 4 != 0) {
                body.write(0)
                cursor++
            }
        }

        val out = ByteArrayOutputStream(cursor)
        val header = p.copyOfRange(0, HEADER)
        for ((s, e) in IDENTIFYING_HEADER_RANGES) for (k in s until e) header[k] = 0
        writeU32(header, 0, cursor)
        out.write(header)
        out.write(u32(n))
        for (i in 0 until n) {
            out.write(latin1(keptSigs[i]))
            out.write(u32(offsets[i]))
            out.write(u32(keptData[i].size))
        }
        body.writeTo(out)
        return out.toByteArray()
    }

    /**
     * Problem description if [p] still contains identifying data, or null if it is clean in the
     * sense of [sanitize]'s output.
     */
    fun problem(p: ByteArray): String? {
        if (p.size < HEADER + 4 || p.ascii(36, 4) != "acsp" || p.u32be(0) != p.size.toLong()) return "malformed ICC profile"
        for ((s, e) in IDENTIFYING_HEADER_RANGES) for (k in s until e) if (p[k].toInt() != 0) return "ICC header field at byte $s is not blank"
        val count = p.u32be(HEADER)
        if (HEADER + 4 + 12 * count > p.size) return "malformed ICC tag table"
        for (i in 0 until count.toInt()) {
            val e = HEADER + 4 + 12 * i
            val sig = p.ascii(e, 4)
            if (sig == "desc") {
                val text = readDescription(p, p.u32be(e + 4).toInt(), p.u32be(e + 8).toInt())?.trim()
                if (text != GENERIC_DESCRIPTION && STANDARD_NAMES.none { it.equals(text, ignoreCase = true) }) {
                    return "ICC description is not a standard name"
                }
            } else if (sig !in COLOUR_TAGS) {
                return "ICC tag '$sig' survived"
            }
        }
        return null
    }

    /** Text of a 'desc' (ICC v2) or 'mluc' (ICC v4) element, first record only. */
    internal fun readDescription(p: ByteArray, off: Int, len: Int): String? {
        if (off < 0 || len < 12 || off + len > p.size) return null
        return when (p.ascii(off, 4)) {
            "desc" -> {
                val n = p.u32be(off + 8).toInt()
                if (n <= 0 || 12 + n > len) null else String(p, off + 12, n, Charsets.ISO_8859_1).trimEnd('\u0000')
            }
            "mluc" -> {
                if (len < 28 || p.u32be(off + 8) < 1L) return null
                val strLen = p.u32be(off + 20).toInt()
                val strOff = p.u32be(off + 24).toInt()
                if (strOff + strLen > len) null else String(p, off + strOff, strLen, Charsets.UTF_16BE).trimEnd('\u0000')
            }
            else -> null
        }
    }

    private fun textDescription(text: String): ByteArray {
        val ascii = latin1(text) + 0
        val out = ByteArrayOutputStream()
        out.write(latin1("desc"))
        out.write(u32(0))
        out.write(u32(ascii.size))
        out.write(ascii)
        out.write(ByteArray(4 + 4 + 2 + 1 + 67)) // empty Unicode and ScriptCode descriptions
        return out.toByteArray()
    }

    private fun mluc(text: String): ByteArray {
        val utf16 = text.toByteArray(Charsets.UTF_16BE)
        val out = ByteArrayOutputStream()
        out.write(latin1("mluc"))
        out.write(u32(0))
        out.write(u32(1)) // one record
        out.write(u32(12)) // record size
        out.write(latin1("enUS"))
        out.write(u32(utf16.size))
        out.write(u32(28))
        out.write(utf16)
        return out.toByteArray()
    }

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun writeU32(b: ByteArray, at: Int, v: Int) = u32(v).copyInto(b, at)
}
