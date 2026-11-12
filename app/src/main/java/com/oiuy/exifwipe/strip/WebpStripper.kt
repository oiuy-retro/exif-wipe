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
 * Removes EXIF and XMP chunks from a WebP file without touching the compressed image data.
 *
 * Simple (lossy "VP8 " or lossless "VP8L") files cannot hold metadata; only their image chunk is
 * kept. Extended ("VP8X") files keep the image, alpha, animation and ICC colour chunks; EXIF, XMP
 * and unknown chunks are dropped and the VP8X flags are updated. A non-normal EXIF Orientation is
 * kept as a minimal EXIF chunk.
 */
object WebpStripper {
    private val KEEP_EXTENDED = setOf("VP8X", "ICCP", "ANIM", "ANMF", "ALPH", "VP8 ", "VP8L")
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

    fun strip(b: ByteArray): ByteArray {
        val chunks = parse(b)
        val first = chunks.first()
        val kept: List<ByteArray> = when (first.fourcc) {
            "VP8 ", "VP8L" -> listOf(copyChunk(b, first))
            "VP8X" -> stripExtended(b, chunks)
            else -> throw StripException("Unrecognised WebP layout")
        }
        val payload = 4 + kept.sumOf { it.size }
        val out = ByteArrayOutputStream(8 + payload)
        out.write(latin1("RIFF"))
        out.write(le32(payload))
        out.write(latin1("WEBP"))
        kept.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun stripExtended(b: ByteArray, chunks: List<Chunk>): List<ByteArray> {
        val vp8x = chunks.first()
        if (vp8x.size < 10) throw StripException("Invalid WebP VP8X chunk")
        if (chunks.none { it.fourcc == "VP8 " || it.fourcc == "VP8L" || it.fourcc == "ANMF" }) {
            throw StripException("WebP contains no image data")
        }

        var orientation: Int? = null
        chunks.firstOrNull { it.fourcc == "EXIF" }?.let { c ->
            var s = c.dataStart
            val e = c.dataStart + c.size
            if (b.hasPrefixAt(s, e, JpegStripper.EXIF_ID)) s += JpegStripper.EXIF_ID.size
            orientation = Orientation.read(b, s, e)
        }

        val out = ArrayList<ByteArray>()
        for (c in chunks) {
            if (c.fourcc !in KEEP_EXTENDED) continue
            if (c === vp8x) {
                val data = b.copyOfRange(c.dataStart, c.dataStart + c.size)
                var flags = data[0].toInt() and 0xFF
                flags = flags and (FLAG_EXIF or FLAG_XMP).inv()
                if (Orientation.needsTag(orientation)) flags = flags or FLAG_EXIF
                if (chunks.none { it.fourcc == "ICCP" }) flags = flags and FLAG_ICC.inv()
                data[0] = flags.toByte()
                out += makeChunk("VP8X", data)
            } else {
                out += copyChunk(b, c)
            }
        }
        if (Orientation.needsTag(orientation)) out += makeChunk("EXIF", Orientation.minimalTiff(orientation!!))
        return out
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
