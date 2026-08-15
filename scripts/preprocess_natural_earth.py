#!/usr/bin/env python3
"""Build browser-ready Natural Earth assets into resources/public/natural-earth/.

Does not copy TIFFs or the SQLite. Requires Pillow.
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
ARCHIVE = ROOT / "data-for-future"
OUT = ROOT / "resources" / "public" / "natural-earth"
SQLITE = ARCHIVE / "packages" / "natural_earth_vector.sqlite"
HYP = ARCHIVE / "HYP_HR_SR_OB_DR" / "HYP_HR_SR_OB_DR.tif"

WKB_LINESTRING = 2
WKB_MULTILINESTRING = 5


def wkb_lines(blob: bytes) -> list[list[list[float]]]:
    endian = blob[0]
    fmt = "<" if endian == 1 else ">"
    typ = struct.unpack_from(fmt + "I", blob, 1)[0]
    if typ == WKB_LINESTRING:
        return [_read_line(blob, 5, fmt)[0]]
    if typ == WKB_MULTILINESTRING:
        n = struct.unpack_from(fmt + "I", blob, 5)[0]
        off = 9
        lines = []
        for _ in range(n):
            off += 1
            inner = struct.unpack_from(fmt + "I", blob, off)[0]
            off += 4
            if inner != WKB_LINESTRING:
                raise ValueError(f"unexpected inner WKB type {inner}")
            pts, off = _read_line(blob, off, fmt)
            lines.append(pts)
        return lines
    raise ValueError(f"unsupported WKB type {typ}")


def _read_line(blob: bytes, off: int, fmt: str) -> tuple[list[list[float]], int]:
    n = struct.unpack_from(fmt + "I", blob, off)[0]
    off += 4
    pts = []
    for _ in range(n):
        x, y = struct.unpack_from(fmt + "dd", blob, off)
        off += 16
        pts.append([x, y])
    return pts, off


def write_json(path: Path, obj) -> None:
    path.write_text(json.dumps(obj, separators=(",", ":")), encoding="utf-8")
    print(f"wrote {path.relative_to(ROOT)} ({path.stat().st_size} bytes)")


def extract_borders(con: sqlite3.Connection, table: str, dest: Path) -> None:
    features = []
    for fid, geom, name in con.execute(f"SELECT ogc_fid, GEOMETRY, name FROM {table}"):
        for i, coords in enumerate(wkb_lines(geom)):
            features.append(
                {
                    "type": "Feature",
                    "properties": {"id": f"{fid}-{i}", "name": name},
                    "geometry": {"type": "LineString", "coordinates": coords},
                }
            )
    write_json(dest, {"type": "FeatureCollection", "features": features})


def extract_labels(con: sqlite3.Connection, table: str, dest: Path) -> None:
    features = []
    for name, lng, lat, a3 in con.execute(
        f"SELECT name, label_x, label_y, adm0_a3 FROM {table} WHERE label_x IS NOT NULL"
    ):
        features.append(
            {
                "type": "Feature",
                "properties": {"name": name, "adm0_a3": a3},
                "geometry": {"type": "Point", "coordinates": [lng, lat]},
            }
        )
    write_json(dest, {"type": "FeatureCollection", "features": features})


def extract_vectors() -> None:
    if not SQLITE.exists():
        sys.exit(f"missing {SQLITE}")
    con = sqlite3.connect(SQLITE)
    extract_borders(con, "ne_110m_admin_0_boundary_lines_land", OUT / "ne_110m_admin_0_boundary_lines_land.json")
    extract_borders(con, "ne_50m_admin_0_boundary_lines_land", OUT / "ne_50m_admin_0_boundary_lines_land.json")
    extract_labels(con, "ne_110m_admin_0_countries", OUT / "ne_110m_admin_0_countries_labels.json")
    extract_labels(con, "ne_50m_admin_0_countries", OUT / "ne_50m_admin_0_countries_labels.json")
    extract_close_overlays(con, DATA / "natural-earth-overlays.json")
    con.close()


def _bbox(coords: list[list[float]]) -> dict:
    xs = [p[0] for p in coords]
    ys = [p[1] for p in coords]
    return {"west": min(xs), "south": min(ys), "east": max(xs), "north": max(ys)}


def extract_close_overlays(con: sqlite3.Connection, dest: Path) -> None:
    paths = []
    for fid, geom, name_l, name_r in con.execute(
        """
        SELECT ogc_fid, GEOMETRY, name_l, name_r
        FROM ne_10m_admin_1_states_provinces_lines
        WHERE name_l IS NOT NULL AND name_r IS NOT NULL
        """
    ):
        if geom is None:
            continue
        for i, coords in enumerate(wkb_lines(geom)):
            paths.append(
                {
                    "id": f"adm1-{fid}-{i}",
                    "kind": "adm1-borders",
                    "bbox": _bbox(coords),
                    "coords": [[lat, lng] for lng, lat in coords],
                }
            )
    labels = []
    for code, name, lat, lng, lrank, area in con.execute(
        """
        SELECT adm1_code, name, latitude, longitude, labelrank, area_sqkm
        FROM ne_10m_admin_1_states_provinces
        WHERE latitude IS NOT NULL AND longitude IS NOT NULL
        """
    ):
        labels.append(
            {
                "id": code,
                "kind": "adm1-names",
                "bbox": {"west": lng, "south": lat, "east": lng, "north": lat},
                "text": name,
                "lat": lat,
                "lng": lng,
                "labelrank": lrank,
                "area-sqkm": area or 0,
            }
        )
    for ne_id, name, lng, lat, pop in con.execute(
        """
        SELECT ne_id, name, longitude, latitude, pop_max
        FROM ne_10m_populated_places_simple
        WHERE pop_max IS NOT NULL AND pop_max > 0
        """
    ):
        labels.append(
            {
                "id": f"city-{ne_id}",
                "kind": "cities",
                "bbox": {"west": lng, "south": lat, "east": lng, "north": lat},
                "text": name,
                "lat": lat,
                "lng": lng,
                "pop-max": pop,
            }
        )
    dest.parent.mkdir(parents=True, exist_ok=True)
    write_json(dest, {"paths": paths, "labels": labels})


def save_webp(im, dest: Path, size: tuple[int, int], **kwargs) -> None:
    from PIL import Image

    out = im.resize(size, Image.Resampling.LANCZOS)
    out.save(dest, "WEBP", quality=80, method=4)
    print(f"wrote {dest.relative_to(ROOT)} ({dest.stat().st_size / 1024 / 1024:.1f} MB) {size[0]}x{size[1]}")


def convert_rasters() -> None:
    from PIL import Image

    Image.MAX_IMAGE_PIXELS = None
    if not HYP.exists():
        sys.exit(f"missing {HYP}")

    print("opening", HYP)
    with Image.open(HYP) as hyp:
        hyp.load()
        rgb = hyp.convert("RGB")
    save_webp(rgb, OUT / "ne-hyp-sr-ob-dr-8k.webp", (8192, 4096))


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--rasters-only", action="store_true")
    p.add_argument("--vectors-only", action="store_true")
    args = p.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    if not args.rasters_only:
        extract_vectors()
    if not args.vectors_only:
        convert_rasters()


if __name__ == "__main__":
    main()
