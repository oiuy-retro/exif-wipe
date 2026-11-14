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

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Random
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A capture moment: local wall-clock time, its UTC offset and sub-second digits. */
data class Moment(val local: LocalDateTime, val offset: ZoneOffset, val millis: Int) {
    val utc: LocalDateTime get() = local.minusSeconds(offset.totalSeconds.toLong())
    val exif: String get() = local.format(EXIF_FORMAT)
    val offsetText: String get() = if (offset.totalSeconds == 0) "+00:00" else offset.id
    val subSec: String get() = "%03d".format(millis)

    companion object {
        val EXIF_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    }
}

/**
 * Random dates after 2002-01-01, weighted toward recent years.
 *
 * Within the allowed range a fraction x in [0, 1] (0 = start, 1 = end) is drawn with density
 * proportional to (FLOOR + x): the most recent day is (FLOOR + 1) / FLOOR = 11 times as likely as
 * the first one, and the density rises linearly in between. The time of day is uniform.
 */
object DateRandomizer {
    val START: LocalDate = LocalDate.of(2002, 1, 1)
    const val FLOOR = 0.1

    /** Inverse of the cumulative distribution F(x) = (FLOOR x + x^2 / 2) / (FLOOR + 1/2). */
    fun fraction(u: Double): Double = (-FLOOR + sqrt(FLOOR * FLOOR + 2 * u * (FLOOR + 0.5))).coerceIn(0.0, 1.0)

    fun date(r: Random, from: LocalDate, to: LocalDate): LocalDate {
        val a = maxOf(from, START)
        val b = maxOf(a, to)
        val days = b.toEpochDay() - a.toEpochDay()
        val pick = (fraction(r.nextDouble()) * (days + 1)).toLong().coerceAtMost(days)
        return a.plusDays(pick)
    }

    /** Random moment in [from, to] at a uniformly random time of day, in [zone]. */
    fun moment(r: Random, from: LocalDate, to: LocalDate, zone: ZoneId, now: Instant): Moment {
        val day = date(r, from, to)
        val time = LocalTime.ofSecondOfDay(r.nextInt(86400).toLong())
        // ZonedDateTime moves times that fall into a daylight-saving gap forward, so the local
        // time and offset written to the file always exist.
        var z = ZonedDateTime.of(day, time, zone)
        if (z.toInstant().isAfter(now)) z = now.atZone(zone).minusMinutes(r.nextInt(600).toLong() + 1)
        return Moment(z.toLocalDateTime(), z.offset, r.nextInt(1000))
    }
}

/** Random positions on land near populated places, weighted by population. */
object PlaceRandomizer {
    private const val CELL_KM = 1.0
    private const val GRID = 11

    fun place(r: Random, ds: Dataset, filter: ((Place) -> Boolean)? = null): Place {
        if (filter != null) {
            val subset = ds.places.filter(filter)
            if (subset.isNotEmpty()) {
                val total = subset.sumOf { it.population }
                var x = (r.nextDouble() * total).toLong()
                for (p in subset) { x -= p.population; if (x < 0) return p }
                return subset.last()
            }
        }
        return ds.placeByPopulation((r.nextDouble() * ds.totalPopulation).toLong().coerceAtMost(ds.totalPopulation - 1))
    }

    /** A random point inside a random land cell of [p]. Returns (latitude, longitude). */
    fun point(r: Random, p: Place): Pair<Double, Double> {
        val cells = (0 until GRID * GRID).filter { p.landMask.testBit(it) }
        val cell = cells[r.nextInt(cells.size)]
        val half = GRID / 2
        val dx = (cell % GRID - half) + (r.nextDouble() - 0.5)
        val dy = (cell / GRID - half) + (r.nextDouble() - 0.5)
        val lat = p.lat + dy * CELL_KM / 110.574
        val lon = p.lon + dx * CELL_KM / (111.320 * cos(Math.toRadians(p.lat)))
        return lat to lon
    }

    fun nearest(ds: Dataset, lat: Double, lon: Double): Place = ds.places.minBy {
        val dLat = it.lat - lat
        val dLon = (it.lon - lon) * cos(Math.toRadians(lat))
        dLat * dLat + dLon * dLon
    }
}

/** Exposure values that fit together for one shot. */
data class Exposure(
    val focal: Double?,
    val focal35: Int?,
    val fNumber: Double?,
    val maxAperture: Double?,
    /** Exposure time as numerator / denominator seconds. */
    val timeNum: Int,
    val timeDen: Int,
    val iso: Int,
    val flash: Int,
    val program: Int,
    val metering: Int,
    val biasThirds: Int,
) {
    val seconds: Double get() = timeNum.toDouble() / timeDen
}

