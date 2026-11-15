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

import com.oiuy.exifwipe.strip.IccProfiles
import com.oiuy.exifwipe.strip.IccSanitizer
import com.oiuy.exifwipe.strip.Ifd
import com.oiuy.exifwipe.strip.ImageFormat
import com.oiuy.exifwipe.strip.Iptc
import com.oiuy.exifwipe.strip.JpegStripper
import com.oiuy.exifwipe.strip.Orientation
import com.oiuy.exifwipe.strip.OutputMeta
import com.oiuy.exifwipe.strip.Planner
import com.oiuy.exifwipe.strip.PngStripper
import com.oiuy.exifwipe.strip.SourceMeta
import com.oiuy.exifwipe.strip.Tiff
import com.oiuy.exifwipe.strip.TiffEntry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatter
import java.util.Random
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Everything the planner chose at random, for tests and the result message. */
data class Choices(
    val body: Body?,
    val lens: Lens?,
    val compat: Compat?,
    val exposure: Exposure?,
    val moment: Moment?,
    val place: Place?,
    val position: Pair<Double, Double>?,
    val altitude: Double?,
)

/**
 * Decides the metadata of the output file from the original's metadata and the user's
 * [WipeOptions]. EXIF is always built from scratch: groups set to KEEP copy only their own tags,
 * REPLACE groups get generated values, WIPE groups get nothing. Maker notes, unknown tags and
 * vendor blocks are never copied.
 *
 * Generated values are chosen together so they agree with each other: the lens fits the body's
 * mount and existed at the photo's date, focal length and aperture lie within the lens's range,
 * the 35 mm equivalent uses the body's crop factor, exposure time, aperture and ISO give a normal
 * exposure for a random scene brightness, the date lies in the body's production era, and the
 * time zone offset, GPS time and file name follow from the date and the chosen place.
 */
