"""Isolate running-mean versus sum-then-divide error on identical camera samples.

This complements the production-engine experiment. It excludes registration and
outlier rejection so numerical bias cannot be concealed by rejecting samples.
NumPy binary16 round trips model storage; arithmetic remains binary32.
"""
from __future__ import annotations

import csv
import hashlib
import json
import sys
from pathlib import Path

import numpy as np


def verify_rounding() -> dict:
    assert np.dtype(np.float16).itemsize == 2
    assert np.float16(1 + 1 / 2048) == np.float16(1)
    assert np.float16(2**-25) == np.float16(0)
    assert np.float16(2048 + 1) == np.float16(2048)
    total = np.float16(0)
    for _ in range(4096):
        total = np.float16(np.float32(total) + np.float32(0.25))
    assert total == np.float16(512)
    return {
        "round_to_nearest_even": True,
        "subnormal_boundary_verified": True,
        "quarter_input": 0.25,
        "quarter_frames": 4096,
        "quarter_fp16_sum": float(total),
        "quarter_fp16_final_mean": float(np.float32(total) / 4096),
        "quarter_exact_sum": 1024.0,
        "quarter_exact_mean": 0.25,
    }


def run(destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    controls = verify_rounding()
    rows = []
    checkpoints = (16, 64, 256, 1024, 4096)
    cases = (
        ("dark", 1000.25, 8.0, False),
        ("bright", 10000.25, 32.0, False),
        ("midtones", 32768.25, 16.0, False),
        ("calibrated", -2.25, 4.0, True),
    )
    for name, level, noise, calibrated in cases:
        rng = np.random.default_rng(585)
        sample_count = 1024
        reference_sum = np.zeros(sample_count, np.float64)
        mean32 = np.zeros(sample_count, np.float32)
        mean16 = np.zeros(sample_count, np.float16)
        sum32 = np.zeros(sample_count, np.float32)
        sum16 = np.zeros(sample_count, np.float16)
        centered_sum16 = np.zeros(sample_count, np.float16)
        origin = None
        for n in range(1, checkpoints[-1] + 1):
            raw = rng.normal(level, noise, sample_count)
            if not calibrated:
                raw = np.rint(raw).clip(0, 65535)
            values = (raw / 65535).astype(np.float32)
            # FP64 oracle averages exactly the FP32 samples seen by all candidates.
            reference_sum += values.astype(np.float64)
            if origin is None:
                origin = np.float32(np.median(values))
            mean32 += (values - mean32) / np.float32(n)
            before = mean16.astype(np.float32)
            mean16 = (before + (values - before) / np.float32(n)).astype(np.float16)
            sum32 += values
            sum16 = (sum16.astype(np.float32) + values).astype(np.float16)
            centered_sum16 = (
                centered_sum16.astype(np.float32) + (values - origin) * np.float32(256)
            ).astype(np.float16)
            if n not in checkpoints:
                continue
            reference = reference_sum / n
            outputs = {
                "mean_fp32": mean32,
                "sum_fp32": sum32 / np.float32(n),
                "mean_fp16": mean16.astype(np.float32),
                "sum_fp16": sum16.astype(np.float32) / np.float32(n),
                "centered_sum_fp16": centered_sum16.astype(np.float32) / np.float32(256 * n) + origin,
            }
            for mode, output in outputs.items():
                error = (output.astype(np.float64) - reference) * 65535
                assert np.isfinite(error).all(), f"Overflow in {name}/{mode}/{n}"
                rows.append({
                    "scenario": name,
                    "frames": n,
                    "mode": mode,
                    "mean_absolute_error_adu": float(np.mean(np.abs(error))),
                    "bias_adu": float(np.mean(error)),
                    "rms_error_adu": float(np.sqrt(np.mean(error * error))),
                    "max_absolute_error_adu": float(np.max(np.abs(error))),
                    "mean_value_adu": float(np.mean(output.astype(np.float64)) * 65535),
                    "reference_mean_value_adu": float(np.mean(reference) * 65535),
                })
    result = {
        "scope": "Four synthetic scenes, 1024 independent samples per frame, identical FP32 inputs, FP64 reference; no registration, calibration pipeline or sigma rejection.",
        "runtime": {"python": sys.version, "numpy": np.__version__},
        "seed": 585,
        "frames": list(checkpoints),
        "representation": "Uncentered mean/sum FP16 round after every frame; centered sum freezes the first-frame median and scales residuals by 256.",
        "count_storage": "Exact integer frame count, never binary16.",
        "controls": controls,
        "source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "records": rows,
    }
    (destination / Path(__file__).name).write_bytes(Path(__file__).read_bytes())
    (destination / "mean-versus-sum.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    with (destination / "mean-versus-sum.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print("CONTROL binary16 boundaries passed; 4096 additions of 0.25 give FP16 sum 512, final mean 0.125.")
    print("scenario,frames,mode,mean_absolute_error_adu,bias_adu")
    for row in rows:
        if row["frames"] == checkpoints[-1]:
            print(f'{row["scenario"]},{row["frames"]},{row["mode"]},'
                  f'{row["mean_absolute_error_adu"]:.6f},{row["bias_adu"]:.6f}')


if __name__ == "__main__":
    run(Path(sys.argv[1] if len(sys.argv) > 1 else "build/diagnostics/fp16-precision"))
