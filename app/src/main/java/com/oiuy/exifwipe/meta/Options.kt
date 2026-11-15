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

/** What to do with one group of metadata. The order matches the three-way switch (left to right). */
enum class Mode { KEEP, WIPE, REPLACE }

/** The few groups all metadata is sorted into. */
enum class Group(val title: String, val summary: String) {
    LOCATION("Location", "GPS position, altitude, direction and speed"),
    DATE("Date & time", "When the photo was taken, time zone, GPS time"),
    CAMERA("Camera & device", "Make, model, serial numbers, software, owner, maker notes"),
    LENS("Lens & exposure", "Lens, focal length, aperture, shutter, ISO, flash, white balance"),
    AUTHOR("Description & authorship", "Artist, copyright, title, comments, ratings, XMP and IPTC text"),
    EXTRAS("Embedded extras", "Thumbnail, motion photo video, HDR gain map, data after the image"),
}

enum class IccMode { KEEP, WIPE, REPLACE }

/** Which colour profile REPLACE writes. */
enum class IccTarget(val label: String) { MATCH_DEVICE("Match device"), SRGB("sRGB"), DISPLAY_P3("Display P3") }

enum class FileNameMode { KEEP, GENERIC, DEVICE }

/** Device whose realistic values REPLACE writes. */
enum class DevicePreset(val label: String, val bodyId: String?) {
    RANDOM("Random camera or phone", null),
    PIXEL("Google Pixel 7", "google-pixel-7"),
    IPHONE("Apple iPhone 13", "apple-iphone-13"),
    SAMSUNG("Samsung Galaxy A12", "samsung-galaxy-a12"),
}

data class WipeOptions(
    val modes: Map<Group, Mode> = Group.entries.associateWith { Mode.WIPE },
    val icc: IccMode = IccMode.KEEP,
    val iccTarget: IccTarget = IccTarget.MATCH_DEVICE,
    val fileName: FileNameMode = FileNameMode.KEEP,
    val preset: DevicePreset = DevicePreset.RANDOM,
    /**
     * Camera brand (the brand column of bodies.tsv) that random devices are picked from, or null
     * for any brand. Only one of [preset] and [brand] is used: a common device wins.
     */
    val brand: String? = null,
    /**
     * Only valid combinations: lens, dates, file name style, software and colour profile fit the
     * random body. Off: these are randomized independently of each other (any lens on any body,
     * any date since 2002). Always on while a common device or a brand is chosen.
     */
    val validOnly: Boolean = true,
) {
    /** True while a common device or a brand is chosen; combinations are then always valid. */
    val deviceChosen: Boolean get() = preset.bodyId != null || brand != null

    /** True if generated values must fit the generated body. */
    val checksCombinations: Boolean get() = validOnly || deviceChosen

    fun mode(g: Group): Mode = modes[g] ?: Mode.WIPE

    fun with(g: Group, m: Mode) = copy(modes = modes + (g to m))

    /** The brand that applies (none while a common device is chosen). */
    val effectiveBrand: String? get() = if (preset.bodyId != null) null else brand

    /**
     * True if a generated device is used for anything: a group on Random that describes the device
     * or the capture, device-style file names or a colour profile matched to the device. A device
     * is only generated while Camera & device is not on Keep (otherwise the photo's own camera is
     * used).
     */
    val usesGeneratedDevice: Boolean
        get() = mode(Group.CAMERA) != Mode.KEEP && (
            mode(Group.CAMERA) == Mode.REPLACE || mode(Group.LENS) == Mode.REPLACE || mode(Group.DATE) == Mode.REPLACE ||
                fileName == FileNameMode.DEVICE || (icc == IccMode.REPLACE && iccTarget == IccTarget.MATCH_DEVICE)
            )

    /** True if the result depends on random choices, so processing the same file twice differs. */
    val randomizes: Boolean
        get() = modes.values.any { it == Mode.REPLACE } || fileName != FileNameMode.KEEP

    companion object {
        /** Version 0.1.0 behaviour: wipe everything, keep a cleaned colour profile and the orientation. */
        val DEFAULT = WipeOptions()
    }
}
