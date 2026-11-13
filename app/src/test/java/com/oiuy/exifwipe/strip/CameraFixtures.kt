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

package com.oiuy.exifwipe.strip

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixtures shaped like real phone photos (modelled on a Samsung Galaxy Ultra HDR JPEG): EXIF with
 * maker notes, GPS and a thumbnail, a vendor ICC profile with copyright and manufacturer fields,
 * XMP and extended XMP, IPTC, MPF, ISO 21496 gain-map URN, FlashPix, unknown vendor segments,
 * comments, and a gain-map image plus a vendor trailer after the end of the image.
 * All identifying strings are invented.
 */
object CameraFixtures {
    const val MAKE = "samsung"
    const val MODEL = "Galaxy S24+"
    const val ICC_COPYRIGHT = "Copyright (c) 2022 Samsung Electronics Co., Ltd."
    const val ICC_DESCRIPTION = "DCI-P3 D65 Gamut with sRGB Transfer"

    /** Strings that must not survive stripping. */
    val IDENTIFYING = listOf(
        MAKE, "Samsung", "Galaxy", "S926USQU", "Jane Doe", "Copyright", "SEC ", "MAKERNOTE-SECRET",
        "2026:11:13", "hdrgm", "GContainer", "MPF", "FPXR", "Ducky", "Photoshop", "21496", "SEFH",
        "Image_UTC_Data", "MCC_Data", "vendor-app4", "META-app3", "app15", "camera comment", "ThumbCam",
        "GainMapCam", "extension/",
    )

    /** Identifying strings found in [b], in Latin-1 or UTF-16BE (ICC text) form. */
    fun leftovers(b: ByteArray): List<String> = IDENTIFYING.filter {
        b.indexOf(latin1(it)) >= 0 || b.indexOf(it.toByteArray(Charsets.UTF_16BE)) >= 0
    }

    // ---- TIFF / EXIF ----

    class Entry(val tag: Int, val type: Int, val count: Int, val value: ByteArray)

    private fun le() = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
    fun ascii(tag: Int, s: String) = (latin1(s) + 0).let { Entry(tag, 2, it.size, it) }
    fun short(tag: Int, v: Int) = Entry(tag, 3, 1, byteArrayOf(v.toByte(), (v shr 8).toByte()))
    fun long(tag: Int, v: Int) = Entry(tag, 4, 1, le().putInt(v).array())
    fun undefined(tag: Int, b: ByteArray) = Entry(tag, 7, b.size, b)
    fun rational(tag: Int, n: Int, d: Int) = Entry(tag, 5, 1, le().putInt(n).array() + le().putInt(d).array())

    /**
     * Little-endian TIFF with IFD0 (+ Exif and GPS sub-IFDs) and IFD1 holding a JPEG thumbnail.
     * Pointer tags are filled in automatically.
     */
    fun tiff(ifd0: List<Entry>, exif: List<Entry>, gps: List<Entry>, thumbnail: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        fun ifdSize(n: Int) = 2 + 12 * n + 4
        fun dataSize(es: List<Entry>) = es.sumOf { if (it.value.size > 4) (it.value.size + 1) / 2 * 2 else 0 }

        val n0 = ifd0.size + 2
        val ifd0Off = 8
        val exifOff = ifd0Off + ifdSize(n0) + dataSize(ifd0)
        val gpsOff = exifOff + ifdSize(exif.size) + dataSize(exif)
        val ifd1Entries = if (thumbnail == null) 0 else 5
        val ifd1Off = gpsOff + ifdSize(gps.size) + dataSize(gps)
        val thumbOff = ifd1Off + ifdSize(ifd1Entries) + 8 // + resolution rational

        fun write(entries: List<Entry>, at: Int, next: Int): ByteArray {
            val sorted = entries.sortedBy { it.tag }
            val b = ByteArrayOutputStream()
            val data = ByteArrayOutputStream()
            var dataAt = at + ifdSize(sorted.size)
            b.write(sorted.size and 0xFF); b.write(sorted.size shr 8)
            for (e in sorted) {
                b.write(e.tag and 0xFF); b.write(e.tag shr 8)
                b.write(e.type); b.write(0)
                b.write(le().putInt(e.count).array())
                if (e.value.size <= 4) {
                    b.write(e.value + ByteArray(4 - e.value.size))
                } else {
                    b.write(le().putInt(dataAt).array())
                    data.write(e.value)
                    if (e.value.size % 2 == 1) data.write(0)
                    dataAt += (e.value.size + 1) / 2 * 2
                }
            }
            b.write(le().putInt(next).array())
            return b.toByteArray() + data.toByteArray()
        }

        out.write(latin1("II")); out.write(byteArrayOf(42, 0)); out.write(le().putInt(8).array())
        out.write(write(ifd0 + long(0x8769, exifOff) + long(0x8825, gpsOff), ifd0Off, if (thumbnail == null) 0 else ifd1Off))
        out.write(write(exif, exifOff, 0))
        out.write(write(gps, gpsOff, 0))
        if (thumbnail != null) {
            val resAt = ifd1Off + ifdSize(ifd1Entries)
            val ifd1 = listOf(
                short(0x0103, 6),
                Entry(0x011A, 5, 1, le().putInt(resAt).array()), // XResolution, data written below
                short(0x0128, 2),
                long(0x0201, thumbOff),
                long(0x0202, thumbnail.size),
            )
            out.write(write(ifd1, ifd1Off, 0))
            out.write(le().putInt(72).array() + le().putInt(1).array())
            out.write(thumbnail)
        }
        return out.toByteArray()
    }

