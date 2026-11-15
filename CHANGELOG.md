# Changelog

All notable changes to this project are listed here.

## 0.3.0 (versionCode 9)

Release of 0.3.0-test1 with the same code.

- "Common devices" (renamed from "Device for random values").
- "Camera brand" setting with 22 brands: random values of that brand only, with
  lenses for the camera's mount, dates in its years of use, and its file
  names, software and colour profile.
- "Only valid combinations" setting, on by default.
- Device settings are grayed out with an explanation when they have no effect.

## 0.3.0-test1 (versionCode 8) - test build

- The "Device for random values" section is now called "Common devices".
- New "Camera brand" setting with the 22 brands of the built-in camera list.
  With a brand chosen, the random camera is of that brand, the lens is made
  for its mount (including other makers' lenses for that mount, never another
  brand's mount on an adapter), the date lies in the camera's years of use
  (after 2002), and file name, software and colour profile follow it.
  Choosing a brand clears the common device and the other way round.
- New "Only valid combinations" setting, on by default. Off: lens, date and
  file name style are randomized independently of the random camera. Always
  on while a common device or brand is chosen.
- Common devices, Camera brand and Only valid combinations are grayed out with
  an explanation when they have no effect, and settings that follow a chosen
  device or brand show a note.
- Lenses are picked so that the photo date can be after the lens came out
  (a few random photos had a date one day before the lens's first full year).
- The error details include the brand and the Only valid combinations setting.

## 0.2.0 (versionCode 7)

Release of the 0.2.0 test builds below, with the same code as 0.2.0-test3,
which was checked on a Samsung Galaxy S24+ (Android 17).

- Settings per group (location, date and time, camera and device, lens and
  exposure, author and copyright, other details): Keep, Wipe or Random.
- Random values describe a real camera or phone from the bundled list, with a
  lens that fits it, a date in its era, a place on land and a matching time zone.
- Device presets: Pixel 7, iPhone 13 and Galaxy A12.
- Colour profile: keep, wipe, or replace with a generated sRGB or Display P3
  profile.
- File name: keep, generic (IMG_1234.JPG) or in the style of the device.
- Errors are recorded and shown on the settings screen with a copy button.
- Read-back after writing works with Android's location redaction.

## 0.2.0-test3 (versionCode 6) - test build

- Fixed: with Location set to Random (or Keep), photos taken by the camera app
  failed with "The file read back after writing did not match" and were put
  back. Android hides the GPS values of other apps' photos from apps without
  the "access media location" permission by returning zeros for them when the
  file is opened read-only, so the read-back never matched the written file.
  The app now reads the original and the written file through a read-write
  link, which Android does not redact. If only a read-only link is available,
  a read-back that differs only by zeros in the written GPS values is
  accepted.
- Because the original was read through the redacted link, 0.2.0-test2 and
  earlier copied zeros instead of the real values when Location was set to
  Keep, and a photo restored after a failed write got zeros in its GPS values.
- After writing, the file is explicitly truncated to the new length and synced,
  in case the storage ignores the truncate flag, and it is checked through the
  same descriptor before being reopened and checked again.
- When a read-back still differs, the error details give both lengths, the
  first and last differing offsets, the part of the file affected, a few bytes
  of context (never from metadata) and which read path was used.

## 0.2.0-test2 (versionCode 5) - test build

- Fixed: with any group set to Random (or a file name setting other than
  Keep), sharing a photo closed the app without changing it, and sharing again
  showed Android's crash message. A pattern used to build file names and
  software strings was valid on a computer but rejected by Android's regular
  expression engine, so the part of the app that makes random values could not
  start. All patterns are now checked against Android's rules by a test.
- Any unexpected error while processing photos is now shown in a dialog
  instead of closing the app; the photo is left unchanged. The dialog has a
  "Copy error details" button.
- New "Last errors" section on the settings screen (only shown after an error)
  with the details of recent problems and of any crash, to copy and send.
  It holds the app and Android version, phone model, settings, file names and
  the error, but no photo contents, metadata values or locations.
- Numbers in file names and dates always use the digits 0-9, whatever the
  phone's language.
- Random locations skip time zones the phone's time zone data does not know
  yet (older names such as Europe/Kiev are used where needed).
- Tests: every setting on Random with each device preset over many random
  seeds, Robolectric tests of the share screen, settings screen and in-place
  writer, and the Android regular expression check.

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
