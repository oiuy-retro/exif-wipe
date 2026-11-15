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

/**
 * Metadata found in the original file, collected by a format reader before anything is written.
 * Nothing in here is ever copied to the output directly; the planner decides what to write.
 */
class SourceMeta(
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    /** Parsed EXIF (first EXIF block only), or null. */
    val tiff: TiffData?,
    /** Orientation 1..8 found in the EXIF block, or null. */
    val orientation: Int?,
    /** Main XMP packet as text, or null. */
    val xmp: String?,
    /** IPTC-IIM data from a Photoshop APP13 segment (JPEG only), or null. */
    val iptc: ByteArray?,
    /** The embedded ICC profile exactly as found, or null. */
    val icc: ByteArray?,
    /** PNG text chunks: (chunk type, raw chunk data). */
    val pngText: List<Pair<String, ByteArray>> = emptyList(),
    /** PNG tIME chunk data, or null. */
    val pngTime: ByteArray? = null,
    /** True if a JPEG has an MPF segment or data after its end-of-image marker. */
    val hasExtras: Boolean = false,
)

/**
 * Exactly what the output file will contain besides pixels and the chunks or segments needed to
 * decode them. [OutputVerifier] checks the written file against this, byte for byte.
 */
class OutputMeta(
    /** Complete TIFF block for the EXIF segment or chunk, or null for none. */
    val exif: ByteArray? = null,
    /** XMP packet (UTF-8), or null. */
    val xmp: ByteArray? = null,
    /** IPTC-IIM data written into a new Photoshop APP13 segment (JPEG only), or null. */
    val iptc: ByteArray? = null,
    /** ICC profile, or null for none. */
    val icc: ByteArray? = null,
    /** True to drop a PNG sRGB chunk (it must not appear together with a replaced iCCP profile). */
    val dropPngSrgb: Boolean = false,
    /** PNG text chunks to keep: (chunk type, raw chunk data). */
    val pngText: List<Pair<String, ByteArray>> = emptyList(),
    /** PNG tIME chunk data to keep, or null. */
    val pngTime: ByteArray? = null,
    /** Keep JPEG MPF data and data after the end-of-image marker (motion photo video, gain map). */
    val keepExtras: Boolean = false,
    /** New display name without directory, or null to keep the current one. */
    val fileName: String? = null,
    /** Short notes shown to the user (for example why a replacement was skipped). */
    val notes: List<String> = emptyList(),
) {
    companion object {
        /** Version 0.1.0 behaviour: orientation only (if needed) and the sanitized colour profile. */
        fun default(src: SourceMeta): OutputMeta = OutputMeta(
            exif = if (Orientation.needsTag(src.orientation)) Orientation.minimalTiff(src.orientation!!) else null,
            icc = src.icc?.let { IccSanitizer.sanitize(it) },
        )
    }
}

/** A format reader/writer: collects [SourceMeta], then writes the file described by an [OutputMeta]. */
fun interface Planner {
    fun plan(src: SourceMeta): OutputMeta
}

class Rewrite(val output: ByteArray, val meta: OutputMeta, val source: SourceMeta)