    fun cameraExif(orientation: Int, make: String = MAKE, thumbnail: ByteArray? = null): ByteArray = tiff(
        ifd0 = listOf(
            ascii(0x010F, make), ascii(0x0110, MODEL), short(0x0112, orientation), ascii(0x0131, "S926USQU6EZIF"),
            ascii(0x0132, "2026:11:13 16:19:12"), ascii(0x8298, "Copyright Jane Doe"), ascii(0x013B, "Jane Doe"),
        ),
        exif = listOf(
            ascii(0x9003, "2026:11:13 16:19:12"), rational(0x829A, 1, 120),
            undefined(0x927C, latin1("SAMSUNG\u0000MAKERNOTE-SECRET serial 1234567890")),
            ascii(0xA420, "UNIQUE-ID-0123456789"),
        ),
        gps = listOf(ascii(0x0001, "N"), ascii(0x0003, "W"), ascii(0x001D, "2026:11:13")),
        thumbnail = thumbnail,
    )

    // ---- ICC ----

    private fun be32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()
    private fun s15(v: Double) = be32(Math.round(v * 65536).toInt())

    private fun mluc(text: String): ByteArray {
        val u = text.toByteArray(Charsets.UTF_16BE)
        return latin1("mluc") + be32(0) + be32(1) + be32(12) + latin1("enUS") + be32(u.size) + be32(28) + u
    }

    private fun xyz(x: Double, y: Double, z: Double) = latin1("XYZ ") + be32(0) + s15(x) + s15(y) + s15(z)

    /** v4 display profile shaped like Samsung's, with vendor fields filled in. */
    fun vendorIcc(): ByteArray {
        val para = latin1("para") + be32(0) + byteArrayOf(0, 3, 0, 0) +
            s15(2.4) + s15(1 / 1.055) + s15(0.055 / 1.055) + s15(1 / 12.92) + s15(0.04045)
        val chad = latin1("sf32") + be32(0) + listOf(1.04781, 0.02289, -0.05013, 0.02954, 0.99048, -0.01704, -0.00923, 0.01505, 0.75214)
            .fold(ByteArray(0)) { a, v -> a + s15(v) }
        val tags = listOf(
            "desc" to mluc(ICC_DESCRIPTION),
            "cprt" to mluc(ICC_COPYRIGHT),
            "dmnd" to mluc("Samsung Electronics"),
            "dmdd" to mluc("Galaxy S24+ display"),
            "wtpt" to xyz(0.9642, 1.0, 0.82491),
            "chad" to chad,
            "rXYZ" to xyz(0.51508, 0.24117, -0.00105),
            "gXYZ" to xyz(0.29195, 0.69223, 0.04189),
            "bXYZ" to xyz(0.15718, 0.06659, 0.78455),
            "rTRC" to para,
            "gTRC" to para,
            "bTRC" to para,
        )
        val header = ByteArray(128)
        latin1("SEC ").copyInto(header, 4) // CMM
        byteArrayOf(4, 0x30, 0, 0).copyInto(header, 8)
        latin1("mntrRGB XYZ ").copyInto(header, 12)
        byteArrayOf(0x07, 0xE6.toByte(), 0, 7, 0, 1, 0, 0, 0, 0, 0, 0).copyInto(header, 24) // 2022-07-01
        latin1("acspSEC ").copyInto(header, 36)
        latin1("SEC S24P").copyInto(header, 48) // manufacturer, model
        (s15(0.9642) + s15(1.0) + s15(0.82491)).copyInto(header, 68)
        latin1("SEC ").copyInto(header, 80) // creator
        val table = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        val dataStart = 128 + 4 + 12 * tags.size
        var paraAt = -1
        for ((sig, d) in tags) {
            val off: Int
            if (d === para && paraAt >= 0) {
                off = paraAt
            } else {
                off = dataStart + data.size()
                data.write(d)
                while (data.size() % 4 != 0) data.write(0)
                if (d === para) paraAt = off
            }
            table.write(latin1(sig)); table.write(be32(off)); table.write(be32(d.size))
        }
        val body = be32(tags.size) + table.toByteArray() + data.toByteArray()
        be32(128 + body.size).copyInto(header, 0)
        return header + body
    }

