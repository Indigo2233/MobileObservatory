"""Summarize Measure-StackFp16.ps1 outputs without changing application code.

Requires Python 3; matplotlib is optional for the standalone scientific figure.
Usage: python scripts/stacking/summarize_fp16.py build/diagnostics/fp16-precision
"""
from __future__ import annotations

import csv
import hashlib
import json
import math
import sys
from pathlib import Path


def main(root: Path) -> None:
    records = []
    provenance = []
    production_hashes = {}
    for run in ("long-run", "contexts", "gradient"):
        directory = root / run
        if not (directory / "precision.csv").is_file():
            continue
        control = json.loads((directory / "control.json").read_text(encoding="utf-8-sig"))
        assert control["productionParityFrames"] == 24 and control["pixelsBitIdentical"]
        snapshot = json.loads((directory / "source-snapshot.json").read_text(encoding="utf-8-sig"))
        for entry in snapshot["files"]:
            source = Path(entry["path"])
            if source.name == "Fp16PrecisionProbe.kt":
                archived = directory / source.name
                if archived.exists():
                    source = archived
            actual = hashlib.sha256(source.read_bytes()).hexdigest()
            assert actual == entry["sha256"], f"Source mismatch: {source}"
            if "/src/main/" in source.as_posix():
                previous = production_hashes.setdefault(source.name, actual)
                assert previous == actual, f"Production source differs across runs: {source}"
        provenance.append({
            "directory": str(directory),
            "snapshot": snapshot,
            "control": control,
        })
        run_records = []
        with (directory / "precision.csv").open(newline="", encoding="utf-8") as stream:
            for row in csv.DictReader(stream):
                run_records.append({
                    key: value if key in ("scenario", "mode") else float(value)
                    for key, value in row.items()
                })
        for scenario in snapshot["scenarios"].split(","):
            observed = [r["frames"] for r in run_records if r["scenario"] == scenario]
            assert observed and max(observed) == snapshot["frames"], f"Incomplete run: {directory}/{scenario}"
        assert all(math.isfinite(value) for row in run_records for value in row.values() if isinstance(value, float))
        records.extend(run_records)
    assert records, "No completed numerical experiments found."
    identities = {(r["scenario"], r["frames"], r["mode"]) for r in records}
    assert len(identities) == len(records), "Duplicate scene/frame/mode result."
    groups = sorted({r["scenario"] for r in records})
    for group in groups:
        frames = sorted({r["frames"] for r in records if r["scenario"] == group})
        for count in frames:
            subset = [r for r in records if r["scenario"] == group and r["frames"] == count]
            assert len(subset) == 8, f"Incomplete comparison at {group}/{count}"
            baseline = next(r for r in subset if r["mode"] == "FP32")
            assert baseline["accepted"] >= count * 0.95

    latest = []
    for group in groups:
        count = max(r["frames"] for r in records if r["scenario"] == group)
        latest.extend(r for r in records if r["scenario"] == group and r["frames"] == count)
    pixels = 3856 * 2180
    rgb32 = pixels * 3 * 4
    preview = (643 * 364) * 72 + 643 * 16
    # The existing RAW16/DDFAPD full-frame plan reserves 1,950,720 bytes for tiles.
    # This is an explicit analytical example, rather than a device allocation measurement.
    base_stack = (pixels * 40 + pixels * 2 + 1_950_720 + preview + 8 * 1024 * 1024) / 2**20
    base_export = (pixels * 44 + preview + 8 * 1024 * 1024) / 2**20
    memory = {
        "scope": "Analytical buffer payloads for 3856x2180 RGB. The numerical harness uses FloatArray shadow storage, so these are not measured heap peaks.",
        "rgb_fp32_mib": rgb32 / 2**20,
        "rgb_fp16_mib": rgb32 / 2 / 2**20,
        "input_mean_m2_fp32_mib": rgb32 * 3 / 2**20,
        "input_mean_m2_fp16_mib": rgb32 * 1.5 / 2**20,
        "three_rgb_buffers_saved_mib": rgb32 * 1.5 / 2**20,
        "count_buffer_unchanged_mib": pixels * 4 / 2**20,
        "fp32_current_stacking_phase_estimate_mib": base_stack,
        "fp32_current_export_phase_estimate_mib": base_export,
        "fp16_all_three_stacking_phase_estimate_mib": base_stack - rgb32 * 1.5 / 2**20,
        "fp16_mean_and_m2_export_phase_estimate_mib": base_export - rgb32 / 2**20,
        "export_estimate_condition": "Requires packed FP16 to be consumed by row/tile at export without a full-size Float32 conversion; current Float32 export filters remain reserved.",
    }
    result = {
        "scope": "Production deep-sky detection/registration/rejection with controlled binary16 storage; synthetic 128x128 RGB linear inputs, FP32 arithmetic, exact counts.",
        "scenarios": groups,
        "rows": len(records),
        "production_sources_verified": production_hashes,
        "memory": memory,
        "latest": latest,
        "records": records,
        "provenance": provenance,
        "limitations": [
            "Synthetic post-decode scenes; no real camera, Bayer demosaicing or hardware FP16 arithmetic measurement.",
            "The frame count and numerical precision study do not measure 4K performance or native/Java heap.",
            "Centered candidates freeze per-channel first-frame medians, scale residuals by 256 and M2 by 65536, and calculate M2 from the unrounded new mean.",
            "The gradient case adds a 600-code-unit peak-to-peak planar background; drifting_sky increases the background by 0.02 code units per frame.",
            "Counter storage is kept exact; raw binary16 increments stop at 2048 and cannot replace frame counts.",
            "Centered candidates are numerical prototypes, not application features or a released APK.",
        ],
    }
    (root / "summary.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    with (root / "combined.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(records[0]))
        writer.writeheader()
        writer.writerows(records)
    print("scenario,frames,mode,noise_ratio,median_faint_flux_error_percent,m2_ratio")
    for row in latest:
        if row["mode"] in ("FP32", "INPUT16", "ALL16", "CENTERED_INPUT_MEAN16", "CENTERED16"):
            print(f'{row["scenario"]},{int(row["frames"])},{row["mode"]},'
                  f'{row["noise_ratio_to_fp32"]:.4f},'
                  f'{row["median_faint_flux_relative_error"] * 100:.4f},'
                  f'{row["median_background_m2_ratio"]:.4f}')
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("matplotlib unavailable; numerical summary and CSV written.")
        return
    figure, axes = plt.subplots(1, 2, figsize=(11, 4), layout="constrained")
    modes = ("FP32", "INPUT16", "ALL16", "CENTERED16")
    for mode in modes:
        data = sorted((r for r in records if r["scenario"] == "dark" and r["mode"] == mode), key=lambda r: r["frames"])
        axes[0].loglog([r["frames"] for r in data], [r["background_std_adu"] for r in data], marker="o", label=mode)
    axes[0].set(title="Dark sky: residual background noise", xlabel="Accepted input frames", ylabel="Noise standard deviation (16-bit code units)")
    axes[0].grid(True, which="both", alpha=.2)
    axes[0].legend(fontsize=8)
    labels = groups
    for offset, mode in enumerate(("INPUT16", "ALL16", "CENTERED16")):
        values = [next(r["noise_ratio_to_fp32"] for r in latest if r["scenario"] == group and r["mode"] == mode) for group in labels]
        axes[1].bar([i + (offset - 1) * .25 for i in range(len(labels))], values, width=.25, label=mode)
    axes[1].axhline(1, color="black", linestyle="--", linewidth=.7)
    axes[1].set(yscale="log", title="Final checkpoint: noise relative to FP32", ylabel="Noise ratio", xticks=list(range(len(labels))), xticklabels=labels)
    axes[1].tick_params(axis="x", rotation=25, labelsize=8)
    axes[1].legend(fontsize=8)
    axes[1].grid(True, axis="y", alpha=.2)
    figure.savefig(root / "precision-comparison.svg")
    figure.savefig(root / "precision-comparison.png", dpi=150)
    plt.close(figure)


if __name__ == "__main__":
    main(Path(sys.argv[1] if len(sys.argv) > 1 else "build/diagnostics/fp16-precision").resolve())
