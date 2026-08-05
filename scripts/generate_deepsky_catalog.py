#!/usr/bin/env python3
"""Build the offline deep-sky catalog asset from OpenNGC.

Usage:
    python scripts/generate_deepsky_catalog.py

Downloads the OpenNGC database (CC-BY-SA-4.0) and writes a trimmed,
pipe-separated table to app/src/main/assets/catalog/deepsky.csv:

    id|type|raHours|decDeg|vmag|sizeArcmin|name1;name2;...

Rows the app cannot point at (duplicates, non-existent entries) are dropped.
"""

from __future__ import annotations

import csv
import io
import os
import re
import sys
import urllib.request

BASE_URL = "https://raw.githubusercontent.com/mattiaverga/OpenNGC/master/database_files"
SOURCES = ("NGC.csv", "addendum.csv")
OUTPUT = os.path.join("app", "src", "main", "assets", "catalog", "deepsky.csv")

SKIPPED_TYPES = {"Dup", "NonEx"}

TYPE_LABELS = {
    "*": "Star",
    "**": "Double Star",
    "*Ass": "Star Association",
    "OCl": "Open Cluster",
    "GCl": "Globular Cluster",
    "Cl+N": "Cluster + Nebula",
    "G": "Galaxy",
    "GPair": "Galaxy Pair",
    "GTrpl": "Galaxy Triplet",
    "GGroup": "Galaxy Group",
    "PN": "Planetary Nebula",
    "HII": "HII Region",
    "DrkN": "Dark Nebula",
    "EmN": "Emission Nebula",
    "Neb": "Nebula",
    "RfN": "Reflection Nebula",
    "SNR": "Supernova Remnant",
    "Nova": "Nova",
    "Other": "Other",
}

# Chinese aliases for the objects observers actually search for. OpenNGC only
# ships English common names, and this app's users type Chinese.
CHINESE_ALIASES = {
    "M 1": "蟹状星云",
    "M 2": "宝瓶座球状星团",
    "M 3": "猎犬座球状星团",
    "M 4": "天蝎座球状星团",
    "M 6": "蝴蝶星团",
    "M 7": "托勒密星团",
    "M 8": "礁湖星云",
    "M 11": "野鸭星团",
    "M 13": "武仙座大球状星团",
    "M 16": "鹰状星云",
    "M 17": "天鹅星云",
    "M 20": "三叶星云",
    "M 22": "人马座球状星团",
    "M 27": "哑铃星云",
    "M 31": "仙女座星系",
    "M 32": "仙女座矮星系",
    "M 33": "三角座星系",
    "M 42": "猎户座大星云",
    "M 43": "猎户座小星云",
    "M 44": "蜂巢星团",
    "M 45": "昴星团",
    "M 51": "涡状星系",
    "M 57": "环状星云",
    "M 63": "向日葵星系",
    "M 64": "黑眼星系",
    "M 76": "小哑铃星云",
    "M 78": "猎户座反射星云",
    "M 81": "波德星系",
    "M 82": "雪茄星系",
    "M 83": "南风车星系",
    "M 87": "室女A星系",
    "M 92": "武仙座球状星团",
    "M 97": "枭状星云",
    "M 101": "风车星系",
    "M 104": "草帽星系",
    "M 106": "猎犬座星系",
    "M 110": "仙女座伴星系",
    "NGC 253": "玉夫座星系",
    "NGC 281": "小精灵星云",
    "NGC 869": "英仙双星团",
    "NGC 884": "英仙双星团",
    "NGC 1499": "加州星云",
    "NGC 2070": "蜘蛛星云",
    "NGC 2237": "玫瑰星云",
    "NGC 2264": "圣诞树星团",
    "NGC 2359": "雷神头盔",
    "NGC 3372": "船底座星云",
    "NGC 5128": "半人马A",
    "NGC 6888": "新月星云",
    "NGC 6960": "面纱星云",
    "NGC 6992": "东面纱星云",
    "NGC 7000": "北美洲星云",
    "NGC 7293": "螺旋星云",
    "NGC 7380": "巫师星云",
    "NGC 7635": "气泡星云",
    "IC 405": "火焰星星云",
    "IC 1396": "象鼻星云",
    "IC 1805": "心脏星云",
    "IC 1848": "灵魂星云",
    "IC 5070": "鹈鹕星云",
    "B 33": "马头星云",
}


