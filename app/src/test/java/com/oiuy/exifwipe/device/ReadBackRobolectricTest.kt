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
package com.oiuy.exifwipe.device

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.oiuy.exifwipe.InPlaceWriter
import com.oiuy.exifwipe.WipeOutcome
import com.oiuy.exifwipe.meta.DevicePreset
import com.oiuy.exifwipe.meta.FileNameMode
import com.oiuy.exifwipe.meta.Group
import com.oiuy.exifwipe.meta.IccMode
import com.oiuy.exifwipe.meta.IccTarget
import com.oiuy.exifwipe.meta.Mode
import com.oiuy.exifwipe.meta.TestPhotos
import com.oiuy.exifwipe.meta.Tags
import com.oiuy.exifwipe.meta.WipeOptions
import com.oiuy.exifwipe.strip.CameraFixtures
import com.oiuy.exifwipe.strip.Ifd
import com.oiuy.exifwipe.strip.ReadBack
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

/**
 * Storage behaviours seen on real phones, simulated with a content provider:
 *  - read-only opens hide (zero) the EXIF GPS values, as Android's media provider does for apps
 *    without the "access media location" permission (0.2.0-test2 failed on a Galaxy S24+ because
 *    its read-back went through such a link while Location was set to Random),
 *  - the truncate flag of the open mode is ignored,
 *  - read-only links return corrupted data, which must fail with a precise diagnostic.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ReadBackRobolectricTest {
    class FakeStorage : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri) = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val f = file!!
            if (mode == "r") {
                var bytes = f.readBytes()
                if (redactLocation) {
                    bytes = bytes.copyOf()
                    for (r in ReadBack.gpsValueRangesInFile(bytes)) for (i in r) bytes[i] = 0
                }
                if (corruptNewReads && !bytes.contentEquals(original)) {
                    bytes = bytes.copyOf()
                    bytes[bytes.size - 100] = (bytes[bytes.size - 100] + 1).toByte()
                }
                val copy = File.createTempFile("view", ".jpg").apply { writeBytes(bytes) }
                return ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            if (mode == "rw" && !allowPlainRw) throw java.io.FileNotFoundException("rw not supported")
            var m = ParcelFileDescriptor.parseMode(mode)
            if (!honourTruncate) m = m and ParcelFileDescriptor.MODE_TRUNCATE.inv()
            return ParcelFileDescriptor.open(f, m)
        }

        companion object {
            var file: File? = null
            var original: ByteArray? = null
            var redactLocation = false
            var honourTruncate = true
            var allowPlainRw = true
            var corruptNewReads = false
        }
    }

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val uri = Uri.parse("content://exifwipe.test.storage/photo/1")
    private val input = CameraFixtures.cameraJpeg()

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(FakeStorage::class.java, "exifwipe.test.storage")
        FakeStorage.file = File(context.cacheDir, "photo.jpg").apply { writeBytes(input) }
        FakeStorage.original = input
        FakeStorage.redactLocation = false
        FakeStorage.honourTruncate = true
        FakeStorage.allowPlainRw = true
        FakeStorage.corruptNewReads = false
        assertTrue("fixture has GPS", ReadBack.gpsValueRangesInFile(input).isNotEmpty())
    }

    private fun allRandom(icc: IccMode = IccMode.WIPE) =
        WipeOptions(Group.entries.associateWith { Mode.REPLACE }, icc, IccTarget.MATCH_DEVICE, FileNameMode.KEEP, DevicePreset.RANDOM)

    private fun gpsOf(b: ByteArray) = TestPhotos.exifOf(b)?.get(Ifd.GPS, Tags.GPS_LATITUDE)?.value

    @Test
    fun randomLocationSurvivesRedactedReadOnlyLinks() {
        FakeStorage.redactLocation = true
        repeat(5) {
            FakeStorage.file!!.writeBytes(input)
            val r = InPlaceWriter(context).wipe(uri, allRandom())
            assertEquals(WipeOutcome.CLEANED, r.outcome)
            val stored = FakeStorage.file!!.readBytes()
            assertFalse(stored.contentEquals(input))
            val lat = gpsOf(stored)
            assertNotNull(lat)
            assertTrue("real random GPS stored, not zeros", lat!!.any { it != 0.toByte() })
        }
    }

    @Test
    fun keptLocationIsTheRealOneNotTheRedactedView() {
        FakeStorage.redactLocation = true
        val keepLocation = WipeOptions.DEFAULT.with(Group.LOCATION, Mode.KEEP)
        InPlaceWriter(context).wipe(uri, keepLocation)
        assertArrayEquals(gpsOf(input), gpsOf(FakeStorage.file!!.readBytes()))
    }

    @Test
    fun redactedReadOnlyFallbackIsAccepted() {
        // A link that cannot be reopened read-write: the final read-back is redacted.
        FakeStorage.redactLocation = true
        FakeStorage.allowPlainRw = false
        val r = InPlaceWriter(context).wipe(uri, allRandom())
        assertEquals(WipeOutcome.CLEANED, r.outcome)
        assertTrue(gpsOf(FakeStorage.file!!.readBytes())!!.any { it != 0.toByte() })
    }

    @Test
    fun truncateFlagIgnoredByStorage() {
        FakeStorage.honourTruncate = false
        val r = InPlaceWriter(context).wipe(uri, WipeOptions.DEFAULT)
        assertEquals(WipeOutcome.CLEANED, r.outcome)
        val stored = FakeStorage.file!!.readBytes()
        assertTrue("shorter than original", stored.size < input.size)
        assertTrue(ReadBack.gpsValueRangesInFile(stored).isEmpty())
    }

    @Test
    fun realMismatchFailsWithDiagnosticsAndRestores() {
        FakeStorage.allowPlainRw = false
        FakeStorage.corruptNewReads = true
        try {
            InPlaceWriter(context).wipe(uri, allRandom())
            fail("expected a read-back failure")
        } catch (e: IOException) {
            val m = e.message!!
            assertTrue(m, m.contains("read path: reopened (r)"))
            assertTrue(m, m.contains("first at offset"))
            assertTrue(m, m.contains("data after the end of the image") || m.contains("compressed image data"))
        }
        assertArrayEquals(input, FakeStorage.file!!.readBytes())
    }
}
