# Changelog

All notable changes to this project are listed here.

## 0.2.0-test1 (versionCode 4) - test build

- New settings screen (open the app from the app drawer). Metadata is sorted
  into six groups (Location, Date & time, Camera & device, Lens & exposure,
  Description & authorship, Embedded extras), each with a Keep / Wipe / Random
  switch. The default is Wipe for every group, which behaves exactly like 0.1.0.
- Random replaces a group with realistic values that agree with each other:
  a camera or phone from a built-in list of 284 bodies and 537 lenses, a lens
  that fits the mount and existed at the date, matching focal length, aperture,
  35 mm equivalent, ISO and shutter speed, a date after 2002 weighted toward
  recent years with a random time of day, and a GPS position on land near a
  populated place (weighted by population) with the matching time zone offset
  and GPS time.
- Device preset for random values: Random, Google Pixel 7, Apple iPhone 13 or
  Samsung Galaxy A12.
- Colour profile setting: keep (cleaned, as before), wipe, or replace with a
  generated sRGB or Display P3 profile.
- File name setting: keep, generic iPhone style `IMG_1234.JPG`, or the chosen
  device's naming style. Name collisions get a new random number.
- EXIF is now built from scratch; kept groups copy only their own known tags.
  XMP and IPTC are filtered by group. With Embedded extras on Keep, the MPF
  index (offsets updated), the data after the image and the thumbnail are kept.
- The safety check now compares the written file with the planned metadata
  byte for byte and rejects any tag from a group set to Wipe.
- Simple WebP files are converted to the extended layout when metadata or a
  colour profile has to be written.
- Bundled data: GeoNames populated places (CC BY 4.0) and a land mask computed
  from Natural Earth (public domain). See docs/DATA.md.

## 0.1.0 (versionCode 3) - 2026-11-14

- First full release. Same app as 0.1.0-test2, verified on a phone; the
  versionCode is higher than the test builds so it installs over them.
- Summary: share target that removes metadata (EXIF, GPS, camera details,
  dates, XMP, IPTC, comments, maker notes, thumbnails, MPF/gain-map and other
  data after the image) from the stored original JPEG, PNG or WebP in place,
  keeping the image data, colour data and orientation.

## 0.1.0-test2 (versionCode 2) - test build

- Fixed: cleaned photos still showed a copyright notice and manufacturer. These
  came from the embedded ICC colour profile (for example Samsung's "Copyright
  (c) 2022 Samsung Electronics Co., Ltd." and the "SEC" manufacturer, platform
  and creator fields), which 0.1.0-test1 kept unchanged. The profile is now
  rebuilt with only its colour data; colours are unchanged.
- PNG iCCP and WebP ICCP profiles are cleaned the same way.
- The safety check now rejects any colour profile with identifying fields.
- Success is reported only after the stored file is read back, matches the
  cleaned copy exactly, and has nothing left to remove.
- Links from other apps are no longer written to directly, even when the app
  grants write access; the stored original is located in the media library
  instead (Google Photos links, media documents, name/size/content lookup).
- The media scanner is asked to re-read each cleaned file on every Android
  version.
- New tests with camera-style fixtures (EXIF with maker notes, GPS and
  thumbnail, vendor ICC, XMP and extended XMP, IPTC, MPF, gain map, FlashPix,
  vendor segments and trailer).

## 0.1.0-test1 (versionCode 1) - test build

- First version.
- Share target for one or more images (ACTION_SEND and ACTION_SEND_MULTIPLE)
  that removes metadata from the stored original in place.
- Lossless metadata removal for JPEG, PNG/APNG and WebP; image data is copied
  unchanged. Colour profiles and a minimal orientation tag are kept.
- Independent safety check of every cleaned file, pixel comparison, temporary
  backup and read-back verification before and after overwriting.
- Edit permission prompts: MediaStore write request on Android 11+,
  RecoverableSecurityException prompt on Android 10, storage permission on
  Android 8 and 9.
- Finds the stored original for Google Photos and photo picker links, and by
  name, size and content for other apps.
- Clear error for shared copies that are not stored on the phone, and for
  unsupported formats (HEIC/HEIF, AVIF, GIF, TIFF, RAW).
- Launcher screen with usage instructions.
- Unit tests for the JPEG, PNG and WebP stripping and the output checks.
