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

import com.oiuy.exifwipe.meta.Group
import com.oiuy.exifwipe.meta.MetadataPlanner
import com.oiuy.exifwipe.meta.Mode
import com.oiuy.exifwipe.meta.TestPhotos
import com.oiuy.exifwipe.meta.WipeOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ReadBackTest {
    private fun randomLocation(): Pair<ByteArray, OutputMeta> {
        val o = WipeOptions.DEFAULT.with(Group.LOCATION, Mode.REPLACE)
        val r = MetadataStripper.process(CameraFixtures.cameraJpeg(), o, MetadataPlanner(o, Random(3), TestPhotos.NOW))
        return r.output to r.meta!!
    }

    @Test
    fun gpsRangesCoverTheWrittenGpsValues() {
        val (out, meta) = randomLocation()
        val ranges = ReadBack.gpsValueRanges(out, meta.exif)
        assertTrue(ranges.size >= 4)
        assertEquals(ranges.toSet(), ReadBack.gpsValueRangesInFile(out).toSet())
        assertTrue(ReadBack.gpsValueRanges(out, null).isEmpty())
    }

    @Test
    fun zeroedGpsValuesAreRecognisedAsRedaction() {
        val (out, meta) = randomLocation()
        val ranges = ReadBack.gpsValueRanges(out, meta.exif)
        val redacted = out.copyOf().also { b -> ranges.forEach { r -> r.forEach { b[it] = 0 } } }
        assertTrue(ReadBack.compare(out, redacted, ranges) is ReadBack.Result.OnlyLocationRedacted)
        assertEquals(ReadBack.Result.Same, ReadBack.compare(out, out.copyOf(), ranges))
        // Without the ranges (or with a non-zero change) it is a real difference.
        assertTrue(ReadBack.compare(out, redacted, emptyList()) is ReadBack.Result.Different)
        val changed = out.copyOf().also { it[ranges[0].first] = 7 }
        assertTrue(ReadBack.compare(out, changed, ranges) is ReadBack.Result.Different)
    }

    @Test
    fun diagnosticsPinpointTheDifference() {
        val (out, meta) = randomLocation()
        val notTruncated = out + ByteArray(50) { 1 }
        val d1 = (ReadBack.compare(out, notTruncated, emptyList()) as ReadBack.Result.Different).details
        assertTrue(d1, d1.contains("not truncated"))
        assertTrue(d1, d1.contains("expected ${out.size} bytes, read back ${out.size + 50} bytes"))

        val pixel = out.copyOf().also { it[it.size - 10] = (it[it.size - 10] + 1).toByte() }
        val d2 = (ReadBack.compare(out, pixel, emptyList()) as ReadBack.Result.Different).details
        assertTrue(d2, d2.contains("first at offset ${out.size - 10}"))
        assertTrue(d2, d2.contains("compressed image data"))
        assertTrue(d2, d2.contains("/ read "))

        // Inside metadata the context bytes are not printed.
        val ranges = ReadBack.gpsValueRanges(out, meta.exif)
        val gps = out.copyOf().also { it[ranges[0].first] = 9 }
        val d3 = (ReadBack.compare(out, gps, ranges) as ReadBack.Result.Different).details
        assertTrue(d3, d3.contains("metadata (EXIF segment"))
        assertFalse(d3, d3.contains("/ read "))
        assertTrue(d3, d3.contains("inside GPS values"))
    }
}
