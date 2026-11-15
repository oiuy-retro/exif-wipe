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
import com.oiuy.exifwipe.strip.Fixtures
import com.oiuy.exifwipe.strip.JpegStripper
import com.oiuy.exifwipe.strip.MetadataStripper
import com.oiuy.exifwipe.strip.OutputVerifier
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Random

/**
 * Every group on Random, colour profile replaced, device-style file names: the settings a user
 * chose when 0.2.0-test1 failed. Runs many seeds with every preset over photo structures like a
 * Samsung Galaxy camera file (Ultra HDR with MPF and trailer, vendor ICC profile, orientation).
 * Real photos can be added locally with -Dexifwipe.photos=<dir>; they are never committed.
 */
class AllRandomStressTest {
    private val thumb = JpegStripper.strip(Fixtures.jpeg())

    private fun inputs(): List<Pair<String, ByteArray>> {
        val list = mutableListOf(
            "samsung-fixture" to CameraFixtures.cameraJpeg(),
            "test-photo" to TestPhotos.jpeg(),
            "plain-jpeg" to Fixtures.jpeg(),
            "progressive" to Fixtures.jpeg(progressive = true),
            "png" to Fixtures.png(),
        )
        System.getProperty("exifwipe.photos")?.let { dir ->
            File(dir).listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                ?.sortedBy { it.name }?.forEach { list += it.name to it.readBytes() }
        }
        return list
    }

    @Test
    fun allRandomNeverFails() {
        val failures = ArrayList<String>()
        val photos = inputs()
        val seeds = Integer.getInteger("exifwipe.seeds", 500)
        for ((name, input) in photos) for (preset in DevicePreset.entries) for (target in IccTarget.entries) {
            for (fileName in listOf(FileNameMode.DEVICE, FileNameMode.GENERIC)) {
                val o = WipeOptions(Group.entries.associateWith { Mode.REPLACE }, IccMode.REPLACE, target, fileName, preset)
                val n = if (target == IccTarget.MATCH_DEVICE && fileName == FileNameMode.DEVICE) seeds else seeds / 10
                for (seed in 0 until n) {
                    val now = Instant.parse("2026-11-15T18:00:00Z").plusSeconds(seed * 7919L)
                    try {
                        val planner = MetadataPlanner(o, Random(seed.toLong()), now, thumbnailMaker = { thumb })
                        val r = MetadataStripper.process(input, o, planner)
                        OutputVerifier.verify(r.format, r.output, r.meta!!)
                        if (!r.changed) failures += "$name $preset $target $fileName seed $seed: output unchanged"
                        if (r.meta!!.fileName.isNullOrBlank()) failures += "$name $preset $target $fileName seed $seed: no file name"
                    } catch (e: Throwable) {
                        failures += "$name $preset $target $fileName seed $seed: ${e.javaClass.simpleName}: ${e.message}"
                    }
                }
            }
        }
        failures.groupBy { it.substringAfter(": ") }.forEach { (msg, l) -> println("${l.size}x $msg  e.g. ${l.first()}") }
        assertTrue("${failures.size} failures, first: ${failures.take(5)}", failures.isEmpty())
    }

    /** Every group on Random with "Only valid combinations" off, and with each brand. */
    @Test
    fun independentAndBrandRandomNeverFail() {
        val failures = ArrayList<String>()
        val photos = inputs()
        val seeds = Integer.getInteger("exifwipe.seeds", 500) / 5
        val variants = listOf(null to false) + Brands.all.map { it to true }
        for ((name, input) in photos) for ((brand, valid) in variants) {
            val o = WipeOptions(Group.entries.associateWith { Mode.REPLACE }, IccMode.REPLACE, IccTarget.MATCH_DEVICE, FileNameMode.DEVICE, DevicePreset.RANDOM, brand, valid)
            val n = if (brand == null) seeds * 5 else seeds / 5
            for (seed in 0 until n) {
                try {
                    val planner = MetadataPlanner(o, Random(seed.toLong()), Instant.parse("2026-11-15T18:00:00Z").plusSeconds(seed * 7919L), thumbnailMaker = { thumb })
                    val r = MetadataStripper.process(input, o, planner)
                    OutputVerifier.verify(r.format, r.output, r.meta!!)
                } catch (e: Throwable) {
                    failures += "$name ${brand ?: "independent"} seed $seed: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
        assertTrue("${failures.size} failures, first: ${failures.take(5)}", failures.isEmpty())
    }
}
