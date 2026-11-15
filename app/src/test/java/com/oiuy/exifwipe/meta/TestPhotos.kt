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

import com.oiuy.exifwipe.strip.Fixtures
import com.oiuy.exifwipe.strip.Ifd
import com.oiuy.exifwipe.strip.Iptc
import com.oiuy.exifwipe.strip.JpegStripper
import com.oiuy.exifwipe.strip.Tiff
import com.oiuy.exifwipe.strip.TiffData
import com.oiuy.exifwipe.strip.TiffEntry
import java.io.File
import java.time.Instant

/** A JPEG with metadata in every group, built with the app's own TIFF writer. */
object TestPhotos {
    val NOW: Instant = Instant.parse("2026-11-14T12:00:00Z")

    const val MAKE = "Canon"
    const val MODEL = "Canon EOS 5D Mark II"
    const val LENS = "EF24-105mm f/4L IS USM"
    const val DATE = "2011:07:04 18:30:15"
    const val ARTIST = "Jane Doe"
    const val SERIAL = "0123456789"

    fun sourceTiff(thumbnail: ByteArray? = null): ByteArray = Tiff.write(
        mapOf(
            Ifd.IFD0 to listOf(
                TiffEntry.ascii(Tags.MAKE, MAKE), TiffEntry.ascii(Tags.MODEL, MODEL), TiffEntry.short(Tags.ORIENTATION, 6),
                TiffEntry.ascii(Tags.SOFTWARE, "Firmware 2.1.2"), TiffEntry.ascii(Tags.DATE_TIME, DATE),
                TiffEntry.ascii(Tags.ARTIST, ARTIST), TiffEntry.ascii(Tags.COPYRIGHT, "(c) $ARTIST"),
                TiffEntry.rational(Tags.X_RESOLUTION, 240L to 1L),
            ),
            Ifd.EXIF to listOf(
                TiffEntry.ascii(Tags.DATE_TIME_ORIGINAL, DATE), TiffEntry.ascii(Tags.DATE_TIME_DIGITIZED, DATE),
                TiffEntry.ascii(Tags.OFFSET_TIME_ORIGINAL, "-04:00"),
                TiffEntry.rational(Tags.EXPOSURE_TIME, 1L to 250L), TiffEntry.rational(Tags.F_NUMBER, 80L to 10L),
                TiffEntry.short(Tags.ISO, 200), TiffEntry.rational(Tags.FOCAL_LENGTH, 50L to 1L),
                TiffEntry.ascii(Tags.LENS_MODEL, LENS), TiffEntry.ascii(Tags.BODY_SERIAL, SERIAL),
                TiffEntry.undefined(Tags.MAKER_NOTE, "CANON-MAKERNOTE-SECRET".toByteArray()),
                TiffEntry.undefined(Tags.USER_COMMENT, ByteArray(8) + "holiday".toByteArray()),
                TiffEntry.short(0x9999, 7), // unknown vendor tag
            ),
            Ifd.GPS to listOf(
                TiffEntry.bytes(Tags.GPS_VERSION, 2, 3, 0, 0),
                TiffEntry.ascii(Tags.GPS_LATITUDE_REF, "N"),
                TiffEntry.rational(Tags.GPS_LATITUDE, 40L to 1L, 42L to 1L, 3000L to 100L),
                TiffEntry.ascii(Tags.GPS_LONGITUDE_REF, "W"),
                TiffEntry.rational(Tags.GPS_LONGITUDE, 74L to 1L, 0L to 1L, 2150L to 100L),
                TiffEntry.rational(Tags.GPS_TIME_STAMP, 22L to 1L, 30L to 1L, 15L to 1L),
                TiffEntry.ascii(Tags.GPS_DATE_STAMP, "2011:07:04"),
            ),
            Ifd.IFD1 to listOf(TiffEntry.short(Tags.COMPRESSION, 6)),
        ),
        thumbnail,
        bigEndian = false,
    )!!

    const val XMP = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.6\">" +
        "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
        "<rdf:Description rdf:about=\"\" xmlns:tiff=\"http://ns.adobe.com/tiff/1.0/\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:xmpMM=\"http://ns.adobe.com/xap/1.0/mm/\" " +
        "xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" xmlns:exif=\"http://ns.adobe.com/exif/1.0/\" " +
        "tiff:Make=\"Canon\" xmp:CreateDate=\"2011-07-04T18:30:15\" xmpMM:DocumentID=\"xmp.did:SECRET-ID\" GCamera:MotionPhoto=\"1\" " +
        "exif:GPSLatitude=\"40,42.5N\">" +
        "<dc:creator><rdf:Seq><rdf:li>Jane Doe</rdf:li></rdf:Seq></dc:creator>" +
        "<exif:GPSLongitude>74,0.358W</exif:GPSLongitude>" +
        "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>"

    val IPTC: ByteArray = Iptc.write(
        listOf(
            Iptc.DataSet(2, 0, byteArrayOf(0, 4)),
            Iptc.DataSet(2, 80, "Jane Doe".toByteArray()), // by-line
            Iptc.DataSet(2, 55, "20110704".toByteArray()), // date created
            Iptc.DataSet(2, 90, "New York".toByteArray()), // city
        ),
    )

    fun jpeg(base: ByteArray = Fixtures.jpeg(), trailer: Boolean = true): ByteArray {
        val thumb = JpegStripper.strip(Fixtures.jpeg())
        val out = Fixtures.insertAfterSoi(
            base,
            Fixtures.segment(0xE1, JpegStripper.EXIF_ID + sourceTiff(thumb)),
            Fixtures.segment(0xE1, JpegStripper.XMP_ID + XMP.toByteArray()),
            Fixtures.segment(0xED, JpegStripper.PHOTOSHOP_ID + Iptc.toPhotoshop(IPTC)),
            JpegStripper.iccSegments(com.oiuy.exifwipe.strip.CameraFixtures.vendorIcc()),
        )
        return if (trailer) out + "MOTION-PHOTO-VIDEO-DATA".toByteArray() else out
    }

    fun exifOf(jpeg: ByteArray): TiffData? {
        val i = indexOf(jpeg, JpegStripper.EXIF_ID)
        if (i < 0) return null
        val len = ((jpeg[i - 2].toInt() and 0xFF) shl 8) or (jpeg[i - 1].toInt() and 0xFF)
        return Tiff.read(jpeg, i + 6, i - 2 + len)
    }

    fun indexOf(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size - n.size) {
            for (k in n.indices) if (h[i + k] != n[k]) continue@outer
            return i
        }
        return -1
    }

    fun save(name: String, bytes: ByteArray) {
        val dir = File("build/test-output").apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
    }
}
