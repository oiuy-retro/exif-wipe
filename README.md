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
- The colour profile (JPEG ICC_PROFILE, PNG iCCP/sRGB/gAMA/cHRM, WebP ICCP),
  transparency, and the JPEG Adobe colour-transform marker.
- The orientation. Phones often store a portrait photo as landscape pixels plus
  an "Orientation" tag. If that tag is anything other than "normal", the
  cleaned file gets a new EXIF block that holds only this one tag (26 bytes,
  no other data). Photos with normal orientation get no EXIF block at all.

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
   only allowed parts, contain no known metadata signatures, and decode to
   exactly the same pixels as the original,
3. keeps a temporary backup of the original inside the app's private storage,
4. overwrites the original, reads it back and compares it with the cleaned copy,
5. if anything fails after the file was opened for writing, writes the backup
   back. The temporary backup is deleted afterwards.

Damaged, truncated or unusual files fail the checks and are left unchanged.

## Build

Requirements: JDK 21 and the Android SDK (platform 37, build-tools 36.0.0).
Set `sdk.dir` in `local.properties` or the `ANDROID_HOME` environment variable.

    ./gradlew testDebugUnitTest   # unit tests for the JPEG, PNG and WebP code
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
- The colour profile is kept for correct colours. Profiles normally describe
  only a colour space, but some contain a device or vendor name.

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
