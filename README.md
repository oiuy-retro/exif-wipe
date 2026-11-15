# Exif Wipe

Exif Wipe is a small Android app that removes hidden metadata from photos stored
on your phone. You share one or more photos to it, and it changes the original
files in place. Unlike Scrambled Exif, it does not make a cleaned copy and does
not open the share menu again: the photo in your gallery is the one that gets
changed, and you can share it anywhere afterwards.

With the default settings everything is removed: EXIF (GPS location, camera
make and model, lens, serial numbers, date and time, thumbnails), XMP,
IPTC/Photoshop data, JPEG comments, PNG text and time chunks, and data stored
after the end of the image. The settings screen can instead keep some groups
of metadata, or replace them with realistic random values (see "Settings").

Always kept, so the photo looks the same:

- The compressed image data, copied byte for byte. Nothing is re-encoded.
- PNG sRGB/gAMA/cHRM, transparency, and the JPEG Adobe colour-transform marker.
- The colour data of the embedded colour profile, unless the colour profile
  setting removes or replaces it. See "Colour profiles" below.
- The orientation. Phones often store a portrait photo as landscape pixels plus
  an "Orientation" tag. If nothing else is written and that tag is anything
  other than "normal", the file gets a new EXIF block, built from scratch, that
  holds only this one tag (26 bytes). Photos with normal orientation and the
  default settings get no EXIF block at all.

Maker notes, unknown and vendor-specific tags, XMP edit history and document
IDs, and vendor data blocks are always removed, whatever the settings.

## Settings

Open Exif Wipe from the app drawer to change the settings. They apply to every
photo shared to the app afterwards.

### Metadata groups

All metadata is sorted into six groups. Each group has a three-way switch:

| Left: Keep | Middle: Wipe (default) | Right: Random |
|---|---|---|
| The original values of this group are copied to the new EXIF block (and kept in XMP/IPTC). | The group is removed. | The group is removed and realistic made-up values are written instead. |

| Group | Contains |
|---|---|
| Location | GPS position, altitude, direction, speed; XMP/IPTC city, state, country |
| Date & time | Date taken, digitized and modified, time zone offsets, sub-seconds, GPS time and date; XMP/IPTC dates; PNG tIME |
| Camera & device | Make, model, software, host computer, body serial number, owner, unique image ID; XMP creator tool |
| Lens & exposure | Lens make, model, specification and serial, focal length, 35 mm equivalent, aperture, shutter, ISO, flash, metering, white balance |
| Description & authorship | Artist, copyright, description, user comment, title, keywords, ratings; XMP dc/rights/IPTC Core; IPTC text; PNG text |
| Embedded extras | EXIF thumbnail, JPEG MPF index and data after the image (motion photo video, HDR gain map), motion photo/container/gain-map XMP |

EXIF is always built from scratch: a group set to Keep copies only its own tags,
and the app writes the tags that describe the file itself (orientation,
resolution, EXIF version, colour space and the real pixel size). Keep for
Embedded extras keeps the original thumbnail (cleaned of its own metadata) and,
for JPEG, the MPF index (with recomputed offsets) and the data after the image.
Random for Embedded extras writes a new thumbnail made from the photo.
Random for Description & authorship writes only what the chosen camera writes
by default (for example "OLYMPUS DIGITAL CAMERA" as description, or Canon's
empty user comment), so for most devices it is the same as Wipe.

### Random values

Random values are chosen together so they agree with each other:

- A device is picked first: the chosen preset, or a random camera or phone from
  the built-in list (about 40% phones). If Camera & device is set to Keep, the
  photo's own camera is looked up in the list.
- The lens fits the body's mount (directly, through the maker's own adapter, or
  as a manual lens on a mechanical adapter, in which case no lens data, aperture
  or focal length is recorded, as with a real adapter). APS-C lenses are only
  paired with crop bodies. The lens existed at the photo's date.
- Focal length and aperture lie within the lens's range (the widest aperture of
  a zoom changes with focal length). The 35 mm equivalent uses the body's crop
  factor; phone modules use their published equivalent.
- Exposure time, aperture and ISO give a normal exposure for a random scene
  brightness (daylight, indoors or low light), stay within the body's ISO range
  and keep the shutter speed hand-holdable where possible. Phones use a fixed
  aperture, odd shutter fractions and ISO steps of one, like real phone files.
- Dates are random days after 1 January 2002, inside the device's production
  years (from the year after its release until 10 years after it was
  discontinued, never in the future). Newer dates are more likely: the
  probability rises linearly, so the most recent day is 11 times as likely as
  the first possible one. The time of day is uniformly random. Date taken,
  digitized and modified are the same; time zone offsets, the GPS time stamp
  (UTC) and date-based file names follow from the same moment.
