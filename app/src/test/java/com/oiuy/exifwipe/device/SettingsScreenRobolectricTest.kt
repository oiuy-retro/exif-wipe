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

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.oiuy.exifwipe.MainActivity
import com.oiuy.exifwipe.SettingsStore
import com.oiuy.exifwipe.meta.DevicePreset
import com.oiuy.exifwipe.meta.FileNameMode
import com.oiuy.exifwipe.meta.Group
import com.oiuy.exifwipe.meta.IccMode
import com.oiuy.exifwipe.meta.IccTarget
import com.oiuy.exifwipe.meta.Mode
import com.oiuy.exifwipe.meta.WipeOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The "Common devices", "Camera brand" and "Only valid combinations" settings: disabled while
 * they have no effect, mutually exclusive, and saved.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SettingsScreenRobolectricTest {
    companion object {
        private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
        val allRandom = WipeOptions(Group.entries.associateWith { Mode.REPLACE }, IccMode.REPLACE, IccTarget.MATCH_DEVICE, FileNameMode.DEVICE, DevicePreset.RANDOM)
        /** Settings saved before the screen opens, set by each test's @Before through [start]. */
        var start: WipeOptions = allRandom
    }

    // The rule launches the activity before @Before runs, so the stored settings are written by a
    // rule ordered first.
    @get:Rule(order = 0)
    val seed = object : org.junit.rules.ExternalResource() {
        override fun before() { SettingsStore(context).save(start) }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private fun saved() = SettingsStore(context).load()

    @Before
    fun reset() { start = allRandom }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = false).performScrollTo()

    @Test
    fun allRandomEnablesPickersAndBrandClearsDevice() {
        node("preset-PIXEL").assertIsEnabled().performClick()
        assertEquals(DevicePreset.PIXEL, saved().preset)
        node("valid-only").assertIsOn().assertIsNotEnabled()
        node("brand-field").assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("brand-any").assertExists()
        compose.onNodeWithTag("brand-Leica").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("Leica", saved().brand)
        assertEquals(DevicePreset.RANDOM, saved().preset)
        node("follows-LENS").assertTextContains("Leica", substring = true)
        node("follows-fileName").assertTextContains("L1", substring = true)
        node("preset-IPHONE").performClick()
        assertNull(saved().brand)
        assertEquals(DevicePreset.IPHONE, saved().preset)
    }

    @Test
    fun validOnlyToggleWhenNothingIsChosen() {
        node("valid-only").assertIsEnabled().assertIsOn().performClick()
        assertFalse(saved().validOnly)
        node("valid-only").assertIsOff()
        node("unmatched-LENS").assertTextContains("any lens", substring = true)
        node("valid-only-help").assertTextContains("on its own", substring = true)
    }

    @Test
    fun cameraOnKeepDisablesEverything() {
        node("group-CAMERA-0").performClick()
        compose.waitForIdle()
        node("preset-PIXEL").assertIsNotEnabled()
        node("brand-field").assertIsNotEnabled()
        node("valid-only").assertIsNotEnabled()
        node("device-help").assertTextContains("Keep", substring = true)
    }
}
