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

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.oiuy.exifwipe.ErrorLog
import com.oiuy.exifwipe.MainActivity
import com.oiuy.exifwipe.SettingsStore
import com.oiuy.exifwipe.WipeActivity
import com.oiuy.exifwipe.meta.DevicePreset
import com.oiuy.exifwipe.meta.FileNameMode
import com.oiuy.exifwipe.meta.Group
import com.oiuy.exifwipe.meta.IccMode
import com.oiuy.exifwipe.meta.IccTarget
import com.oiuy.exifwipe.meta.Mode
import com.oiuy.exifwipe.meta.WipeOptions
import com.oiuy.exifwipe.strip.CameraFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Smoke test of the share screen and settings screen with every setting on Random. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ShareFlowRobolectricTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val allRandom = WipeOptions(Group.entries.associateWith { Mode.REPLACE }, IccMode.REPLACE, IccTarget.MATCH_DEVICE, FileNameMode.DEVICE, DevicePreset.RANDOM)

    @Test
    fun settingsRoundTrip() {
        SettingsStore(context).save(allRandom)
        assertEquals(allRandom, SettingsStore(context).load())
    }

    @Test
    fun shareScreenReportsInsteadOfCrashing() {
        SettingsStore(context).save(allRandom)
        ErrorLog.clear(context)
        val f = File(context.cacheDir, "20261115_120000.jpg").apply { writeBytes(CameraFixtures.cameraJpeg()) }
        val intent = Intent(context, WipeActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(f))
        }
        val controller = Robolectric.buildActivity(WipeActivity::class.java, intent).setup()
        repeat(50) { ShadowLooper.idleMainLooper(); Thread.sleep(20) }
        // A file link cannot be changed in place: the screen stays open with an explanation, and the
        // details are saved for copying.
        assertFalse(controller.get().isFinishing)
        val log = ErrorLog.read(context)
        assertNotNull(log)
        assertTrue(log!!, log.contains("Only content links can be changed"))
        assertTrue(log, log.contains("Location=replace"))
        controller.pause().stop().destroy()
    }

    @Test
    fun settingsScreenShowsLastErrors() {
        ErrorLog.record(context, ErrorLog.entry(context, "test entry", IllegalStateException("boom"), allRandom))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowLooper.idleMainLooper()
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
        assertTrue(ErrorLog.read(context)!!.contains("IllegalStateException: boom"))
    }
}
