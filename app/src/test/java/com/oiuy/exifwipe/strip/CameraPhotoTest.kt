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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

class CameraPhotoTest {
    /** Writes before/after files so they can be inspected with exiftool (see docs/TESTING.md). */
    private fun save(name: String, bytes: ByteArray) {
        val dir = File("build/test-output").apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
    }

    private fun iccOf(jpeg: ByteArray): ByteArray? {
        val chunks = ArrayList<Pair<Int, ByteArray>>()
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            val m = jpeg.u8(pos + 1)
            if (m == 0xDA || m == 0xD9) break
            val len = jpeg.u16be(pos + 2)
            if (m == 0xE2 && jpeg.hasPrefixAt(pos + 4, pos + 2 + len, JpegStripper.ICC_ID)) {
                chunks += jpeg.u8(pos + 4 + 12) to jpeg.copyOfRange(pos + 4 + 14, pos + 2 + len)
            }
            pos += 2 + len
        }
        return if (chunks.isEmpty()) null else JpegStripper.assembleIcc(chunks)
    }

    @Test
    fun fixtureReallyContainsTheIdentifyingData() {
        val input = CameraFixtures.cameraJpeg()
        assertEquals(CameraFixtures.IDENTIFYING, CameraFixtures.leftovers(input))
    }

    @Test
    fun cameraJpegLosesEveryIdentifyingField() {
        val base = Fixtures.jpeg()
        val input = CameraFixtures.cameraJpeg(base)
        val result = MetadataStripper.strip(input)
        save("camera-before.jpg", input)
        save("camera-after.jpg", result.output)
        assertTrue(result.changed)
        assertEquals(emptyList<String>(), CameraFixtures.leftovers(result.output))
        // Only the rebuilt orientation EXIF, the cleaned ICC profile and image segments remain.
        val markers = Fixtures.jpegMarkers(result.output)
        assertEquals(listOf(0xE1, 0xE2), markers.filter { it in 0xE0..0xEF || it == 0xFE })
        assertEquals(6, Orientation.read(result.output, 12, 12 + Orientation.MINIMAL_TIFF_SIZE))
        // Decoded through each file's own colour profile, the colours must be identical.
        assertArrayEquals(Fixtures.pixels(input), Fixtures.pixels(result.output))
    }

    @Test
    fun iccProfileKeepsColourDataAndStandardName() {
        val out = MetadataStripper.strip(CameraFixtures.cameraJpeg()).output
        val icc = iccOf(out)
        assertNotNull(icc)
        assertNull(IccSanitizer.problem(icc!!))
        val original = CameraFixtures.vendorIcc()
        fun tag(p: ByteArray, sig: String): ByteArray? {
            val n = p.u32be(128).toInt()
            for (i in 0 until n) {
                val e = 132 + 12 * i
                if (p.ascii(e, 4) == sig) return p.copyOfRange(p.u32be(e + 4).toInt(), (p.u32be(e + 4) + p.u32be(e + 8)).toInt())
            }
            return null
        }
        for (sig in listOf("wtpt", "chad", "rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC")) {
            assertArrayEquals(sig, tag(original, sig), tag(icc, sig))
        }
        for (sig in listOf("cprt", "dmnd", "dmdd")) assertNull(tag(icc, sig))
        val desc = tag(icc, "desc")!!
        assertEquals(CameraFixtures.ICC_DESCRIPTION, IccSanitizer.readDescription(desc, 0, desc.size))
        // Header: version, class, colour space, PCS, illuminant unchanged; vendor fields blank.
        assertArrayEquals(original.copyOfRange(8, 24), icc.copyOfRange(8, 24))
        assertArrayEquals(original.copyOfRange(64, 80), icc.copyOfRange(64, 80))
        assertTrue(icc.copyOfRange(48, 56).all { it.toInt() == 0 })
        assertTrue(icc.copyOfRange(80, 84).all { it.toInt() == 0 })
        // The three tone curves still share one data block.
        val offsets = (0 until icc.u32be(128).toInt()).map { 132 + 12 * it }
            .filter { icc.ascii(it, 3).endsWith("TR") || icc.ascii(it, 4).endsWith("TRC") }
            .map { icc.u32be(it + 4) }
        assertEquals(1, offsets.toSet().size)
    }

    @Test
    fun outputOfVersionTest1IsCleanedAgain() {
        // 0.1.0-test1 kept the vendor ICC profile; this is the user's "still shows copyright and
        // manufacturer" case.
        val test1 = CameraFixtures.test1Output()
        assertEquals(listOf("Samsung", "Galaxy", "Copyright", "SEC "), CameraFixtures.leftovers(test1))
        val result = MetadataStripper.strip(test1)
        save("test1-before.jpg", test1)
        save("test1-after.jpg", result.output)
        assertTrue(result.changed)
        assertEquals(emptyList<String>(), CameraFixtures.leftovers(result.output))
    }

    @Test
    fun cleanedCameraJpegIsStable() {
        val once = MetadataStripper.strip(CameraFixtures.cameraJpeg()).output
        val twice = MetadataStripper.strip(once)
        assertFalse(twice.changed)
        assertArrayEquals(once, twice.output)
    }

    @Test
    fun largeIccProfileIsSplitAcrossSegments() {
        val icc = CameraFixtures.vendorIcc()
        // Pad with a big colour lookup table so the profile needs two APP2 segments.
        val big = IccSanitizer.sanitize(icc)!!
        val segments = JpegStripper.iccSegments(big + ByteArray(70000))
        assertEquals(0xFF, segments.u8(0))
        var count = 0
        var pos = 0
        while (pos < segments.size) {
            count++
            pos += 2 + segments.u16be(pos + 2)
        }
        assertEquals(2, count)
    }

    @Test
    fun malformedIccIsDropped() {
        val base = Fixtures.jpeg()
        val bad = Fixtures.segment(0xE2, JpegStripper.ICC_ID + byteArrayOf(1, 1) + latin1("Copyright Samsung, not a profile"))
        val out = MetadataStripper.strip(Fixtures.insertAfterSoi(base, bad)).output
        assertNull(iccOf(out))
        assertFalse(Fixtures.contains(out, "Samsung"))
    }

    @Test
    fun iccSanitizerHandlesVersion2Profiles() {
        val v4 = CameraFixtures.vendorIcc()
        val v2 = v4.copyOf().also { it[8] = 2; it[9] = 0x10 }
        val clean = IccSanitizer.sanitize(v2)!!
        assertNull(IccSanitizer.problem(clean))
        val n = clean.u32be(128).toInt()
        val descEntry = (0 until n).map { 132 + 12 * it }.first { clean.ascii(it, 4) == "desc" }
        assertEquals("desc", clean.ascii(clean.u32be(descEntry + 4).toInt(), 4))
        assertArrayEquals(clean, IccSanitizer.sanitize(clean))
    }

    @Test
    fun nonStandardDescriptionIsReplaced() {
        val icc = CameraFixtures.vendorIcc()
        val renamed = String(icc, Charsets.ISO_8859_1)
            .replace(String(CameraFixtures.ICC_DESCRIPTION.toByteArray(Charsets.UTF_16BE), Charsets.ISO_8859_1),
                String("Galaxy S24+ Camera Tuned Gamut".padEnd(CameraFixtures.ICC_DESCRIPTION.length).toByteArray(Charsets.UTF_16BE), Charsets.ISO_8859_1))
            .toByteArray(Charsets.ISO_8859_1)
        val clean = IccSanitizer.sanitize(renamed)!!
        assertFalse(clean.indexOf("Galaxy".toByteArray(Charsets.UTF_16BE)) >= 0)
        assertTrue(clean.indexOf(IccSanitizer.GENERIC_DESCRIPTION.toByteArray(Charsets.UTF_16BE)) >= 0)
    }

    @Test
    fun pngIccpIsCleaned() {
        val icc = CameraFixtures.vendorIcc()
        val deflater = Deflater()
        deflater.setInput(icc); deflater.finish()
        val z = ByteArrayOutputStream().also { o -> val buf = ByteArray(4096); while (!deflater.finished()) o.write(buf, 0, deflater.deflate(buf)) }.toByteArray()
        val iccp = PngStripper.chunk("iCCP", latin1("Samsung P3\u0000\u0000") + z)
        val input = Fixtures.insertAfterIhdr(Fixtures.png(), iccp)
        val out = MetadataStripper.strip(input).output
        save("png-before.png", input)
        save("png-after.png", out)
        assertEquals(emptyList<String>(), CameraFixtures.leftovers(out))
        assertTrue("iCCP" in Fixtures.pngChunks(out))
        assertArrayEquals(Fixtures.pixels(input), Fixtures.pixels(out))
        assertFalse(MetadataStripper.strip(out).changed)
    }

    @Test
    fun webpIccpIsCleaned() {
        val icc = CameraFixtures.vendorIcc()
        val vp8x = WebpStripper.makeChunk("VP8X", byteArrayOf(WebpStripper.FLAG_ICC.toByte(), 0, 0, 0, 9, 0, 0, 9, 0, 0))
        val body = latin1("WEBP") + vp8x + WebpStripper.makeChunk("ICCP", icc) + WebpStripper.makeChunk("VP8L", byteArrayOf(0x2F, 1, 2, 3))
        val n = body.size
        val input = latin1("RIFF") + byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()) + body
        val out = MetadataStripper.strip(input).output
        assertEquals(emptyList<String>(), CameraFixtures.leftovers(out))
        assertEquals(listOf("VP8X", "ICCP", "VP8L"), WebpStripper.parse(out).map { it.fourcc })
        assertEquals(WebpStripper.FLAG_ICC, out.u8(20))
    }
}