    /** Splits a profile into [parts] APP2 ICC_PROFILE segments. */
    fun iccSegments(icc: ByteArray, parts: Int): List<ByteArray> {
        val size = (icc.size + parts - 1) / parts
        return (0 until parts).map { i ->
            Fixtures.segment(0xE2, JpegStripper.ICC_ID + byteArrayOf((i + 1).toByte(), parts.toByte()) +
                icc.copyOfRange(i * size, minOf(icc.size, (i + 1) * size)))
        }
    }

    // ---- JPEG ----

    private const val XMP = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.2\"><rdf:RDF " +
        "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" " +
        "xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" xmlns:tiff=\"http://ns.adobe.com/tiff/1.0/\" " +
        "hdrgm:Version=\"1.0\" tiff:Make=\"samsung\"><Container:Directory>GContainer</Container:Directory>" +
        "<dc:rights>Jane Doe</dc:rights></rdf:Description></rdf:RDF></x:xmpmeta>"

    fun cameraJpeg(base: ByteArray = Fixtures.jpeg()): ByteArray {
        val thumbnail = Fixtures.insertAfterSoi(
            JpegStripper.strip(Fixtures.jpeg()),
            Fixtures.segment(0xE1, JpegStripper.EXIF_ID + cameraExif(1, make = "ThumbCam")),
        )
        val exif = Fixtures.segment(0xE1, JpegStripper.EXIF_ID + cameraExif(6, thumbnail = thumbnail))
        val iccParts = iccSegments(vendorIcc(), 2)
        val app4 = Fixtures.segment(0xE4, latin1("vendor-app4 ") + ByteArray(64) { it.toByte() })
        val xmp = Fixtures.segment(0xE1, latin1("http://ns.adobe.com/xap/1.0/\u0000$XMP"))
        val extXmp = Fixtures.segment(0xE1, latin1("http://ns.adobe.com/xmp/extension/\u0000") + latin1("0123456789ABCDEF0123456789ABCDEF") +
            byteArrayOf(0, 0, 0, 40, 0, 0, 0, 0) + latin1("<rdf:li>Jane Doe extended</rdf:li>"))
        val urn = Fixtures.segment(0xE2, latin1("urn:iso:std:iso:ts:21496:-1\u0000"))
        val mpf = Fixtures.segment(0xE2, latin1("MPF\u0000") + cameraExif(1).copyOfRange(0, 40))
        val fpxr = Fixtures.segment(0xE2, latin1("FPXR\u0000\u0000\u0001 flashpix contents"))
        val app3 = Fixtures.segment(0xE3, latin1("META-app3 data"))
        val ducky = Fixtures.segment(0xEC, latin1("Ducky\u0000\u0001\u0000\u0004\u0000\u0000\u0000\u0050"))
        val iptc = Fixtures.segment(0xED, latin1("Photoshop 3.0\u00008BIM\u0004\u0004\u0000\u0000\u0000\u0000\u0000\u000c\u001c\u0002\u0050\u0000\u0008Jane Doe"))
        val app15 = Fixtures.segment(0xEF, latin1("app15 vendor"))
        val com = Fixtures.segment(0xFE, latin1("camera comment"))

        val gainMap = Fixtures.insertAfterSoi(
            JpegStripper.strip(Fixtures.jpeg()),
            Fixtures.segment(0xE1, latin1("http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>hdrgm GainMapCam</x:xmpmeta>")),
            Fixtures.segment(0xE1, JpegStripper.EXIF_ID + cameraExif(1, make = "GainMapCam")),
        )
        val samsungTrailer = latin1("\u0000\u0000\u000a\u0001\u000eImage_UTC_Data1763068752445\u0000\u0000\u0001\u000eMCC_Data310SEFH") +
            byteArrayOf(0x6B, 0, 0, 0) + latin1("SEFT")

        // Keep the base JPEG's own JFIF APP0 too (it follows SOI in the encoder output).
        return Fixtures.insertAfterSoi(
            base, exif, iccParts[0], iccParts[1], app4, xmp, extXmp, urn, mpf, fpxr, app3, ducky, iptc, app15, com,
        ) + gainMap + samsungTrailer
    }

    /** What Exif Wipe 0.1.0-test1 produced from a Samsung photo: orientation EXIF plus the vendor ICC. */
    fun test1Output(base: ByteArray = Fixtures.jpeg()): ByteArray {
        val stripped = JpegStripper.strip(base)
        return stripped.copyOfRange(0, 2) + JpegStripper.orientationSegment(6) + iccSegments(vendorIcc(), 1)[0] +
            stripped.copyOfRange(2, stripped.size)
    }
}
