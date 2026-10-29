# Recorded IMX585 guiding fixture

`imx585-field.raw8.gz` is a lossless gzip of an unmodified 1024 × 768 pixel crop from the user-supplied `2026-10-25-2004-IMX585.fits`. The even crop origin (656, 788) preserves RGGB phase. `imx585-field.json` records dimensions, acquisition metadata, and SHA-256 hashes. The original full FITS is read-only and remains outside the repository.

The crop contains real unsaturated stars, a saturated stellar core, sensor noise, Bayer background offsets, and stars cut by its boundaries. These support portable regressions on machines without the original file. The Kotlin tests check the stored crop's size; provenance hashes can be verified against the original file.

`GuideFitsReplayTest` uses production detection, matching, calibration, star analysis, correction policy, backlash compensation, and pulse task ownership. Synthetic translations interpolate each of the four CFA sampling planes independently. This retains the sensor lattice and avoids color reconstruction. Interpolation moves the recorded noise and changes sampling; seeing, independent shot noise, mechanical flexure, and actual SDK exposure timing require field tests.

The virtual mount specifies orthogonal axes rotated by 30°, rates of 0.004 and 0.006 px/ms, RA backlash of 600 ms and DEC backlash of 1800 ms. These are test inputs. They do not measure the physical mount used with the original recording.

Run the portable fixture:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.indigo.mobileobservatory.guide.*' -PstellariumNonCommercial=true -PsequenceEnabled=false -PemulatorTest=false
```

Run the original full frame and render CSV evidence (Python with numpy, astropy and matplotlib):

```powershell
.\scripts\Test-GuideFitsReplay.ps1 -FitsPath '<absolute FITS path>'
```

Reports default to `app/build/reports/guide-fits-full/`, including known/measured shifts, fitted calibration, star profiles, seven algorithm closed-loop traces, summary JSON and PNG figures. Gradle tracks the external FITS as a task input.

The `phd2-filter-reference`, `phd2-star-reference` and `phd2-mass-reference` resources contain outputs from compiled, pinned upstream C++ bodies. Their JSON sidecars identify source commits and hashes. The mass reference contains 1,786 samples, including cloud transitions, exposure changes and history expiry; its generator reads Git objects and injects a deterministic millisecond clock. See `docs/validation/guiding-quality/README.md` for frame-quality and profile validation.
