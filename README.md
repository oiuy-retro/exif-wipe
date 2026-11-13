# Exif Wipe

Exif Wipe is a small Android app that removes hidden metadata from photos stored
on your phone. You share one or more photos to it, and it changes the original
files in place. Unlike Scrambled Exif, it does not make a cleaned copy and does
not open the share menu again: the photo in your gallery is the one that gets
cleaned, and you can share it anywhere afterwards.

Removed: EXIF (GPS location, camera make and model, lens, serial numbers, date
and time, thumbnails), XMP, IPTC/Photoshop data, JPEG comments, PNG text and
time chunks, and data stored after the end of the image.

Kept, so the photo looks exactly the same:

- The compressed image data, copied byte for byte. Nothing is re-encoded.
- The colour data of the embedded colour profile (JPEG ICC_PROFILE, PNG iCCP,
  WebP ICCP), plus PNG sRGB/gAMA/cHRM, transparency, and the JPEG Adobe
  colour-transform marker. See "Colour profiles" below.
- The orientation. Phones often store a portrait photo as landscape pixels plus
  an "Orientation" tag. If that tag is anything other than "normal", the
  cleaned file gets a new EXIF block, built from scratch, that holds only this
  one tag (26 bytes, no other data). Photos with normal orientation get no EXIF
  block at all.

## Colour profiles

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

The photo decodes to exactly the same colours as before; the app checks this
before writing. A profile that cannot be read is removed, in which case viewers
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
4. A short message shows how many photos were cleaned. If any photo could not
   be cleaned, a dialog lists each one with the reason. Failed photos are left
   unchanged.

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

1. reads the whole original into memory and removes the metadata there,
2. re-checks the cleaned copy independently: it must be well formed, contain
   only allowed parts (including a colour profile without identifying fields),
   contain no known metadata signatures, and decode to exactly the same colours
   as the original,
3. keeps a temporary backup of the original inside the app's private storage,
4. overwrites the original, reads it back from storage, and reports success
   only if it is exactly the cleaned copy and cleaning it again finds nothing
   left to remove,
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

    ./gradlew testDebugUnitTest   # unit tests for the JPEG, PNG, WebP and ICC code

The tests write before/after sample files to `app/build/test-output/`. See
docs/TESTING.md for checking them with exiftool.
    ./gradlew assembleDebug       # debug APK in app/build/outputs/apk/debug/

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
- Data stored after the image is removed. This includes the video part of
  motion photos (Samsung Motion Photo, Google Motion Photo) and the HDR gain
  map of Ultra HDR JPEGs. The still image is unchanged, but these extras are
  lost.
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

## License

Copyright (C) 2026 oiuy-retro. Exif Wipe is free software under the GNU
General Public License, version 3 or (at your option) any later version. See
LICENSE.
