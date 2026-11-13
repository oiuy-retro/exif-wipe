# Changelog

All notable changes to this project are listed here.

## 0.1.0-test2 (versionCode 2) - unreleased

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

## 0.1.0-test1 (versionCode 1)

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
