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

import java.io.InputStream

/** A camera or phone body from bodies.tsv. */
data class Body(
    val id: String,
    val make: String,
    val model: String,
    val brand: String,
    val kind: String,
    val mount: String,
    val crop: Double,
    val yearFrom: Int,
    /** Last production year, or 0 if still sold. */
    val yearTo: Int,
    val software: String,
    val fileName: String,
    val width: Int,
    val height: Int,
    val isoMin: Int,
    val isoMax: Int,
    val bigEndian: Boolean,
    /** "srgb", "p3" or "none" (no embedded profile). */
    val icc: String,
    val exifVersion: String,
    /** True if the model string or file name pattern could not be confirmed from a real file. */
    val unverified: Boolean,
) {
    val isPhone get() = kind == "phone"
    val exifAtLeast230 get() = exifVersion >= "0230"
    val writesOffsetTime get() = exifVersion >= "0231"
}

/** A lens or a built-in camera module from lenses.tsv. */
data class Lens(
    val make: String,
    val model: String,
    val mounts: List<String>,
    val focalMin: Double,
    val focalMax: Double,
    val apertureWide: Double,
    val apertureTele: Double,
    val minAperture: Double,
    val year: Int,
    val apsOnly: Boolean,
    val manual: Boolean,
    /** 35 mm equivalent for phone modules (their crop factor differs per module). */
    val focal35: Int?,
) {
    val isZoom get() = focalMax > focalMin

    /** Widest aperture available at [focal] mm, interpolated between the wide and tele ends. */
    fun maxApertureAt(focal: Double): Double {
        if (!isZoom) return apertureWide
        val t = ((focal - focalMin) / (focalMax - focalMin)).coerceIn(0.0, 1.0)
        return apertureWide + (apertureTele - apertureWide) * t
    }
}

/** A populated place from places.tsv (GeoNames, with a Natural Earth land mask). */
class Place(
    val lat: Double,
    val lon: Double,
    val population: Long,
    val elevation: Int,
    val zone: String,
    val country: String,
    val radiusKm: Double,
    /** Bit (y * 11 + x) set if the 1 km cell at grid position (x, y), centre (5, 5), is fully on land. */
    val landMask: java.math.BigInteger,
)

class Dataset(val bodies: List<Body>, val lenses: List<Lens>, val places: List<Place>) {
    private val cumulativePopulation: LongArray = LongArray(places.size).also { a ->
        var sum = 0L
        places.forEachIndexed { i, p -> sum += p.population; a[i] = sum }
    }
    val totalPopulation: Long get() = cumulativePopulation.lastOrNull() ?: 0L

    fun body(id: String): Body? = bodies.firstOrNull { it.id == id }

