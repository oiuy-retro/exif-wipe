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
 * Rewrites the metadata chunks of a WebP file without touching the compressed image data.
 *
 * Image, alpha and animation chunks are copied. EXIF, XMP and ICCP are collected into a
 * [SourceMeta]; the [Planner] decides what is written back, and the VP8X flags are set to match.
 * Unknown chunks are dropped. A simple (lossy "VP8 " or lossless "VP8L") file cannot hold
 * metadata, so it is converted to the extended layout (a VP8X header in front of the unchanged
 * image chunk) only when the plan writes EXIF, XMP or a colour profile. [strip] uses the version
 * 0.1.0 plan: a minimal EXIF chunk for a non-normal orientation and the sanitized ICC profile.
 */
object WebpStripper {
    private val IMAGE_CHUNKS = setOf("ANIM", "ANMF", "ALPH", "VP8 ", "VP8L")
    internal const val FLAG_ICC = 0x20
    internal const val FLAG_ALPHA = 0x10
    internal const val FLAG_EXIF = 0x08
    internal const val FLAG_XMP = 0x04
    internal const val FLAG_ANIMATION = 0x02

    internal class Chunk(val fourcc: String, val start: Int, val dataStart: Int, val size: Int, val end: Int)

    internal fun parse(b: ByteArray): List<Chunk> {
        if (b.size < 12 || b.ascii(0, 4) != "RIFF" || b.ascii(8, 4) != "WEBP") throw StripException("Not a WebP file")
        val riffEnd = 8L + b.u32le(4)
        if (riffEnd > b.size) throw StripException("WebP file is truncated")
        val chunks = ArrayList<Chunk>()
        var pos = 12
        while (pos < riffEnd) {
            if (pos + 8 > riffEnd) throw StripException("WebP chunk header is truncated")
            val size = b.u32le(pos + 4)
            val padded = size + (size and 1L)
            if (pos + 8 + padded > riffEnd) {
                // Some writers omit the final pad byte; accept that only for the last chunk.
                if (pos + 8 + size != riffEnd) throw StripException("WebP chunk runs past the end of the file")
            }
            val end = minOf(pos + 8 + padded, riffEnd).toInt()
            chunks += Chunk(b.ascii(pos, 4), pos, pos + 8, size.toInt(), end)
            pos = end
        }
        if (chunks.isEmpty()) throw StripException("WebP contains no image data")
        return chunks
    }

    fun strip(b: ByteArray): ByteArray = rewrite(b) { OutputMeta.default(it) }.output

