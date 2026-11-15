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

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.testTag
import com.oiuy.exifwipe.meta.Brands
import com.oiuy.exifwipe.meta.DevicePreset
import com.oiuy.exifwipe.meta.FileNameMode
import com.oiuy.exifwipe.meta.Group
import com.oiuy.exifwipe.meta.IccMode
import com.oiuy.exifwipe.meta.IccTarget
import com.oiuy.exifwipe.meta.Mode
import com.oiuy.exifwipe.meta.WipeOptions

/** Launcher screen: settings and how to use the share target. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ExifWipeTheme { Surface(Modifier.fillMaxSize()) { MainScreen() } } }
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    var options by remember { mutableStateOf(store.load()) }
    fun update(o: WipeOptions) { options = o; store.save(o) }

    Column(
        Modifier
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Exif Wipe", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Removes or replaces hidden metadata (location, camera, date and time, XMP, IPTC, comments) " +
                "in photos stored on this phone. The original file is changed in place; no copy is made " +
                "and nothing is shared onward.",
        )
        Settings(options, ::update)
        LastErrors()
        Instructions()
    }
}

/** What the chosen common device or brand is called in notes, or null if neither is chosen. */
private fun chosenDevice(o: WipeOptions): String? = when {
    o.preset.bodyId != null -> o.preset.label
    o.brand != null -> o.brand
    else -> null
}

