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

import com.oiuy.exifwipe.strip.Fixtures.contains
import com.oiuy.exifwipe.strip.Fixtures.exifTiff
import com.oiuy.exifwipe.strip.Fixtures.insertAfterIhdr
import com.oiuy.exifwipe.strip.Fixtures.pngChunks
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class PngStripperTest {
    private val text = PngStripper.chunk("tEXt", latin1("Author\u0000Jane Doe"))
    private val xmp = PngStripper.chunk("iTXt", latin1("XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">http://ns.adobe.com/exif GPS</x:xmpmeta>"))
    private val exif = PngStripper.chunk("eXIf", exifTiff(3))
    private val time = PngStripper.chunk("tIME", byteArrayOf(0x07, 0xEA.toByte(), 1, 2, 3, 4, 5))
    private val phys = PngStripper.chunk("pHYs", byteArrayOf(0, 0, 0x0B, 0x13, 0, 0, 0x0B, 0x13, 1))
    private val gama = PngStripper.chunk("gAMA", byteArrayOf(0, 0, 0xB1.toByte(), 0x8F.toByte()))
    private val privateChunk = PngStripper.chunk("prVt", latin1("vendor data"))

    @Test
    fun removesMetadataChunksAndKeepsPixels() {
        val base = Fixtures.png(alpha = true)
        val input = insertAfterIhdr(base, text, xmp, exif, time, phys, gama, privateChunk) + latin1("trailing")
        val result = MetadataStripper.strip(input)
        assertTrue(result.changed)
        val out = result.output
        for (s in listOf("Jane Doe", "xmpmeta", "SecretCam", "2026:01:02", "vendor", "trailing")) {
            assertFalse("still contains $s", contains(out, s))
        }
        val names = pngChunks(out)
        assertEquals("IHDR", names.first())
        assertEquals("IEND", names.last())
        assertTrue("gAMA" in names)
        assertTrue(names.none { it in setOf("tEXt", "iTXt", "tIME", "pHYs", "prVt") })
        assertEquals(names.indexOf("IDAT") - 1, names.indexOf("eXIf"))
        assertArrayEquals(Fixtures.pixels(base), Fixtures.pixels(out))
    }

    @Test
    fun keepsOrientationOnly() {
        val out = PngStripper.strip(insertAfterIhdr(Fixtures.png(), exif))
        var pos = 8
        while (out.ascii(pos + 4, 4) != "eXIf") pos += 12 + out.u32be(pos).toInt()
        val len = out.u32be(pos).toInt()
        assertTrue(Orientation.isMinimalTiff(out, pos + 8, len))
        assertEquals(3, Orientation.read(out, pos + 8, pos + 8 + len))
    }

    @Test
    fun keepsPaletteTransparency() {
        val cm = IndexColorModel(8, 2, byteArrayOf(0, -1), byteArrayOf(0, 0), byteArrayOf(0, -1), byteArrayOf(0, -1))
        val img = BufferedImage(8, 8, BufferedImage.TYPE_BYTE_INDEXED, cm)
        for (y in 0 until 8) for (x in 0 until 8) img.raster.setSample(x, y, 0, (x + y) % 2)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        val input = insertAfterIhdr(bytes, text)
        val out = PngStripper.strip(input)
        assertTrue("tRNS" in pngChunks(out))
        assertArrayEquals(Fixtures.pixels(bytes), Fixtures.pixels(out))
    }

    @Test
    fun idempotent() {
        val once = MetadataStripper.strip(insertAfterIhdr(Fixtures.png(), text, exif)).output
        val twice = MetadataStripper.strip(once)
        assertFalse(twice.changed)
        assertArrayEquals(once, twice.output)
    }

    @Test(expected = StripException::class)
    fun corruptImageDataFails() {
        val base = Fixtures.png()
        val idat = base.indexOf(latin1("IDAT"))
        val broken = base.copyOf()
        broken[idat + 6] = (broken[idat + 6] + 1).toByte()
        PngStripper.strip(broken)
    }

    @Test(expected = StripException::class)
    fun missingIendFails() {
        val base = Fixtures.png()
        PngStripper.strip(base.copyOfRange(0, base.size - 12))
    }
}
