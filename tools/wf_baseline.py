#!/usr/bin/env python3
"""Private WF evidence collection and honest, de-identified summaries (standard library only)."""
import argparse
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import subprocess
import sys
import time


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"), parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Nonfinite JSON: " + value)))


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def angle(ra1, dec1, ra2, dec2):
    ra1, dec1, ra2, dec2 = map(math.radians, (ra1, dec1, ra2, dec2))
    value = math.sin((dec1 - dec2) / 2) ** 2 + math.cos(dec1) * math.cos(dec2) * math.sin((ra1 - ra2) / 2) ** 2
    return math.degrees(2 * math.asin(math.sqrt(max(0.0, min(1.0, value)))))


def distribution(values):
    values = sorted(value for value in values if number(value))
    if not values:
        return {"count": 0, "median": None, "p95": None, "max": None}
    return {"count": len(values), "median": statistics.median(values),
            "p95": values[max(0, math.ceil(len(values) * .95) - 1)], "max": values[-1]}


def read_events(path):
    events = []
    with path.open(encoding="utf-8-sig") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            event = json.loads(line, parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Nonfinite event")))
            if event.get("schema") != 1:
                raise ValueError(f"Unsupported event schema at line {line_number}")
            events.append(event)
    return events


def read_fits_header(path):
    header = {}
    with path.open("rb") as stream:
        for _ in range(128):
            block = stream.read(2880)
            if len(block) != 2880:
                raise ValueError("Truncated FITS header")
            for offset in range(0, 2880, 80):
                card = block[offset:offset + 80].decode("ascii", errors="strict")
                key = card[:8].strip()
                if key == "END":
                    return header
                if card[8:10] != "= ":
                    continue
                token = card[10:]
                if token.lstrip().startswith("'"):
                    value = token.lstrip()[1:].split("'", 1)[0].strip()
                else:
                    value = token.split("/", 1)[0].strip()
                    try:
                        value = float(value.replace("D", "E"))
                    except ValueError:
                        pass
                header[key] = value
    raise ValueError("FITS header exceeds supported bound")


def wcs_reference(path, width, height):
    header = read_fits_header(path)
    if not str(header.get("CTYPE1", "")).startswith("RA---TAN") or not str(header.get("CTYPE2", "")).startswith("DEC--TAN"):
        raise ValueError("Reference must use RA/Dec TAN WCS")
    frame = str(header.get("RADESYS", header.get("RADECSYS", ""))).upper()
    equinox = header.get("EQUINOX")
    if frame != "ICRS" and not (frame == "FK5" and equinox == 2000):
        raise ValueError("Reference WCS frame is not explicitly ICRS/J2000")
    if header.get("NAXIS1", width) != width or header.get("NAXIS2", height) != height:
        raise ValueError("Reference image dimensions do not match")
    required = ("CRVAL1", "CRVAL2", "CRPIX1", "CRPIX2")
    if not all(number(header.get(key)) for key in required):
        raise ValueError("Missing reference WCS coordinates")
    if "CD1_1" in header:
        cd = [header.get(key) for key in ("CD1_1", "CD1_2", "CD2_1", "CD2_2")]
    else:
        cd = [header.get("CDELT1", float("nan")) * header.get("PC1_1", 1),
              header.get("CDELT1", float("nan")) * header.get("PC1_2", 0),
              header.get("CDELT2", float("nan")) * header.get("PC2_1", 0),
              header.get("CDELT2", float("nan")) * header.get("PC2_2", 1)]
    if not all(number(value) for value in cd) or abs(cd[0] * cd[3] - cd[1] * cd[2]) < 1e-15:
        raise ValueError("Invalid reference WCS matrix")
    ra0, dec0 = math.radians(header["CRVAL1"]), math.radians(header["CRVAL2"])
    center = (math.cos(dec0) * math.cos(ra0), math.cos(dec0) * math.sin(ra0), math.sin(dec0))
    east = (-math.sin(ra0), math.cos(ra0), 0)
    north = (-math.cos(ra0) * math.sin(dec0), -math.sin(ra0) * math.sin(dec0), math.cos(dec0))

    def sky(x, y):
        dx, dy = x - header["CRPIX1"], y - header["CRPIX2"]
        if "-SIP" in str(header.get("CTYPE1")):
            def correction(prefix):
                order = header.get(prefix + "_ORDER")
                if not number(order) or not 0 <= order <= 9:
                    raise ValueError("Missing or unbounded SIP order")
                return sum(header.get(f"{prefix}_{i}_{j}", 0) * dx ** i * dy ** j
                           for i in range(int(order) + 1) for j in range(int(order) + 1 - i))
            dx, dy = dx + correction("A"), dy + correction("B")
        tx, ty = math.radians(cd[0] * dx + cd[1] * dy), math.radians(cd[2] * dx + cd[3] * dy)
        vector = [center[i] + east[i] * tx + north[i] * ty for i in range(3)]
        length = math.sqrt(sum(value * value for value in vector))
        vector = [value / length for value in vector]
        return math.degrees(math.atan2(vector[1], vector[0])) % 360, math.degrees(math.asin(max(-1.0, min(1.0, vector[2]))))

    # WF extraction uses zero-based (w/2,h/2), rows increasing downward. FITS is one-based.
    x, y = width / 2 + 1, height / 2 + 1
    ra, dec = sky(x, y)
    up_ra, up_dec = sky(x, y - .5)
    rr, dd, ur, ud = map(math.radians, (ra, dec, up_ra, up_dec))
    position_angle = math.degrees(math.atan2(math.sin(ur - rr) * math.cos(ud),
        math.cos(dd) * math.sin(ud) - math.sin(dd) * math.cos(ud) * math.cos(ur - rr))) % 360
    left, right = sky(x - width / 2, y), sky(x + width / 2, y)
    bottom, top = sky(x, y + height / 2), sky(x, y - height / 2)
    return {"raDeg": ra, "decDeg": dec, "rotationDeg": position_angle,
            "fovWidthDeg": angle(*left, *right), "fovHeightDeg": angle(*bottom, *top)}


def performance(events):
    elapsed = [event.get("elapsedMs") for event in events]
    cold, warm = [], []
    allocations, gc_pauses, blocking_pauses = [], [], []
    previous = {}
    for event in events:
        diagnostics = event.get("diagnostics", {})
        (cold if diagnostics.get("indexLoads") else warm).append(event.get("elapsedMs"))
        metrics = event.get("metrics", {})
        session = event.get("sessionId")
        old = previous.get(session)
        if old and number(event.get("elapsedRealtimeNanos")) and number(old.get("elapsedRealtimeNanos")):
            seconds = (event["elapsedRealtimeNanos"] - old["elapsedRealtimeNanos"]) / 1e9
            if seconds > 0:
                before = old.get("metrics", {})
                for key, values, divide in (("bytesAllocatedCounter", allocations, seconds),
                    ("gcTimeMillis", gc_pauses, 1), ("blockingGcTimeMillis", blocking_pauses, 1)):
                    if number(metrics.get(key)) and number(before.get(key)) and metrics[key] >= before[key]:
                        values.append((metrics[key] - before[key]) / divide)
        previous[session] = event
    return {"solverElapsedMs": distribution(elapsed), "withIndexLoadsMs": distribution(cold),
        "withoutIndexLoadsMs": distribution(warm), "observedAllocationBytesPerSecond": distribution(allocations),
        "gcTimeDeltaBetweenSolvesMs": distribution(gc_pauses), "blockingGcTimeDeltaBetweenSolvesMs": distribution(blocking_pauses),
        "observedJavaHeapBytes": distribution([event.get("metrics", {}).get("javaHeapUsedBytes") for event in events]),
        "observedNativeHeapBytes": distribution([event.get("metrics", {}).get("nativeHeapAllocatedBytes") for event in events]),
        "batteryTemperatureCelsius": distribution([event.get("metrics", {}).get("batteryTemperatureCelsius") for event in events]),
        "captureElapsedMs": distribution([event.get("capture", {}).get("captureLatencyMs") for event in events]),
        "extractionElapsedMs": distribution([event.get("capture", {}).get("extractionElapsedNanos", 0) / 1e6
            for event in events if number(event.get("capture", {}).get("extractionElapsedNanos"))]),
        "queueDropRate": None,
        "limitations": ["Sampled heap maxima are not a continuous process high-water mark.",
            "ART time deltas are cumulative intervals, not individual GC pause percentiles.",
            "Allocation deltas include all app work between solves and cannot isolate the solver.",
            "No frame-queue drop counter is supplied; queue drop rate stays unknown."]}


def summarize(manifest_path, events):
    manifest = load_json(manifest_path)
    if manifest.get("schema") != 1:
        raise ValueError("Unsupported evidence manifest schema")
    base = manifest_path.parent
    thresholds = {"centerDeg": .5, "rotationDeg": 2, "fovRelative": .10}
    thresholds.update(manifest.get("mismatchThresholds", {}))
    for key, maximum in (("centerDeg", 1), ("rotationDeg", 5), ("fovRelative", .15)):
        if not number(thresholds.get(key)) or not 0 < thresholds[key] <= maximum:
            raise ValueError("Invalid or unbounded mismatch threshold: " + key)
    eligible, rejected, mismatches, errors = [], [], [], []
    hashes, groups = set(), set()
    conditions = {(place, moon): 0 for place in ("urban", "suburban") for moon in (True, False)}
    focal = {value: 0 for value in (14, 24, 35, 50)}
    index = {}
    for event in events:
        digest = event.get("capture", {}).get("imageSha256")
        if digest:
            index.setdefault(digest, []).append(event)
    for sample in manifest.get("samples", []):
        sid = sample.get("sampleId", "unnamed")
        reasons = []
        try:
            if sample.get("evidenceType") != "REAL_CAPTURE": reasons.append("not_attested_real_capture")
            if not sample.get("testedRevision"): reasons.append("missing_tested_revision")
            group = sample.get("captureGroupId")
            if not group or group in groups: reasons.append("missing_or_repeated_capture_group")
            image = base / sample.get("image", "")
            if not image.is_file(): raise ValueError("missing_image")
            digest = sha(image)
            if digest in hashes: reasons.append("repeated_image_hash")
            matches = index.get(digest, [])
            selection = sample.get("probeEvent") or {}
            matches = [event for event in matches if all(event.get(key) == value for key, value in selection.items())]
            if len(matches) != 1: raise ValueError("image_has_no_unique_probe_event")
            event = matches[0]
            device = event.get("device", {})
            if device.get("isEmulator") is not False: reasons.append("emulator_or_unknown_device")
            reference = sample.get("reference") or {}
            if not isinstance(reference, dict): raise ValueError("invalid_reference_record")
            if reference.get("independent") is not True or reference.get("source") not in ("ASTAP_W08", "ASTROMETRY_NET"):
                reasons.append("missing_independent_optical_reference")
            if not reference.get("version") or reference.get("forImageSha256") != digest:
                reasons.append("unbound_reference_identity")
            artifact_name = reference.get("artifact")
            artifact = base / artifact_name if isinstance(artifact_name, str) and artifact_name else None
            if artifact is None or not artifact.is_file() or sha(artifact) != reference.get("artifactSha256"):
                reasons.append("missing_or_changed_reference_artifact")
            if sample.get("imageYAxis") != "DOWN": reasons.append("unconfirmed_image_axes")
            capture = event.get("capture", {})
            if not capture.get("logicalCameraId") or not number(capture.get("exposureNs")):
                reasons.append("missing_capture_metadata")
            sky = sample.get("sky") or {}
            if sky.get("place") not in ("urban", "suburban") or not isinstance(sky.get("moon"), bool): reasons.append("missing_sky_conditions")
            equivalent = sample.get("equivalentFocalLengthMm", capture.get("equivalentFocalLengthMm"))
            if not number(equivalent) or equivalent <= 0: reasons.append("missing_equivalent_focal_length")
            if reasons:
                rejected.append({"sampleId": sid, "reasons": reasons}); continue
            if artifact.suffix.lower() in (".wcs", ".fits", ".fit"):
                truth = wcs_reference(artifact, event["frameWidth"], event["frameHeight"])
            else:
                payload = load_json(artifact)
                if payload.get("frame") != "J2000" or payload.get("imageYAxis") != "DOWN": raise ValueError("reference_frame_or_axes")
                truth = {key: payload.get(key) for key in ("raDeg", "decDeg", "rotationDeg", "fovWidthDeg", "fovHeightDeg")}
            if not all(number(value) for value in truth.values()) or not (-90 <= truth["decDeg"] <= 90): raise ValueError("invalid_reference_coordinates")
            if not (0 <= truth["raDeg"] < 360 and 0 < truth["fovWidthDeg"] < 180 and 0 < truth["fovHeightDeg"] < 180): raise ValueError("invalid_reference_geometry")
            if not isinstance(event.get("success"), bool): raise ValueError("invalid_solver_outcome")
            if event.get("success") is True:
                if not all(number(event.get(key)) for key in truth): raise ValueError("invalid_accepted_solution")
                measured = {"sampleId": sid,
                    "centerErrorDeg": angle(event["raDeg"], event["decDeg"], truth["raDeg"], truth["decDeg"]),
                    "rotationErrorDeg": abs((event["rotationDeg"] - truth["rotationDeg"] + 180) % 360 - 180),
                    "fovRelativeError": max(abs(event[key] / truth[key] - 1) for key in ("fovWidthDeg", "fovHeightDeg"))}
                errors.append(measured)
                if measured["centerErrorDeg"] > thresholds["centerDeg"] or measured["rotationErrorDeg"] > thresholds["rotationDeg"] or measured["fovRelativeError"] > thresholds["fovRelative"]:
                    mismatches.append(sid)
            hashes.add(digest); groups.add(group); eligible.append(event)
            conditions[(sky["place"], sky["moon"])] += 1
            nearest = min(focal, key=lambda value: abs(value - equivalent))
            if abs(nearest - equivalent) <= 4: focal[nearest] += 1
        except (ValueError, OSError, KeyError, TypeError) as failure:
            rejected.append({"sampleId": sid, "reasons": [str(failure)]})
    missing = []
    if len(groups) < 30: missing.append(f"independent_real_capture_groups:{len(groups)}/30")
    for (place, moon), count in conditions.items():
        if count < 6: missing.append(f"sky_coverage:{place}/moon={moon}:{count}/6")
    for value, count in focal.items():
        if count == 0: missing.append(f"focal_coverage:{value}mm")
    if not any(event.get("capture", {}).get("exposureNs", float("inf")) <= 500_000_000 for event in eligible):
        missing.append("limited_0.5_second_capture_case")
    accepted = sum(event.get("success") is True for event in eligible)
    return {"schema": 1, "status": "missing_evidence" if missing else "failed" if mismatches or rejected else "passed",
        "eligibleRealGroups": len(groups), "acceptedSolutions": accepted, "acceptedMismatches": len(mismatches),
        "successRate": accepted / len(eligible) if eligible else None,
        "acceptedMismatchRate": len(mismatches) / accepted if accepted else None,
        "successRateScope": "eligible_bound_solver_attempts; pre-solver capture/motion failures are not observed",
        "mismatchThresholds": thresholds,
        "missingEvidence": missing, "rejectedSamples": rejected, "mismatchSampleIds": mismatches,
        "centerErrorDeg": distribution([value["centerErrorDeg"] for value in errors]),
        "rotationErrorDeg": distribution([value["rotationErrorDeg"] for value in errors]),
        "fovRelativeError": distribution([value["fovRelativeError"] for value in errors]),
        "performanceAllProbeEvents": performance(events),
        "limitations": ["Reference independence is an explicit evidence attestation bound to an artifact and input hash.",
            "A probe result or synthetic fixture alone does not verify the telescope boresight.",
            "No novice observations or 3-hour endurance claim is produced by this report."]}


def adb_command(args, *command):
    if not re.fullmatch(r"[A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)+", args.package):
        raise ValueError("Invalid package ID")
    return subprocess.run([args.adb, "-s", args.serial, *command], capture_output=True, check=True, timeout=30)


def probe_control(args):
    if args.command == "enable":
        adb_command(args, "shell", "run-as", args.package, "mkdir", "-p", "files/wf-baseline")
        adb_command(args, "shell", "run-as", args.package, "touch", "files/wf-baseline/enabled")
    else:
        adb_command(args, "shell", "run-as", args.package, "rm", "-f", "files/wf-baseline/enabled")
    print("Probe " + args.command + "d on explicitly selected " + args.serial)


def export(args):
    args.output.mkdir(parents=True, exist_ok=True)
    count = 0; events = []
    for name in ("solver-events.previous.jsonl", "solver-events.jsonl"):
        try:
            result = adb_command(args, "exec-out", "run-as", args.package, "cat", "files/wf-baseline/" + name)
            (args.output / name).write_bytes(result.stdout)
            loaded = read_events(args.output / name)
            count += len(loaded); events.extend(loaded)
        except subprocess.CalledProcessError:
            pass
    exported_images, unavailable = [], []
    if args.include_images:
        for name in sorted({event.get("capture", {}).get("imageName") for event in events if event.get("capture", {}).get("imageName")}):
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,180}", name):
                unavailable.append(name); continue
            remote = f"/sdcard/Android/data/{args.package}/files/phone_sky_m0/{name}"
            try:
                data = adb_command(args, "exec-out", "run-as", args.package, "cat", remote).stdout
                path = args.output / name
                if path.exists() and path.read_bytes() != data:
                    unavailable.append(name + ": name_collision"); continue
                path.write_bytes(data); exported_images.append(name)
            except subprocess.CalledProcessError:
                unavailable.append(name)
    write_json(args.output / "export-status.json", {"schema": 1, "probeEvents": count, "deviceSerial": args.serial,
        "status": "exported" if count else "missing_probe_events", "realCaptureGroupsVerified": 0,
        "privateImagesExported": len(exported_images), "unavailableOrChangedImages": unavailable})
    print(f"Exported {count} events; no reference WCS or real-capture gate has been assumed.")


