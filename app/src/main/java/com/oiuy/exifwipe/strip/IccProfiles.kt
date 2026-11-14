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
import kotlin.math.roundToInt

/**
 * Builds standard RGB display profiles (ICC version 4.3) from their published definitions, so no
 * vendor profile file has to be shipped or copied:
 *  - sRGB: ITU-R BT.709 primaries, D65 white, the sRGB tone curve (IEC 61966-2-1),
 *  - Display P3: DCI-P3 primaries, D65 white, the same sRGB tone curve (as used by Apple devices).
 *
 * Colorants are computed from the primaries and adapted to the D50 connection space with the
 * Bradford transform; the adaptation matrix is stored in 'chad'. The header carries no creator,
 * device, platform or date, and the profile has no copyright tag, so it passes
 * [IccSanitizer.problem].
 */
object IccProfiles {
    enum class Space(val description: String, val primaries: DoubleArray) {
        SRGB("sRGB IEC61966-2.1", doubleArrayOf(0.640, 0.330, 0.300, 0.600, 0.150, 0.060)),
        DISPLAY_P3("Display P3", doubleArrayOf(0.680, 0.320, 0.265, 0.690, 0.150, 0.060)),
    }

    private val D65 = doubleArrayOf(0.3127 / 0.3290, 1.0, (1 - 0.3127 - 0.3290) / 0.3290)
    /** D50 as encoded in ICC profiles. */
    private val D50 = doubleArrayOf(0.9642, 1.0, 0.8249)
    private val BRADFORD = arrayOf(
        doubleArrayOf(0.8951, 0.2664, -0.1614),
        doubleArrayOf(-0.7502, 1.7135, 0.0367),
        doubleArrayOf(0.0389, -0.0685, 1.0296),
    )

    private val cache = HashMap<Space, ByteArray>()

    fun profile(space: Space): ByteArray = synchronized(cache) { cache.getOrPut(space) { build(space) } }.copyOf()

    /** Columns are the XYZ (D50) of the red, green and blue colorants. */
    internal fun colorants(space: Space): Array<DoubleArray> {
        val p = space.primaries
        val xyz = Array(3) { i ->
            val x = p[2 * i]
            val y = p[2 * i + 1]
            doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        }
        // Scale the primaries so that R + G + B = white.
        val m = Array(3) { r -> DoubleArray(3) { c -> xyz[c][r] } }
        val s = mul(inv(m), D65)
        val rgbToXyz = Array(3) { r -> DoubleArray(3) { c -> m[r][c] * s[c] } }
        return mulM(adaptation(), rgbToXyz)
    }

    /** Bradford chromatic adaptation from D65 to D50. */
    internal fun adaptation(): Array<DoubleArray> {
        val src = mul(BRADFORD, D65)
        val dst = mul(BRADFORD, D50)
        val diag = Array(3) { r -> DoubleArray(3) { c -> if (r == c) dst[r] / src[r] else 0.0 } }
        return mulM(inv(BRADFORD), mulM(diag, BRADFORD))
    }

    private fun build(space: Space): ByteArray {
        val m = colorants(space)
        fun xyz(v: DoubleArray) = latin1("XYZ ") + u32(0) + v.fold(ByteArray(0)) { a, x -> a + s15(x) }
        val tags = listOf(
            "desc" to mluc(space.description),
            "wtpt" to xyz(D50),
            "rXYZ" to xyz(doubleArrayOf(m[0][0], m[1][0], m[2][0])),
            "gXYZ" to xyz(doubleArrayOf(m[0][1], m[1][1], m[2][1])),
            "bXYZ" to xyz(doubleArrayOf(m[0][2], m[1][2], m[2][2])),
            "rTRC" to srgbCurve(),
            "gTRC" to srgbCurve(),
            "bTRC" to srgbCurve(),
            "chad" to latin1("sf32") + u32(0) + adaptation().flatMap { it.toList() }.fold(ByteArray(0)) { a, x -> a + s15(x) },
        )
        // The three tone curves share one data block.
        val blocks = LinkedHashMap<String, ByteArray>()
        val offsets = IntArray(tags.size)
        var cursor = 128 + 4 + 12 * tags.size
        val body = ByteArrayOutputStream()
        val placed = HashMap<String, Int>()
        tags.forEachIndexed { i, (sig, data) ->
            val key = if (sig.endsWith("TRC")) "TRC" else sig
            val at = placed[key]
            if (at != null) { offsets[i] = at; return@forEachIndexed }
            offsets[i] = cursor
            placed[key] = cursor
            blocks[key] = data
            body.write(data)
            cursor += data.size
            while (cursor % 4 != 0) { body.write(0); cursor++ }
        }
        val header = ByteArray(128)
        u32(cursor).copyInto(header, 0)
        byteArrayOf(4, 0x30, 0, 0).copyInto(header, 8) // version 4.3
        latin1("mntr").copyInto(header, 12)
        latin1("RGB ").copyInto(header, 16)
        latin1("XYZ ").copyInto(header, 20)
        latin1("acsp").copyInto(header, 36)
        // Rendering intent 0 (perceptual) at 64; PCS illuminant at 68.
        (s15(D50[0]) + s15(D50[1]) + s15(D50[2])).copyInto(header, 68)
        val out = ByteArrayOutputStream(cursor)
        out.write(header)
        out.write(u32(tags.size))
        tags.forEachIndexed { i, (sig, data) ->
            out.write(latin1(sig))
            out.write(u32(offsets[i]))
            out.write(u32(data.size))
        }
        body.writeTo(out)
        return out.toByteArray()
    }

    /** 'para' function type 3: the sRGB curve, Y = ((X + 0.055) / 1.055)^2.4 above 0.04045, X / 12.92 below. */
    private fun srgbCurve(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(latin1("para"))
        out.write(u32(0))
        out.write(byteArrayOf(0, 3, 0, 0))
        for (v in doubleArrayOf(2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045)) out.write(s15(v))
        return out.toByteArray()
    }

    private fun mluc(text: String): ByteArray {
        val utf16 = text.toByteArray(Charsets.UTF_16BE)
        return latin1("mluc") + u32(0) + u32(1) + u32(12) + latin1("enUS") + u32(utf16.size) + u32(28) + utf16
    }

    private fun s15(v: Double): ByteArray = u32((v * 65536.0).roundToInt())

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun mul(m: Array<DoubleArray>, v: DoubleArray) = DoubleArray(3) { r -> (0..2).sumOf { m[r][it] * v[it] } }

    private fun mulM(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(3) { r -> DoubleArray(3) { c -> (0..2).sumOf { a[r][it] * b[it][c] } } }

    private fun inv(m: Array<DoubleArray>): Array<DoubleArray> {
        val (a, b, c) = m[0].let { Triple(it[0], it[1], it[2]) }
        val (d, e, f) = m[1].let { Triple(it[0], it[1], it[2]) }
        val (g, h, i) = m[2].let { Triple(it[0], it[1], it[2]) }
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        return arrayOf(
            doubleArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
            doubleArrayOf((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
            doubleArrayOf((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det),
        )
    }
}
