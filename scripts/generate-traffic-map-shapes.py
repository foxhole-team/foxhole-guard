#!/usr/bin/env python3
"""Generate the dashboard traffic-map country shapes asset.

Reads the canonical Natural Earth 50m admin-0 GeoJSON and emits the compact
preprocessed asset consumed by TrafficMapCountryShapeAssetParser.

The output mirrors the Kotlin visual-shape pipeline in TrafficMapCountryShapes.kt:
per-ring dateline unwrapping, point simplification, and strict island filtering
so that only land that is actually visible at dashboard scale is kept. Antarctica
is dropped because it is clamped to the projection floor and only smears the
bottom edge of the map.

Points are stored as [lat, lon]. Run from foxhole_app/:
    python3 scripts/generate-traffic-map-shapes.py
"""
from __future__ import annotations

import json
import math
import os
from typing import List, Tuple

SRC = "app/src/main/assets/maps/ne_50m_admin_0_countries.geojson"
OUT = "app/src/main/assets/maps/ne_50m_admin_0_countries_preprocessed.json"

MIN_RING_POINTS = 3
MIN_RELATIVE_RING_AREA = 0.02
MIN_ABSOLUTE_RING_AREA = 0.5
MIN_SHAPE_AREA = 1.0
MAX_POINTS_PER_RING = 220
MIN_POINT_DELTA_DEG = 0.045
COORD_DECIMALS = 5

# Countries that only ever render as a clamped smear or invisible speck.
EXCLUDED_CODES = {"AQ"}  # Antarctica

Point = Tuple[float, float]  # (lat, lon)


def iso_code(props: dict) -> str | None:
    for key in ("ISO_A2", "ISO_A2_EH", "WB_A2", "POSTAL"):
        value = props.get(key)
        if isinstance(value, str):
            value = value.strip().upper()
            if len(value) == 2 and value.isalpha():
                return value
    return None


def norm_lon(lon: float) -> float:
    n = math.fmod(lon, 360.0)
    if n < -180.0:
        n += 360.0
    if n > 180.0:
        n -= 360.0
    return n


def normalize_ring_longitudes(ring: List[Point]) -> List[Point]:
    if len(ring) < MIN_RING_POINTS:
        return ring
    norm = [norm_lon(lon) for (_, lon) in ring]
    distinct_sorted = sorted(set(norm))
    if len(distinct_sorted) <= 1:
        return [(lat, norm[i]) for i, (lat, _) in enumerate(ring)]
    largest_gap = -math.inf
    interval_start = distinct_sorted[0]
    last = len(distinct_sorted) - 1
    for index, lon in enumerate(distinct_sorted):
        nxt = distinct_sorted[0] + 360.0 if index == last else distinct_sorted[index + 1]
        gap = nxt - lon
        if gap > largest_gap:
            largest_gap = gap
            interval_start = distinct_sorted[0] if index == last else distinct_sorted[index + 1]
    out: List[Point] = []
    for i, (lat, _) in enumerate(ring):
        lon = norm[i]
        out.append((lat, lon + 360.0 if lon < interval_start else lon))
    return out


def simplify_ring(ring: List[Point]) -> List[Point]:
    open_ring = ring[:-1] if len(ring) > 1 and ring[0] == ring[-1] else ring
    if len(open_ring) <= MAX_POINTS_PER_RING:
        return open_ring
    by_distance: List[Point] = []
    for p in open_ring:
        prev = by_distance[-1] if by_distance else None
        if prev is None or abs(p[0] - prev[0]) + abs(p[1] - prev[1]) >= MIN_POINT_DELTA_DEG:
            by_distance.append(p)
    reduced = by_distance if len(by_distance) >= MIN_RING_POINTS else open_ring
    if len(reduced) <= MAX_POINTS_PER_RING:
        return reduced
    stride = max(1, math.ceil(len(reduced) / MAX_POINTS_PER_RING))
    sampled = [p for i, p in enumerate(reduced) if i % stride == 0]
    return sampled if len(sampled) >= MIN_RING_POINTS else reduced[:MAX_POINTS_PER_RING]


def ring_area(ring: List[Point]) -> float:
    if len(ring) < MIN_RING_POINTS:
        return 0.0
    area = 0.0
    n = len(ring)
    for i in range(n):
        cur = ring[i]
        nxt = ring[(i + 1) % n]
        # (lon, lat) shoelace
        area += cur[1] * nxt[0] - nxt[1] * cur[0]
    return abs(area) / 2.0


def polygon_rings(geometry: dict) -> List[List[Point]]:
    gtype = geometry.get("type")
    coords = geometry.get("coordinates")
    rings: List[List[Point]] = []
    if gtype == "Polygon":
        polys = [coords]
    elif gtype == "MultiPolygon":
        polys = coords
    else:
        return rings
    for poly in polys:
        for ring in poly:
            pts = [(float(c[1]), float(c[0])) for c in ring if len(c) >= 2]
            if len(pts) >= MIN_RING_POINTS:
                rings.append(pts)
    return rings


def visual_shape(rings: List[List[Point]]) -> List[List[Point]] | None:
    normalized = []
    for ring in rings:
        r = simplify_ring(normalize_ring_longitudes(ring))
        if len(r) >= MIN_RING_POINTS:
            normalized.append(r)
    if not normalized:
        return None
    areas = [ring_area(r) for r in normalized]
    largest_area = max(areas)
    largest_index = areas.index(largest_area)
    threshold = max(largest_area * MIN_RELATIVE_RING_AREA, MIN_ABSOLUTE_RING_AREA)
    visual = [r for i, r in enumerate(normalized) if i == largest_index or areas[i] >= threshold]
    if largest_area < MIN_SHAPE_AREA or not visual:
        return None
    return visual


def round_point(p: Point) -> List[float]:
    lat = round(p[0], COORD_DECIMALS)
    lon = round(p[1], COORD_DECIMALS)
    # collapse -0.0
    return [lat + 0.0, lon + 0.0]


def main() -> None:
    with open(SRC, "r", encoding="utf-8") as f:
        src = json.load(f)
    countries = []
    for feature in src.get("features", []):
        props = feature.get("properties", {})
        code = iso_code(props)
        if code is None or code in EXCLUDED_CODES:
            continue
        shape = visual_shape(polygon_rings(feature.get("geometry", {})))
        if shape is None:
            continue
        rings = [[round_point(p) for p in ring] for ring in shape]
        countries.append({"code": code, "rings": rings})
    countries.sort(key=lambda c: c["code"])
    payload = {"countries": countries}
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(payload, f, separators=(",", ":"), ensure_ascii=False)
        f.write("\n")
    total_rings = sum(len(c["rings"]) for c in countries)
    total_points = sum(len(r) for c in countries for r in c["rings"])
    size = os.path.getsize(OUT)
    print(f"countries={len(countries)} rings={total_rings} points={total_points} bytes={size}")


if __name__ == "__main__":
    main()