def scaffold(args):
    events = read_events(args.events)
    samples = []
    seen = set()
    for event in events:
        capture = event.get("capture", {})
        digest = capture.get("imageSha256")
        name = capture.get("imageName")
        if not digest or not name or digest in seen:
            continue
        seen.add(digest)
        samples.append({"sampleId": f"sample-{len(samples) + 1:03}", "captureGroupId": None,
            "evidenceType": "UNVERIFIED", "testedRevision": None, "image": name,
            "imageYAxis": "DOWN", "equivalentFocalLengthMm": capture.get("equivalentFocalLengthMm"),
            "sky": {"place": None, "moon": None}, "reference": {"source": None, "version": None,
                "independent": False, "forImageSha256": digest, "artifact": None, "artifactSha256": None}})
    write_json(args.output, {"schema": 1, "mismatchThresholds": {"centerDeg": .5, "rotationDeg": 2, "fovRelative": .10}, "samples": samples})
    print(f"Prepared {len(samples)} unverified rows. Add private images and independent reference artifacts.")


def resource_sample(args):
    if args.duration <= 0 or args.interval <= 0:
        raise ValueError("Duration and interval must be positive")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    qemu = adb_command(args, "shell", "getprop", "ro.kernel.qemu").stdout.decode().strip()
    model = adb_command(args, "shell", "getprop", "ro.product.model").stdout.decode().strip()
    fingerprint = adb_command(args, "shell", "getprop", "ro.build.fingerprint").stdout.decode().strip()
    emulator = qemu == "1" or "sdk" in model.lower() or fingerprint.startswith("generic") or "emulator" in fingerprint
    started = time.monotonic()
    with args.output.open("w", encoding="utf-8") as stream:
        while time.monotonic() - started < args.duration:
            battery = adb_command(args, "shell", "dumpsys", "battery").stdout.decode(errors="replace")
            memory = adb_command(args, "shell", "dumpsys", "meminfo", args.package).stdout.decode(errors="replace")
            try:
                thermal = adb_command(args, "shell", "dumpsys", "thermalservice").stdout.decode(errors="replace")
            except subprocess.CalledProcessError:
                thermal = ""
            def integer(pattern, text):
                match = re.search(pattern, text, re.I)
                return int(match.group(1)) if match else None
            temperature = integer(r"temperature:\s*(-?\d+)", battery)
            sensors = [{"celsius": float(value), "type": int(kind), "name": name.strip()}
                for value, kind, name in re.findall(r"mValue=([-\d.]+),\s*mType=(\d+),\s*mName=([^,}]+)", thermal)]
            powers = [re.search(label + r" powered:\s*(true|false)", battery, re.I) for label in ("AC", "USB", "Wireless")]
            external_power = any(match.group(1).lower() == "true" for match in powers) if all(powers) else None
            event = {"schema": 1, "kind": "resource_sample", "elapsedSeconds": time.monotonic() - started,
                "isEmulator": emulator if model and fingerprint else None, "deviceModel": model,
                "deviceFingerprint": fingerprint, "requestedIntervalSeconds": args.interval,
                "phaseAnnotation": args.phase,
                "batteryChargeMicroampHours": integer(r"charge counter:\s*(-?\d+)", battery),
                "batteryLevel": integer(r"level:\s*(\d+)", battery),
                "batteryScale": integer(r"scale:\s*(\d+)", battery),
                "batteryTemperatureCelsius": temperature / 10 if temperature is not None else None,
                "usbPowered": powers[1].group(1).lower() == "true" if powers[1] else None,
                "externallyPowered": external_power,
                "batteryVoltageMillivolts": integer(r"voltage:\s*(\d+)", battery), "thermalSensors": sensors,
                "totalPssBytes": (integer(r"TOTAL PSS:\s*(\d+)", memory) or 0) * 1024 if "TOTAL PSS:" in memory else None,
                "totalRssBytes": (integer(r"TOTAL RSS:\s*(\d+)", memory) or 0) * 1024 if "TOTAL RSS:" in memory else None}
            stream.write(json.dumps(event, allow_nan=False) + "\n"); stream.flush()
            time.sleep(min(args.interval, max(0, args.duration - (time.monotonic() - started))))
    print("Recorded observed system samples; emulator/USB-powered data does not establish phone endurance.")