- Exif version, byte order, software string (for example an iOS version that
  existed on that date, or a firmware version), and whether lens tags and time
  zone offsets are written follow the device.

### Random locations

Random locations are always on land and near where people live:

1. A place is picked from a bundled list of 11,492 cities and towns with at
   least 50,000 inhabitants (GeoNames), with probability proportional to its
   population, so large cities come up far more often than small towns.
2. Around each place the app has a grid of 1 km cells covering a radius of 1.5
   to 5 km (larger for larger cities). Only cells that are completely on land
   (outside oceans and large lakes, according to Natural Earth 1:10m data) are
   used. A random cell is chosen and then a random point inside it, so the
   point is never exactly the city centre and never in the water.
3. The altitude is the place's elevation plus a few metres. The time zone
   offset is the place's real offset (including daylight saving time) on the
   random date, and the GPS time is the same moment in UTC.

If Location is Random and Date & time is Keep, only places whose time zone had
the photo's original offset on that date are used. If Location is Keep and
Date & time is Random, the time zone of the nearest listed place is used.

### Device preset

"Device for random values" chooses which device the random values describe:

| Preset | Why this model |
|---|---|
| Random | A random camera or phone from the built-in list for each photo. |
| Google Pixel 7 | The most used non-Pro Pixel: as common as the Pixel 8 Pro in US government web analytics (October 2026) and among the most owned Pixels in 2026 owner surveys. |
| Apple iPhone 13 | The most used iPhone model (about 11% of iPhones in TelemetryDeck's October 2026 data). |
| Samsung Galaxy A12 | The most used Android phone model in AppBrain's November 2026 device statistics, and one of the best-selling phones of 2021. |

Values follow real files from these phones: make and model strings, software
versions, lens names, focal lengths, apertures, colour profile, byte order and
file names (iPhone `IMG_1234.JPG`, Pixel `PXL_20240501_103015123.jpg` in UTC,
Samsung `20240501_103015.jpg` in local time).

### Colour profile

- Keep (default): the profile is rebuilt with only its colour data (see below).
- Wipe: the embedded profile (JPEG ICC_PROFILE, PNG iCCP, WebP ICCP) is
  removed. Viewers then assume sRGB, so wide-colour photos can look duller.
- Replace: a standard profile generated by the app is written: sRGB, Display P3,
  or "Match device" (Display P3 for iPhones, sRGB for everything else). The
  profiles are computed from the published colour space definitions (primaries,
  D65 white point adapted to D50 with the Bradford method, sRGB tone curve), not
  copied from any device. The pixel values are unchanged, but if the new profile
  differs from the original one, colours are displayed differently (for example
  a Display P3 profile on an sRGB photo makes colours more saturated). For PNG,
  the sRGB chunk is removed because it must not appear together with a profile.
  The EXIF colour space tag is set to match (sRGB or "uncalibrated" for P3).

### File name

- Keep (default): the name is not changed.
- Generic: the iPhone style `IMG_1234.JPG` with a random four-digit number
  (`.PNG` or `.WEBP` for those formats).
- Device: the naming style of the chosen device, for example `DSC_1234.JPG`
  (Nikon), `DSCF1234.JPG` (Fujifilm), `P1012345.JPG` (Olympus/Panasonic),
  `PXL_20240501_103015123.jpg` (Pixel). Date-based names use the photo's
  (kept or random) date. Devices whose naming could not be confirmed use the
  generic name.

Renaming happens after the new contents were written and checked. If the name is
already taken in the same folder, another random number of the same length is
tried. A failed rename is shown as a warning; the metadata change is kept.

## Colour profiles (Keep)

Phone photos usually embed an ICC colour profile so wide-colour (Display P3)
images show the right colours. These profiles also hold text that photo
viewers show as metadata: a copyright notice (for example "Copyright (c) 2022
Samsung Electronics Co., Ltd."), a device manufacturer and model, the profile
creator, platform and creation date. Version 0.1.0-test1 kept the profile
unchanged, so this text remained after cleaning.

Exif Wipe now rebuilds the profile:

- kept unchanged: white and black point, primaries, tone curves, chromatic
  adaptation, colour lookup tables, luminance and CICP data, which is all that
  is needed to reproduce the colours,
- removed: copyright, device manufacturer and model descriptions, and every
  other non-colour tag,
- blanked in the header: preferred CMM, creation date, platform, device
  manufacturer, model and attributes, creator and profile ID,
- the description is kept only if it is a standard colour space name such as
  "sRGB IEC61966-2.1", "Display P3" or "DCI-P3 D65 Gamut with sRGB Transfer";
  any other description is replaced with "Colour profile".

With Keep, the photo decodes to exactly the same colours as before; the app
checks this before writing. A profile that cannot be read is removed, in which case viewers
treat the photo as sRGB.

## How to use

1. In your gallery or file manager, select one or more photos.
2. Tap Share and choose "Wipe metadata".
3. Android asks whether Exif Wipe may modify the photos. Tap Allow.
   - Android 11 and newer: one prompt for all selected photos.
   - Android 10: one prompt per photo.
   - Android 8 and 9: a one-time "photos and media" permission prompt.
   - If the sharing app did not pass a direct link to the stored photo,
     Exif Wipe may also ask once for photo access so it can find the original.
4. A short message shows how many photos were changed. If any photo could not
   be changed, a dialog lists each one with the reason. Failed photos are left
   unchanged. The dialog's "Copy error details" button copies a technical
   report you can send to the developer.

If something goes wrong, open Exif Wipe from the app drawer: the "Last errors"
section (shown only after an error) lists the recent problems, including app
crashes, with buttons to copy or clear them. The report contains the app and
Android version, the phone model, the settings, file names and the error, but
no photo contents, metadata values or locations. The app never sends it
anywhere; it has no internet permission.

Opening Exif Wipe from the app drawer shows these instructions.

## Requirements

- Android 8.0 (API 26) or newer. Built for Android 16 (target API 36).
- Supported formats: JPEG, PNG (including APNG) and WebP (including animated
  and lossless WebP).
- Not supported, reported as such and left unchanged: HEIC/HEIF, AVIF, GIF,
  TIFF, DNG and other RAW files.
- The app has no internet permission and no analytics.

## Install

Download the APK from the repository's Releases page, open it on your phone,
and allow installing from that source when Android asks. Test builds are
marked "Pre-release". Each release lists the APK's SHA-256 checksum, which you
can compare with the file you downloaded.

## How it keeps your original safe

For each photo the app:

1. reads the whole original into memory and plans the new metadata there. It
   reads through a read-write link where possible, because Android replaces
   the GPS values of other apps' photos with zeros on read-only links for apps
   without the "access media location" permission,
2. re-checks the new copy independently: it must be well formed, contain only
   the parts needed to decode the image plus exactly the planned metadata
   (compared byte for byte), contain no known metadata signatures outside the
   planned blocks, contain no EXIF tag of a group set to Wipe, and decode to
   exactly the same pixels as the original (the same colours if the colour
   profile is kept),
3. keeps a temporary backup of the original inside the app's private storage,
4. overwrites the original, truncates it to the new length, reads it back
   through the same descriptor and again after reopening it, and reports
   success only if it is exactly the new copy (or, on a read-only link,
   differs only by Android's zeroed GPS values) and passes the check again,
5. if anything fails after the file was opened for writing, writes the backup
   back. The temporary backup is deleted afterwards.

Damaged, truncated or unusual files fail the checks and are left unchanged.

After writing, the app asks Android's media scanner to re-read the file so the
gallery and other apps see the new contents.

The app writes only to the photo stored on the phone: a MediaStore item, a file
from Android's own storage or Downloads document providers, or a photo found in
the media library that is byte-identical to the shared copy. It never writes
to another app's link, even if that app allows it, because such links often
point at a temporary copy.

## Build

Requirements: JDK 21 and the Android SDK (platform 37, build-tools 36.0.0).
Set `sdk.dir` in `local.properties` or the `ANDROID_HOME` environment variable.

    ./gradlew testDebugUnitTest   # unit tests
    ./gradlew assembleDebug       # debug APK in app/build/outputs/apk/debug/

The tests write sample files to `app/build/test-output/`. See docs/TESTING.md
for checking them with exiftool. docs/DATA.md describes the bundled data files
and how `places.tsv` is generated.

## Release signing

Release builds are signed only when a signing properties file is present.

1. Copy `keystore.properties.example` to `keystore.properties` in the project
   folder, or to any private location and set the environment variable
   `EXIF_WIPE_KEYSTORE_PROPERTIES` to its path.
2. Fill in `storeFile` (absolute path to the `.jks`), `storePassword`,
   `keyAlias` and `keyPassword`.
3. Run `./gradlew assembleRelease`. The APK is written to
   `app/build/outputs/apk/release/`.

`keystore.properties`, `*.jks` and `*.keystore` are ignored by git and must
never be committed. Android only installs an update over an existing install
if it is signed with the same key, so keep the keystore and its passwords
backed up.

## Limitations

- Only photos stored on the phone can be changed. These cannot, and are
  reported as an error instead:
  - photos that exist only in the cloud (for example Google Photos backups not
    on the device),
  - apps that share a temporary copy instead of the stored photo (many chat,
    email and cloud storage apps), unless the copy is byte-identical to a photo
    in the phone's media library,
  - files on storage that is not in the phone's media library and whose
    sharing app does not allow changes.
- Unless Embedded extras is set to Keep, data stored after the image is
  removed. This includes the video part of motion photos (Samsung Motion Photo,
  Google Motion Photo) and the HDR gain map of Ultra HDR JPEGs. With Keep, that
  data is kept unchanged, including any metadata inside it.
- Keep copies only tags the app knows. Extended XMP, maker notes and unknown
  tags are not kept, and XMP that cannot be parsed safely is dropped.
- Random values are realistic but not perfect: they are not based on the
  picture content (a night photo may get daylight exposure values), the pixel
  size is the photo's real size and may not match the chosen camera's sensor,
  and an expert can still find signs that a file was edited (for example the
  JPEG compression tables differ from the camera's).
- Random locations are near cities and towns of at least 50,000 people; the
  land check uses Natural Earth 1:10m data, so points very close to a coastline
  or a small lake may be up to a few hundred metres off.
- Renaming needs the media library (MediaStore) or a document provider that
  supports renaming. Links shared by some apps cannot be renamed; the metadata
  is still changed and a warning is shown. Android may adjust the name if it
  does not fit the file type.
- The file's modification date changes. Gallery apps that sort by file date
  instead of the (now removed) "date taken" may show a cleaned photo as new.
- On Android 8 and 9, the phone's media database may keep showing the old
  location until the media scanner refreshes the file.
- Files larger than 200 MB are skipped.
- HEIC/HEIF and AVIF are not supported yet.
- Gallery apps keep their own caches. Google Photos in particular shows the
  details of its cloud backup, which Exif Wipe cannot change; a photo that was
  already backed up keeps its metadata there. Samsung Gallery may show old
  details until it refreshes.
- Photos cleaned with 0.1.0-test1 still contain the vendor colour profile text.
  Share them to Exif Wipe again to remove it.
- An app that shares a photo through its own link (not MediaStore) is only
  handled if the photo can be found in the media library; otherwise it is
  reported as an error.

## Credits and attributions

Exif Wipe is inspired by and partly based on Scrambled Exif by Juan García
Basilio (juanitobananas), licensed under the GNU GPL version 3 or later:
https://gitlab.com/juanitobananas/scrambled-exif

- `JpegStripper.kt` and `PngStripper.kt` are adapted from the `JpegScrambler`
  and `PngScrambler` classes in Scrambled Exif's
  `app/src/main/java/com/jarsilio/android/scrambledeggsif/utils/ExifScrambler.kt`
  (version 1.8.3), which also credits Eric Cochran's ExifDataRemover
  (https://github.com/NightlyNexus/ExifDataRemover). Each adapted file states
  the original copyright and what was changed.
- The idea of re-checking the cleaned output (structure plus a metadata
  signature scan) and the magic-number format detection follow Scrambler
  (https://gitlab.com/juanitobananas/scrambler), the library extracted from
  Scrambled Exif. That code was rewritten here, not copied.

Exif Wipe is a separate app, not a fork, and is not affiliated with or endorsed
by the Scrambled Exif authors. Please do not report Exif Wipe problems to them.

Bundled data:

- Random locations use GeoNames data (https://www.geonames.org/,
  "cities15000"), licensed under Creative Commons Attribution 4.0
  (https://creativecommons.org/licenses/by/4.0/). The app's `places.tsv` is
  derived from it (filtered, reduced to coordinates, population, elevation,
  time zone and a land mask).
- The land mask is computed from Natural Earth 1:10m land, minor islands and
  lakes data (https://www.naturalearthdata.com/), which is in the public domain.
- Camera, lens and phone names, focal lengths, apertures, ISO ranges, years and
  file naming patterns in `bodies.tsv` and `lenses.tsv` are facts compiled for
  this app from manufacturer specifications and sample files; no third-party
  files were copied. Brand and product names belong to their owners. The
  install-base sources for the presets are TelemetryDeck (iPhone models),
  AppBrain (Android device models), analytics.usa.gov (device models) and
  Counterpoint Research.

## License

Copyright (C) 2026 oiuy-retro. Exif Wipe is free software under the GNU
General Public License, version 3 or (at your option) any later version. See
LICENSE.
