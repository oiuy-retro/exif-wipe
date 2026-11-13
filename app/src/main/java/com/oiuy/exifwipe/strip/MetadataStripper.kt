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

/** Outcome of stripping one image in memory. */
class StripResult(
    val format: ImageFormat,
    val output: ByteArray,
    /** False if the input already had no removable metadata; the original need not be rewritten. */
    val changed: Boolean,
)

object MetadataStripper {
    val supportedFormats = setOf(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP)

    /** Returns a metadata-free copy of [input], verified by [OutputVerifier]. Never modifies [input]. */
    fun strip(input: ByteArray): StripResult {
        val format = ImageFormat.detect(input)
        val output = when (format) {
            ImageFormat.JPEG -> JpegStripper.strip(input)
            ImageFormat.PNG -> PngStripper.strip(input)
            ImageFormat.WEBP -> WebpStripper.strip(input)
            else -> throw UnsupportedFormatException(format)
        }
        OutputVerifier.verify(format, output)
        return StripResult(format, output, !output.contentEquals(input))
    }
}