def summarize_resources(samples, minimum_duration=10800):
    if any(sample.get("kind") != "resource_sample" or not number(sample.get("elapsedSeconds")) for sample in samples):
        raise ValueError("Resource summary requires sampler records")
    duration = samples[-1]["elapsedSeconds"] - samples[0]["elapsedSeconds"] if len(samples) > 1 else 0
    physical = bool(samples) and all(sample.get("isEmulator") is False for sample in samples)
    unplugged = bool(samples) and all(sample.get("externallyPowered") is False for sample in samples)
    gaps = [second["elapsedSeconds"] - first["elapsedSeconds"] for first, second in zip(samples, samples[1:])]
    if any(gap <= 0 for gap in gaps): raise ValueError("Resource samples must have strictly increasing elapsed time")
    intervals = [sample.get("requestedIntervalSeconds") for sample in samples]
    gap_limit = max(30, max((value for value in intervals if number(value) and value > 0), default=10) * 3)
    continuous = len(samples) >= 2 and all(gap <= gap_limit for gap in gaps)
    missing = []
    if not physical: missing.append("physical_device_samples")
    if not unplugged: missing.append("unplugged_discharge_samples")
    if duration < minimum_duration: missing.append(f"duration_seconds:{duration:.3f}/{minimum_duration}")
    if not continuous: missing.append("continuous_sampling")
    if not any(number(sample.get("batteryTemperatureCelsius")) for sample in samples): missing.append("readable_battery_temperature")
    if not any(number(sample.get("totalPssBytes")) and sample["totalPssBytes"] > 0 for sample in samples): missing.append("readable_app_memory")
    charge = [sample.get("batteryChargeMicroampHours") for sample in samples]
    observed_charge_delta = charge[0] - charge[-1] if len(charge) > 1 and all(number(value) for value in charge) else None
    charge_valid = len(charge) > 1 and all(number(value) and value > 0 for value in charge) and all(
        second <= first for first, second in zip(charge, charge[1:]))
    consumed = observed_charge_delta if physical and unplugged and continuous and charge_valid else None
    if not charge_valid: missing.append("readable_discharge_charge_counter")
    return {"schema": 1, "status": "observed_baseline" if not missing else "missing_evidence",
        "sampleCount": len(samples), "observedDurationSeconds": duration,
        "phaseAnnotations": sorted({sample.get("phaseAnnotation", "unknown") for sample in samples}),
        "observedChargeCounterDeltaMicroampHours": observed_charge_delta,
        "consumedMicroampHours": consumed, "samplingGapSeconds": distribution(gaps),
        "maximumAllowedSamplingGapSeconds": gap_limit, "missingEvidence": missing,
        "totalPssBytes": distribution([sample.get("totalPssBytes") for sample in samples]),
        "totalRssBytes": distribution([sample.get("totalRssBytes") for sample in samples]),
        "batteryTemperatureCelsius": distribution([sample.get("batteryTemperatureCelsius") for sample in samples]),
        "limitations": ["A discharge baseline is an observation, not a guarantee of 3-hour runtime.",
            "Phase names are operator annotations; main-camera/guide concurrency is not independently verified.",
            "Sampling can miss peaks and does not measure individual GC pauses or queue drops."]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("enable", "disable", "export", "sample"):
        command = commands.add_parser(name)
        command.add_argument("--adb", default="adb")
        command.add_argument("--serial", required=True, help="Always identify the intended Android device")
        command.add_argument("--package", default="com.indigo.mobileobservatory")
        if name in ("export", "sample"): command.add_argument("--output", type=Path, required=True)
        if name == "export": command.add_argument("--include-images", action="store_true", help="Export retained private FITS; never add them to the public repository")
        if name == "sample":
            command.add_argument("--duration", type=float, default=30, help="Seconds; continuous phone baseline can use 10800")
            command.add_argument("--interval", type=float, default=5)
            command.add_argument("--phase", required=True, help="Operator annotation, not automatically verified app state")
    command = commands.add_parser("scaffold")
    command.add_argument("--events", type=Path, required=True); command.add_argument("--output", type=Path, required=True)
    command = commands.add_parser("summarize")
    command.add_argument("--events", type=Path, nargs="+", required=True)
    command.add_argument("--manifest", type=Path, required=True); command.add_argument("--output", type=Path, required=True)
    command.add_argument("--require-real-gate", action="store_true")
    command = commands.add_parser("summarize-resources")
    command.add_argument("--input", type=Path, required=True); command.add_argument("--output", type=Path, required=True)
    command.add_argument("--minimum-duration", type=float, default=10800)
    args = parser.parse_args(argv)
    if args.command in ("enable", "disable"): probe_control(args)
    elif args.command == "export": export(args)
    elif args.command == "sample": resource_sample(args)
    elif args.command == "scaffold": scaffold(args)
    elif args.command == "summarize-resources":
        report = summarize_resources(read_events(args.input), args.minimum_duration)
        write_json(args.output, report); print(report["status"])
    else:
        events = [event for path in args.events for event in read_events(path)]
        report = summarize(args.manifest, events)
        write_json(args.output, report)
        print(f"{report['status']}: {report['eligibleRealGroups']}/30 eligible real groups; {report['acceptedMismatches']} accepted mismatches")
        if args.require_real_gate and report["status"] != "passed": return 2
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.SubprocessError, KeyError, TypeError) as failure:
        print("WF evidence operation failed: " + str(failure), file=sys.stderr)
        sys.exit(1)
