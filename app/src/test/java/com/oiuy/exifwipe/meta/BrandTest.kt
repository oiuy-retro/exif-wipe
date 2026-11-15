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

import com.oiuy.exifwipe.strip.JpegStripper
import com.oiuy.exifwipe.strip.Fixtures
import com.oiuy.exifwipe.strip.Ifd
import com.oiuy.exifwipe.strip.MetadataStripper
import com.oiuy.exifwipe.strip.OutputVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Random

/**
 * The "Camera brand" setting: with any brand chosen, every random value must fit that brand. The
 * body is one of its cameras or phones, the lens is made for the body's mount (or is its built-in
 * lens), the date lies in the body's era, and file name, software and colour profile follow the body.
 */
class BrandTest {
    private val ds = Dataset.get()
    private val thumb = JpegStripper.strip(Fixtures.jpeg())
    private val now = Instant.parse("2026-11-15T18:00:00Z")
    private val today = LocalDate.of(2026, 11, 15)
    private val seeds = Integer.getInteger("exifwipe.brandSeeds", 300)

    private fun allRandom(brand: String?, preset: DevicePreset = DevicePreset.RANDOM) = WipeOptions(
        Group.entries.associateWith { Mode.REPLACE }, IccMode.REPLACE, IccTarget.MATCH_DEVICE, FileNameMode.DEVICE, preset, brand,
    )

