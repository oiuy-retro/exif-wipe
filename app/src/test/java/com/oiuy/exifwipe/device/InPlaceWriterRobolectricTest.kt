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

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.oiuy.exifwipe.InPlaceWriter
import com.oiuy.exifwipe.WipeOutcome
import com.oiuy.exifwipe.meta.DevicePreset
import com.oiuy.exifwipe.meta.FileNameMode
import com.oiuy.exifwipe.meta.Group
import com.oiuy.exifwipe.meta.IccMode
import com.oiuy.exifwipe.meta.IccTarget
import com.oiuy.exifwipe.meta.Mode
import com.oiuy.exifwipe.meta.WipeOptions
import com.oiuy.exifwipe.strip.CameraFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Runs the Android part of the write path (BitmapFactory pixel checks, thumbnail generation,
 * bundled data loading, file writing and read-back) under Robolectric with the real native
 * graphics library.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class InPlaceWriterRobolectricTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun photos(): List<Pair<String, ByteArray>> {
        val list = mutableListOf("samsung-fixture.jpg" to CameraFixtures.cameraJpeg())
        System.getProperty("exifwipe.photos")?.let { dir ->
            File(dir).listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg") }?.sortedBy { it.name }
                ?.forEach { list += it.name to it.readBytes() }
        }
        return list
    }

    @Test
    fun allRandomWritesAndVerifies() {
        val writer = InPlaceWriter(context)
        for ((name, bytes) in photos()) for (preset in DevicePreset.entries) for (target in IccTarget.entries) {
            val o = WipeOptions(Group.entries.associateWith { Mode.REPLACE }, IccMode.REPLACE, target, FileNameMode.DEVICE, preset)
            val f = File(context.cacheDir, "in-$name").apply { writeBytes(bytes) }
            val r = writer.wipe(Uri.fromFile(f), o)
            assertEquals("$name $preset $target", WipeOutcome.CLEANED, r.outcome)
            assertTrue("$name changed", !f.readBytes().contentEquals(bytes))
            // Second run on the already changed file (the user's second attempt).
            val r2 = writer.wipe(Uri.fromFile(f), o)
            assertEquals("$name $preset $target second run", WipeOutcome.CLEANED, r2.outcome)
        }
    }
}
