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

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** Builders for test images and metadata blocks. */
object Fixtures {
    fun image(width: Int = 48, height: Int = 32, alpha: Boolean = false): BufferedImage {
        val img = BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val a = if (alpha) ((x * 5) and 0xFF) else 0xFF
            img.setRGB(x, y, (a shl 24) or ((x * 5 and 0xFF) shl 16) or ((y * 7 and 0xFF) shl 8) or ((x * y) and 0xFF))
        }
        return img
    }

    fun jpeg(progressive: Boolean = false): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam
        if (progressive) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(image(), null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    fun png(alpha: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(image(alpha = alpha), "png", out)
        return out.toByteArray()
    }

    fun pixels(bytes: ByteArray): IntArray {
        val img = ImageIO.read(ByteArrayInputStream(bytes)) ?: error("could not decode")
        return img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
    }

    /**
     * Little-endian TIFF block with Make, Orientation, DateTime and a GPS IFD pointer, followed by
     * a GPS IFD with a latitude reference. Mimics a phone camera's EXIF.
     */
    fun exifTiff(orientation: Int): ByteArray {
        val o = ByteArrayOutputStream()
        fun u16(v: Int) { o.write(v and 0xFF); o.write(v shr 8 and 0xFF) }
        fun u32(v: Int) { u16(v and 0xFFFF); u16(v ushr 16) }
        val make = latin1("SecretCam\u0000")
        val date = latin1("2026:01:02 03:04:05\u0000")
        // Layout: header(8) IFD0 at 8: count(2) + 4 entries(48) + next(4) = 62 -> data at 62
        val makeOff = 8 + 2 + 4 * 12 + 4
        val dateOff = makeOff + make.size
        val gpsOff = dateOff + date.size
        o.write(latin1("II")); u16(42); u32(8)
        u16(4)
        u16(0x010F); u16(2); u32(make.size); u32(makeOff)
        u16(0x0112); u16(3); u32(1); u16(orientation); u16(0)
        u16(0x0132); u16(2); u32(date.size); u32(dateOff)
        u16(0x8825); u16(4); u32(1); u32(gpsOff)
        u32(0)
        o.write(make); o.write(date)
        u16(1)
        u16(0x0001); u16(2); u32(2); o.write('N'.code); o.write(0); u16(0)
        u32(0)
        return o.toByteArray()
    }

    fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    /** Inserts [segments] directly after the SOI marker. */
    fun insertAfterSoi(jpeg: ByteArray, vararg segments: ByteArray): ByteArray =
        jpeg.copyOfRange(0, 2) + segments.fold(ByteArray(0)) { a, s -> a + s } + jpeg.copyOfRange(2, jpeg.size)

    fun pngChunks(png: ByteArray): List<String> {
        val names = ArrayList<String>()
        var pos = 8
        while (pos + 12 <= png.size) {
            val len = png.u32be(pos).toInt()
            names += png.ascii(pos + 4, 4)
            pos += 12 + len
        }
        return names
    }

    fun jpegMarkers(jpeg: ByteArray): List<Int> {
        val markers = ArrayList<Int>()
        var pos = 2
        while (pos + 3 < jpeg.size) {
            val m = jpeg.u8(pos + 1)
            markers += m
            if (m == 0xD9) break
            val len = jpeg.u16be(pos + 2)
            pos += 2 + len
            if (m == 0xDA) {
                while (!(jpeg.u8(pos) == 0xFF && jpeg.u8(pos + 1) != 0 && jpeg.u8(pos + 1) !in 0xD0..0xD7)) pos++
            }
        }
        return markers
    }

    /** Inserts a PNG chunk right after IHDR. */
    fun insertAfterIhdr(png: ByteArray, vararg chunks: ByteArray): ByteArray {
        val ihdrEnd = 8 + 12 + 13
        return png.copyOfRange(0, ihdrEnd) + chunks.fold(ByteArray(0)) { a, s -> a + s } + png.copyOfRange(ihdrEnd, png.size)
    }

    fun contains(haystack: ByteArray, s: String) = haystack.indexOf(latin1(s)) >= 0
}