def fetch(name: str) -> list[dict]:
    url = f"{BASE_URL}/{name}"
    with urllib.request.urlopen(url, timeout=120) as response:
        text = response.read().decode("utf-8")
    return list(csv.DictReader(io.StringIO(text), delimiter=";"))


NAME_PATTERN = re.compile(r"^([A-Za-z]+)0*(\d+)([A-Za-z]?)$")


def normalize_name(raw: str) -> str:
    """`NGC0224` -> `NGC 224`, `IC0001` -> `IC 1`, `Mel022` -> `Mel 22`.

    Component designations such as `NGC0080 NED01` are left untouched.
    """
    match = NAME_PATTERN.match(raw.strip())
    if not match:
        return raw.strip()
    prefix, digits, suffix = match.groups()
    return f"{prefix} {int(digits)}{suffix}"


def parse_ra_hours(text: str) -> float | None:
    parts = text.split(":")
    if len(parts) != 3:
        return None
    hours, minutes, seconds = (float(p) for p in parts)
    return hours + minutes / 60.0 + seconds / 3600.0


def parse_dec_degrees(text: str) -> float | None:
    text = text.strip()
    parts = text.split(":")
    if len(parts) != 3:
        return None
    sign = -1.0 if parts[0].strip().startswith("-") else 1.0
    degrees, minutes, seconds = (abs(float(p)) for p in parts)
    return sign * (degrees + minutes / 60.0 + seconds / 3600.0)


def build_rows(records: list[dict]) -> list[str]:
    rows = []
    for record in records:
        if record.get("Type") in SKIPPED_TYPES:
            continue
        ra = parse_ra_hours(record.get("RA", ""))
        dec = parse_dec_degrees(record.get("Dec", ""))
        if ra is None or dec is None:
            continue

        catalog_name = normalize_name(record.get("Name", ""))
        names = []
        messier = record.get("M", "").strip()
        if messier:
            names.append(f"M {int(messier)}")
        if catalog_name:
            names.append(catalog_name)
        ngc = record.get("NGC", "").strip()
        if ngc and ngc.isdigit():
            names.append(f"NGC {int(ngc)}")
        ic = record.get("IC", "").strip()
        if ic and ic.isdigit():
            names.append(f"IC {int(ic)}")
        for common in record.get("Common names", "").split(","):
            common = common.strip()
            if common:
                names.append(common)
        if not names:
            continue

        identifier = names[0]
        chinese = CHINESE_ALIASES.get(identifier)
        if chinese:
            names.append(chinese)

        deduped = []
        for name in names:
            if name not in deduped:
                deduped.append(name)

        magnitude = record.get("V-Mag", "").strip() or record.get("B-Mag", "").strip()
        size = record.get("MajAx", "").strip()
        rows.append(
            "|".join(
                [
                    identifier,
                    TYPE_LABELS.get(record.get("Type", ""), record.get("Type", "")),
                    f"{ra:.6f}",
                    f"{dec:.6f}",
                    magnitude,
                    size,
                    ";".join(deduped),
                ]
            )
        )
    return rows


def main() -> int:
    records: list[dict] = []
    for source in SOURCES:
        print(f"downloading {source} ...")
        records.extend(fetch(source))
    print(f"{len(records)} source records")

    rows = build_rows(records)
    rows.sort()
    print(f"{len(rows)} catalog entries")

    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
    with io.open(OUTPUT, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("# OpenNGC subset, CC-BY-SA-4.0. Generated by scripts/generate_deepsky_catalog.py\n")
        handle.write("# id|type|raHours|decDeg|vmag|sizeArcmin|names\n")
        for row in rows:
            handle.write(row + "\n")
    size_kb = os.path.getsize(OUTPUT) / 1024
    print(f"wrote {OUTPUT} ({size_kb:.1f} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
