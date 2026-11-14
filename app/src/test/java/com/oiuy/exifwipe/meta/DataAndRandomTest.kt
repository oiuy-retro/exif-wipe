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

import com.oiuy.exifwipe.strip.Ifd
import com.oiuy.exifwipe.strip.Tiff
import com.oiuy.exifwipe.strip.TiffEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random

class DataAndRandomTest {
    private val ds = Dataset.get()

    @Test
    fun datasetLoads() {
        assertTrue(ds.bodies.size >= 250)
        assertTrue(ds.lenses.size >= 500)
        assertTrue(ds.bodies.map { it.brand }.toSet().size >= 20)
        assertTrue(ds.lenses.map { it.make }.toSet().size >= 35)
        assertTrue(ds.places.size > 10_000)
        for (p in DevicePreset.entries) p.bodyId?.let { assertNotNull(it, ds.body(it)) }
        for (b in ds.bodies) {
            assertTrue(b.id, b.yearFrom in 1995..2026 && (b.yearTo == 0 || b.yearTo >= b.yearFrom))
            assertTrue(b.id, b.isoMin in 25..800 && b.isoMax >= b.isoMin)
            if (!b.unverified) assertTrue("no lens for ${b.id}", ds.lenses.any { Compat.of(b, it) == Compat.NATIVE })
            if (b.isPhone) assertTrue(b.id, ds.lenses.filter { Compat.of(b, it) != null }.all { it.focal35 != null })
        }
        for (l in ds.lenses) {
            assertTrue(l.model, l.focalMin > 0 && l.focalMax >= l.focalMin)
            assertTrue(l.model, l.apertureWide > 0.7 && l.apertureTele >= l.apertureWide && l.minAperture >= l.apertureTele)
        }
        for (p in ds.places) {
            assertTrue(p.landMask.bitCount() > 0)
            ZoneId.of(p.zone)
        }
    }

    @Test
    fun mountCompatibility() {
        fun body(id: String) = ds.body(id)!!
        fun lens(model: String) = ds.lenses.first { it.model == model }
        assertEquals(Compat.NATIVE, Compat.of(body("canon-r6"), lens("EF50mm f/1.8 II")))
        assertEquals(Compat.NATIVE, Compat.of(body("canon-5d2"), lens("EF50mm f/1.8 II")))
        val rf = ds.lenses.first { it.mounts == listOf("canon-rf") && !it.apsOnly }
        assertNull(Compat.of(body("canon-5d2"), rf))
        val efs = ds.lenses.first { it.apsOnly && "canon-ef" in it.mounts }
        assertNull(Compat.of(body("canon-5d2"), efs))
        val fd = ds.lenses.first { "canon-fd" in it.mounts }
        assertEquals(Compat.ADAPTED_MANUAL, Compat.of(body("sony-a7"), fd))
        assertNull(Compat.of(body("canon-5d2"), fd)) // FD cannot focus to infinity on EF
        val f = ds.lenses.first { it.mounts == listOf("nikon-f") && !it.manual && !it.apsOnly }
        assertEquals(Compat.NATIVE, Compat.of(body("nikon-z6"), f))
        val iphone = ds.lenses.first { it.model.startsWith("iPhone 13 back dual wide camera 5.1mm") }
        assertNull(Compat.of(body("google-pixel-7"), iphone))
        assertEquals(Compat.NATIVE, Compat.of(body("apple-iphone-13"), iphone))
    }

    @Test
    fun datesAreWeightedTowardRecentYears() {
        val r = Random(42)
        val from = LocalDate.of(2002, 1, 1)
        val to = LocalDate.of(2026, 11, 14)
        val years = IntArray(25)
        val n = 200_000
        var sum = 0.0
        repeat(n) {
            val d = DateRandomizer.date(r, from, to)
            assertTrue(!d.isBefore(from) && !d.isAfter(to))
            years[d.year - 2002]++
            sum += (d.toEpochDay() - from.toEpochDay()).toDouble() / (to.toEpochDay() - from.toEpochDay())
        }
        // Mean of the density (0.1 + x) / 0.6 on [0, 1] is (0.05 + 1/3) / 0.6 = 0.639.
        assertEquals(0.639, sum / n, 0.005)
        // Every 4-year block is more likely than the one before; 2025 about 8.7 times as likely as 2002
        // ((0.1 + 0.944) / (0.1 + 0.02) for the middle of each year).
        val blocks = (0 until 6).map { b -> (0 until 4).sumOf { years[4 * b + it] } }
        for (b in 1 until 6) assertTrue("$blocks", blocks[b] > blocks[b - 1])
        val ratio = years[23].toDouble() / years[0]
        assertTrue("ratio $ratio", ratio in 7.5..10.0)
        // The inverse CDF maps the ends correctly.
        assertEquals(0.0, DateRandomizer.fraction(0.0), 1e-12)
        assertEquals(1.0, DateRandomizer.fraction(1.0), 1e-12)
    }

