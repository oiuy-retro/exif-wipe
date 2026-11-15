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

import android.app.Application

/** Records any crash in [ErrorLog] before Android's default handling, so it can be copied later. */
class ExifWipeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            ErrorLog.record(this, ErrorLog.entry(this, "Crash in thread ${thread.name}", e, null))
            previous?.uncaughtException(thread, e)
        }
        // Parse the bundled camera list early so the settings screen does not wait for it.
        Thread({ runCatching { com.oiuy.exifwipe.meta.Dataset.get() } }, "dataset-preload").start()
    }
}
