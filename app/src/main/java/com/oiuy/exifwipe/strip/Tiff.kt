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

/** TIFF field types used by EXIF. */
object TiffType {
    const val BYTE = 1
    const val ASCII = 2
    const val SHORT = 3
    const val LONG = 4
    const val RATIONAL = 5
    const val SBYTE = 6
    const val UNDEFINED = 7
    const val SSHORT = 8
    const val SLONG = 9
    const val SRATIONAL = 10
    const val FLOAT = 11
    const val DOUBLE = 12

    fun size(type: Int): Int = when (type) {
        BYTE, ASCII, SBYTE, UNDEFINED -> 1
        SHORT, SSHORT -> 2
        LONG, SLONG, FLOAT -> 4
        RATIONAL, SRATIONAL, DOUBLE -> 8
        else -> 0
    }

    /** Width of one byte-order-dependent unit inside a value of [type] (rationals are two 4-byte numbers). */
    fun unit(type: Int): Int = when (type) {
        SHORT, SSHORT -> 2
        LONG, SLONG, FLOAT, RATIONAL, SRATIONAL -> 4
        DOUBLE -> 8
        else -> 1
    }
}

/** The IFDs an EXIF block can hold. */
enum class Ifd { IFD0, EXIF, GPS, IFD1 }

/** One TIFF field. [value] is stored big-endian, independent of the file's byte order. */
class TiffEntry(val tag: Int, val type: Int, val count: Int, val value: ByteArray) {
    init {
        require(TiffType.size(type) > 0) { "unknown TIFF type $type" }
        require(value.size == count * TiffType.size(type)) { "value size does not match count for tag $tag" }
    }

    fun ascii(): String = String(value, Charsets.ISO_8859_1).trimEnd('\u0000')

    /** First value as an integer for SHORT, LONG, BYTE types; null otherwise. */
    fun int(index: Int = 0): Long? = when (type) {
        TiffType.SHORT -> if (count > index) value.u16be(index * 2).toLong() else null
        TiffType.LONG -> if (count > index) value.u32be(index * 4) else null
        TiffType.BYTE -> if (count > index) value.u8(index).toLong() else null
        else -> null
    }

    /** Rational number at [index] for RATIONAL / SRATIONAL types. */
    fun rational(index: Int = 0): Double? {
        if (count <= index) return null
        return when (type) {
            TiffType.RATIONAL -> {
                val d = value.u32be(index * 8 + 4)
                if (d == 0L) null else value.u32be(index * 8).toDouble() / d
            }
            TiffType.SRATIONAL -> {
                val n = value.u32be(index * 8).toInt()
                val d = value.u32be(index * 8 + 4).toInt()
                if (d == 0) null else n.toDouble() / d
            }
            else -> null
        }
    }

    companion object {
        fun ascii(tag: Int, s: String): TiffEntry {
            val b = latin1(s) + 0
            return TiffEntry(tag, TiffType.ASCII, b.size, b)
        }

        fun short(tag: Int, vararg v: Int): TiffEntry {
            val b = ByteArray(v.size * 2)
            v.forEachIndexed { i, x -> b[2 * i] = (x shr 8).toByte(); b[2 * i + 1] = x.toByte() }
            return TiffEntry(tag, TiffType.SHORT, v.size, b)
        }

        fun long(tag: Int, vararg v: Long): TiffEntry {
            val b = ByteArray(v.size * 4)
            v.forEachIndexed { i, x -> be32(x.toInt()).copyInto(b, 4 * i) }
            return TiffEntry(tag, TiffType.LONG, v.size, b)
        }

        fun rational(tag: Int, vararg v: Pair<Long, Long>): TiffEntry {
            val b = ByteArray(v.size * 8)
            v.forEachIndexed { i, (n, d) -> be32(n.toInt()).copyInto(b, 8 * i); be32(d.toInt()).copyInto(b, 8 * i + 4) }
            return TiffEntry(tag, TiffType.RATIONAL, v.size, b)
        }

        fun srational(tag: Int, n: Int, d: Int): TiffEntry = TiffEntry(tag, TiffType.SRATIONAL, 1, be32(n) + be32(d))

        fun undefined(tag: Int, b: ByteArray): TiffEntry = TiffEntry(tag, TiffType.UNDEFINED, b.size, b)

        fun bytes(tag: Int, vararg v: Int): TiffEntry = TiffEntry(tag, TiffType.BYTE, v.size, ByteArray(v.size) { v[it].toByte() })

        internal fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }
}

