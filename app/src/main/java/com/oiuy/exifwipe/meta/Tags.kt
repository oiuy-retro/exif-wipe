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

/**
 * Sorts EXIF tags into [Group]s. Tags not listed here are never copied from the original:
 * unknown and vendor tags (Xiaomi 0x9999, padding, maker notes) are always removed.
 *
 * "Structural" tags describe the file itself (orientation, resolution, EXIF version, pixel
 * dimensions, colour space) and are written by the app, never copied.
 */
object Tags {
    // IFD0
    const val IMAGE_DESCRIPTION = 0x010E
    const val MAKE = 0x010F
    const val MODEL = 0x0110
    const val ORIENTATION = 0x0112
    const val X_RESOLUTION = 0x011A
    const val Y_RESOLUTION = 0x011B
    const val RESOLUTION_UNIT = 0x0128
    const val SOFTWARE = 0x0131
    const val DATE_TIME = 0x0132
    const val ARTIST = 0x013B
    const val HOST_COMPUTER = 0x013C
    const val YCBCR_POSITIONING = 0x0213
    const val COMPRESSION = 0x0103
    const val COPYRIGHT = 0x8298
    const val RATING = 0x4746
    const val RATING_PERCENT = 0x4749
    const val XP_TITLE = 0x9C9B
    const val XP_COMMENT = 0x9C9C
    const val XP_AUTHOR = 0x9C9D
    const val XP_KEYWORDS = 0x9C9E
    const val XP_SUBJECT = 0x9C9F

    // EXIF IFD
    const val EXPOSURE_TIME = 0x829A
    const val F_NUMBER = 0x829D
    const val EXPOSURE_PROGRAM = 0x8822
    const val ISO = 0x8827
    const val SENSITIVITY_TYPE = 0x8830
    const val EXIF_VERSION = 0x9000
    const val DATE_TIME_ORIGINAL = 0x9003
    const val DATE_TIME_DIGITIZED = 0x9004
    const val OFFSET_TIME = 0x9010
    const val OFFSET_TIME_ORIGINAL = 0x9011
    const val OFFSET_TIME_DIGITIZED = 0x9012
    const val COMPONENTS_CONFIGURATION = 0x9101
    const val SHUTTER_SPEED_VALUE = 0x9201
    const val APERTURE_VALUE = 0x9202
    const val BRIGHTNESS_VALUE = 0x9203
    const val EXPOSURE_BIAS = 0x9204
    const val MAX_APERTURE_VALUE = 0x9205
    const val METERING_MODE = 0x9207
    const val LIGHT_SOURCE = 0x9208
    const val FLASH = 0x9209
    const val FOCAL_LENGTH = 0x920A
    const val MAKER_NOTE = 0x927C
    const val USER_COMMENT = 0x9286
    const val SUBSEC_TIME = 0x9290
    const val SUBSEC_TIME_ORIGINAL = 0x9291
    const val SUBSEC_TIME_DIGITIZED = 0x9292
    const val FLASHPIX_VERSION = 0xA000
    const val COLOR_SPACE = 0xA001
    const val PIXEL_X = 0xA002
    const val PIXEL_Y = 0xA003
    const val SENSING_METHOD = 0xA217
    const val SCENE_TYPE = 0xA301
    const val CUSTOM_RENDERED = 0xA401
    const val EXPOSURE_MODE = 0xA402
    const val WHITE_BALANCE = 0xA403
    const val DIGITAL_ZOOM = 0xA404
    const val FOCAL_LENGTH_35 = 0xA405
    const val SCENE_CAPTURE_TYPE = 0xA406
    const val IMAGE_UNIQUE_ID = 0xA420
    const val CAMERA_OWNER = 0xA430
    const val BODY_SERIAL = 0xA431
    const val LENS_SPECIFICATION = 0xA432
    const val LENS_MAKE = 0xA433
    const val LENS_MODEL = 0xA434
    const val LENS_SERIAL = 0xA435
    const val IMAGE_TITLE = 0xA436
    const val PHOTOGRAPHER = 0xA437
    const val IMAGE_EDITOR = 0xA438
    const val CAMERA_FIRMWARE = 0xA439
    const val RAW_DEVELOPING_SOFTWARE = 0xA43A
    const val IMAGE_EDITING_SOFTWARE = 0xA43B
    const val METADATA_EDITING_SOFTWARE = 0xA43C
    const val COMPOSITE_IMAGE = 0xA460

