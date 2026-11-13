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
 *
 * The magic-number detection follows the approach of ImageType.kt in Scrambler by
 * Juan García Basilio (https://gitlab.com/juanitobananas/scrambler), the library
 * extracted from Scrambled Exif. It was rewritten here; no code was copied.
 */

package com.oiuy.exifwipe.strip

enum class ImageFormat(val label: String) {
    JPEG("JPEG"),
    PNG("PNG"),
    WEBP("WebP"),
    HEIF("HEIC/HEIF/AVIF"),
    GIF("GIF"),
    BMP("BMP"),
    TIFF("TIFF/DNG"),
    UNKNOWN("Unrecognised"),
    ;

    companion object {
        private val heifBrands = setOf(
            "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
            "mif1", "msf1", "avif", "avis",
        )

        fun detect(b: ByteArray): ImageFormat {
            fun starts(vararg v: Int) = b.size >= v.size && v.indices.all { b.u8(it) == v[it] }
            return when {
                starts(0xFF, 0xD8, 0xFF) -> JPEG
                starts(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
                b.ascii(0, 4) == "RIFF" && b.ascii(8, 4) == "WEBP" -> WEBP
                b.ascii(4, 4) == "ftyp" && b.ascii(8, 4) in heifBrands -> HEIF
                b.ascii(0, 4) == "GIF8" -> GIF
                b.ascii(0, 2) == "BM" -> BMP
                starts(0x49, 0x49, 0x2A, 0x00) || starts(0x4D, 0x4D, 0x00, 0x2A) -> TIFF
                else -> UNKNOWN
            }
        }
    }
}
