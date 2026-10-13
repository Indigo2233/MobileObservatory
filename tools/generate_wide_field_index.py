#!/usr/bin/env python3
"""Reproduce the app's bright-neighbour triangles; no network or third-party packages."""
import argparse
import collections
import csv
import hashlib
import io
import json
import math
from pathlib import Path
import struct
import sys

VERSION = 1
MAGIC = 0x57464958
STEP = 0.35
ANCHOR_MAGNITUDE = 4.5
BUCKETS = (("WF_A", 50.0, 12), ("WF_B", 32.0, 10), ("WF_C", 22.0, 8))


def read_catalog(path):
    text = path.read_text(encoding="utf-8-sig")
    stars = []
    for row in csv.DictReader(line for line in text.splitlines() if line and not line.startswith("#")):
        star = (float(row["ra_deg"]), float(row["dec_deg"]), float(row["mag"]), int(row["hip"]) if row["hip"] else -1)
        if not all(math.isfinite(value) for value in star[:3]) or not (0 <= star[0] < 360 and -90 <= star[1] <= 90):
            raise ValueError("Invalid catalog coordinates")
        stars.append(star)
    if not stars:
        raise ValueError("Empty catalog")
    # The checked-in catalog already contains Sirius. Refuse ambiguous historical input.
    if len(stars) >= 100 and not any(star[3] == 32349 for star in stars):
        raise ValueError("Regenerate the current catalog with Sirius before making its index")
    return stars


def fingerprint(stars):
    digest = hashlib.sha256(struct.pack(">i", len(stars)))
    for star in stars:
        digest.update(struct.pack(">dddi", *star))
    return digest.digest()


def generate(stars, ordinal):
    name, max_side, neighbours = BUCKETS[ordinal]
    vectors = []
    for ra, dec, _, _ in stars:
        ra, dec = math.radians(ra), math.radians(dec)
        vectors.append((math.cos(dec) * math.cos(ra), math.cos(dec) * math.sin(ra), math.sin(dec)))
    anchors = [i for i, star in enumerate(stars) if star[2] <= ANCHOR_MAGNITUDE]

    def distance(a, b):
        return math.acos(max(-1.0, min(1.0, sum(x * y for x, y in zip(vectors[a], vectors[b])))))

    rows = []
    for anchor in anchors:
        near = sorted((i for i in anchors if i != anchor), key=lambda i: distance(anchor, i))[:neighbours]
        for i in range(len(near) - 1):
            for j in range(i + 1, len(near)):
                b, c = near[i], near[j]
                sides = sorted((distance(anchor, b), distance(anchor, c), distance(b, c)))
                if sides[2] > math.radians(max_side):
                    continue
                rows.append(tuple(int(math.degrees(side) / STEP) for side in sides) + (anchor, b, c))
    rows.sort(key=lambda row: row[:3])  # Python sort is stable, matching Kotlin's stable key sort.
    payload = b"".join(struct.pack(">iiiiii", *row) for row in rows)
    header = struct.pack(">iii", MAGIC, VERSION, len(stars)) + fingerprint(stars)
    header += struct.pack(">ididi", ordinal, max_side, neighbours, STEP, len(rows))
    header += hashlib.sha256(payload).digest()
    assert len(header) == 104
    counts = collections.Counter(row[:3] for row in rows)
    metadata = {
        "bucket": name, "maxSideDeg": max_side, "neighbourCount": neighbours,
        "anchorCount": len(anchors), "recordCount": len(rows), "uniqueKeys": len(counts),
        "keysWithMultipleRows": sum(count > 1 for count in counts.values()),
        "extraRowsOnSharedKeys": sum(count - 1 for count in counts.values()),
        "maxRowsPerKey": max(counts.values(), default=0),
        "uniqueUnorderedTriangles": len({tuple(sorted(row[3:])) for row in rows}),
        "bytes": len(header) + len(payload), "residentArrayBytes": len(rows) * 20,
        "payloadSha256": hashlib.sha256(payload).hexdigest(),
        "fileSha256": hashlib.sha256(header + payload).hexdigest(),
    }
    return header + payload, metadata


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=Path("app/src/main/assets/catalog/phone_hyg_v41_m6.csv"))
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/catalog/wf-index"))
    parser.add_argument("--check", action="store_true", help="Verify generated resources without replacing any file")
    args = parser.parse_args(argv)
    stars = read_catalog(args.catalog)
    manifest = {
        "formatVersion": VERSION, "source": "HYG Database v4.1", "license": "CC BY-SA 4.0; see THIRD_PARTY_HYG.txt",
        "catalogCsvSha256": hashlib.sha256(args.catalog.read_bytes()).hexdigest(),
        "catalogGeometrySha256": fingerprint(stars).hex(), "catalogStars": len(stars),
        "anchorMaximumMagnitude": ANCHOR_MAGNITUDE, "keyStepDeg": STEP, "buckets": [],
    }
    if not args.check:
        args.output.mkdir(parents=True, exist_ok=True)
    for ordinal, (name, _, _) in enumerate(BUCKETS):
        data, stats = generate(stars, ordinal)
        path = args.output / (name.lower() + ".wfidx")
        stats["file"] = path.name
        manifest["buckets"].append(stats)
        if args.check:
            if not path.is_file() or path.read_bytes() != data:
                raise ValueError(f"Index is not reproducible: {path}")
        else:
            path.write_bytes(data)
        print(f"{name}: {stats['recordCount']} rows, {stats['bytes']} bytes, {stats['uniqueKeys']} keys")
    encoded = (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    path = args.output / "manifest.json"
    if args.check:
        if not path.is_file() or path.read_bytes() != encoded:
            raise ValueError(f"Manifest is not reproducible: {path}")
    else:
        path.write_bytes(encoded)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError) as failure:
        print(f"Index generation failed: {failure}", file=sys.stderr)
        sys.exit(1)
