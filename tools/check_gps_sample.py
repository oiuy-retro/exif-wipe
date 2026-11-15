#!/usr/bin/env python3
"""Checks that random GPS points written by the unit tests lie on land.

Run the unit tests first (they write app/build/test-output/gps-sample.csv), then:
    python3 tools/check_gps_sample.py /path/to/naturalearth
The directory must contain ne_10m_land.shp, ne_10m_minor_islands.shp and ne_10m_lakes.shp.
Needs: shapely, pyshp, numpy.
"""
import csv
import importlib.util
import os
import sys

import numpy as np
import shapely

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("build_places", os.path.join(here, "build_places.py"))
bp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bp)

src = sys.argv[1]
land = shapely.union_all(bp.load(f"{src}/ne_10m_land.shp") + bp.load(f"{src}/ne_10m_minor_islands.shp"))
lakes = shapely.union_all(bp.load(f"{src}/ne_10m_lakes.shp"))
rows = list(csv.DictReader(open(os.path.join(here, "..", "app/build/test-output/gps-sample.csv"))))
x = np.array([float(r["lon"]) for r in rows])
y = np.array([float(r["lat"]) for r in rows])
on = shapely.contains_xy(land, x, y) & ~shapely.contains_xy(lakes, x, y)
print(f"{on.sum()} of {len(rows)} points on land")
sys.exit(0 if on.all() else 1)
