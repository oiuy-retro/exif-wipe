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

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Random

/** The camera brands of the bundled body list, for the "Camera brand" setting. */
object Brands {
    /** Every brand in bodies.tsv, alphabetically. */
    val all: List<String> by lazy { Dataset.get().bodies.map { it.brand }.distinct().sortedBy { it.lowercase() } }

    /** Bodies of [brand] that random values can use. */
    fun bodies(brand: String): List<Body> = Dataset.get().bodies.filter { it.brand == brand }

    /** True if the brand makes phones only, cameras only, or both. */
    fun kinds(brand: String): String {
        val b = bodies(brand)
        val phones = b.count { it.isPhone }
        return when {
            phones == b.size -> "phones"
            phones == 0 -> "cameras"
            else -> "cameras and phones"
        }
    }

    /** An example file name in the style of [brand] (or of [body]), for the settings screen. */
    fun exampleFileName(body: Body): String {
        val m = Moment(LocalDateTime.of(2024, 5, 1, 10, 30, 15), ZoneOffset.ofHours(2), 123)
        return Patterns.fileName(Random(7), body.fileName, m)
    }

    /** Distinct example names for a brand (models can use different styles), up to [max]. */
    fun exampleFileNames(brand: String, max: Int = 3): List<String> =
        bodies(brand).map { it.fileName }.distinct().take(max).map { p ->
            exampleFileName(bodies(brand).first { it.fileName == p })
        }
}
