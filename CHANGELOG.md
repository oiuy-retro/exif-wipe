# Changelog

All notable changes to this project are listed here.

## 0.1.0 (versionCode 1) - unreleased

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
