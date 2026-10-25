#!/usr/bin/env python3
"""Render evidence emitted by the production Kotlin guiding replay; no guiding algorithm is duplicated here."""
import argparse
import csv
import hashlib
import json
from pathlib import Path

import numpy as np
from astropy.io import fits
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def table(root, name):
    with (root / f"{name}.csv").open(encoding="utf-8") as stream:
        return [{key: float(value) for key, value in row.items()} for row in csv.DictReader(stream)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fits", type=Path, required=True)
    parser.add_argument("--reports", type=Path, required=True)
    args = parser.parse_args()
    image, header = fits.getdata(args.fits, header=True)
    stars = table(args.reports, "stars")
    translations = table(args.reports, "translations")
    calibration = table(args.reports, "calibration")[0]
    scale = 206.264806 * header["XPIXSZ"] * header["XBINNING"] / header["FOCALLEN"]
    summary = {
        "source": args.fits.name,
        "sourceSha256": hashlib.sha256(args.fits.read_bytes()).hexdigest(),
        "width": int(image.shape[1]), "height": int(image.shape[0]),
        "bits": header["BITPIX"], "bayer": header.get("BAYERPAT"),
        "arcsecPerPixel": scale, "usableStars": len(stars),
        "primary": stars[0], "translationCount": len(translations),
        "translationMaxErrorPx": max(row["errorPx"] for row in translations),
        "translationRmsErrorPx": float(np.sqrt(np.mean([row["errorPx"] ** 2 for row in translations]))),
        "calibration": calibration, "closedLoop": {},
        "scope": "Recorded image with synthetic CFA-preserving shifts and specified mount dynamics; no physical hardware measurement."
    }
    figure, axes = plt.subplots(2, 2, figsize=(13, 8), dpi=140)
    ax = axes[0, 0]
    ax.imshow(image, cmap="gray", vmin=18, vmax=55, origin="upper", interpolation="nearest")
    ax.scatter([s["x"] for s in stars], [s["y"] for s in stars], s=50, facecolors="none", edgecolors="cyan", linewidths=.8)
    ax.set_title(f"Raw RGGB field: {len(stars)} usable stars, {scale:.3f} arcsec/px")
    ax.set_xlabel("Source x (px)"); ax.set_ylabel("Source y (px)")
    ax = axes[0, 1]
    primary = stars[0]; cx = round(primary["x"]); cy = round(primary["y"])
    ax.imshow(image[cy-12:cy+13,cx-12:cx+13], cmap="gray", interpolation="nearest")
    ax.set_title(f"Primary star: SNR {primary['snr']:.1f}, HFR {primary['hfr']:.2f} px")
    ax.set_xlabel("Raw aperture x (px)"); ax.set_ylabel("Raw aperture y (px)")
    ax = axes[1, 0]
    expected = np.array([[row["expectedX"], row["expectedY"]] for row in translations])
    errors = np.array([[row["measuredX"]-row["expectedX"], row["measuredY"]-row["expectedY"]] for row in translations])
    ax.plot(np.arange(len(errors)), errors[:,0], "o-", label="x error")
    ax.plot(np.arange(len(errors)), errors[:,1], "s-", label="y error")
    ax.axhline(0,color="gray",lw=.5); ax.set_ylim(-.25,.25)
    ax.set_title(f"Known translations: maximum error {summary['translationMaxErrorPx']:.4f} px")
    ax.set_xlabel("Test offset index"); ax.set_ylabel("Measured - expected (px)"); ax.legend()
    ax = axes[1, 1]
    profile_path = args.reports / "profile.csv"
    if profile_path.exists():
        profile = table(args.reports, "profile")
        radial = np.array([row["intensity"] for row in profile])
        ax.plot([row["radius"] for row in profile], radial / radial.max(),label="Radial intensity")
        ax.plot([row["radius"] for row in profile],[row["energy"] for row in profile],label="Encircled energy")
        ax.axvline(primary["hfr"],color="gray",ls="--",label="HFR")
        ax.axhline(.5,color="gray",lw=.5); ax.legend()
    ax.set_title("Production star-profile measurements")
    ax.set_xlabel("Radius (px)"); ax.set_ylabel("Normalized intensity / energy")
    figure.suptitle("IMX585 recorded-image guiding replay", fontsize=14)
    figure.tight_layout(); figure.savefig(args.reports / "image-and-measurement.png"); plt.close(figure)
    loops = sorted(args.reports.glob("closed-loop-*.csv"))
    if loops:
        fig, axes = plt.subplots(2,2,figsize=(13,7.5),dpi=140)
        for ax, path in zip(axes.flat, loops):
            rows=table(args.reports,path.stem)
            error=np.array([row["trueErrorPx"] for row in rows]); measured=np.array([row["measuredErrorPx"] for row in rows])
            tail=error[-30:]
            summary["closedLoop"][path.stem.removeprefix("closed-loop-")]={
                "steps":len(rows),"last30RmsPx":float(np.sqrt(np.mean(tail**2))),
                "last30RmsArcsec":float(np.sqrt(np.mean(tail**2))*scale),
                "maxMeasurementErrorPx":max(row["measurementErrorPx"] for row in rows)}
            ax.plot(error,label="Known mount error");ax.plot(measured,label="Image-measured error",alpha=.7)
            ax.axvline(len(rows)-30,color="gray",ls="--",lw=.7)
            ax.set_title(path.stem.removeprefix("closed-loop-"));ax.set_xlabel("Guide cycle");ax.set_ylabel("Error norm (px)");ax.legend(fontsize=8)
        fig.suptitle("Closed-loop image replay with specified drift and backlash")
        fig.tight_layout();fig.savefig(args.reports/"closed-loop.png");plt.close(fig)
    (args.reports/"summary.json").write_text(json.dumps(summary,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(summary,indent=2))


if __name__ == "__main__":
    main()