object ExposureRandomizer {
    val F_STOPS = doubleArrayOf(
        0.95, 1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.5, 4.0, 4.5, 5.0, 5.6, 6.3, 7.1, 8.0, 9.0,
        10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0, 25.0, 29.0, 32.0, 36.0, 40.0, 45.0,
    )
    val ISOS = intArrayOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000,
        6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200, 64000, 80000, 102400,
    )
    /** Camera shutter speeds in 1/3 stops: (numerator, denominator). */
    val SHUTTERS: List<Pair<Int, Int>> = listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500,
        400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4).map { 1 to it } +
        listOf(3 to 10, 4 to 10, 5 to 10, 6 to 10, 8 to 10, 1 to 1, 13 to 10, 16 to 10, 2 to 1, 25 to 10, 32 to 10, 4 to 1)

    /** Scene brightness (EV at ISO 100): mostly daylight, some indoor and some low light. */
    fun sceneEv(r: Random): Double {
        val u = r.nextDouble()
        return when {
            u < 0.55 -> 11.0 + r.nextDouble() * 4.0
            u < 0.85 -> 6.0 + r.nextDouble() * 5.0
            else -> 2.0 + r.nextDouble() * 4.0
        }
    }

    fun snapStop(n: Double): Double = F_STOPS.minBy { kotlin.math.abs(ln(it) - ln(n)) }

    /** First standard f-stop that is not wider than [maxAperture]. */
    private fun stopNotWider(maxAperture: Double): Double = F_STOPS.firstOrNull { it >= maxAperture - 0.05 } ?: F_STOPS.last()

    fun camera(r: Random, body: Body, lens: Lens?, compat: Compat?): Exposure {
        val manual = lens == null || compat == Compat.ADAPTED_MANUAL
        val focal = lens?.let { l -> if (l.isZoom) pickFocal(r, l) else l.focalMin }
        val crop = body.crop
        val f35 = focal?.let { f -> lens.focal35 ?: (f * crop).roundToInt() }
        val maxAp = lens?.let { stopNotWider(it.maxApertureAt(focal!!)) }
        var n = if (lens == null) 4.0 else {
            // Mostly wide open to f/8; occasionally smaller apertures for landscapes.
            val choices = F_STOPS.filter { it >= maxAp!! - 0.01 && it <= lens.minAperture + 0.01 }
            val upTo8 = choices.filter { it <= 8.0 }.ifEmpty { choices }
            if (r.nextDouble() < 0.85) upTo8[(r.nextDouble().pow(1.6) * upTo8.size).toInt().coerceAtMost(upTo8.size - 1)]
            else choices[r.nextInt(choices.size)]
        }
        val ev = sceneEv(r)
        val handheld = 1.0 / maxOf(30.0, (f35 ?: 50).toDouble())
        val baseIso = maxOf(body.isoMin, 100).let { b -> ISOS.first { it >= b } }
        val isoChoices = ISOS.filter { it >= baseIso && it <= body.isoMax }
        var iso = isoChoices.first()
        fun time() = n * n / 2.0.pow(ev) * 100.0 / iso
        while (time() > handheld && iso < isoChoices.last()) iso = isoChoices[isoChoices.indexOf(iso) + 1]
        // Too bright for the fastest shutter: stop the lens down.
        while (time() < 1.0 / 8000 && lens != null && n < lens.minAperture) n = F_STOPS[F_STOPS.indexOfFirst { it >= n } .let { if (F_STOPS[it] == n) it + 1 else it }.coerceAtMost(F_STOPS.size - 1)]
        val t = time().coerceIn(1.0 / 8000, 4.0)
        val shutter = SHUTTERS.minBy { kotlin.math.abs(ln(it.first.toDouble() / it.second) - ln(t)) }
        val dark = ev < 6
        val flash = when {
            body.kind == "action" -> 0x20 // no flash function
            dark && r.nextDouble() < 0.3 -> 0x19 // auto, fired
            r.nextDouble() < 0.5 -> 0x10 // off, did not fire
            else -> 0x18 // auto, did not fire
        }
        val program = if (r.nextDouble() < 0.7) 2 else 3
        val bias = if (r.nextDouble() < 0.8) 0 else listOf(-2, -1, 1, 2)[r.nextInt(4)]
        return Exposure(
            if (manual) null else focal, if (manual) null else f35, if (manual) null else n, if (manual) null else maxAp,
            shutter.first, shutter.second, iso, flash, if (manual) 1 else program, 5, bias,
        )
    }

    /** Phones: fixed aperture, odd shutter fractions (1/191 s) and ISO steps of one. */
    fun phone(r: Random, body: Body, module: Lens): Exposure {
        val ev = sceneEv(r)
        val n = module.apertureWide
        val tMax = 1.0 / 15
        var iso = body.isoMin.toDouble()
        var t = n * n / 2.0.pow(ev) * 100.0 / iso
        if (t > tMax) { iso = (iso * t / tMax).coerceAtMost(body.isoMax.toDouble()); t = n * n / 2.0.pow(ev) * 100.0 / iso }
        t = t.coerceIn(1.0 / 12000, 1.0)
        val den = (1.0 / t).roundToInt().coerceAtLeast(1)
        val flash = if (ev < 6 && r.nextDouble() < 0.15) 0x19 else 0x10
        val metering = if (body.make == "Apple") 5 else 2
        return Exposure(module.focalMin, module.focal35, n, n, 1, den, iso.roundToInt(), flash, 2, metering, 0)
    }

    private fun pickFocal(r: Random, l: Lens): Double {
        // Zooms are used most at their ends; otherwise uniformly in between (in log space).
        val u = r.nextDouble()
        val f = when {
            u < 0.3 -> l.focalMin
            u < 0.5 -> l.focalMax
            else -> kotlin.math.exp(ln(l.focalMin) + r.nextDouble() * (ln(l.focalMax) - ln(l.focalMin)))
        }
        val rounded = if (l.focalMax < 20) (f * 10).roundToInt() / 10.0 else f.roundToInt().toDouble()
        return rounded.coerceIn(l.focalMin, l.focalMax)
    }

    /** APEX value 2 * log2(N). */
    fun apex(n: Double) = 2 * log2(n)
}

