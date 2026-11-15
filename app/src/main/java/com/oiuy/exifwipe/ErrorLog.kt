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
import android.os.Build
import com.oiuy.exifwipe.meta.Group
import com.oiuy.exifwipe.meta.WipeOptions
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Keeps the details of the most recent errors in the app's private storage so they can be copied
 * from the settings screen and sent to the developer. Holds no photo contents, metadata values or
 * folder paths: only the time, app and Android version, phone model, settings, file name and the
 * error with its stack trace. Nothing is sent anywhere by the app (it has no internet permission).
 */
object ErrorLog {
    private const val FILE = "error-log.txt"
    private const val MAX_CHARS = 24_000

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    fun header(context: Context): String {
        val pi = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val version = pi?.let { "${it.versionName} (${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()})" } ?: "?"
        return "Exif Wipe $version, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    fun describe(options: WipeOptions): String =
        Group.entries.joinToString(", ") { "${it.title}=${options.mode(it).name.lowercase(Locale.ROOT)}" } +
            ", colour profile=${options.icc.name.lowercase(Locale.ROOT)}/${options.iccTarget.name.lowercase(Locale.ROOT)}" +
            ", file name=${options.fileName.name.lowercase(Locale.ROOT)}, device=${options.preset.name.lowercase(Locale.ROOT)}, brand=${options.brand ?: "any"}, valid only=${options.validOnly}"

    fun stackTrace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    /** Builds one report entry. */
    fun entry(context: Context, what: String, t: Throwable?, options: WipeOptions?): String = buildString {
        append("=== ").append(ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append('\n')
        append(header(context)).append('\n')
        if (options != null) append("Settings: ").append(describe(options)).append('\n')
        append(what).append('\n')
        if (t != null) append(stackTrace(t).take(8_000))
    }

    /** Adds an entry to the log (newest first), keeping it short. Never throws. */
    fun record(context: Context, entry: String) {
        try {
            val f = file(context)
            val old = if (f.exists()) f.readText() else ""
            f.writeText((entry.trimEnd() + "\n\n" + old).take(MAX_CHARS))
        } catch (_: Throwable) {
        }
    }

    fun read(context: Context): String? = try {
        file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
