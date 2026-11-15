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

/** IPTC-IIM datasets inside Photoshop image resource blocks (JPEG APP13). */
object Iptc {
    private val BIM = latin1("8BIM")
    private const val IPTC_RESOURCE = 0x0404

    class DataSet(val record: Int, val number: Int, val value: ByteArray)

    /** IPTC-IIM bytes of resource 0x0404, or null. */
    fun fromPhotoshop(b: ByteArray, start: Int, end: Int): ByteArray? {
        var p = start
        while (p + 12 <= end) {
            if (!b.hasPrefixAt(p, end, BIM)) return null
            val id = b.u16be(p + 4)
            val nameLen = b.u8(p + 6)
            var q = p + 7 + nameLen
            if ((1 + nameLen) % 2 == 1) q++
            if (q + 4 > end) return null
            val size = b.u32be(q)
            val data = q + 4
            if (data + size > end) return null
            if (id == IPTC_RESOURCE) return b.copyOfRange(data, data + size.toInt())
            p = data + size.toInt() + (size.toInt() and 1)
        }
        return null
    }

    fun toPhotoshop(iim: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(BIM)
        out.write(byteArrayOf((IPTC_RESOURCE shr 8).toByte(), IPTC_RESOURCE.toByte(), 0, 0))
        val n = iim.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(iim)
        if (n % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    /** Datasets in order; stops at the first malformed or extended-length one. */
    fun parse(iim: ByteArray): List<DataSet> {
        val list = ArrayList<DataSet>()
        var p = 0
        while (p + 5 <= iim.size && iim.u8(p) == 0x1C) {
            val len = iim.u16be(p + 3)
            if (len and 0x8000 != 0 || p + 5 + len > iim.size) break
            list += DataSet(iim.u8(p + 1), iim.u8(p + 2), iim.copyOfRange(p + 5, p + 5 + len))
            p += 5 + len
        }
        return list
    }

    fun write(sets: List<DataSet>): ByteArray {
        val out = ByteArrayOutputStream()
        for (s in sets) {
            out.write(byteArrayOf(0x1C, s.record.toByte(), s.number.toByte(), (s.value.size shr 8).toByte(), s.value.size.toByte()))
            out.write(s.value)
        }
        return out.toByteArray()
    }
}
