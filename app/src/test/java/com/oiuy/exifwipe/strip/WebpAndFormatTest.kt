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
import com.oiuy.exifwipe.strip.Fixtures.segment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebpStripperTest {
    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = latin1("WEBP") + chunks.fold(ByteArray(0)) { a, c -> a + c }
        val n = body.size
        return latin1("RIFF") + byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()) + body
    }

    private val vp8l = WebpStripper.makeChunk("VP8L", byteArrayOf(0x2F, 1, 2, 3, 4, 5, 6)) // odd size, padded
    private val iccp = WebpStripper.makeChunk("ICCP", latin1("fakeprofile!"))

    private fun vp8x(flags: Int) = WebpStripper.makeChunk("VP8X", byteArrayOf(flags.toByte(), 0, 0, 0, 9, 0, 0, 9, 0, 0))

    @Test
    fun extendedWebpDropsExifAndXmp() {
        val input = riff(
            vp8x(WebpStripper.FLAG_ICC or WebpStripper.FLAG_EXIF or WebpStripper.FLAG_XMP),
            iccp,
            vp8l,
            WebpStripper.makeChunk("EXIF", JpegStripper.EXIF_ID + exifTiff(8)),
            WebpStripper.makeChunk("XMP ", latin1("<x:xmpmeta>http://ns.adobe.com/ secret</x:xmpmeta>")),
            WebpStripper.makeChunk("UNKN", latin1("vendor")),
        )
        val result = MetadataStripper.strip(input)
        assertTrue(result.changed)
        val out = result.output
        val chunks = WebpStripper.parse(out).map { it.fourcc }
        assertEquals(listOf("VP8X", "ICCP", "VP8L", "EXIF"), chunks)
        assertEquals(WebpStripper.FLAG_ICC or WebpStripper.FLAG_EXIF, out.u8(20))
        assertEquals(out.size.toLong() - 8, out.u32le(4))
        assertTrue(out.indexOf(vp8l) > 0)
        for (s in listOf("SecretCam", "secret", "vendor", "2026")) assertFalse(contains(out, s))
        val exif = WebpStripper.parse(out).last()
        assertEquals(8, Orientation.read(out, exif.dataStart, exif.dataStart + exif.size))
    }

    @Test
    fun normalOrientationClearsExifFlag() {
        val input = riff(vp8x(WebpStripper.FLAG_EXIF), vp8l, WebpStripper.makeChunk("EXIF", exifTiff(1)))
        val out = WebpStripper.strip(input)
        assertEquals(listOf("VP8X", "VP8L"), WebpStripper.parse(out).map { it.fourcc })
        assertEquals(0, out.u8(20))
        OutputVerifier.verify(ImageFormat.WEBP, out)
    }

    @Test
    fun simpleWebpKeepsOnlyImageChunk() {
        val vp8 = WebpStripper.makeChunk("VP8 ", byteArrayOf(1, 2, 3, 4))
        val input = riff(vp8, WebpStripper.makeChunk("XMP ", latin1("junk")))
        val out = MetadataStripper.strip(input).output
        assertArrayEquals(riff(vp8), out)
    }

    @Test
    fun idempotent() {
        val input = riff(vp8x(WebpStripper.FLAG_EXIF), vp8l, WebpStripper.makeChunk("EXIF", exifTiff(6)))
        val once = MetadataStripper.strip(input).output
        val twice = MetadataStripper.strip(once)
        assertFalse(twice.changed)
        assertArrayEquals(once, twice.output)
    }

    @Test(expected = StripException::class)
    fun truncatedFails() {
        val input = riff(vp8x(0), vp8l)
        WebpStripper.strip(input.copyOfRange(0, input.size - 4))
    }
}

class FormatAndVerifierTest {
    @Test
    fun detectsFormats() {
        assertEquals(ImageFormat.JPEG, ImageFormat.detect(Fixtures.jpeg()))
        assertEquals(ImageFormat.PNG, ImageFormat.detect(Fixtures.png()))
        val heic = byteArrayOf(0, 0, 0, 24) + latin1("ftypheic") + ByteArray(12)
        assertEquals(ImageFormat.HEIF, ImageFormat.detect(heic))
        assertEquals(ImageFormat.GIF, ImageFormat.detect(latin1("GIF89a....")))
        assertEquals(ImageFormat.UNKNOWN, ImageFormat.detect(latin1("hello world")))
    }

    @Test
    fun unsupportedFormatsAreRejected() {
        val heic = byteArrayOf(0, 0, 0, 24) + latin1("ftypheic") + ByteArray(12)
        try {
            MetadataStripper.strip(heic)
            fail("expected exception")
        } catch (e: UnsupportedFormatException) {
            assertEquals(ImageFormat.HEIF, e.format)
        }
    }

    @Test
    fun verifierRejectsLeftoverMetadata() {
        val base = JpegStripper.strip(Fixtures.jpeg())
        val withComment = Fixtures.insertAfterSoi(base, segment(0xFE, latin1("hi")))
        val withExif = Fixtures.insertAfterSoi(base, segment(0xE1, JpegStripper.EXIF_ID + exifTiff(6)))
        val withTrailer = base + latin1("x")
        for (bad in listOf(withComment, withExif, withTrailer)) {
            try {
                OutputVerifier.verify(ImageFormat.JPEG, bad)
                fail("verifier accepted a dirty file")
            } catch (_: StripException) {
            }
        }
        OutputVerifier.verify(ImageFormat.JPEG, base)
    }

    @Test
    fun orientationReaderHandlesBothByteOrders() {
        val le = exifTiff(5)
        assertEquals(5, Orientation.read(le, 0, le.size))
        val be = Orientation.minimalTiff(7)
        assertEquals(7, Orientation.read(be, 0, be.size))
        assertTrue(Orientation.isMinimalTiff(be, 0, be.size))
        assertFalse(Orientation.isMinimalTiff(le, 0, le.size))
    }
}
