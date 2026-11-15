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

import com.oiuy.exifwipe.strip.CameraFixtures
import com.oiuy.exifwipe.strip.MetadataStripper
import com.oiuy.exifwipe.strip.OutputVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.Random

/**
 * Checks for differences between the desktop JVM (where unit tests run) and Android.
 *
 * 0.2.0-test1 crashed on phones because a regular expression with an unescaped '}' is accepted by
 * the JVM but rejected by Android's ICU regex engine; the error happened while a Kotlin object was
 * initialised, so it surfaced as an Error that the share screen did not catch.
 */
class PortabilityTest {
    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    /** String literals passed to Regex(...), Pattern.compile(...) or .toRegex(). */
    private fun regexLiterals(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val raw = Regex("(?:Regex|Pattern\\.compile)\\(\\s*\"\"\"(.*?)\"\"\"")
        val plain = Regex("(?:Regex|Pattern\\.compile)\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        for (f in sources) {
            val text = f.readText()
            raw.findAll(text).forEach { out += f.name to it.groupValues[1] }
            plain.findAll(text).forEach { m ->
                // Undo Kotlin string escapes ("\\d" in source is \d in the pattern).
                out += f.name to m.groupValues[1].replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\")
            }
        }
        return out
    }

    /** Problems ICU reports that java.util.regex accepts: a '{' or '}' that is not part of a {n,m} quantifier. */
    private fun icuProblems(p: String): List<String> {
        val problems = ArrayList<String>()
        var i = 0
        var inClass = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> i++ // skip the escaped character
                inClass -> if (c == ']') inClass = false
                c == '[' -> { inClass = true; if (p.getOrNull(i + 1) == '^') i++; if (p.getOrNull(i + 1) == ']') i++ }
                c == '{' -> {
                    val q = Regex("\\{\\d+(,\\d*)?\\}").matchAt(p, i)
                    if (q == null) problems += "unescaped '{' at $i" else i = q.range.last
                }
                c == '}' -> problems += "unescaped '}' at $i"
            }
            i++
        }
        return problems
    }

    @Test
    fun regexPatternsAreValidOnAndroid() {
        val literals = regexLiterals()
        assertTrue("found the app's patterns", literals.size >= 4)
        val bad = literals.mapNotNull { (file, p) -> icuProblems(p).takeIf { it.isNotEmpty() }?.let { "$file: $p -> $it" } }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
        // The checker itself catches the 0.2.0-test1 pattern.
        assertTrue(icuProblems("""\{([a-z0-9]+)(?::([^}]*))?}""").isNotEmpty())
        assertTrue(icuProblems("""\{([a-z0-9]+)(?::([^\}]*))?\}""").isEmpty())
        assertTrue(icuProblems("0[0-9]{3}").isEmpty())
    }

    @Test
    fun fileNamePatternsStillExpand() {
        val m = Moment(java.time.LocalDateTime.of(2024, 5, 1, 10, 30, 15), java.time.ZoneOffset.ofHours(2), 123)
        assertEquals("PXL_20240501_083015123.jpg", Patterns.fileName(Random(1), "PXL_{utc:yyyyMMdd_HHmmssSSS}.jpg", m))
        assertEquals("20240501_103015.jpg", Patterns.fileName(Random(1), "{local:yyyyMMdd_HHmmss}.jpg", m))
        assertTrue(Patterns.fileName(Random(1), "DSC_{n4}.JPG", m).matches(Regex("DSC_\\d{4}\\.JPG")))
    }

    @Test
    fun everyBundledTimeZoneResolves() {
        val zones = Dataset.get().places.map { it.zone }.toSet()
        for (z in zones) assertNotNull(z, Zones.find(z))
        // Older ids used when the phone's time zone data predates a rename.
        assertEquals(Zones.find("Europe/Kiev")?.rules?.getOffset(Instant.EPOCH), Zones.find("Europe/Kyiv")?.rules?.getOffset(Instant.EPOCH))
        assertEquals(java.time.ZoneOffset.UTC, Zones.of("Not/A_Zone"))
    }

    @Test
    fun valuesUseAsciiDigitsInEveryLocale() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "hi-IN-u-nu-deva", "th-TH-u-nu-thai", "de-DE")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                for (preset in DevicePreset.entries) for (seed in 0L until 20L) {
                    val o = WipeOptions(Group.entries.associateWith { Mode.REPLACE }, IccMode.REPLACE, IccTarget.MATCH_DEVICE, FileNameMode.DEVICE, preset)
                    val planner = MetadataPlanner(o, Random(seed), Instant.parse("2026-11-15T18:00:00Z"))
                    val r = MetadataStripper.process(CameraFixtures.cameraJpeg(), o, planner)
                    OutputVerifier.verify(r.format, r.output, r.meta!!)
                    val name = r.meta!!.fileName!!
                    assertTrue("$tag $preset $name", name.all { it.code < 128 })
                    val exif = TestPhotos.exifOf(r.output)!!
                    exif.entries.values.flatten().filter { it.type == 2 }.forEach { e ->
                        assertTrue("$tag ascii tag", e.value.all { it >= 0 })
                    }
                }
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
