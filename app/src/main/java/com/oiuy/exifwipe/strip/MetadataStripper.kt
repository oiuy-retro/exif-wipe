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

import com.oiuy.exifwipe.meta.Group
import com.oiuy.exifwipe.meta.MetadataPlanner
import com.oiuy.exifwipe.meta.Mode
import com.oiuy.exifwipe.meta.Tags
import com.oiuy.exifwipe.meta.WipeOptions

/** Outcome of rewriting one image in memory. */
class StripResult(
    val format: ImageFormat,
    val output: ByteArray,
    /** False if the output is identical to the input; the original need not be rewritten. */
    val changed: Boolean,
    /** What was written (and checked). */
    val meta: OutputMeta? = null,
)

object MetadataStripper {
    val supportedFormats = setOf(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP)

    /** Returns a metadata-free copy of [input] (version 0.1.0 behaviour), verified by [OutputVerifier]. */
    fun strip(input: ByteArray): StripResult = rewrite(input) { OutputMeta.default(it) }

    /** Rewrites [input] according to [options]; generated values come from [planner]. */
    fun process(input: ByteArray, options: WipeOptions, planner: MetadataPlanner): StripResult {
        val result = rewrite(input, planner)
        checkPolicy(result.output, result.format, options, result.meta!!)
        return result
    }

    fun rewrite(input: ByteArray, planner: Planner): StripResult {
        val format = ImageFormat.detect(input)
        val r = when (format) {
            ImageFormat.JPEG -> JpegStripper.rewrite(input, planner)
            ImageFormat.PNG -> PngStripper.rewrite(input, planner)
            ImageFormat.WEBP -> WebpStripper.rewrite(input, planner)
            else -> throw UnsupportedFormatException(format)
        }
        OutputVerifier.verify(format, r.output, r.meta)
        return StripResult(format, r.output, !r.output.contentEquals(input), r.meta)
    }

    /**
     * Second, independent check of the planned EXIF against the options: re-reads the EXIF that
     * was written and fails if it holds a tag of a group set to WIPE, a maker note or any tag the
     * app does not know.
     */
    internal fun checkPolicy(output: ByteArray, format: ImageFormat, options: WipeOptions, meta: OutputMeta) {
        val exif = meta.exif ?: return
        val tiff = Tiff.read(exif) ?: throw StripException("Safety check failed: written EXIF cannot be read back")
        for ((ifd, list) in tiff.entries) for (e in list) {
            if (ifd == Ifd.IFD1) continue
            if (e.tag in Tags.STRUCTURAL[ifd].orEmpty()) continue
            val g = Tags.group(ifd, e.tag) ?: throw StripException("Safety check failed: unexpected EXIF tag 0x%04X".format(e.tag))
            if (options.mode(g) == Mode.WIPE) throw StripException("Safety check failed: ${g.title} data was written although it is set to wipe")
        }
        if (tiff.thumbnail != null && options.mode(Group.EXTRAS) == Mode.WIPE) throw StripException("Safety check failed: thumbnail written although extras are set to wipe")
        if (meta.keepExtras && options.mode(Group.EXTRAS) != Mode.KEEP) throw StripException("Safety check failed: extras kept")
        if (format != ImageFormat.JPEG && meta.keepExtras) throw StripException("Safety check failed: extras kept")
        if (output.isEmpty()) throw StripException("Safety check failed: empty output")
    }
}