/** Parsed EXIF block: entries per IFD (pointer tags removed) plus the IFD1 thumbnail. */
class TiffData(
    val entries: Map<Ifd, List<TiffEntry>>,
    val thumbnail: ByteArray?,
    val bigEndian: Boolean,
) {
    fun get(ifd: Ifd, tag: Int): TiffEntry? = entries[ifd]?.firstOrNull { it.tag == tag }
}

object Tiff {
    const val TAG_EXIF_IFD = 0x8769
    const val TAG_GPS_IFD = 0x8825
    const val TAG_INTEROP_IFD = 0xA005
    const val TAG_THUMB_OFFSET = 0x0201
    const val TAG_THUMB_LENGTH = 0x0202
    private val POINTERS = setOf(TAG_EXIF_IFD, TAG_GPS_IFD, TAG_INTEROP_IFD, TAG_THUMB_OFFSET, TAG_THUMB_LENGTH)
    private const val MAX_ENTRIES = 1000

    /**
     * Reads a TIFF/EXIF block from [b] between [start] and [end]. Returns null if it is not a
     * readable TIFF. Malformed entries are skipped; values that point outside the block are dropped.
     */
    fun read(b: ByteArray, start: Int = 0, end: Int = b.size): TiffData? {
        if (start < 0 || end > b.size || end - start < 8) return null
        val little = when (b.ascii(start, 2)) {
            "II" -> true
            "MM" -> false
            else -> return null
        }
        val len = end - start
        fun u16(p: Int) = if (little) b.u16le(start + p) else b.u16be(start + p)
        fun u32(p: Int) = if (little) b.u32le(start + p) else b.u32be(start + p)
        if (u16(2) != 42) return null

        val visited = HashSet<Long>()
        fun readIfd(offset: Long): Pair<List<Pair<TiffEntry, Long>>, Long>? {
            if (offset < 8 || offset + 2 > len || !visited.add(offset)) return null
            val o = offset.toInt()
            val n = u16(o)
            if (n > MAX_ENTRIES || o + 2 + 12L * n > len) return null
            val list = ArrayList<Pair<TiffEntry, Long>>()
            for (i in 0 until n) {
                val e = o + 2 + 12 * i
                val tag = u16(e)
                val type = u16(e + 2)
                val count = u32(e + 4)
                val size = TiffType.size(type)
                if (size == 0 || count > len) continue
                val total = count * size
                val valuePos = if (total <= 4) (e + 8).toLong() else u32(e + 8)
                if (valuePos + total > len) continue
                val raw = b.copyOfRange(start + valuePos.toInt(), start + (valuePos + total).toInt())
                if (little) toBigEndian(raw, TiffType.unit(type))
                val pointer = if (type == TiffType.LONG || type == TiffType.SHORT) {
                    if (type == TiffType.LONG) raw.u32be(0) else raw.u16be(0).toLong()
                } else -1L
                list += TiffEntry(tag, type, count.toInt(), raw) to pointer
            }
            val next = if (o + 2 + 12 * n + 4 <= len) u32(o + 2 + 12 * n) else 0L
            return list to next
        }

        val result = LinkedHashMap<Ifd, List<TiffEntry>>()
        val (ifd0, next) = readIfd(u32(4)) ?: return null
        result[Ifd.IFD0] = ifd0.map { it.first }.filter { it.tag !in POINTERS }
        ifd0.firstOrNull { it.first.tag == TAG_EXIF_IFD }?.let { (_, p) ->
            readIfd(p)?.let { (l, _) -> result[Ifd.EXIF] = l.map { it.first }.filter { it.tag !in POINTERS } }
        }
        ifd0.firstOrNull { it.first.tag == TAG_GPS_IFD }?.let { (_, p) ->
            readIfd(p)?.let { (l, _) -> result[Ifd.GPS] = l.map { it.first } }
        }
        var thumb: ByteArray? = null
        if (next != 0L) {
            readIfd(next)?.let { (l, _) ->
                result[Ifd.IFD1] = l.map { it.first }.filter { it.tag !in POINTERS }
                val off = l.firstOrNull { it.first.tag == TAG_THUMB_OFFSET }?.second ?: -1L
                val n = l.firstOrNull { it.first.tag == TAG_THUMB_LENGTH }?.second ?: -1L
                if (off > 0 && n > 0 && off + n <= len) thumb = b.copyOfRange(start + off.toInt(), start + (off + n).toInt())
            }
        }
        return TiffData(result, thumb, !little)
    }