/** Expands software and file-name patterns from bodies.tsv. */
object Patterns {
    private data class Ios(val major: Int, val released: LocalDate)
    private val IOS = listOf(
        Ios(10, LocalDate.of(2016, 9, 13)), Ios(11, LocalDate.of(2017, 9, 19)), Ios(12, LocalDate.of(2018, 9, 17)),
        Ios(13, LocalDate.of(2019, 9, 19)), Ios(14, LocalDate.of(2020, 9, 16)), Ios(15, LocalDate.of(2021, 9, 20)),
        Ios(16, LocalDate.of(2022, 9, 12)), Ios(17, LocalDate.of(2023, 9, 18)), Ios(18, LocalDate.of(2024, 9, 16)),
        Ios(26, LocalDate.of(2025, 9, 15)),
    )
    /** Last iOS major version each iPhone in the dataset can run. */
    private val IOS_MAX = mapOf("iPhone 7" to 15)

    fun iosVersion(r: Random, model: String, date: LocalDate): String {
        val cap = IOS_MAX[model] ?: Int.MAX_VALUE
        val rel = IOS.lastOrNull { !it.released.isAfter(date) && it.major <= cap } ?: IOS.first()
        val next = IOS.firstOrNull { it.released.isAfter(rel.released) && it.major <= cap }
        val end = if (next != null && !next.released.isAfter(date)) next.released else date
        val months = ((end.toEpochDay() - rel.released.toEpochDay()) / 30).toInt()
        val minor = (months / 2).coerceIn(0, 7)
        val patch = if (r.nextDouble() < 0.3) ".${1 + r.nextInt(3)}" else ""
        return "${rel.major}.$minor$patch".let { if (minor == 0 && patch.isEmpty()) "${rel.major}.0" else it }
    }

    fun software(r: Random, body: Body, date: LocalDate): String? {
        if (body.software.isBlank()) return null
        return when (body.software) {
            "{ios}" -> iosVersion(r, body.model, date)
            "{hdrplus}" -> "HDR+ 1.0." + (1 + r.nextInt(9)).toString() + (1..8).joinToString("") { r.nextInt(10).toString() } + "zd"
            else -> body.software.replace("{model}", body.model).map { if (it == '#') ('0' + r.nextInt(10)) else it }.joinToString("")
        }
    }

    private val TOKEN = Regex("""\{([a-z0-9]+)(?::([^}]*))?}""", RegexOption.IGNORE_CASE)

    /** File name from [pattern] for a photo taken at [m]. Counters are random. */
    fun fileName(r: Random, pattern: String, m: Moment): String = TOKEN.replace(pattern) { t ->
        val (name, arg) = t.destructured
        when (name) {
            "n4" -> "%04d".format(1 + r.nextInt(9999))
            "n5" -> "%05d".format(1 + r.nextInt(9999))
            "n7" -> "%07d".format(1 + r.nextInt(999999))
            "f3" -> (100 + r.nextInt(100)).toString()
            "M" -> "123456789ABC"[m.local.monthValue - 1].toString()
            "dd" -> "%02d".format(m.local.dayOfMonth)
            "local" -> m.local.withNano(m.millis * 1_000_000).format(DateTimeFormatter.ofPattern(arg))
            "utc" -> m.utc.withNano(m.millis * 1_000_000).format(DateTimeFormatter.ofPattern(arg))
            else -> t.value
        }
    }
}
