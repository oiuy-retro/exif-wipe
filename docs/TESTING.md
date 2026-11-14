# Testing

## Unit tests

    ./gradlew testDebugUnitTest

The tests cover JPEG, PNG and WebP stripping, ICC profile cleaning, the
orientation tag, the output safety check and format detection. Test fixtures
are generated in code (`app/src/test/java/com/oiuy/exifwipe/strip/`); no real
photos are stored in the repository. `CameraFixtures.kt` builds files shaped
like a Samsung Galaxy Ultra HDR photo with invented identifying values.

## Checking the output with exiftool

The tests write before/after files to `app/build/test-output/`:

- `camera-before.jpg` / `camera-after.jpg`: camera-style JPEG
- `test1-before.jpg` / `test1-after.jpg`: what 0.1.0-test1 left behind
  (orientation EXIF plus the vendor colour profile), cleaned again
- `png-before.png` / `png-after.png`: PNG with a vendor iCCP profile

Install ExifTool (https://exiftool.org) and run:

    exiftool -a -G1 -s -u app/build/test-output/camera-after.jpg

The output should list only file structure (type, size, encoding), the
`IFD0 Orientation` tag, and the colour profile's colour data (description,
white point, chromatic adaptation, matrix columns, tone curves). There should
be no Make, Model, Software, Copyright, Artist, GPS, dates, maker notes,
thumbnail, XMP, IPTC, MPF, comments, ProfileCopyright, DeviceManufacturer,
DeviceModel, PrimaryPlatform or ProfileCreator values.

## On a phone

See the checklist in the pull request. To inspect a cleaned photo, copy it to
a computer (for example over USB) and run exiftool on it as above.