    private fun toBigEndian(raw: ByteArray, unit: Int) {
        if (unit <= 1) return
        var i = 0
        while (i + unit <= raw.size) {
            var a = i
            var z = i + unit - 1
            while (a < z) {
                val t = raw[a]; raw[a] = raw[z]; raw[z] = t
                a++; z--
            }
            i += unit
        }
    }

    /**
     * Writes a new TIFF block from scratch. Entries are sorted by tag; sub-IFD pointers and the
     * thumbnail offset/length are generated. Empty IFDs are left out. Returns null if nothing at all
     * would be written.
     */
    fun write(entries: Map<Ifd, List<TiffEntry>>, thumbnail: ByteArray?, bigEndian: Boolean = true): ByteArray? {
        val ifd0 = entries[Ifd.IFD0].orEmpty().filter { it.tag !in POINTERS }.toMutableList()
        val exif = entries[Ifd.EXIF].orEmpty().filter { it.tag !in POINTERS }.sortedBy { it.tag }
        val gps = entries[Ifd.GPS].orEmpty().sortedBy { it.tag }
        val ifd1Base = entries[Ifd.IFD1].orEmpty().filter { it.tag !in POINTERS }
        if (ifd0.isEmpty() && exif.isEmpty() && gps.isEmpty()) return null

        // Placeholders; real offsets are patched in once the layout is known.
        if (exif.isNotEmpty()) ifd0 += TiffEntry.long(TAG_EXIF_IFD, 0)
        if (gps.isNotEmpty()) ifd0 += TiffEntry.long(TAG_GPS_IFD, 0)
        ifd0.sortBy { it.tag }
        val ifd1 = if (thumbnail != null) {
            (ifd1Base + TiffEntry.long(TAG_THUMB_OFFSET, 0) + TiffEntry.long(TAG_THUMB_LENGTH, thumbnail.size.toLong())).sortedBy { it.tag }
        } else {
            emptyList()
        }

        fun ifdSize(list: List<TiffEntry>) = 2 + 12 * list.size + 4 + list.sumOf { e -> e.value.size.let { if (it > 4) it + (it and 1) else 0 } }
        val off0 = 8
        val offExif = off0 + ifdSize(ifd0)
        val offGps = offExif + if (exif.isEmpty()) 0 else ifdSize(exif)
        val off1 = offGps + if (gps.isEmpty()) 0 else ifdSize(gps)
        val offThumb = off1 + if (ifd1.isEmpty()) 0 else ifdSize(ifd1)

        fun patch(list: List<TiffEntry>, tag: Int, v: Int) = list.map { if (it.tag == tag) TiffEntry.long(tag, v.toLong()) else it }
        val ifd0p = patch(patch(ifd0, TAG_EXIF_IFD, offExif), TAG_GPS_IFD, offGps)
        val ifd1p = patch(ifd1, TAG_THUMB_OFFSET, offThumb)

        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            if (bigEndian) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) } else { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        }
        fun u32(v: Int) {
            if (bigEndian) { u16(v ushr 16); u16(v and 0xFFFF) } else { u16(v and 0xFFFF); u16(v ushr 16) }
        }
        fun value(e: TiffEntry): ByteArray {
            val v = e.value.copyOf()
            if (!bigEndian) toBigEndian(v, TiffType.unit(e.type)) // the swap is symmetric
            return v
        }
        fun writeIfd(list: List<TiffEntry>, at: Int, next: Int) {
            check(out.size() == at)
            var data = at + 2 + 12 * list.size + 4
            val blobs = ByteArrayOutputStream()
            u16(list.size)
            for (e in list) {
                u16(e.tag)
                u16(e.type)
                u32(e.count)
                val v = value(e)
                if (v.size <= 4) {
                    out.write(v)
                    repeat(4 - v.size) { out.write(0) }
                } else {
                    u32(data)
                    blobs.write(v)
                    if (v.size and 1 == 1) blobs.write(0)
                    data += v.size + (v.size and 1)
                }
            }
            u32(next)
            blobs.writeTo(out)
        }
        out.write(latin1(if (bigEndian) "MM" else "II"))
        u16(42)
        u32(8)
        writeIfd(ifd0p, off0, if (ifd1.isEmpty()) 0 else off1)
        if (exif.isNotEmpty()) writeIfd(exif, offExif, 0)
        if (gps.isNotEmpty()) writeIfd(gps, offGps, 0)
        if (ifd1.isNotEmpty()) {
            writeIfd(ifd1p, off1, 0)
            out.write(thumbnail!!)
        }
        return out.toByteArray()
    }
}
