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

package com.oiuy.exifwipe

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Launcher screen: explains how to use the share target. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ExifWipeTheme { Surface(Modifier.fillMaxSize()) { Instructions() } } }
    }
}

@Composable
private fun Instructions() {
    val version = Build.VERSION.SDK_INT
    val permissionNote = when {
        version >= 30 -> "Android asks once per share whether Exif Wipe may modify the selected photos. Tap Allow."
        version == 29 -> "Android asks for each photo whether Exif Wipe may modify it. Tap Allow."
        else -> "The first time, allow Exif Wipe to access photos and media."
    }
    Column(
        Modifier
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Exif Wipe", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Removes hidden metadata (location, camera model, date and time, XMP, IPTC, comments) " +
                "from photos stored on this phone. The original file is changed in place; no copy is made " +
                "and nothing is shared onward.",
        )
        Text("How to use", style = MaterialTheme.typography.titleMedium)
        Text(
            "1. Open your gallery or file manager and select one or more photos.\n" +
                "2. Tap Share and choose \"Wipe metadata\".\n" +
                "3. $permissionNote\n" +
                "4. A short message shows how many photos were cleaned.",
        )
        Text("What is kept", style = MaterialTheme.typography.titleMedium)
        Text(
            "The image data is copied unchanged, so the photo looks exactly the same. The colour profile " +
                "keeps its colour data; its copyright and manufacturer text is removed. If the photo was taken sideways, a single orientation tag is kept so it still " +
                "displays upright.",
        )
        Text("Supported files", style = MaterialTheme.typography.titleMedium)
        Text("JPEG, PNG and WebP. HEIC, AVIF, GIF, TIFF and RAW files are reported as not supported and left unchanged.")
        Text("Limitations", style = MaterialTheme.typography.titleMedium)
        Text(
            "Only photos stored on this phone can be changed. Photos that exist only in the cloud, or that " +
                "an app shares as a temporary copy, are reported as errors. Motion photo video and HDR gain " +
                "map data stored after the image are removed. Some gallery apps sort by file date and may " +
                "show a cleaned photo as new.",
        )
        Text("This app has no internet permission.", style = MaterialTheme.typography.bodySmall)
    }
}
