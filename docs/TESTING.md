# Testing

## Unit tests

    ./gradlew testDebugUnitTest

The tests cover JPEG, PNG and WebP rewriting, ICC profile cleaning and
generation, the orientation tag, the output safety check, format detection,
and the metadata settings: every group in every mode, all 729 group
combinations (with colour profile, file name and preset varied) through the
safety checks, consistency of random values (lens fits body and date, focal
length and aperture within the lens range, 35 mm equivalent, ISO range, time
zone and GPS time, position near the chosen place), the date weighting, the
time of day distribution, population weighting of places, file names, colour
profile modes, the MPF index update and the TIFF writer. Test fixtures
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

Files written by the metadata-settings tests (`app/src/test/java/com/oiuy/exifwipe/meta/`):

- `mode-<group>-<keep|wipe|replace>.jpg`: one group changed, all others wiped
- `preset-iphone.jpg`, `preset-pixel.jpg`, `preset-samsung.jpg`: everything
  random with a device preset and a replaced colour profile
- `icc-srgb.jpg`, `icc-display_p3.jpg`, `icc-replace.png`, `generated-*.icc`:
  generated colour profiles
- `extras-keep-mpf.jpg`: kept MPF index and second image
- `replace-all.png`: random metadata in a PNG eXIf chunk

Check them with:

    exiftool -validate -warning -a app/build/test-output/*.jpg app/build/test-output/*.png
    exiftool -a -G1 -s app/build/test-output/preset-pixel.jpg

All files written with a full EXIF block validate without warnings. Files with
only the orientation tag (the 0.1.0 behaviour) get the expected warning about
the missing YCbCrPositioning tag.

### Random locations on land

`gps-sample.csv` holds 5,000 random points. To check them against Natural
Earth land data (needs Python with shapely, pyshp and numpy, and the
`ne_10m_land`, `ne_10m_minor_islands` and `ne_10m_lakes` shapefiles):

    python3 tools/check_gps_sample.py /path/to/naturalearth

### All settings on Random, and Android differences

- `AllRandomStressTest` runs every group on Random with each device preset,
  colour profile target and file name style over hundreds of random seeds. To
  include your own photos (never commit them), set `EXIFWIPE_PHOTOS`:

      EXIFWIPE_PHOTOS=/path/to/photos ./gradlew testDebugUnitTest --tests '*AllRandomStressTest*'

- `PortabilityTest` checks that every regular expression in the app is also
  valid for Android's ICU regex engine (an unescaped `}` is accepted by the
  desktop JVM but not on Android; this caused the 0.2.0-test1 crash), that all
  bundled time zones resolve, and that numbers are written with ASCII digits
  in any language setting.
- `app/src/test/java/com/oiuy/exifwipe/device/` holds Robolectric tests that
  run the Android code (bitmap decoding with the real native graphics library,
  the in-place writer, the share screen and the settings screen).
  `ReadBackRobolectricTest` simulates storage that zeroes GPS values on
  read-only links (Android's location redaction), storage that ignores the
  truncate flag, and a real read-back mismatch. The first
  run downloads Robolectric's Android framework jar.

## On a phone

See the checklist in the pull request. To inspect a cleaned photo, copy it to
a computer (for example over USB) and run exiftool on it as above.