    fun rewrite(b: ByteArray, planner: Planner): Rewrite {
        val chunks = parse(b)
        val first = chunks.first()
        fun data(c: Chunk) = b.copyOfRange(c.dataStart, c.dataStart + c.size)
        val simple = first.fourcc == "VP8 " || first.fourcc == "VP8L"
        if (!simple && first.fourcc != "VP8X") throw StripException("Unrecognised WebP layout")

        val width: Int
        val height: Int
        var alpha = false
        var flags = 0
        if (simple) {
            // Only needed if metadata is added; unreadable headers fail later in that case.
            val dims = simpleDimensions(b, first)
            width = dims?.first ?: 0
            height = dims?.second ?: 0
            alpha = dims?.third ?: false
        } else {
            if (first.size < 10) throw StripException("Invalid WebP VP8X chunk")
            if (chunks.none { it.fourcc == "VP8 " || it.fourcc == "VP8L" || it.fourcc == "ANMF" }) {
                throw StripException("WebP contains no image data")
            }
            flags = b.u8(first.dataStart)
            width = 1 + (b.u8(first.dataStart + 4) or (b.u8(first.dataStart + 5) shl 8) or (b.u8(first.dataStart + 6) shl 16))
            height = 1 + (b.u8(first.dataStart + 7) or (b.u8(first.dataStart + 8) shl 8) or (b.u8(first.dataStart + 9) shl 16))
        }

        var orientation: Int? = null
        var tiff: TiffData? = null
        chunks.firstOrNull { it.fourcc == "EXIF" }?.let { c ->
            var s = c.dataStart
            val e = c.dataStart + c.size
            if (b.hasPrefixAt(s, e, JpegStripper.EXIF_ID)) s += JpegStripper.EXIF_ID.size
            orientation = Orientation.read(b, s, e)
            tiff = Tiff.read(b, s, e)
        }
        val xmp = chunks.firstOrNull { it.fourcc == "XMP " }?.let { String(data(it), Charsets.UTF_8) }
        val icc = chunks.firstOrNull { it.fourcc == "ICCP" }?.let { data(it) }
        val src = SourceMeta(ImageFormat.WEBP, width, height, tiff, orientation, xmp, null, icc)
        val plan = planner.plan(src)

        val out = ArrayList<ByteArray>()
        if (simple && plan.exif == null && plan.xmp == null && plan.icc == null) {
            out += copyChunk(b, first)
        } else {
            var f = flags and (FLAG_EXIF or FLAG_XMP or FLAG_ICC).inv()
            if (simple && alpha) f = f or FLAG_ALPHA
            if (plan.exif != null) f = f or FLAG_EXIF
            if (plan.xmp != null) f = f or FLAG_XMP
            if (plan.icc != null) f = f or FLAG_ICC
            if (simple && (width <= 0 || height <= 0)) throw StripException("Invalid WebP image header")
            val vp8x = if (simple) ByteArray(10) else data(first)
            vp8x[0] = f.toByte()
            if (simple) {
                put24(vp8x, 4, width - 1)
                put24(vp8x, 7, height - 1)
            }
            out += makeChunk("VP8X", vp8x)
            // Chunk order from the WebP container specification: VP8X, ICCP, ANIM/image, EXIF, XMP.
            plan.icc?.let { out += makeChunk("ICCP", it) }
            for (c in chunks) if (c.fourcc in IMAGE_CHUNKS) out += copyChunk(b, c)
            plan.exif?.let { out += makeChunk("EXIF", it) }
            plan.xmp?.let { out += makeChunk("XMP ", it) }
        }
        val payload = 4 + out.sumOf { it.size }
        val bytes = ByteArrayOutputStream(8 + payload)
        bytes.write(latin1("RIFF"))
        bytes.write(le32(payload))
        bytes.write(latin1("WEBP"))
        out.forEach { bytes.write(it) }
        return Rewrite(bytes.toByteArray(), plan, src)
    }

    /** (width, height, alpha) of a simple WebP from its VP8 or VP8L header. */
    internal fun simpleDimensions(b: ByteArray, c: Chunk): Triple<Int, Int, Boolean>? {
        val d = c.dataStart
        return if (c.fourcc == "VP8L") {
            if (c.size < 5 || b.u8(d) != 0x2F) return null
            val bits = b.u32le(d + 1)
            Triple(((bits and 0x3FFF) + 1).toInt(), (((bits shr 14) and 0x3FFF) + 1).toInt(), (bits shr 28) and 1L == 1L)
        } else {
            if (c.size < 10 || b.u8(d + 3) != 0x9D || b.u8(d + 4) != 0x01 || b.u8(d + 5) != 0x2A) return null
            Triple(b.u16le(d + 6) and 0x3FFF, b.u16le(d + 8) and 0x3FFF, false)
        }
    }

    private fun put24(a: ByteArray, at: Int, v: Int) {
        a[at] = v.toByte(); a[at + 1] = (v shr 8).toByte(); a[at + 2] = (v shr 16).toByte()
    }

    private fun copyChunk(b: ByteArray, c: Chunk): ByteArray = makeChunk(c.fourcc, b.copyOfRange(c.dataStart, c.dataStart + c.size))

    internal fun makeChunk(fourcc: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(8 + data.size + 1)
        out.write(latin1(fourcc))
        out.write(le32(data.size))
        out.write(data)
        if (data.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
}