    /** Place whose cumulative population range contains [r] (0 <= r < total). */
    fun placeByPopulation(r: Long): Place {
        var lo = 0
        var hi = places.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumulativePopulation[mid] > r) hi = mid else lo = mid + 1
        }
        return places[lo]
    }

    /** Lenses that can be mounted on [body] and record their data in the file (see [Compat]). */
    fun lensesFor(body: Body): List<Pair<Lens, Compat>> = lenses.mapNotNull { l -> Compat.of(body, l)?.let { l to it } }

    fun findBody(make: String?, model: String?): Body? {
        if (model.isNullOrBlank()) return null
        val m = model.trim()
        return bodies.firstOrNull { it.model.equals(m, ignoreCase = true) && (make == null || it.make.equals(make.trim(), ignoreCase = true)) }
            ?: bodies.firstOrNull { it.model.equals(m, ignoreCase = true) }
    }

    companion object {
        @Volatile private var cached: Dataset? = null

        /** Loads the bundled data once (from Java resources, which also works in unit tests). */
        fun get(): Dataset = cached ?: synchronized(this) {
            cached ?: load(
                open("bodies.tsv"), open("lenses.tsv"), open("places.tsv"),
            ).also { cached = it }
        }

        private fun open(name: String): InputStream =
            Dataset::class.java.getResourceAsStream("/exifwipe/$name") ?: error("missing resource $name")

        fun load(bodies: InputStream, lenses: InputStream, places: InputStream): Dataset {
            fun rows(s: InputStream) = s.bufferedReader(Charsets.UTF_8).useLines { l ->
                l.filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }.toList()
            }
            val b = rows(bodies).map { f ->
                Body(
                    f[0], f[1], f[2], f[3], f[4], f[5], f[6].toDouble(), f[7].toInt(), f[8].toInt(), f[9], f[10],
                    f[11].toInt(), f[12].toInt(), f[13].toInt(), f[14].toInt(), f[15] == "MM", f[16], f[17],
                    f.getOrNull(18).orEmpty().contains('u'),
                )
            }
            val l = rows(lenses).map { f ->
                val flags = f.getOrNull(9).orEmpty().split(',').toSet()
                Lens(
                    f[0], f[1], f[2].split(','), f[3].toDouble(), f[4].toDouble(), f[5].toDouble(), f[6].toDouble(),
                    f[7].toDouble(), f[8].toInt(), "aps" in flags, "manual" in flags, f.getOrNull(10)?.takeIf { it.isNotBlank() }?.toInt(),
                )
            }
            val zones = ArrayList<String>()
            val p = ArrayList<Place>()
            for (f in rows(places)) {
                when (f[0]) {
                    "Z" -> zones += f[1]
                    "P" -> p += Place(
                        f[1].toDouble(), f[2].toDouble(), f[3].toLong(), f[4].toInt(), zones[f[5].toInt()], f[6], f[7].toDouble(),
                        java.math.BigInteger(f[8], 16),
                    )
                }
            }
            return Dataset(b, l, p)
        }
    }
}

/** How a lens connects to a body. */
enum class Compat {
    /** Native mount, or an electronic adapter: the camera records the lens and aperture. */
    NATIVE,
    /** A plain mechanical adapter: the camera knows nothing about the lens. */
    ADAPTED_MANUAL;

    companion object {
        /** Mirrorless mounts that take almost anything through a mechanical adapter. */
        private val ADAPTABLE = setOf("sony-e", "fuji-x", "fuji-g", "mft", "nikon-z", "canon-rf", "canon-efm", "leica-l", "samsung-nx")
        private val LEGACY = setOf("canon-fd", "minolta-md", "olympus-om", "m42", "contax-yashica", "leica-r", "leica-m", "mamiya-645")
        private val ELECTRONIC_ADAPTERS = mapOf(
            "canon-rf" to setOf("canon-ef"), // EF-EOS R adapter
            "canon-efm" to setOf("canon-ef"), // EF-EOS M adapter
            "nikon-z" to setOf("nikon-f"), // FTZ adapter
            "sony-e" to setOf("a"), // LA-EA adapters
            "mft" to setOf("four-thirds"), // MMF adapters
        )
        /** Common mechanical adapters for DSLR mounts (M42 and other legacy lenses on EF). */
        private val DSLR_MANUAL = mapOf(
            "canon-ef" to setOf("m42", "contax-yashica", "leica-r", "olympus-om"),
            "pentax-k" to setOf("m42"),
        )

        /** True if [lens] was made for [body]'s mount or for one of the maker's electronic adapters for it. */
        fun isOwnMount(body: Body, lens: Lens): Boolean =
            lens.mounts.any { m -> m == body.mount || ELECTRONIC_ADAPTERS[body.mount]?.contains(m) == true }

        fun of(body: Body, lens: Lens): Compat? {
            if (body.mount.startsWith("fixed:")) return if (body.mount in lens.mounts) NATIVE else null
            if (lens.mounts.any { it.startsWith("fixed:") }) return null
            // APS-C-only lenses are not paired with full-frame or larger bodies.
            if (lens.apsOnly && body.crop < 1.3) return null
            val manual = lens.manual
            for (m in lens.mounts) {
                val native = m == body.mount || ELECTRONIC_ADAPTERS[body.mount]?.contains(m) == true
                if (native) return if (manual) ADAPTED_MANUAL else NATIVE
            }
            for (m in lens.mounts) {
                if (body.mount in ADAPTABLE && m in LEGACY) return ADAPTED_MANUAL
                if (DSLR_MANUAL[body.mount]?.contains(m) == true) return ADAPTED_MANUAL
            }
            return null
        }
    }
}