/** One line under a setting that follows the chosen device or brand. */
@Composable
private fun FollowsNote(text: String, tag: String) {
    Text(
        text,
        Modifier.testTag(tag),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Settings(o: WipeOptions, update: (WipeOptions) -> Unit) {
    val device = chosenDevice(o)
    // Notes only appear while the chosen device or brand is actually used.
    val follows = device != null && o.usesGeneratedDevice
    val brandBodies = o.effectiveBrand?.let { Brands.bodies(it) }
    Text("Settings", style = MaterialTheme.typography.titleLarge)
    Text(
        "For each group: Keep leaves the original values, Wipe removes them, Random removes them and " +
            "writes realistic made-up values instead.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Group.entries.forEachIndexed { i, g ->
                if (i > 0) HorizontalDivider()
                Text(g.title, style = MaterialTheme.typography.titleSmall)
                Text(g.summary, style = MaterialTheme.typography.bodySmall)
                Segmented(listOf("Keep", "Wipe", "Random"), o.mode(g).ordinal, "group-${g.name}") { update(o.with(g, Mode.entries[it])) }
                if (follows && o.mode(g) == Mode.REPLACE) {
                    val note = when (g) {
                        Group.CAMERA -> if (brandBodies != null) "Follows $device: a random $device ${Brands.kinds(device!!).removeSuffix("s")} for each photo."
                        else "Follows $device: always this device."
                        Group.LENS -> if (brandBodies != null) "Follows $device: lenses made for the chosen camera's mount (including other makers' lenses for that mount), or its built-in lens."
                        else "Follows $device: its own camera modules."
                        Group.DATE -> "Follows $device: dates when the chosen ${if (brandBodies != null) "camera" else "device"} was in use."
                        else -> null
                    }
                    note?.let { FollowsNote(it, "follows-${g.name}") }
                }
                if (!o.checksCombinations && o.usesGeneratedDevice && o.mode(g) == Mode.REPLACE) {
                    when (g) {
                        Group.LENS -> FollowsNote("Not matched to the camera: any lens, even one that does not fit it.", "unmatched-LENS")
                        Group.DATE -> FollowsNote("Not matched to the camera: any date since 2002.", "unmatched-DATE")
                        else -> {}
                    }
                }
            }
        }
    }
    if (Group.entries.any { o.mode(it) == Mode.KEEP }) {
        Text(
            "Kept groups keep their values, so a photo is only as anonymous as the groups you wipe or randomize.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    DeviceChoice(o, update)

    Text("Colour profile", style = MaterialTheme.typography.titleMedium)
    Segmented(listOf("Keep", "Wipe", "Replace"), o.icc.ordinal, "icc") { update(o.copy(icc = IccMode.entries[it])) }
    Text(
        when (o.icc) {
            IccMode.KEEP -> "Keeps the colour data of the original profile; its copyright and manufacturer text is removed."
            IccMode.WIPE -> "Removes the embedded profile. Viewers then assume sRGB, so wide-colour photos can look duller."
            IccMode.REPLACE -> "Writes a standard profile generated by the app. Colours can shift if it differs from the original profile."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (o.icc == IccMode.REPLACE) {
        Segmented(IccTarget.entries.map { it.label }, o.iccTarget.ordinal, "iccTarget") { update(o.copy(iccTarget = IccTarget.entries[it])) }
        if (follows && o.iccTarget == IccTarget.MATCH_DEVICE) {
            val spaces = (brandBodies ?: listOfNotNull(o.preset.bodyId?.let { id -> com.oiuy.exifwipe.meta.Dataset.get().body(id) }))
                .map { if (it.icc == "p3") "Display P3" else "sRGB" }.distinct()
            FollowsNote("Match device follows $device: ${if (spaces.size == 1) spaces[0] else spaces.joinToString(" or ") + ", depending on the model"}.", "follows-icc")
        }
    }

    Text("File name", style = MaterialTheme.typography.titleMedium)
    Segmented(listOf("Keep", "Generic", "Device"), o.fileName.ordinal, "fileName") { update(o.copy(fileName = FileNameMode.entries[it])) }
    Text(
        when (o.fileName) {
            FileNameMode.KEEP -> "The file keeps its name."
            FileNameMode.GENERIC -> "Renames to the iPhone style IMG_1234.JPG with a random number."
            FileNameMode.DEVICE -> "Renames in the style of the random device, for example PXL_20240501_103015123.jpg or DSC_1234.JPG."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    if (follows && o.fileName == FileNameMode.DEVICE) {
        val examples = brandBodies?.let { Brands.exampleFileNames(o.effectiveBrand!!) }
            ?: listOfNotNull(o.preset.bodyId?.let { id -> com.oiuy.exifwipe.meta.Dataset.get().body(id)?.let(Brands::exampleFileName) })
        FollowsNote("Follows $device, for example ${examples.joinToString(", ")}.", "follows-fileName")
    }
    TextButton(onClick = { update(WipeOptions.DEFAULT) }) { Text("Reset to defaults (wipe everything)") }
}

/**
 * "Common devices" and "Camera brand": two pickers that exclude each other (choosing one resets
 * the other to "None"/"Any brand"). Both are disabled while no generated device is used.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceChoice(o: WipeOptions, update: (WipeOptions) -> Unit) {
    val enabled = o.usesGeneratedDevice
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val textColor = if (enabled) Color.Unspecified else disabledColor
    Text("Common devices", style = MaterialTheme.typography.titleMedium, color = textColor)
    Text(
        when {
            o.mode(Group.CAMERA) == Mode.KEEP ->
                "Not used while Camera & device is set to Keep: random values then follow the photo's own camera."
            !enabled ->
                "Not used with these settings. It applies when Camera & device, Lens & exposure or Date & time is set to " +
                    "Random, or File name to Device."
            else -> "Random values describe this device. Choosing a common device clears the camera brand below, and the other way round."
        },
        Modifier.testTag("device-help"),
        style = MaterialTheme.typography.bodySmall,
    )
    Column(Modifier.selectableGroup()) {
        DevicePreset.entries.forEach { p ->
            val label = if (p == DevicePreset.RANDOM) "None (use the camera brand below)" else p.label
            Row(
                Modifier
                    .fillMaxWidth()
                    .testTag("preset-${p.name}")
                    .selectable(selected = o.preset == p, enabled = enabled, role = Role.RadioButton) {
                        update(o.copy(preset = p, brand = if (p.bodyId != null) null else o.brand))
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = o.preset == p, onClick = null, enabled = enabled)
                Text(label, Modifier.padding(start = 12.dp), color = textColor)
            }
        }
    }

    Text("Camera brand", style = MaterialTheme.typography.titleMedium, color = textColor)
    var expanded by remember { mutableStateOf(false) }
    val current = o.brand?.let { "$it (${Brands.kinds(it)})" } ?: "Any brand"
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text("Brand") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("brand-field")
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.exposedDropdownSize()) {
            DropdownMenuItem(
                text = { Text("Any brand") },
                onClick = { update(o.copy(brand = null)); expanded = false },
                modifier = Modifier.testTag("brand-any"),
            )
            Brands.all.forEach { b ->
                DropdownMenuItem(
                    text = { Text("$b (${Brands.kinds(b)})") },
                    onClick = { update(o.copy(brand = b, preset = DevicePreset.RANDOM)); expanded = false },
                    modifier = Modifier.testTag("brand-$b"),
                )
            }
        }
    }
    Text(
        "Picks a random camera or phone of this brand for each photo, with a lens made for its mount, dates when it was " +
            "in use, the brand's file names and software. Legacy brands such as Minolta and Kodak are included.",
        style = MaterialTheme.typography.bodySmall,
        color = textColor,
    )
    val toggleEnabled = enabled && !o.deviceChosen
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("valid-only")
            .toggleable(value = o.checksCombinations, enabled = toggleEnabled, role = Role.Switch) { update(o.copy(validOnly = it)) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Only valid combinations",
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = if (toggleEnabled) Color.Unspecified else disabledColor,
        )
        Switch(checked = o.checksCombinations, onCheckedChange = null, enabled = toggleEnabled)
    }
    Text(
        when {
            !enabled -> "Not used with these settings."
            o.deviceChosen -> "Always on while a common device or camera brand is chosen."
            o.validOnly -> "The camera or phone is still picked at random, but the lens, dates, file name, software and colour profile fit it."
            else -> "Each group is randomized on its own: any lens with any camera, any date since 2002 and file names of another device."
        },
        Modifier.testTag("valid-only-help"),
        style = MaterialTheme.typography.bodySmall,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(labels: List<String>, selected: Int, tag: String, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            SegmentedButton(
                modifier = Modifier.testTag("$tag-$i"),
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = labels.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun LastErrors() {
    val context = LocalContext.current
    var log by remember { mutableStateOf(ErrorLog.read(context)) }
    if (log == null) return
    Text("Last errors", style = MaterialTheme.typography.titleMedium)
    Text(
        "Details of recent problems, newest first. They contain the app and Android version, phone model, " +
            "settings, file names and the error, but no photo contents, metadata values or locations. " +
            "Copy them to send them to the developer.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedCard(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                log!!,
                Modifier
                    .padding(12.dp)
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Exif Wipe error details", log))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Error details copied", Toast.LENGTH_SHORT).show()
        }) { Text("Copy error details") }
        TextButton(onClick = { ErrorLog.clear(context); log = null }) { Text("Clear") }
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
    Text("How to use", style = MaterialTheme.typography.titleMedium)
    Text(
        "1. Open your gallery or file manager and select one or more photos.\n" +
            "2. Tap Share and choose \"Wipe metadata\".\n" +
            "3. $permissionNote\n" +
            "4. A short message shows how many photos were changed.",
    )
    Text("What is always kept", style = MaterialTheme.typography.titleMedium)
    Text(
        "The image data is copied unchanged, so the photo has exactly the same pixels. If the photo was taken " +
            "sideways, the orientation tag is kept so it still displays upright. Maker notes, serial numbers in " +
            "unknown tags and vendor data blocks are always removed.",
    )
    Text("Supported files", style = MaterialTheme.typography.titleMedium)
    Text("JPEG, PNG and WebP. HEIC, AVIF, GIF, TIFF and RAW files are reported as not supported and left unchanged.")
    Text("Limitations", style = MaterialTheme.typography.titleMedium)
    Text(
        "Only photos stored on this phone can be changed. Photos that exist only in the cloud, or that " +
            "an app shares as a temporary copy, are reported as errors. Motion photo video and HDR gain " +
            "map data stored after the image are removed unless Embedded extras is set to Keep. Renaming " +
            "can fail for files shared by some apps; the metadata is still changed. Some gallery apps sort by " +
            "file date and may show a changed photo as new.",
    )
    Text("Data sources", style = MaterialTheme.typography.titleMedium)
    Text(
        "Random places: GeoNames (geonames.org), licensed CC BY 4.0, and Natural Earth land data (public domain). " +
            "Camera, lens and phone names, focal lengths, apertures and years are facts compiled for this app. " +
            "Colour profiles are generated from the published sRGB and Display P3 definitions.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text("This app has no internet permission.", style = MaterialTheme.typography.bodySmall)
}