class MetadataPlanner(
    private val options: WipeOptions,
    private val random: Random,
    private val now: Instant = Instant.now(),
    private val ds: Dataset = Dataset.get(),
    /** Makes a new EXIF thumbnail (JPEG bytes) from the image; null if not available. */
    private val thumbnailMaker: (() -> ByteArray?)? = null,
) : Planner {
    var choices: Choices? = null
        private set

    private val today: LocalDate = now.atZone(UTC).toLocalDate()

    private fun mode(g: Group) = options.mode(g)

    override fun plan(src: SourceMeta): OutputMeta {
        val notes = ArrayList<String>()
        val t = src.tiff
        val srcMake = t?.get(Ifd.IFD0, Tags.MAKE)?.ascii()?.trim()?.takeIf { it.isNotEmpty() }
        val srcModel = t?.get(Ifd.IFD0, Tags.MODEL)?.ascii()?.trim()?.takeIf { it.isNotEmpty() }
        val srcLocal = (t?.get(Ifd.EXIF, Tags.DATE_TIME_ORIGINAL) ?: t?.get(Ifd.IFD0, Tags.DATE_TIME))?.ascii()?.let(::parseExifDate)
        val srcOffset = (t?.get(Ifd.EXIF, Tags.OFFSET_TIME_ORIGINAL) ?: t?.get(Ifd.EXIF, Tags.OFFSET_TIME))?.ascii()?.let(::parseOffset)
        val srcSubSec = t?.get(Ifd.EXIF, Tags.SUBSEC_TIME_ORIGINAL)?.ascii()?.trim()?.toIntOrNull()
        val srcPosition = t?.let(::gpsPosition)
        val srcLensModel = t?.get(Ifd.EXIF, Tags.LENS_MODEL)?.ascii()?.trim()

        // 1. The device all generated values describe.
        val body: Body? = when {
            mode(Group.CAMERA) == Mode.KEEP -> ds.findBody(srcMake, srcModel)
            options.preset.bodyId != null -> ds.body(options.preset.bodyId)
            else -> randomBody(srcMake, srcLensModel, if (mode(Group.DATE) == Mode.KEEP) srcLocal?.toLocalDate() else null)
        }
        if (body != null && mode(Group.DATE) == Mode.KEEP && srcLocal != null && srcLocal.year < body.yearFrom &&
            mode(Group.CAMERA) == Mode.REPLACE
        ) notes += "The kept date is older than the ${body.model}."

        // 2. Lens, chosen before the date so the date can follow the lens's release year.
        var lens: Lens? = null
        var compat: Compat? = null
        if (mode(Group.LENS) == Mode.REPLACE && body != null) {
            val maxYear = if (mode(Group.DATE) == Mode.KEEP && srcLocal != null) srcLocal.year else rangeFor(body).second.year
            val picked = if (options.checksCombinations) pickLens(body, maxYear) else anyLens()
            picked?.let { lens = it.first; compat = it.second }
        }

        // 3. Place and time.
        var place: Place? = null
        var position: Pair<Double, Double>? = null
        if (mode(Group.LOCATION) == Mode.REPLACE) {
            place = if (mode(Group.DATE) == Mode.KEEP && srcLocal != null && srcOffset != null) {
                PlaceRandomizer.place(random, ds) { p -> Zones.find(p.zone)?.rules?.getOffset(srcLocal) == srcOffset }
            } else {
                PlaceRandomizer.place(random, ds)
            }
            position = PlaceRandomizer.point(random, place)
        }
        val zone: ZoneId = Zones.of(
            when {
                place != null -> place.zone
                mode(Group.LOCATION) == Mode.KEEP && srcPosition != null -> PlaceRandomizer.nearest(ds, srcPosition.first, srcPosition.second).zone
                else -> PlaceRandomizer.place(random, ds).zone
            },
        )
        val (from, to) = if (options.checksCombinations) rangeFor(body, lens) else rangeFor(null)
        val moment: Moment? = when (mode(Group.DATE)) {
            Mode.REPLACE -> DateRandomizer.moment(random, from, to, zone, now)
            Mode.KEEP -> srcLocal?.let { Moment(it, srcOffset ?: zone.rules.getOffset(it), (srcSubSec ?: 0).coerceIn(0, 999)) }
            Mode.WIPE -> null
        }
        val altitude = place?.let { it.elevation + random.nextInt(30) - 5 + random.nextDouble() }

        // 4. Exposure.
        val exposure: Exposure? = if (mode(Group.LENS) != Mode.REPLACE) null else when {
            body != null && body.isPhone && lens != null && lens!!.mounts.any { it.startsWith("fixed:") } ->
                ExposureRandomizer.phone(random, body, lens!!)
            body != null && lens != null -> ExposureRandomizer.camera(random, body, lens, compat)
            else -> ExposureRandomizer.camera(random, body ?: GENERIC, null, null) // exposure only
        }
        choices = Choices(body, lens, compat, exposure, moment, place, position, altitude)

        // 5. EXIF entries per group.
        val e = mapOf(Ifd.IFD0 to ArrayList<TiffEntry>(), Ifd.EXIF to ArrayList(), Ifd.GPS to ArrayList())
        fun add(ifd: Ifd, entry: TiffEntry) { e.getValue(ifd).removeAll { it.tag == entry.tag }; e.getValue(ifd) += entry }
        fun copy(g: Group, filter: (Ifd, TiffEntry) -> Boolean = { _, _ -> true }) {
            if (t == null) return
            for (ifd in listOf(Ifd.IFD0, Ifd.EXIF, Ifd.GPS)) for (entry in t.entries[ifd].orEmpty()) {
                if (Tags.group(ifd, entry.tag) == g && filter(ifd, entry)) add(ifd, entry)
            }
        }
        val gpsStamp = { _: Ifd, x: TiffEntry -> x.tag == Tags.GPS_TIME_STAMP || x.tag == Tags.GPS_DATE_STAMP }
        val writeOffsets = body?.writesOffsetTime ?: true
        val writeSubSec = body == null || body.isPhone || body.exifAtLeast230

        when (mode(Group.LOCATION)) {
            Mode.KEEP -> copy(Group.LOCATION)
            Mode.REPLACE -> {
                val (lat, lon) = position!!
                add(Ifd.GPS, TiffEntry.bytes(Tags.GPS_VERSION, 2, 2, 0, 0))
                add(Ifd.GPS, TiffEntry.ascii(Tags.GPS_LATITUDE_REF, if (lat >= 0) "N" else "S"))
                add(Ifd.GPS, dms(Tags.GPS_LATITUDE, abs(lat)))
                add(Ifd.GPS, TiffEntry.ascii(Tags.GPS_LONGITUDE_REF, if (lon >= 0) "E" else "W"))
                add(Ifd.GPS, dms(Tags.GPS_LONGITUDE, abs(lon)))
                add(Ifd.GPS, TiffEntry.bytes(Tags.GPS_ALTITUDE_REF, if (altitude!! < 0) 1 else 0))
                add(Ifd.GPS, TiffEntry.rational(Tags.GPS_ALTITUDE, (abs(altitude) * 100).roundToLong() to 100L))
            }
            Mode.WIPE -> Unit
        }
        val hasPosition = e.getValue(Ifd.GPS).any { it.tag == Tags.GPS_LATITUDE }

        when (mode(Group.DATE)) {
            Mode.KEEP -> {
                copy(Group.DATE) { ifd, x -> ifd != Ifd.GPS || (hasPosition && !gpsStamp(ifd, x)) }
                if (hasPosition) {
                    if (mode(Group.LOCATION) == Mode.KEEP) copy(Group.DATE) { ifd, x -> ifd == Ifd.GPS && gpsStamp(ifd, x) }
                    else moment?.let { gpsTime(it).forEach { x -> add(Ifd.GPS, x) } }
                }
            }
            Mode.REPLACE -> {
                val m = moment!!
                add(Ifd.IFD0, TiffEntry.ascii(Tags.DATE_TIME, m.exif))
                add(Ifd.EXIF, TiffEntry.ascii(Tags.DATE_TIME_ORIGINAL, m.exif))
                add(Ifd.EXIF, TiffEntry.ascii(Tags.DATE_TIME_DIGITIZED, m.exif))
                if (writeOffsets) for (tag in listOf(Tags.OFFSET_TIME, Tags.OFFSET_TIME_ORIGINAL, Tags.OFFSET_TIME_DIGITIZED)) {
                    add(Ifd.EXIF, TiffEntry.ascii(tag, m.offsetText))
                }
                if (writeSubSec) for (tag in listOf(Tags.SUBSEC_TIME, Tags.SUBSEC_TIME_ORIGINAL, Tags.SUBSEC_TIME_DIGITIZED)) {
                    add(Ifd.EXIF, TiffEntry.ascii(tag, m.subSec))
                }
                if (hasPosition) gpsTime(m).forEach { add(Ifd.GPS, it) }
            }
            Mode.WIPE -> Unit
        }

        when (mode(Group.CAMERA)) {
            Mode.KEEP -> copy(Group.CAMERA)
            Mode.REPLACE -> if (body != null) {
                add(Ifd.IFD0, TiffEntry.ascii(Tags.MAKE, body.make))
                add(Ifd.IFD0, TiffEntry.ascii(Tags.MODEL, body.model))
                Patterns.software(random, body, (moment?.local?.toLocalDate() ?: today))?.let { add(Ifd.IFD0, TiffEntry.ascii(Tags.SOFTWARE, it)) }
                if (body.make == "Apple") add(Ifd.IFD0, TiffEntry.ascii(Tags.HOST_COMPUTER, body.model))
            }
            Mode.WIPE -> Unit
        }

        when (mode(Group.LENS)) {
            Mode.KEEP -> copy(Group.LENS)
            Mode.REPLACE -> exposure?.let { x -> lensEntries(body, lens, compat, x).forEach { add(Ifd.EXIF, it) } }
            Mode.WIPE -> Unit
        }

        when (mode(Group.AUTHOR)) {
            Mode.KEEP -> copy(Group.AUTHOR)
            Mode.REPLACE -> body?.let { b ->
                if (b.make.startsWith("OLYMPUS")) add(Ifd.IFD0, TiffEntry.ascii(Tags.IMAGE_DESCRIPTION, "OLYMPUS DIGITAL CAMERA".padEnd(31)))
                if (b.make == "Canon" && b.kind != "phone") add(Ifd.EXIF, TiffEntry.undefined(Tags.USER_COMMENT, ByteArray(264)))
            }
            Mode.WIPE -> Unit
        }

        // 6. Colour profile.
        val icc: ByteArray? = when (options.icc) {
            IccMode.KEEP -> src.icc?.let { IccSanitizer.sanitize(it) }
            IccMode.WIPE -> null
            IccMode.REPLACE -> IccProfiles.profile(iccSpace(body))
        }

        // 7. Thumbnail (JPEG only).
        val thumbnail: ByteArray? = if (src.format != ImageFormat.JPEG) null else when (mode(Group.EXTRAS)) {
            Mode.KEEP -> t?.thumbnail?.let { runCatching { JpegStripper.strip(it) }.getOrNull() }
            Mode.REPLACE -> runCatching { thumbnailMaker?.invoke() }.getOrNull()
            Mode.WIPE -> null
        }?.takeIf { it.size < 48_000 }

        // 8. Assemble the EXIF block.
        val anyContent = e.values.any { it.isNotEmpty() } || thumbnail != null
        val exif: ByteArray? = if (!anyContent) {
            if (Orientation.needsTag(src.orientation)) Orientation.minimalTiff(src.orientation!!) else null
        } else {
            val describesDevice = body != null && (mode(Group.CAMERA) == Mode.REPLACE || mode(Group.LENS) == Mode.REPLACE)
            val version = if (describesDevice) body!!.exifVersion
            else t?.get(Ifd.EXIF, Tags.EXIF_VERSION)?.value?.let { String(it, Charsets.ISO_8859_1) }?.takeIf { it.matches(Regex("0[0-9]{3}")) } ?: "0232"
            val bigEndian = if (body != null && mode(Group.CAMERA) == Mode.REPLACE) body.bigEndian else t?.bigEndian ?: true
            if (e.getValue(Ifd.GPS).isNotEmpty()) add(Ifd.GPS, TiffEntry.bytes(Tags.GPS_VERSION, 2, 2, 0, 0))
            add(Ifd.IFD0, TiffEntry.short(Tags.ORIENTATION, src.orientation ?: 1))
            add(Ifd.IFD0, TiffEntry.rational(Tags.X_RESOLUTION, 72L to 1L))
            add(Ifd.IFD0, TiffEntry.rational(Tags.Y_RESOLUTION, 72L to 1L))
            add(Ifd.IFD0, TiffEntry.short(Tags.RESOLUTION_UNIT, 2))
            if (src.format == ImageFormat.JPEG) add(Ifd.IFD0, TiffEntry.short(Tags.YCBCR_POSITIONING, 1))
            add(Ifd.EXIF, TiffEntry.undefined(Tags.EXIF_VERSION, latin(version)))
            if (src.format == ImageFormat.JPEG) add(Ifd.EXIF, TiffEntry.undefined(Tags.COMPONENTS_CONFIGURATION, byteArrayOf(1, 2, 3, 0)))
            add(Ifd.EXIF, TiffEntry.undefined(Tags.FLASHPIX_VERSION, latin("0100")))
            add(Ifd.EXIF, TiffEntry.short(Tags.COLOR_SPACE, if (icc == null || isSrgb(icc)) 1 else 0xFFFF))
            if (src.width > 0 && src.height > 0) {
                add(Ifd.EXIF, TiffEntry.long(Tags.PIXEL_X, src.width.toLong()))
                add(Ifd.EXIF, TiffEntry.long(Tags.PIXEL_Y, src.height.toLong()))
            }
            val ifd1 = if (thumbnail == null) emptyList() else listOf(
                TiffEntry.short(Tags.COMPRESSION, 6),
                TiffEntry.rational(Tags.X_RESOLUTION, 72L to 1L),
                TiffEntry.rational(Tags.Y_RESOLUTION, 72L to 1L),
                TiffEntry.short(Tags.RESOLUTION_UNIT, 2),
            )
            Tiff.write(e + (Ifd.IFD1 to ifd1), thumbnail, bigEndian)
        }

        // 9. XMP, IPTC and PNG text: only kept groups.
        val keep = Group.entries.filter { mode(it) == Mode.KEEP }.toSet()
        val keepExtras = mode(Group.EXTRAS) == Mode.KEEP && src.format == ImageFormat.JPEG && src.hasExtras
        val xmpKeep = if (keepExtras) keep else keep - Group.EXTRAS
        val xmp = src.xmp?.let { XmpFilter.filter(it, xmpKeep) }
        val iptc = src.iptc?.let { filterIptc(it, keep) }
        val pngText = src.pngText.filter { (_, d) -> pngTextGroup(PngStripper.keyword(d)) in keep }
        val pngTime = if (mode(Group.DATE) == Mode.KEEP) src.pngTime else null

        // 10. File name.
        val fileName = fileName(src.format, body, moment, zone, from, to)

        return OutputMeta(
            exif = exif, xmp = xmp, iptc = iptc, icc = icc, dropPngSrgb = options.icc == IccMode.REPLACE,
            pngText = pngText, pngTime = pngTime, keepExtras = keepExtras, fileName = fileName, notes = notes,
        )
    }

    // ---- device and lens -------------------------------------------------------------------

    private fun randomBody(srcMake: String?, srcLensModel: String?, keptDate: LocalDate?): Body {
        val brand = options.effectiveBrand
        // With a brand chosen, bodies whose model string or naming was not confirmed from a real
        // file are allowed if the brand has no confirmed ones (some legacy brands).
        var candidates = if (brand == null) ds.bodies.filter { !it.unverified }
        else ds.bodies.filter { it.brand == brand }.let { all -> all.filter { !it.unverified }.ifEmpty { all } }
        if (keptDate != null) {
            candidates = candidates.filter { keptDate.year >= it.yearFrom && (it.yearTo == 0 || keptDate.year <= it.yearTo + 10) }
                .ifEmpty { candidates }
        }
        if (mode(Group.LENS) == Mode.KEEP) {
            val keptLens = srcLensModel?.let { m -> ds.lenses.firstOrNull { it.model.equals(m, ignoreCase = true) } }
            val byLens = keptLens?.let { l -> candidates.filter { Compat.of(it, l) == Compat.NATIVE } }.orEmpty()
            val byMake = srcMake?.let { mk -> candidates.filter { it.make.equals(mk, ignoreCase = true) } }.orEmpty()
            candidates = byLens.ifEmpty { byMake }.ifEmpty { candidates }
        }
        // Keep bodies that can be given a lens.
        candidates = if (brand == null) candidates.filter { b -> ds.lenses.any { Compat.of(b, it) == Compat.NATIVE } }.ifEmpty { candidates }
        else candidates.filter { b -> ds.lenses.any { l -> Compat.isOwnMount(b, l) } }.ifEmpty { candidates }
        val phones = candidates.filter { it.isPhone }
        val cameras = candidates.filter { !it.isPhone }
        val pool = when {
            phones.isEmpty() -> cameras
            cameras.isEmpty() -> phones
            random.nextDouble() < PHONE_SHARE -> phones
            else -> cameras
        }
        return pool[random.nextInt(pool.size)]
    }

    /**
     * With a brand chosen, only lenses made for the body's own mount (or for the maker's own
     * electronic adapter, such as EF lenses on RF bodies or Minolta/Sony A lenses on E bodies)
     * are used: no legacy lenses on mechanical adapters. Third-party lenses for that mount count.
     * Manual lenses made for the mount itself (for example Leica M lenses on a Leica M) are allowed.
     */
    private fun lensAllowed(body: Body, lens: Lens, compat: Compat?): Boolean {
        if (compat == null) return false
        if (options.effectiveBrand == null) return true
        return Compat.isOwnMount(body, lens)
    }

    private fun pickLens(body: Body, maxYear: Int): Pair<Lens, Compat>? {
        // Prefer lenses released before the last year the body is in use, so the date can be
        // after both the body's and the lens's first full year.
        val fitting = ds.lensesFor(body).filter { it.first.year <= maxYear && lensAllowed(body, it.first, it.second) }
        val all = fitting.filter { it.first.year < maxYear }.ifEmpty { fitting }
        if (all.isEmpty()) return null
        if (body.mount.startsWith("fixed:")) {
            // Phones: mostly the main camera (the module closest to a 26 mm equivalent).
            val main = all.minBy { abs((it.first.focal35 ?: 26) - 26) }
            return if (all.size == 1 || random.nextDouble() < 0.8) main else (all - main)[random.nextInt(all.size - 1)]
        }
        val native = all.filter { it.second == Compat.NATIVE }
        val adapted = all.filter { it.second == Compat.ADAPTED_MANUAL }
        val pool = if (adapted.isNotEmpty() && (native.isEmpty() || random.nextDouble() < 0.12)) adapted else native
        val ownBrand = pool.filter { sameBrand(body.brand, it.first.make) }
        val list = if (ownBrand.isNotEmpty() && random.nextDouble() < 0.6) ownBrand else pool
        return list[random.nextInt(list.size)]
    }

    /** Any interchangeable lens, ignoring mount and year ("Only valid combinations" off). */
    private fun anyLens(): Pair<Lens, Compat>? {
        val all = ds.lenses.filter { l -> l.mounts.none { it.startsWith("fixed:") } }
        return if (all.isEmpty()) null else all[random.nextInt(all.size)] to Compat.NATIVE
    }

    private fun sameBrand(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().substringBefore(' ').let { ALIASES[it] ?: it }
        return norm(a) == norm(b)
    }

    /** Dates a photo from [body] (with [lens]) can have: from its first full year until 10 years after it was discontinued. */
    private fun rangeFor(body: Body?, lens: Lens? = null): Pair<LocalDate, LocalDate> {
        var from = DateRandomizer.START
        var to = today
        if (body != null) {
            from = maxOf(from, firstFullYear(body.yearFrom))
            if (body.yearTo != 0) to = minOf(to, LocalDate.of(body.yearTo + 10, 12, 31))
        }
        if (lens != null) from = maxOf(from, firstFullYear(lens.year))
        if (from.isAfter(to)) from = to
        return from to to
    }

    /** January of the year after release (release months are not in the data), or mid-year if that is in the future. */
    private fun firstFullYear(year: Int): LocalDate {
        val next = LocalDate.of(year + 1, 1, 1)
        return if (next.isAfter(today)) LocalDate.of(year, 7, 1).coerceAtMost(today) else next
    }

    private fun LocalDate.coerceAtMost(o: LocalDate) = if (isAfter(o)) o else this

    private fun lensEntries(body: Body?, lens: Lens?, compat: Compat?, x: Exposure): List<TiffEntry> {
        val out = ArrayList<TiffEntry>()
        out += TiffEntry.rational(Tags.EXPOSURE_TIME, x.timeNum.toLong() to x.timeDen.toLong())
        x.fNumber?.let { out += TiffEntry.rational(Tags.F_NUMBER, (it * 100).roundToLong() to 100L) }
        out += TiffEntry.short(Tags.EXPOSURE_PROGRAM, x.program)
        out += TiffEntry.short(Tags.ISO, x.iso)
        val tv = -kotlin.math.log2(x.seconds)
        out += TiffEntry.srational(Tags.SHUTTER_SPEED_VALUE, (tv * 1000).roundToInt(), 1000)
        x.fNumber?.let { out += TiffEntry.rational(Tags.APERTURE_VALUE, (ExposureRandomizer.apex(it) * 1000).roundToLong() to 1000L) }
        out += TiffEntry.srational(Tags.EXPOSURE_BIAS, x.biasThirds, if (x.biasThirds == 0) 1 else 3)
        x.maxAperture?.let { out += TiffEntry.rational(Tags.MAX_APERTURE_VALUE, (ExposureRandomizer.apex(it) * 1000).roundToLong() to 1000L) }
        out += TiffEntry.short(Tags.METERING_MODE, x.metering)
        out += TiffEntry.short(Tags.FLASH, x.flash)
        x.focal?.let { out += TiffEntry.rational(Tags.FOCAL_LENGTH, (it * 100).roundToLong() to 100L) }
        out += TiffEntry.short(Tags.SENSING_METHOD, 2)
        out += TiffEntry.short(Tags.EXPOSURE_MODE, if (x.program == 1) 1 else 0)
        out += TiffEntry.short(Tags.WHITE_BALANCE, 0)
        x.focal35?.let { out += TiffEntry.short(Tags.FOCAL_LENGTH_35, it) }
        out += TiffEntry.short(Tags.SCENE_CAPTURE_TYPE, 0)
        if (body != null && lens != null && compat == Compat.NATIVE && body.exifAtLeast230) {
            out += TiffEntry.rational(
                Tags.LENS_SPECIFICATION,
                r(lens.focalMin), r(lens.focalMax), r(lens.apertureWide), r(lens.apertureTele),
            )
            if (body.isPhone && lens.mounts.any { it.startsWith("fixed:") }) out += TiffEntry.ascii(Tags.LENS_MAKE, body.make)
            out += TiffEntry.ascii(Tags.LENS_MODEL, lens.model)
        }
        return out
    }

    private fun r(v: Double): Pair<Long, Long> = if (v == v.roundToLong().toDouble()) v.roundToLong() to 1L else (v * 100).roundToLong() to 100L

    // ---- dates, places, GPS ----------------------------------------------------------------

    private fun gpsTime(m: Moment): List<TiffEntry> {
        val u = m.utc
        return listOf(
            TiffEntry.rational(Tags.GPS_TIME_STAMP, u.hour.toLong() to 1L, u.minute.toLong() to 1L, u.second.toLong() to 1L),
            TiffEntry.ascii(Tags.GPS_DATE_STAMP, u.format(DateTimeFormatter.ofPattern("yyyy:MM:dd", java.util.Locale.ROOT))),
        )
    }

    private fun dms(tag: Int, v: Double): TiffEntry {
        val deg = v.toLong()
        val minutes = (v - deg) * 60
        val min = minutes.toLong()
        val sec = ((minutes - min) * 60 * 10000).roundToLong()
        return TiffEntry.rational(tag, deg to 1L, min to 1L, sec to 10000L)
    }

    // ---- colour ------------------------------------------------------------------------------

    private fun iccSpace(body: Body?): IccProfiles.Space = when (options.iccTarget) {
        IccTarget.SRGB -> IccProfiles.Space.SRGB
        IccTarget.DISPLAY_P3 -> IccProfiles.Space.DISPLAY_P3
        IccTarget.MATCH_DEVICE -> if (body?.icc == "p3") IccProfiles.Space.DISPLAY_P3 else IccProfiles.Space.SRGB
    }

    // ---- file name ---------------------------------------------------------------------------

    private fun fileName(format: ImageFormat, body: Body?, moment: Moment?, zone: ZoneId, from: LocalDate, to: LocalDate): String? {
        if (options.fileName == FileNameMode.KEEP) return null
        val generic = "IMG_{n4}.JPG"
        // A chosen brand uses its reported naming even where it was not confirmed from a real file.
        // Without valid combinations the naming comes from another random device.
        val namer = if (options.checksCombinations || options.fileName != FileNameMode.DEVICE) body
        else ds.bodies.filter { !it.unverified }.let { it[random.nextInt(it.size)] }
        val pattern = if (options.fileName == FileNameMode.DEVICE && namer != null && (!namer.unverified || options.effectiveBrand != null)) namer.fileName else generic
        val m = moment ?: DateRandomizer.moment(random, from, to, zone, now)
        val name = Patterns.fileName(random, pattern, m)
        return FileNames.withExtension(name, format)
    }

    // ---- IPTC and PNG text -------------------------------------------------------------------

    private fun filterIptc(iim: ByteArray, keep: Set<Group>): ByteArray? {
        val sets = Iptc.parse(iim)
        val kept = sets.filter { s ->
            when {
                s.record == 1 && s.number == 90 -> true // coded character set
                s.record == 2 && s.number == 0 -> true // record version
                s.record == 2 -> iptcGroup(s.number) in keep
                else -> false
            }
        }
        if (kept.none { it.record == 2 && it.number != 0 }) return null
        return Iptc.write(kept)
    }

    companion object {
        /** Share of random devices that are phones (most photos today are taken with phones). */
        const val PHONE_SHARE = 0.4

        private val ALIASES = mapOf("konica" to "minolta", "asahi" to "pentax", "om" to "olympus", "carl" to "zeiss")

        /** Body used for exposure values when the kept camera is not in the dataset. */
        internal val GENERIC = Body(
            "generic", "", "", "", "dslr", "", 1.5, 2002, 0, "", "IMG_{n4}.JPG", 0, 0, 100, 6400, true, "none", "0230", false,
        )

        fun iptcGroup(dataset: Int): Group? = when (dataset) {
            55, 60, 62, 63, 30, 35 -> Group.DATE
            90, 92, 95, 100, 101, 26, 27 -> Group.LOCATION
            65, 70 -> Group.CAMERA // originating program, program version
            in 1..254 -> Group.AUTHOR
            else -> null
        }

        fun pngTextGroup(keyword: String): Group? = when (keyword) {
            "Creation Time" -> Group.DATE
            "Software", "Source" -> Group.CAMERA
            "Title", "Author", "Description", "Copyright", "Disclaimer", "Warning", "Comment" -> Group.AUTHOR
            else -> null
        }

        internal fun parseExifDate(s: String): LocalDateTime? = runCatching {
            LocalDateTime.parse(s.trim().take(19), Moment.EXIF_FORMAT)
        }.getOrNull()

        internal fun parseOffset(s: String): ZoneOffset? = runCatching { ZoneOffset.of(s.trim()) }.getOrNull()

        /** (latitude, longitude) from GPS tags, or null. */
        internal fun gpsPosition(t: com.oiuy.exifwipe.strip.TiffData): Pair<Double, Double>? {
            fun coord(tag: Int, refTag: Int, neg: String): Double? {
                val e = t.get(Ifd.GPS, tag) ?: return null
                val d = e.rational(0) ?: return null
                val m = e.rational(1) ?: 0.0
                val s = e.rational(2) ?: 0.0
                val v = d + m / 60 + s / 3600
                return if (t.get(Ifd.GPS, refTag)?.ascii()?.trim() == neg) -v else v
            }
            val lat = coord(Tags.GPS_LATITUDE, Tags.GPS_LATITUDE_REF, "S") ?: return null
            val lon = coord(Tags.GPS_LONGITUDE, Tags.GPS_LONGITUDE_REF, "W") ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return lat to lon
        }

        internal fun isSrgb(profile: ByteArray): Boolean {
            val count = ((profile[128].toInt() and 0xFF) shl 24) or ((profile[129].toInt() and 0xFF) shl 16) or
                ((profile[130].toInt() and 0xFF) shl 8) or (profile[131].toInt() and 0xFF)
            for (i in 0 until count) {
                val e = 132 + 12 * i
                if (e + 12 > profile.size) break
                if (String(profile, e, 4, Charsets.ISO_8859_1) == "desc") {
                    fun u32(p: Int) = ((profile[p].toInt() and 0xFF) shl 24) or ((profile[p + 1].toInt() and 0xFF) shl 16) or
                        ((profile[p + 2].toInt() and 0xFF) shl 8) or (profile[p + 3].toInt() and 0xFF)
                    val d = IccSanitizer.description(profile, u32(e + 4), u32(e + 8)) ?: return false
                    return d.trim().lowercase().startsWith("srgb")
                }
            }
            return false
        }

        private fun latin(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    }
}

/** File-name helpers. */
object FileNames {
    /** [name] with the extension for [format], in the same letter case as the pattern's extension. */
    fun withExtension(name: String, format: ImageFormat): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val oldExt = if (dot > 0) name.substring(dot + 1) else "JPG"
        val ext = when (format) {
            ImageFormat.PNG -> "png"
            ImageFormat.WEBP -> "webp"
            else -> "jpg"
        }
        val upper = oldExt.any { it.isUpperCase() }
        return "$base." + if (upper) ext.uppercase() else ext
    }

    /**
     * Another name of the same style for when [name] is taken: the last run of digits is replaced
     * with a new random number of the same length (IMG_0042.JPG -> IMG_7311.JPG). Names without
     * digits get a " (n)" suffix.
     */
    fun alternative(name: String, random: Random, attempt: Int): String {
        val run = Regex("[0-9]+").findAll(name.substringBeforeLast('.')).lastOrNull()
        if (run == null || attempt > 8) return numbered(name, attempt)
        val len = run.value.length
        val max = Math.min(10.0.pow(len).toLong(), 1_000_000_000L)
        val n = (1 + (random.nextDouble() * (max - 1)).toLong()).toString().padStart(len, '0')
        return name.substring(0, run.range.first) + n + name.substring(run.range.last + 1)
    }

    /** "IMG_1234 (2).JPG" style name for the n-th collision. */
    fun numbered(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
    }
}