    /** Regex for names made from a bodies.tsv file name pattern. */
    private fun nameRegex(pattern: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            if (pattern[i] == '{') {
                val end = pattern.indexOf('}', i)
                val token = pattern.substring(i + 1, end)
                sb.append(
                    when {
                        token == "n4" -> "\\d{4}"
                        token == "n5" -> "\\d{5}"
                        token == "n7" -> "\\d{7}"
                        token == "f3" -> "1\\d{2}"
                        token == "M" -> "[1-9A-C]"
                        token == "dd" -> "\\d{2}"
                        token.startsWith("local:") || token.startsWith("utc:") ->
                            token.substringAfter(':').map { if (it.isLetter()) "\\d" else Regex.escape(it.toString()) }.joinToString("")
                        else -> error("unknown token $token")
                    },
                )
                i = end + 1
            } else {
                sb.append(Regex.escape(pattern[i].toString())); i++
            }
        }
        return Regex(sb.append("$").toString())
    }

    /** Dates a photo from [body] with [lens] may carry. */
    /** [today] is the UTC date of the run; local dates east of UTC can be one day later. */
    private fun range(body: Body, lens: Lens?): Pair<LocalDate, LocalDate> {
        fun first(y: Int) = LocalDate.of(y + 1, 1, 1).let { if (it.isAfter(today)) LocalDate.of(y, 7, 1) else it }
        var from = maxOf(LocalDate.of(2002, 1, 1), first(body.yearFrom))
        if (lens != null) from = maxOf(from, first(lens.year))
        val to = if (body.yearTo == 0) today.plusDays(1) else minOf(today.plusDays(1), LocalDate.of(body.yearTo + 10, 12, 31))
        return from to to
    }

    @Test
    fun everyBrandStaysWithinItsOwnCamerasLensesAndYears() {
        val failures = ArrayList<String>()
        val input = TestPhotos.jpeg()
        assertEquals(22, Brands.all.size)
        for (brand in Brands.all) {
            val seen = HashSet<String>()
            for (seed in 0 until seeds) {
                val tag = "$brand seed $seed"
                try {
                    val o = allRandom(brand)
                    val planner = MetadataPlanner(o, Random(seed * 31L + brand.hashCode()), now, thumbnailMaker = { thumb })
                    val r = MetadataStripper.process(input, o, planner)
                    OutputVerifier.verify(r.format, r.output, r.meta!!)
                    val c = planner.choices!!
                    val body = c.body ?: run { failures += "$tag: no body"; continue }
                    seen += body.id
                    if (body.brand != brand) failures += "$tag: body ${body.id} is ${body.brand}"
                    val t = TestPhotos.exifOf(r.output)!!
                    if (t.get(Ifd.IFD0, Tags.MAKE)?.ascii() != body.make) failures += "$tag: make ${t.get(Ifd.IFD0, Tags.MAKE)?.ascii()}"
                    if (t.get(Ifd.IFD0, Tags.MODEL)?.ascii() != body.model) failures += "$tag: model"
                    val lens = c.lens
                    if (lens != null) {
                        if (!Compat.isOwnMount(body, lens)) failures += "$tag: lens ${lens.model} ${lens.mounts} on ${body.model} (${body.mount})"
                        val written = t.get(Ifd.EXIF, Tags.LENS_MODEL)?.ascii()
                        if (written != null && c.compat != Compat.ADAPTED_MANUAL && written != lens.model && !body.isPhone) failures += "$tag: lens model $written"
                    }
                    val date = LocalDateTime.parse(t.get(Ifd.EXIF, Tags.DATE_TIME_ORIGINAL)!!.ascii(), DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")).toLocalDate()
                    val (from, to) = range(body, lens)
                    if (date.isBefore(from) || date.isAfter(to)) failures += "$tag: date $date outside $from..$to for ${body.model} ${lens?.model}"
                    if (date.year < 2002) failures += "$tag: date before 2002"
                    val name = r.meta!!.fileName
                    if (name == null || !nameRegex(body.fileName).matches(name)) failures += "$tag: file name $name does not match ${body.fileName}"
                    val software = t.get(Ifd.IFD0, Tags.SOFTWARE)?.ascii()
                    if (body.software.isBlank() && software != null) failures += "$tag: software $software for ${body.model}"
                    if (body.software.isNotBlank() && software == null) failures += "$tag: no software for ${body.model}"
                    val icc = r.meta!!.icc
                    val p3 = icc != null && String(icc, Charsets.UTF_16BE).contains("Display P3")
                    if (p3 != (body.icc == "p3")) failures += "$tag: colour profile p3=$p3 for ${body.model} (${body.icc})"
                } catch (e: Throwable) {
                    failures += "$tag: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
            val bodies = Brands.bodies(brand)
            println("$brand: ${seen.size} of ${bodies.size} bodies used in $seeds seeds")
            if (bodies.size >= 3 && seen.size < 2) failures += "$brand: only ${seen.size} bodies used"
        }
        failures.groupBy { it.substringAfter(": ").take(40) }.forEach { (_, l) -> println("${l.size}x ${l.first()}") }
        assertTrue("${failures.size} failures, first: ${failures.take(5)}", failures.isEmpty())
    }

    @Test
    fun leicaNeverGetsAMinoltaOrOtherBrandsLens() {
        for (seed in 0 until 400) {
            val p = MetadataPlanner(allRandom("Leica"), Random(seed.toLong()), now)
            MetadataStripper.process(Fixtures.jpeg(), allRandom("Leica"), p)
            val lens = p.choices!!.lens ?: continue
            val leicaMounts = Brands.bodies("Leica").map { it.mount }.toSet()
            assertTrue("seed $seed: ${lens.make} ${lens.model} ${lens.mounts}", lens.mounts.any { it in leicaMounts })
            assertTrue("seed $seed: ${lens.make}", "Minolta" !in lens.make)
        }
    }

    @Test
    fun ownMountRules() {
        val a99 = ds.bodies.first { it.brand == "Sony" && it.mount == "a" }
        val minoltaA = ds.lenses.first { it.make.contains("Minolta") && "a" in it.mounts }
        assertTrue("Minolta A-mount lens on a Sony A body", Compat.isOwnMount(a99, minoltaA))
        val leicaM = ds.bodies.first { it.brand == "Leica" && it.mount == "leica-m" }
        assertTrue(ds.lenses.filter { Compat.isOwnMount(leicaM, it) }.all { "leica-m" in it.mounts })
        val canonRf = ds.bodies.first { it.mount == "canon-rf" }
        val ef = ds.lenses.first { "canon-ef" in it.mounts && it.make == "Canon" }
        assertTrue("EF lens on RF body through Canon's adapter", Compat.isOwnMount(canonRf, ef))
        val nikonF = ds.lenses.first { "nikon-f" in it.mounts && "canon-ef" !in it.mounts }
        assertTrue("Nikon F lens is not own mount on Canon RF", !Compat.isOwnMount(canonRf, nikonF))
        // Every body of every brand has at least one lens for its own mount.
        for (b in ds.bodies) assertTrue(b.id, ds.lenses.any { Compat.isOwnMount(b, it) && Compat.of(b, it) != null })
    }

    @Test
    fun commonDeviceOverridesBrand() {
        val o = allRandom("Leica", DevicePreset.PIXEL)
        assertNull(o.effectiveBrand)
        for (seed in 0 until 20) {
            val p = MetadataPlanner(o, Random(seed.toLong()), now)
            MetadataStripper.process(Fixtures.jpeg(), o, p)
            assertEquals("google-pixel-7", p.choices!!.body!!.id)
        }
    }

    @Test
    fun brandNotUsedWhenCameraIsKept() {
        val o = allRandom("Leica").with(Group.CAMERA, Mode.KEEP)
        assertTrue(!o.usesGeneratedDevice)
        val wipeOnly = WipeOptions.DEFAULT.copy(brand = "Leica")
        assertTrue(!wipeOnly.usesGeneratedDevice)
        assertTrue(WipeOptions.DEFAULT.copy(fileName = FileNameMode.DEVICE).usesGeneratedDevice)
    }

    @Test
    fun validCombinationsWithAnyBrand() {
        val o = allRandom(null)
        assertTrue(o.checksCombinations)
        for (seed in 0 until seeds) {
            val p = MetadataPlanner(o, Random(seed.toLong()), now, thumbnailMaker = { thumb })
            val r = MetadataStripper.process(TestPhotos.jpeg(), o, p)
            OutputVerifier.verify(r.format, r.output, r.meta!!)
            val c = p.choices!!
            val body = c.body!!
            c.lens?.let { assertTrue("seed $seed ${it.model} on ${body.model}", Compat.of(body, it) != null) }
            val (from, to) = range(body, c.lens)
            val d = c.moment!!.local.toLocalDate()
            assertTrue("seed $seed $d not in $from..$to", !d.isBefore(from) && !d.isAfter(to))
            assertTrue("seed $seed ${r.meta!!.fileName}", nameRegex(body.fileName).matches(r.meta!!.fileName!!))
        }
    }

    @Test
    fun withoutValidCombinationsGroupsAreIndependent() {
        val o = allRandom(null).copy(validOnly = false)
        var badLens = 0
        var otherNames = 0
        var outsideEra = 0
        for (seed in 0 until seeds) {
            val p = MetadataPlanner(o, Random(seed.toLong()), now, thumbnailMaker = { thumb })
            val r = MetadataStripper.process(TestPhotos.jpeg(), o, p)
            OutputVerifier.verify(r.format, r.output, r.meta!!)
            val c = p.choices!!
            val body = c.body!!
            if (c.lens != null && Compat.of(body, c.lens!!) == null) badLens++
            if (!nameRegex(body.fileName).matches(r.meta!!.fileName!!)) otherNames++
            val d = c.moment!!.local.toLocalDate()
            assertTrue("seed $seed $d", d.year >= 2002 && !d.isAfter(today.plusDays(1)))
            val (from, to) = range(body, null)
            if (d.isBefore(from) || d.isAfter(to)) outsideEra++
        }
        println("valid only off: $badLens lenses that do not fit, $otherNames other naming, $outsideEra dates outside the era of $seeds")
        assertTrue(badLens > seeds / 4)
        assertTrue(otherNames > seeds / 4)
        assertTrue(outsideEra > 0)
    }

    @Test
    fun chosenBrandIsAlwaysValid() {
        val o = allRandom("Leica").copy(validOnly = false)
        assertTrue(o.checksCombinations)
        for (seed in 0 until 100) {
            val p = MetadataPlanner(o, Random(seed.toLong()), now)
            MetadataStripper.process(Fixtures.jpeg(), o, p)
            val c = p.choices!!
            assertEquals("Leica", c.body!!.brand)
            c.lens?.let { assertTrue(Compat.isOwnMount(c.body!!, it)) }
        }
    }
}
