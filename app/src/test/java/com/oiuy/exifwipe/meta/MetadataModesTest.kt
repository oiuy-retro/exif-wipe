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

package com.oiuy.exifwipe.meta

import com.oiuy.exifwipe.meta.TestPhotos.NOW
import com.oiuy.exifwipe.meta.TestPhotos.exifOf
import com.oiuy.exifwipe.strip.CameraFixtures
import com.oiuy.exifwipe.strip.Fixtures
import com.oiuy.exifwipe.strip.IccProfiles
import com.oiuy.exifwipe.strip.IccSanitizer
import com.oiuy.exifwipe.strip.Ifd
import com.oiuy.exifwipe.strip.JpegStripper
import com.oiuy.exifwipe.strip.MetadataStripper
import com.oiuy.exifwipe.strip.OutputVerifier
import com.oiuy.exifwipe.strip.PngStripper
import com.oiuy.exifwipe.strip.StripResult
import com.oiuy.exifwipe.strip.TiffData
import com.oiuy.exifwipe.strip.ImageFormat
import com.oiuy.exifwipe.strip.WebpStripper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class MetadataModesTest {
    private val source = TestPhotos.jpeg()

    private fun run(options: WipeOptions, input: ByteArray = source, seed: Long = 1): Pair<StripResult, MetadataPlanner> {
        val planner = MetadataPlanner(options, Random(seed), NOW)
        return MetadataStripper.process(input, options, planner) to planner
    }

    private fun only(g: Group, m: Mode) = WipeOptions.DEFAULT.with(g, m)

    private fun tagsOf(t: TiffData?, g: Group): List<Int> =
        t?.entries?.flatMap { (ifd, l) -> l.filter { Tags.group(ifd, it.tag) == g }.map { it.tag } }.orEmpty()

    private fun contains(b: ByteArray, s: String) = TestPhotos.indexOf(b, s.toByteArray()) >= 0

    @Test
    fun defaultOptionsGiveTheSameBytesAsVersion010() {
        for (input in listOf(source, CameraFixtures.cameraJpeg(), Fixtures.png(), Fixtures.jpeg(progressive = true))) {
            val legacy = MetadataStripper.strip(input).output
            val (r, _) = run(WipeOptions.DEFAULT, input)
            assertArrayEquals(legacy, r.output)
            assertNull(r.meta!!.fileName)
        }
    }

    @Test
    fun everyGroupInEveryModeOnlyTouchesItsOwnTags() {
        for (g in Group.entries) for (m in Mode.entries) {
            val (r, _) = run(only(g, m))
            val t = exifOf(r.output)
            for (other in Group.entries) if (other != g) assertTrue("$g=$m leaked $other", tagsOf(t, other).isEmpty())
            val own = tagsOf(t, g)
            when (m) {
                Mode.WIPE -> assertTrue("$g wiped", own.isEmpty())
                Mode.KEEP -> if (g != Group.EXTRAS) assertTrue("$g kept", own.isNotEmpty())
                Mode.REPLACE -> if (g != Group.EXTRAS && g != Group.AUTHOR) assertTrue("$g replaced", own.isNotEmpty())
            }
            assertFalse("maker note", contains(r.output, "CANON-MAKERNOTE-SECRET"))
            assertFalse("document id", contains(r.output, "SECRET-ID"))
            assertTrue("orientation kept", t == null || t.get(Ifd.IFD0, Tags.ORIENTATION)?.int() == 6L)
            assertArrayEquals(Fixtures.pixels(source), Fixtures.pixels(r.output))
            TestPhotos.save("mode-${g.name.lowercase()}-${m.name.lowercase()}.jpg", r.output)
        }
    }

    @Test
    fun keepCopiesTheOriginalValues() {
        val keepAll = WipeOptions(Group.entries.associateWith { Mode.KEEP })
        val (r, _) = run(keepAll)
        val t = exifOf(r.output)!!
        assertEquals(TestPhotos.MODEL, t.get(Ifd.IFD0, Tags.MODEL)!!.ascii())
        assertEquals(TestPhotos.LENS, t.get(Ifd.EXIF, Tags.LENS_MODEL)!!.ascii())
        assertEquals(TestPhotos.DATE, t.get(Ifd.EXIF, Tags.DATE_TIME_ORIGINAL)!!.ascii())
        assertEquals(TestPhotos.SERIAL, t.get(Ifd.EXIF, Tags.BODY_SERIAL)!!.ascii())
        assertEquals("2011:07:04", t.get(Ifd.GPS, Tags.GPS_DATE_STAMP)!!.ascii())
        assertNotNull(t.thumbnail)
        assertNull(t.get(Ifd.EXIF, Tags.MAKER_NOTE))
        assertNull(t.get(Ifd.EXIF, 0x9999))
        // Extras: the trailer stays, XMP keeps known properties only, IPTC is copied.
        assertTrue(contains(r.output, "MOTION-PHOTO-VIDEO-DATA"))
        assertTrue(contains(r.output, "Jane Doe"))
        assertTrue(contains(r.output, "GCamera:MotionPhoto"))
        assertFalse(contains(r.output, "xmptk"))
        assertTrue(contains(r.output, "New York"))
    }

    @Test
    fun xmpAndIptcFollowTheirGroups() {
        val (r, _) = run(only(Group.AUTHOR, Mode.KEEP))
        val out = r.output
        assertTrue("<rdf:li>Jane Doe</rdf:li>", contains(out, "<rdf:li>Jane Doe</rdf:li>"))
        assertFalse("tiff:Make", contains(out, "tiff:Make"))
        assertFalse("GPSLatitude", contains(out, "GPSLatitude"))
        assertFalse("GPSLongitude", contains(out, "GPSLongitude"))
        assertFalse("CreateDate", contains(out, "CreateDate"))
        assertFalse("MotionPhoto", contains(out, "MotionPhoto"))
        assertFalse("New York", contains(out, "New York")) // IPTC city is location
        assertFalse("20110704", contains(out, "20110704")) // IPTC date
        assertTrue("Jane Doe", contains(out, "Jane Doe"))
        val loc = run(only(Group.LOCATION, Mode.KEEP)).first.output
        assertTrue("GPSLatitude", contains(loc, "GPSLatitude"))
        assertTrue("<exif:GPSLongitude>74,0.358W</exif:GPSLongitude>", contains(loc, "<exif:GPSLongitude>74,0.358W</exif:GPSLongitude>"))
        assertTrue("New York", contains(loc, "New York"))
        assertFalse("Jane Doe", contains(loc, "Jane Doe"))
        // Unparseable XMP (undeclared prefix) is dropped completely.
        val keepAllButExtras = WipeOptions(Group.entries.associateWith { Mode.KEEP }).with(Group.EXTRAS, Mode.WIPE)
        val cam = run(keepAllButExtras, CameraFixtures.cameraJpeg()).first.output
        assertFalse("http://ns.adobe.com/xap/1.0/", contains(cam, "http://ns.adobe.com/xap/1.0/"))
    }

    @Test
    fun allCombinationsPassTheSafetyChecks() {
        val modes = Mode.entries
        var n = 0
        for (code in 0 until 729) {
            var c = code
            val map = Group.entries.associateWith { modes[c % 3].also { c /= 3 } }
            val options = WipeOptions(map, icc = IccMode.entries[code % 3], fileName = FileNameMode.entries[code % 3],
                preset = DevicePreset.entries[code % 4])
            val (r, _) = run(options, seed = code.toLong())
            OutputVerifier.verify(r.format, r.output, r.meta)
            n++
        }
        assertEquals(729, n)
    }

    @Test
    fun replacedValuesAreConsistent() {
        val ds = Dataset.get()
        val all = WipeOptions(Group.entries.associateWith { Mode.REPLACE }, fileName = FileNameMode.DEVICE)
        repeat(1500) { seed ->
            val (r, planner) = run(all, seed = seed.toLong())
            val c = planner.choices!!
            val t = exifOf(r.output)!!
            val body = c.body!!
            assertEquals(body.make, t.get(Ifd.IFD0, Tags.MAKE)!!.ascii())
            assertEquals(body.model, t.get(Ifd.IFD0, Tags.MODEL)!!.ascii())
            assertFalse(body.unverified)
            // Date: inside the era, never in the future, all fields equal.
            val dto = t.get(Ifd.EXIF, Tags.DATE_TIME_ORIGINAL)!!.ascii()
            assertEquals(dto, t.get(Ifd.EXIF, Tags.DATE_TIME_DIGITIZED)!!.ascii())
            assertEquals(dto, t.get(Ifd.IFD0, Tags.DATE_TIME)!!.ascii())
            val local = MetadataPlanner.parseExifDate(dto)!!
            assertTrue(dto, local.year >= 2002 && local.year >= body.yearFrom)
            if (body.yearTo != 0) assertTrue(local.year <= body.yearTo + 10)
            assertFalse(local.atOffset(c.moment!!.offset).toInstant().isAfter(NOW))
            // Time zone offset and GPS time match the place.
            val zone = java.time.ZoneId.of(c.place!!.zone)
            assertEquals(zone.rules.getOffset(local), c.moment.offset)
            t.get(Ifd.EXIF, Tags.OFFSET_TIME_ORIGINAL)?.let { assertEquals(c.moment.offsetText, it.ascii()) }
            if (body.writesOffsetTime) assertNotNull(t.get(Ifd.EXIF, Tags.OFFSET_TIME_ORIGINAL))
            val utc = c.moment.utc
            assertEquals(utc.format(java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd")), t.get(Ifd.GPS, Tags.GPS_DATE_STAMP)!!.ascii())
            assertEquals(utc.hour.toDouble(), t.get(Ifd.GPS, Tags.GPS_TIME_STAMP)!!.rational(0)!!, 0.0)
            // Position near the place.
            val pos = MetadataPlanner.gpsPosition(t)!!
            val dLat = (pos.first - c.place.lat) * 110.574
            val dLon = (pos.second - c.place.lon) * 111.32 * Math.cos(Math.toRadians(c.place.lat))
            assertTrue(Math.hypot(dLat, dLon) < 8.0)
            // Lens and exposure.
            val iso = t.get(Ifd.EXIF, Tags.ISO)!!.int()!!.toInt()
            assertTrue("iso $iso for ${body.id}", iso >= body.isoMin && iso <= maxOf(body.isoMax, body.isoMin))
            val lens = c.lens
            if (lens != null) {
                assertNotNull(Compat.of(body, lens))
                assertTrue(lens.year <= local.year)
                val focal = t.get(Ifd.EXIF, Tags.FOCAL_LENGTH)?.rational()
                if (c.compat == Compat.NATIVE) {
                    assertNotNull(focal)
                    assertTrue("$focal in ${lens.model}", focal!! >= lens.focalMin - 0.05 && focal <= lens.focalMax + 0.05)
                    val n = t.get(Ifd.EXIF, Tags.F_NUMBER)!!.rational()!!
                    assertTrue("f/$n on ${lens.model} at $focal", n >= lens.maxApertureAt(focal) - 0.1 && n <= lens.minAperture + 0.01)
                    val f35 = t.get(Ifd.EXIF, Tags.FOCAL_LENGTH_35)!!.int()!!.toInt()
                    val expected = lens.focal35 ?: Math.round(focal * body.crop).toInt()
                    assertEquals("${body.id} ${lens.model}", expected, f35)
                    val lm = t.get(Ifd.EXIF, Tags.LENS_MODEL)
                    if (body.exifAtLeast230) assertEquals(lens.model, lm!!.ascii()) else assertNull(lm)
                } else {
                    assertNull(focal)
                    assertNull(t.get(Ifd.EXIF, Tags.LENS_MODEL))
                    assertNull(t.get(Ifd.EXIF, Tags.F_NUMBER))
                }
            }
            val exposure = t.get(Ifd.EXIF, Tags.EXPOSURE_TIME)!!.rational()!!
            assertTrue(exposure in (1.0 / 12000)..4.0)
            assertNotNull(r.meta!!.fileName)
        }
        assertTrue(ds.bodies.isNotEmpty())
    }

    @Test
    fun presetsUseTheChosenPhone() {
        for (p in DevicePreset.entries.filter { it.bodyId != null }) {
            val o = WipeOptions(Group.entries.associateWith { Mode.REPLACE }, preset = p, fileName = FileNameMode.DEVICE,
                icc = IccMode.REPLACE)
            repeat(50) { seed ->
                val (r, planner) = run(o, seed = seed.toLong())
                val t = exifOf(r.output)!!
                val body = Dataset.get().body(p.bodyId!!)!!
                assertEquals(body.model, t.get(Ifd.IFD0, Tags.MODEL)!!.ascii())
                val name = r.meta!!.fileName!!
                val m = planner.choices!!.moment!!
                when (p) {
                    DevicePreset.IPHONE -> {
                        assertTrue(name, name.matches(Regex("IMG_\\d{4}\\.JPG")))
                        assertTrue(t.get(Ifd.EXIF, Tags.LENS_MODEL)!!.ascii().startsWith("iPhone 13 back dual wide camera"))
                        assertTrue(t.get(Ifd.IFD0, Tags.SOFTWARE)!!.ascii().matches(Regex("(15|16|17|18|26)\\.\\d+(\\.\\d)?")))
                        assertEquals(0xFFFF, t.get(Ifd.EXIF, Tags.COLOR_SPACE)!!.int()!!.toInt())
                        assertTrue(icc(r.output)!!.let { contains(it, "D\u0000i\u0000s\u0000p\u0000l\u0000a\u0000y") })
                        assertTrue(m.local.year >= 2022)
                    }
                    DevicePreset.PIXEL -> {
                        val expected = "PXL_" + m.utc.withNano(m.millis * 1_000_000).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS")) + ".jpg"
                        assertEquals(expected, name)
                        assertTrue(t.get(Ifd.IFD0, Tags.SOFTWARE)!!.ascii().matches(Regex("HDR\\+ 1\\.0\\.\\d{9}zd")))
                        assertFalse(t.bigEndian)
                        assertEquals(1, t.get(Ifd.EXIF, Tags.COLOR_SPACE)!!.int()!!.toInt())
                    }
                    else -> {
                        val expected = m.local.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".jpg"
                        assertEquals(expected, name)
                        assertEquals("samsung", t.get(Ifd.IFD0, Tags.MAKE)!!.ascii())
                        assertNull(t.get(Ifd.EXIF, Tags.LENS_MODEL)) // Exif 2.2 has no lens tags
                    }
                }
                if (seed == 0) TestPhotos.save("preset-${p.name.lowercase()}.jpg", r.output)
            }
        }
    }

    private fun icc(jpeg: ByteArray): ByteArray? {
        val i = TestPhotos.indexOf(jpeg, JpegStripper.ICC_ID)
        if (i < 0) return null
        val len = ((jpeg[i - 2].toInt() and 0xFF) shl 8) or (jpeg[i - 1].toInt() and 0xFF)
        return jpeg.copyOfRange(i + JpegStripper.ICC_ID.size + 2, i - 2 + len)
    }

    @Test
    fun fileNames() {
        val generic = WipeOptions.DEFAULT.copy(fileName = FileNameMode.GENERIC)
        val seen = HashSet<String>()
        repeat(200) { seed ->
            val name = run(generic, seed = seed.toLong()).first.meta!!.fileName!!
            assertTrue(name, name.matches(Regex("IMG_\\d{4}\\.JPG")))
            assertFalse(name == "IMG_0000.JPG")
            seen += name
        }
        assertTrue(seen.size > 150)
        val png = run(generic, Fixtures.png()).first.meta!!.fileName!!
        assertTrue(png, png.matches(Regex("IMG_\\d{4}\\.PNG")))
        // Device style with the date kept uses the kept local time.
        val samsung = WipeOptions.DEFAULT.copy(fileName = FileNameMode.DEVICE, preset = DevicePreset.SAMSUNG).with(Group.DATE, Mode.KEEP)
        assertEquals("20110704_183015.jpg", run(samsung).first.meta!!.fileName)
        val pixel = samsung.copy(preset = DevicePreset.PIXEL)
        assertEquals("PXL_20110704_223015000.jpg", run(pixel).first.meta!!.fileName)
        // Collisions get a new number of the same length.
        val alt = FileNames.alternative("IMG_0042.JPG", Random(3), 1)
        assertTrue(alt, alt.matches(Regex("IMG_\\d{4}\\.JPG")))
        assertEquals("photo (2).jpg", FileNames.alternative("photo.jpg", Random(3), 2))
        assertEquals("IMG_1234.webp", FileNames.withExtension("IMG_1234.jpg", ImageFormat.WEBP))
    }

    @Test
    fun colourProfileModes() {
        val wipe = run(WipeOptions.DEFAULT.copy(icc = IccMode.WIPE)).first.output
        assertNull(icc(wipe))
        val keep = run(WipeOptions.DEFAULT).first.output
        assertArrayEquals(IccSanitizer.sanitize(CameraFixtures.vendorIcc()), icc(keep))
        for ((target, space) in listOf(IccTarget.SRGB to IccProfiles.Space.SRGB, IccTarget.DISPLAY_P3 to IccProfiles.Space.DISPLAY_P3)) {
            val out = run(WipeOptions.DEFAULT.copy(icc = IccMode.REPLACE, iccTarget = target).with(Group.DATE, Mode.REPLACE)).first.output
            val p = icc(out)!!
            assertArrayEquals(IccProfiles.profile(space), p)
            assertNull(IccSanitizer.problem(p))
            val cs = exifOf(out)!!.get(Ifd.EXIF, Tags.COLOR_SPACE)!!.int()!!.toInt()
            assertEquals(if (space == IccProfiles.Space.SRGB) 1 else 0xFFFF, cs)
            TestPhotos.save("icc-${space.name.lowercase()}.jpg", out)
        }
        // PNG: replacing drops sRGB, wiping keeps it.
        val srgbPng = Fixtures.insertAfterIhdr(Fixtures.png(), PngStripper.chunk("sRGB", byteArrayOf(0)))
        val replaced = run(WipeOptions.DEFAULT.copy(icc = IccMode.REPLACE), srgbPng).first.output
        assertEquals(listOf("IHDR", "iCCP", "IDAT", "IEND"), Fixtures.pngChunks(replaced).distinct())
        val wiped = run(WipeOptions.DEFAULT.copy(icc = IccMode.WIPE), srgbPng).first.output
        assertTrue("sRGB" in Fixtures.pngChunks(wiped))
        TestPhotos.save("icc-replace.png", replaced)
    }

    @Test
    fun generatedProfilesMatchPublishedColorants() {
        val s = IccProfiles.colorants(IccProfiles.Space.SRGB)
        assertEquals(0.4361, s[0][0], 0.0005); assertEquals(0.2225, s[1][0], 0.0005); assertEquals(0.0139, s[2][0], 0.0005)
        assertEquals(0.3851, s[0][1], 0.0005); assertEquals(0.7169, s[1][1], 0.0005)
        assertEquals(0.1431, s[0][2], 0.0005); assertEquals(0.7142, s[2][2], 0.0005)
        val p = IccProfiles.colorants(IccProfiles.Space.DISPLAY_P3)
        assertEquals(0.5151, p[0][0], 0.0005); assertEquals(0.2412, p[1][0], 0.0005)
        assertEquals(0.2920, p[0][1], 0.0005); assertEquals(0.6922, p[1][1], 0.0005)
        assertEquals(0.1571, p[0][2], 0.0005); assertEquals(0.7843, p[2][2], 0.0005)
        TestPhotos.save("generated-srgb.icc", IccProfiles.profile(IccProfiles.Space.SRGB))
        TestPhotos.save("generated-display-p3.icc", IccProfiles.profile(IccProfiles.Space.DISPLAY_P3))
    }

    @Test
    fun webpAndPngCarryPlannedMetadata() {
        val o = WipeOptions(Group.entries.associateWith { Mode.REPLACE }, icc = IccMode.REPLACE)
        val png = run(o, Fixtures.png()).first.output
        assertTrue("eXIf" in Fixtures.pngChunks(png))
        TestPhotos.save("replace-all.png", png)
        // A real lossless WebP converted from the test image is not available on the JVM, so build a VP8L header.
        val vp8l = byteArrayOf(0x2F, 0x2F, 0xC0.toByte(), 0x07, 0x10) + ByteArray(20)
        val simple = riff(WebpStripper.makeChunk("VP8L", vp8l))
        val out = run(o, simple).first.output
        val chunks = WebpStripper.parse(out).map { it.fourcc }
        assertEquals(listOf("VP8X", "ICCP", "VP8L", "EXIF"), chunks)
        val flags = out[20].toInt()
        assertEquals(WebpStripper.FLAG_ICC or WebpStripper.FLAG_EXIF or WebpStripper.FLAG_ALPHA, flags)
        // Canvas size from the VP8L header: 48 x 32.
        assertEquals(47, out[24].toInt() and 0xFF)
        assertEquals(31, out[27].toInt() and 0xFF)
        // With the defaults a simple WebP stays as it is.
        assertArrayEquals(simple, run(WipeOptions.DEFAULT, simple).first.output)
    }

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = chunks.fold("WEBP".toByteArray()) { a, c -> a + c }
        val n = body.size
        return "RIFF".toByteArray() + byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()) + body
    }

    @Test
    fun keptDateAndReplacedLocationShareTheOffset() {
        val o = WipeOptions.DEFAULT.with(Group.DATE, Mode.KEEP).with(Group.LOCATION, Mode.REPLACE)
        repeat(100) { seed ->
            val (r, planner) = run(o, seed = seed.toLong())
            val c = planner.choices!!
            val local = MetadataPlanner.parseExifDate(TestPhotos.DATE)!!
            assertEquals(java.time.ZoneOffset.ofHours(-4), java.time.ZoneId.of(c.place!!.zone).rules.getOffset(local))
            val t = exifOf(r.output)!!
            assertEquals("2011:07:04", t.get(Ifd.GPS, Tags.GPS_DATE_STAMP)!!.ascii())
            assertEquals(22.0, t.get(Ifd.GPS, Tags.GPS_TIME_STAMP)!!.rational(0)!!, 0.0)
        }
    }

    @Test
    fun keptLensGetsAMatchingBody() {
        val o = WipeOptions.DEFAULT.with(Group.LENS, Mode.KEEP).with(Group.CAMERA, Mode.REPLACE)
        repeat(100) { seed ->
            val (r, planner) = run(o, seed = seed.toLong())
            assertEquals("canon-ef", planner.choices!!.body!!.mount.let { if (it == "canon-rf" || it == "canon-efm") "canon-ef" else it })
            assertEquals(TestPhotos.LENS, exifOf(r.output)!!.get(Ifd.EXIF, Tags.LENS_MODEL)!!.ascii())
        }
    }

    @Test
    fun extrasModes() {
        val keep = run(only(Group.EXTRAS, Mode.KEEP)).first.output
        assertTrue(contains(keep, "MOTION-PHOTO-VIDEO-DATA"))
        assertNotNull(exifOf(keep)!!.thumbnail)
        val wipe = run(only(Group.EXTRAS, Mode.WIPE)).first.output
        assertFalse(contains(wipe, "MOTION-PHOTO-VIDEO-DATA"))
        val replaceOptions = only(Group.EXTRAS, Mode.REPLACE)
        val thumb = JpegStripper.strip(Fixtures.jpeg())
        val planner = MetadataPlanner(replaceOptions, Random(1), NOW, thumbnailMaker = { thumb })
        val replaced = MetadataStripper.process(source, replaceOptions, planner).output
        assertFalse(contains(replaced, "MOTION-PHOTO-VIDEO-DATA"))
        assertArrayEquals(thumb, exifOf(replaced)!!.thumbnail)
    }
}