    @Test
    fun timeOfDayIsUniformAndValid() {
        val r = Random(7)
        val hours = IntArray(24)
        val zone = ZoneId.of("America/New_York")
        repeat(48_000) {
            val m = DateRandomizer.moment(r, LocalDate.of(2002, 1, 1), LocalDate.of(2026, 11, 13), zone, TestPhotos.NOW)
            hours[m.local.hour]++
            assertEquals(zone.rules.getOffset(m.local), m.offset)
            assertTrue(zone.rules.isValidOffset(m.local, m.offset))
        }
        for (h in hours) assertTrue("$h", h in 1700..2300)
    }

    @Test
    fun placesAreWeightedByPopulationAndOnLandCells() {
        val r = Random(11)
        val counts = HashMap<Place, Int>()
        val out = StringBuilder("lat,lon,place_lat,place_lon,radius_km\n")
        repeat(20_000) { i ->
            val p = PlaceRandomizer.place(r, ds)
            counts[p] = (counts[p] ?: 0) + 1
            val (lat, lon) = PlaceRandomizer.point(r, p)
            val dLat = (lat - p.lat) * 110.574
            val dLon = (lon - p.lon) * 111.32 * Math.cos(Math.toRadians(p.lat))
            assertTrue(Math.abs(dLat) <= 5.5 + 1e-6 && Math.abs(dLon) <= 5.5 + 1e-6)
            // The cell the point is in is marked as land.
            val cx = Math.round(dLon).toInt() + 5
            val cy = Math.round(dLat).toInt() + 5
            assertTrue(p.landMask.testBit(cy * 11 + cx))
            if (i < 5000) out.append("%.6f,%.6f,%.5f,%.5f,%.1f\n".format(lat, lon, p.lat, p.lon, p.radiusKm))
        }
        // Population weighting: the largest places come up far more often than small towns.
        val big = ds.places.maxBy { it.population }
        val small = ds.places.minBy { it.population }
        assertTrue((counts[big] ?: 0) > 20)
        assertTrue((counts[small] ?: 0) < 5)
        File("build/test-output").mkdirs()
        File("build/test-output/gps-sample.csv").writeText(out.toString())
    }

    @Test
    fun tiffRoundTrip() {
        val entries = mapOf(
            Ifd.IFD0 to listOf(TiffEntry.ascii(Tags.MAKE, "Make"), TiffEntry.short(Tags.ORIENTATION, 3)),
            Ifd.EXIF to listOf(TiffEntry.rational(Tags.F_NUMBER, 28L to 10L), TiffEntry.srational(Tags.EXPOSURE_BIAS, -2, 3)),
            Ifd.GPS to listOf(TiffEntry.ascii(Tags.GPS_LATITUDE_REF, "S")),
        )
        for (be in listOf(true, false)) {
            val b = Tiff.write(entries, byteArrayOf(1, 2, 3), be)!!
            val t = Tiff.read(b)!!
            assertEquals(be, t.bigEndian)
            assertEquals("Make", t.get(Ifd.IFD0, Tags.MAKE)!!.ascii())
            assertEquals(3L, t.get(Ifd.IFD0, Tags.ORIENTATION)!!.int())
            assertEquals(2.8, t.get(Ifd.EXIF, Tags.F_NUMBER)!!.rational()!!, 1e-9)
            assertEquals(-2.0 / 3, t.get(Ifd.EXIF, Tags.EXPOSURE_BIAS)!!.rational()!!, 1e-9)
            assertEquals("S", t.get(Ifd.GPS, Tags.GPS_LATITUDE_REF)!!.ascii())
            assertArrayEquals(byteArrayOf(1, 2, 3), t.thumbnail)
            assertArrayEquals(b, Tiff.write(t.entries, t.thumbnail, be))
        }
        // An orientation-only block is the 26-byte minimal TIFF.
        assertArrayEquals(
            com.oiuy.exifwipe.strip.Orientation.minimalTiff(6),
            Tiff.write(mapOf(Ifd.IFD0 to listOf(TiffEntry.short(Tags.ORIENTATION, 6))), null),
        )
    }

    @Test
    fun exposureModelGivesNormalExposures() {
        val r = Random(5)
        val body = ds.body("canon-5d2")!!
        repeat(5000) {
            val lens = ds.lensesFor(body).filter { it.second == Compat.NATIVE }.let { l -> l[r.nextInt(l.size)].first }
            val x = ExposureRandomizer.camera(r, body, lens, Compat.NATIVE)
            val ev100 = Math.log(x.fNumber!! * x.fNumber / x.seconds) / Math.log(2.0) - Math.log(x.iso / 100.0) / Math.log(2.0)
            assertTrue("EV $ev100", ev100 in 0.0..17.5)
            assertTrue(x.iso in body.isoMin..body.isoMax)
            assertTrue(x.focal!! in lens.focalMin..lens.focalMax)
        }
    }
}
