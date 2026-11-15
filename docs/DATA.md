# Bundled data

The files in `app/src/main/resources/exifwipe/` are used for random metadata.
They are tab-separated text; lines starting with `#` are comments.

## bodies.tsv: cameras and phones

284 bodies from 22 brands (Canon, Nikon, Sony, Fujifilm, Olympus/OM System,
Panasonic, Pentax/Ricoh, Leica, Sigma, Samsung NX, Minolta/Konica Minolta,
Kodak, Contax/Kyocera, Epson, Casio, Hasselblad, GoPro, Apple, Google,
Samsung phones and others).

Columns: id, EXIF Make, EXIF Model, brand, kind (dslr, mirrorless,
rangefinder, compact, phone, action), mount (`fixed:<id>` for built-in
lenses), crop factor, first and last production year (0 = still sold),
software pattern, file name pattern, pixel width and height, ISO range, EXIF
byte order, colour profile (srgb, p3, none), EXIF version, flags (`u` = model
string or file naming not confirmed from a real file; such bodies are never
picked at random and use the generic file name).

Software pattern: `#` is a random digit, `{model}` the model, `{ios}` an iOS
version that existed on the photo's date, `{hdrplus}` a Pixel HDR+ string.

File name pattern: `{n4}`, `{n5}`, `{n7}` random counters, `{f3}` a folder
number (100-199), `{M}` the month as 1-9/A-C, `{dd}` the day,
`{local:<pattern>}` and `{utc:<pattern>}` the photo's time formatted with a
java.time pattern.

## lenses.tsv: lenses and built-in camera modules

537 lenses from 39 makers, including current and legacy mounts: Canon FD, EF,
EF-S, EF-M and RF; Nikon F and Z; Sony/Minolta A and Sony E; Fujifilm X and G;
Micro Four Thirds and Four Thirds; Pentax K and M42; Leica M, R and L;
Contax/Yashica and Contax N; Olympus OM; Minolta MD; Samsung NX; Sigma SA;
Hasselblad XCD; Mamiya 645; and third-party lenses from Sigma, Tamron, Tokina,
Samyang/Rokinon, Viltrox, Laowa, TTArtisan, 7Artisans, Yongnuo, Meike, Zeiss,
Voigtlander, Schneider-Kreuznach, Rodenstock and others.

Columns: make, model (marketing name as cameras record it), mounts, focal
length range (mm), widest aperture at the short and long end, smallest
aperture, year, flags (`aps` = APS-C/DX image circle, `manual` = no electronic
contacts, `fixed` = built-in lens, `phone` = phone camera module), 35 mm
equivalent (phone modules).

Mount compatibility used by the app (`Compat` in `Dataset.kt`): same mount;
the maker's electronic adapters (EF on RF and EF-M, F on Z, A on E, Four
Thirds on Micro Four Thirds); manual legacy lenses on mirrorless bodies and
a few DSLR mounts through mechanical adapters (no lens data recorded). APS-C
lenses are only used on bodies with a crop factor of 1.3 or more.

These two files contain only facts (names, numbers, years) compiled from
manufacturer specifications and sample files. No third-party file was copied.

## places.tsv: populated places

11,492 places with at least 50,000 inhabitants and 284 time zones, generated
by `tools/build_places.py` from:

- GeoNames `cities15000` (https://download.geonames.org/export/dump/),
  licensed under CC BY 4.0. Administrative seats without their own
  population, historical, abandoned and destroyed places are excluded.
- Natural Earth 1:10m physical vectors `ne_10m_land`, `ne_10m_minor_islands`
  and `ne_10m_lakes` (https://www.naturalearthdata.com/), public domain.

Each `P` line holds latitude, longitude, population, elevation (m), time zone
index (into the `Z` lines), country code, radius (km) and a land mask. The
radius is sqrt(population) / 400 km, limited to 1.5 to 5 km. The mask has one
bit per 1 km cell of an 11 x 11 grid centred on the place (bit y * 11 + x);
a bit is set if the cell's centre is within the radius and all of a 6 x 6 grid
of points covering the cell (edges included) are on land and outside lakes.

To regenerate (Python 3 with shapely, numpy and pyshp):

    python3 tools/build_places.py <dir with cities15000.txt and the shapefiles> \
        app/src/main/resources/exifwipe/places.tsv
