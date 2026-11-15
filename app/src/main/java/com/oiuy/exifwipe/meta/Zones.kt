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

import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Time zones by IANA id, tolerant of older time zone data on the phone. Android updates its time
 * zone database separately from the app, so an id that is new in the data the app was built from
 * (for example Europe/Kyiv, renamed in 2022) can be unknown on an older phone. Such ids fall back
 * to the previous id for the same area, which has the same offsets.
 */
object Zones {
    private val ALIASES = mapOf(
        "Europe/Kyiv" to "Europe/Kiev",
        "America/Ciudad_Juarez" to "America/Ojinaga",
        "America/Nuuk" to "America/Godthab",
        "Pacific/Kanton" to "Pacific/Enderbury",
        "Asia/Qostanay" to "Asia/Almaty",
        "Asia/Yangon" to "Asia/Rangoon",
        "Asia/Kolkata" to "Asia/Calcutta",
        "Asia/Kathmandu" to "Asia/Katmandu",
        "Asia/Ho_Chi_Minh" to "Asia/Saigon",
        "Atlantic/Faroe" to "Atlantic/Faeroe",
    )
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** The zone for [id], an older alias of it, or null if the phone knows neither. */
    fun find(id: String): ZoneId? {
        val v = cache.getOrPut(id) { tryOf(id) ?: ALIASES[id]?.let(::tryOf) ?: NONE }
        return v as? ZoneId
    }

    /** Like [find], but never fails: unknown ids give UTC. */
    fun of(id: String): ZoneId = find(id) ?: ZoneOffset.UTC

    fun isAvailable(id: String) = find(id) != null

    private fun tryOf(id: String): ZoneId? = try {
        ZoneId.of(id).also { it.rules } // also loads the rules, which can fail separately
    } catch (_: Exception) {
        null
    }

    private val NONE = Any()
}
