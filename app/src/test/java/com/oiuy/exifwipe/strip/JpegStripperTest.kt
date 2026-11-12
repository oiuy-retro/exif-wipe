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
import com.oiuy.exifwipe.strip.Fixtures.insertAfterSoi
import com.oiuy.exifwipe.strip.Fixtures.jpegMarkers
import com.oiuy.exifwipe.strip.Fixtures.segment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegStripperTest {
    private val exif6 = segment(0xE1, JpegStripper.EXIF_ID + exifTiff(6))
    private val xmp = segment(0xE1, latin1("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>GPS secret</x:xmpmeta>"))
    private val iptc = segment(0xED, latin1("Photoshop 3.0\u00008BIM secret"))
    private val comment = segment(0xFE, latin1("taken at home"))
    private val mpf = segment(0xE2, latin1("MPF\u0000secret"))
    private val icc = segment(0xE2, JpegStripper.ICC_ID + byteArrayOf(1, 1) + latin1("fakeprofile"))
    private val adobe = segment(0xEE, JpegStripper.ADOBE_ID + byteArrayOf(0, 100, 0, 0, 0, 0, 1))

    private fun dirty(base: ByteArray = Fixtures.jpeg()) =
        insertAfterSoi(base, exif6, xmp, iptc, comment, mpf) + latin1("MOTION-PHOTO-VIDEO-TRAILER")

    @Test
    fun removesMetadataAndKeepsPixelsIdentical() {
        val original = dirty()
        val result = MetadataStripper.strip(original)
        assertEquals(ImageFormat.JPEG, result.format)
        assertTrue(result.changed)
        val out = result.output
        for (s in listOf("SecretCam", "2026:01:02", "ns.adobe.com", "Photoshop", "taken at home", "MPF", "TRAILER")) {
            assertFalse("still contains $s", contains(out, s))
        }
        assertArrayEquals(Fixtures.pixels(original), Fixtures.pixels(out))
        assertEquals(0xD9, out.u8(out.size - 1))
    }

    @Test
    fun keepsOnlyOrientationInMinimalExif() {
        val out = JpegStripper.strip(dirty())
        val markers = jpegMarkers(out)
        assertEquals(0xE1, markers.first())
        assertEquals(1, markers.count { it == 0xE1 })
        assertTrue(markers.none { it == 0xE0 || it == 0xED || it == 0xFE })
        assertEquals(6, Orientation.read(out, 12, 12 + Orientation.MINIMAL_TIFF_SIZE))
    }

    @Test
    fun dropsExifEntirelyWhenOrientationIsNormal() {
        val base = Fixtures.jpeg()
        val out = JpegStripper.strip(insertAfterSoi(base, segment(0xE1, JpegStripper.EXIF_ID + exifTiff(1))))
        assertTrue(jpegMarkers(out).none { it in 0xE0..0xEF })
        assertFalse(contains(out, "Exif"))
    }

    @Test
    fun keepsIccAndAdobeSegments() {
        val base = Fixtures.jpeg()
        val out = JpegStripper.strip(insertAfterSoi(base, icc, adobe, mpf, exif6))
        OutputVerifier.verify(ImageFormat.JPEG, out)
        assertTrue(out.indexOf(icc) > 0)
        assertTrue(out.indexOf(adobe) > 0)
        assertFalse(contains(out, "MPF"))
    }

    @Test
    fun scanDataIsCopiedByteForByte() {
        val base = Fixtures.jpeg()
        val sos = (2 until base.size - 1).first { base.u8(it) == 0xFF && base.u8(it + 1) == 0xDA }
        val tail = base.copyOfRange(sos, base.size)
        val out = JpegStripper.strip(insertAfterSoi(base, exif6, comment))
        assertTrue(out.indexOf(tail) > 0)
        assertEquals(out.size, out.indexOf(tail) + tail.size)
    }

    @Test
    fun progressiveJpegWithMetadataBetweenScans() {
        val base = Fixtures.jpeg(progressive = true)
        // Put a comment after the first scan's data, before the next marker.
        val markers = ArrayList<Int>()
        var secondMarker = -1
        var pos = 2
        var scans = 0
        while (pos < base.size) {
            val m = base.u8(pos + 1)
            markers += m
            val len = base.u16be(pos + 2)
            pos += 2 + len
            if (m == 0xDA) {
                scans++
                while (!(base.u8(pos) == 0xFF && base.u8(pos + 1) != 0 && base.u8(pos + 1) !in 0xD0..0xD7)) pos++
                if (scans == 1) { secondMarker = pos; break }
            }
        }
        val withComment = base.copyOfRange(0, secondMarker) + comment + base.copyOfRange(secondMarker, base.size)
        val input = insertAfterSoi(withComment, exif6)
        val out = MetadataStripper.strip(input).output
        assertFalse(contains(out, "taken at home"))
        assertArrayEquals(Fixtures.pixels(base), Fixtures.pixels(out))
        assertTrue(jpegMarkers(out).count { it == 0xDA } > 1)
    }

    @Test
    fun strippingIsIdempotent() {
        val once = MetadataStripper.strip(dirty()).output
        val twice = MetadataStripper.strip(once)
        assertFalse(twice.changed)
        assertArrayEquals(once, twice.output)
    }

    @Test
    fun cleanFileIsReportedUnchanged() {
        val base = Fixtures.jpeg()
        val stripped = JpegStripper.strip(base) // drops the JFIF APP0 header
        assertFalse(MetadataStripper.strip(stripped).changed)
    }

    @Test(expected = StripException::class)
    fun truncatedFileFails() {
        val d = dirty()
        val noTrailer = d.copyOfRange(0, d.indexOf(latin1("MOTION")))
        JpegStripper.strip(noTrailer.copyOfRange(0, noTrailer.size - 100))
    }

    @Test(expected = StripException::class)
    fun segmentLengthPastEndFails() {
        val base = Fixtures.jpeg()
        JpegStripper.strip(base.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00) + latin1("Exif"))
    }

    @Test(expected = StripException::class)
    fun garbageBetweenSegmentsFails() {
        val base = Fixtures.jpeg()
        JpegStripper.strip(insertAfterSoi(base, exif6, latin1("junk")))
    }
}