    // GPS IFD
    const val GPS_VERSION = 0x0000
    const val GPS_LATITUDE_REF = 0x0001
    const val GPS_LATITUDE = 0x0002
    const val GPS_LONGITUDE_REF = 0x0003
    const val GPS_LONGITUDE = 0x0004
    const val GPS_ALTITUDE_REF = 0x0005
    const val GPS_ALTITUDE = 0x0006
    const val GPS_TIME_STAMP = 0x0007
    const val GPS_DATE_STAMP = 0x001D

    private val IFD0_GROUPS: Map<Int, Group> = buildMap {
        put(IMAGE_DESCRIPTION, Group.AUTHOR)
        put(ARTIST, Group.AUTHOR)
        put(COPYRIGHT, Group.AUTHOR)
        put(RATING, Group.AUTHOR)
        put(RATING_PERCENT, Group.AUTHOR)
        for (t in XP_TITLE..XP_SUBJECT) put(t, Group.AUTHOR)
        put(MAKE, Group.CAMERA)
        put(MODEL, Group.CAMERA)
        put(SOFTWARE, Group.CAMERA)
        put(HOST_COMPUTER, Group.CAMERA)
        put(DATE_TIME, Group.DATE)
    }

    private val EXIF_GROUPS: Map<Int, Group> = buildMap {
        for (t in listOf(DATE_TIME_ORIGINAL, DATE_TIME_DIGITIZED, OFFSET_TIME, OFFSET_TIME_ORIGINAL, OFFSET_TIME_DIGITIZED,
            SUBSEC_TIME, SUBSEC_TIME_ORIGINAL, SUBSEC_TIME_DIGITIZED)) put(t, Group.DATE)
        for (t in listOf(IMAGE_UNIQUE_ID, CAMERA_OWNER, BODY_SERIAL, CAMERA_FIRMWARE, RAW_DEVELOPING_SOFTWARE,
            IMAGE_EDITING_SOFTWARE, METADATA_EDITING_SOFTWARE)) put(t, Group.CAMERA)
        // Maker notes belong to Camera & device but are never copied: they contain internal offsets
        // that break when moved, and often serial numbers in encrypted form.
        for (t in listOf(USER_COMMENT, IMAGE_TITLE, PHOTOGRAPHER, IMAGE_EDITOR)) put(t, Group.AUTHOR)
        for (t in listOf(
            EXPOSURE_TIME, F_NUMBER, EXPOSURE_PROGRAM, ISO, SENSITIVITY_TYPE, 0x8831, 0x8832, 0x8833, 0x8834, 0x8835,
            SHUTTER_SPEED_VALUE, APERTURE_VALUE, BRIGHTNESS_VALUE, EXPOSURE_BIAS, MAX_APERTURE_VALUE, 0x9206,
            METERING_MODE, LIGHT_SOURCE, FLASH, FOCAL_LENGTH, 0x9214, 0xA20B, 0xA20C, 0xA20E, 0xA20F, 0xA210,
            0xA214, 0xA215, SENSING_METHOD, 0xA300, SCENE_TYPE, CUSTOM_RENDERED, EXPOSURE_MODE, WHITE_BALANCE,
            DIGITAL_ZOOM, FOCAL_LENGTH_35, SCENE_CAPTURE_TYPE, 0xA407, 0xA408, 0xA409, 0xA40A, 0xA40C,
            LENS_SPECIFICATION, LENS_MAKE, LENS_MODEL, LENS_SERIAL, COMPOSITE_IMAGE, 0xA461, 0xA462,
        )) put(t, Group.LENS)
    }

    /** Tags the app writes itself to describe the file. Never copied from the original. */
    val STRUCTURAL: Map<Ifd, Set<Int>> = mapOf(
        Ifd.IFD0 to setOf(ORIENTATION, X_RESOLUTION, Y_RESOLUTION, RESOLUTION_UNIT, YCBCR_POSITIONING),
        Ifd.EXIF to setOf(EXIF_VERSION, FLASHPIX_VERSION, COLOR_SPACE, PIXEL_X, PIXEL_Y, COMPONENTS_CONFIGURATION),
        Ifd.GPS to setOf(GPS_VERSION),
        Ifd.IFD1 to setOf(COMPRESSION, X_RESOLUTION, Y_RESOLUTION, RESOLUTION_UNIT),
    )

    /** Group of a tag, or null if the tag is structural or unknown. */
    fun group(ifd: Ifd, tag: Int): Group? = when (ifd) {
        Ifd.IFD0 -> IFD0_GROUPS[tag]
        Ifd.EXIF -> EXIF_GROUPS[tag]
        Ifd.GPS -> when (tag) {
            GPS_VERSION -> null
            GPS_TIME_STAMP, GPS_DATE_STAMP -> Group.DATE
            in 0x01..0x1F -> Group.LOCATION
            else -> null
        }
        Ifd.IFD1 -> null
    }
}
