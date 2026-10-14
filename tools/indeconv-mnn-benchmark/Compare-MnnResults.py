#!/usr/bin/env python3
import argparse
import json
import math
import re
from pathlib import Path

import numpy as np


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    return parser.parse_args()


def backend_counts(log_text: str):
    values = [int(value) for value in re.findall(r" Run on (\d+)", log_text)]
    return {
        "cpu": values.count(0),
        "opencl": values.count(3),
        "vulkan": values.count(7),
        "other": len(values) - values.count(0) - values.count(3) - values.count(7),
        "total": len(values),
    }


def gpu_times(log_text: str, mode: str):
    if mode.startswith("opencl"):
        return [float(value) / 1000.0 for value in re.findall(r"total kernel time:(\d+) us", log_text)]
    return [float(value) for value in re.findall(r"Total time summed up is\s+([0-9.]+) ms", log_text)]


def main():
    args = parse_args()
    reference = np.fromfile(args.reference, dtype="<f4")
    if reference.size != 512 * 512 * 3:
        raise SystemExit(f"unexpected reference size: {reference.size}")

    report = {"reference": str(args.reference), "modes": {}}
    for summary_path in sorted(args.results.glob("*/summary.json")):
        mode_dir = summary_path.parent
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
        mode = summary["mode"]
        output = np.fromfile(mode_dir / "output.bin", dtype="<f4")
        if output.size != reference.size:
            raise SystemExit(f"unexpected output size for {mode}: {output.size}")
        difference = output.astype(np.float64) - reference.astype(np.float64)
        mae = float(np.mean(np.abs(difference)))
        rmse = float(np.sqrt(np.mean(difference * difference)))
        maximum = float(np.max(np.abs(difference)))
        dynamic_range = float(np.max(reference) - np.min(reference))
        psnr = math.inf if rmse == 0 else 20.0 * math.log10(dynamic_range / rmse)
        log_path = mode_dir / "run.log"
        log_text = log_path.read_text(encoding="utf-8", errors="replace") if log_path.exists() else ""
        kernel_times = gpu_times(log_text, mode)
        summary["accuracy"] = {
            "mae": mae,
            "rmse": rmse,
            "max_abs_error": maximum,
            "psnr_db": psnr,
        }
        summary["accuracy_pass"] = bool(
            np.isfinite(output).all() and mae <= 5.0e-4 and maximum <= 2.0e-2
        )
        summary["operator_backends"] = backend_counts(log_text)
        summary["gpu_kernel_ms"] = kernel_times
        if kernel_times:
            summary["gpu_kernel_mean_ms"] = float(np.mean(kernel_times[-4:]))
        report["modes"][mode] = summary

    output_path = args.results / "comparison.json"
    output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(output_path)
    for mode, summary in report["modes"].items():
        accuracy = summary["accuracy"]
        backends = summary["operator_backends"]
        print(
            f"{mode:15s} mean={summary['mean_ms']:9.3f} ms "
            f"peak={summary['peak_rss_mb']:8.1f} MB "
            f"MAE={accuracy['mae']:.7g} PSNR={accuracy['psnr_db']:.2f} dB "
            f"accuracy={'PASS' if summary['accuracy_pass'] else 'FAIL'} "
            f"ops(cpu/cl/vk)={backends['cpu']}/{backends['opencl']}/{backends['vulkan']}"
        )


if __name__ == "__main__":
    main()
