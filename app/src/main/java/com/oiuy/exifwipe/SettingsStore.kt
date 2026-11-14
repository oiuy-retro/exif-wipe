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

package com.oiuy.exifwipe

import android.content.Context
import com.oiuy.exifwipe.meta.DevicePreset
import com.oiuy.exifwipe.meta.FileNameMode
import com.oiuy.exifwipe.meta.Group
import com.oiuy.exifwipe.meta.IccMode
import com.oiuy.exifwipe.meta.IccTarget
import com.oiuy.exifwipe.meta.Mode
import com.oiuy.exifwipe.meta.WipeOptions

/** Stores [WipeOptions] in the app's private preferences. Missing or unknown values fall back to the defaults. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): WipeOptions {
        fun <T : Enum<T>> read(key: String, values: Array<T>, default: T): T =
            prefs.getString(key, null)?.let { s -> values.firstOrNull { it.name == s } } ?: default
        val d = WipeOptions.DEFAULT
        return WipeOptions(
            modes = Group.entries.associateWith { read("group.${it.name}", Mode.entries.toTypedArray(), Mode.WIPE) },
            icc = read("icc", IccMode.entries.toTypedArray(), d.icc),
            iccTarget = read("iccTarget", IccTarget.entries.toTypedArray(), d.iccTarget),
            fileName = read("fileName", FileNameMode.entries.toTypedArray(), d.fileName),
            preset = read("preset", DevicePreset.entries.toTypedArray(), d.preset),
        )
    }

    fun save(o: WipeOptions) {
        prefs.edit().apply {
            for (g in Group.entries) putString("group.${g.name}", o.mode(g).name)
            putString("icc", o.icc.name)
            putString("iccTarget", o.iccTarget.name)
            putString("fileName", o.fileName.name)
            putString("preset", o.preset.name)
        }.apply()
    }
}
