# Stacking implementation and acceptance record

Recorded 2026-10-13. Scope: deep-sky live stacking and offline planetary SER/PSER
stacking in Indigo Observatory / 靛空台. Checked items have the synthetic evidence
described below; they do not imply real-sky or physical-device validation.

This is the initial algorithm/build acceptance record. The subsequent usability
and EAA update, latest 611-JVM/16-Android regression and current APK are documented
in [STACKING_USABILITY.md](STACKING_USABILITY.md); the initial APK checksum below
is retained as historical evidence.

## Acceptance checklist

- [x] Precision-preserving linear model and independently checked float FITS output.
- [x] Star detection, robust matching, subpixel translation and rotation.
- [x] Synthetic inadequate-star, trail/cloud and registration rejection tests.
- [x] Per-pixel coverage weights, noise reduction and bounded transient rejection.
- [x] Bounded live queue, copy ownership, frame accounting, lifecycle and worker fairness tests.
- [x] Synthetic planetary global/multi-point comparison including limb, poles, belts and rings.
- [x] Reversible processing, independent switches and metadata round-trip/replay tests.
- [x] Masked background model, reliability rejection and weak-nebula preservation tests.
- [x] Reproducible synthetic images, numeric metrics and desktop JVM measurements.
- [x] Complete JVM regression: 599 tests, zero failures/errors/skips.
- [x] Android analyze/select/stack/render/export/reopen/cancel: six tests passed.
- [x] Responsive bilingual/night-theme UI, adaptive branches and orientation acceptance.
- [x] Standard APK build, checksum and frozen binary/SDK invariants verified.
- [ ] Physical camera/phone, sustained recording, thermal/memory and real-sky acceptance.

## User flows and input contracts

Live deep-sky stacking starts from the camera capture controls or preview tools
with a supported raw layout: connect the main camera, set exposure/gain/ROI, open
Live stacking, then start. The result view offers single-frame/stack comparison,
processing, pause/resume, reset and export.
Start establishes a capture-session key, pause preserves accumulation, and reset
starts a new reference/accumulation. Exposure, gain, offset, format, ROI, binning
or camera identity changes invalidate compatibility; incompatible frames are not
silently mixed. Camera removal/background handling stops accepting new work.
Accumulated exposure counts accepted frames only.

The independent planetary workflow opens from Tools → Planetary stacking, or a
SER/PSER file action in the file browser/player. Choose a recording, set its target
ROI, analyze quality, select 10/25/50 percent or a custom fraction/count, align and
stack, then adjust and export. This workflow analyzes
quality in a target ROI, displays its distribution, selects the best fraction or
count, and stacks with global or multi-point alignment. The file/player entrance
does not require a connected camera. Input is streaming and multi-pass. A disk
quality index uses five bytes per input frame and a fixed histogram; the recording
is never loaded in full. Selection is capped at 10,000,000 frames so float sample
counts remain exact. SER's frame rate/timestamps are not exposure measurements;
unknown exposure is reported as unknown, rather than inferred from frame spacing.
MP4, AVI and FITS sequences are not planetary inputs in this first version.

The decoder supports mono, all four Bayer patterns and RGB/BGR containers,
8/10/12/14/16-bit sample contracts, and RGB24/RGB48. Declared valid bits, byte
order, channel order and CFA phase are applied before demosaicing and software
binning. Float accumulation retains precision. Linear FITS preserves negative
values and values above one; display processing is downstream. SER/PSER format,
truncation and long-offset acceptance counts belong in the final record below.

SER does not fully specify sub-16-bit alignment inside a 16-bit container. The
stacking workflow therefore fixes alignment for the entire sequence: **RIGHT by
default, LEFT by manual selection**. AUTO is rejected because per-frame brightness
guessing can alter photometry. An endian convention selector handles compatible
legacy recordings. Choices are stored in metadata. Legacy reader/player behavior
is separate from this fixed-alignment science workflow.

Camera adapters expose explicit `sciencePixelTraits`; unknown/processed layouts
cannot start science stacking. ZWO RAW16 native depths below 16 are LEFT. QHY and
Player One preserve delivered full 16-bit containers. ToupTek uses SDK FourCC,
delivered bit depth and zero-padding with raw mode bypassing the color pipeline;
its processed RGB/HDR is unsupported for science stacking. Even hardware binning
of Bayer data is rejected unless an explicit output CFA layout exists. These are
source/fixture contracts, not newly performed vendor-device validation.

Raw SER/PSER recording normalizes original callback samples with that explicit
layout **before display software binning**, preventing Bayer color mixing and
ambiguous left-aligned recordings. Recording captures its layout; a format/ROI/
binning/readout layout change stops it before settings change. Accepted science
queue inputs own copies before the preview pipeline can recycle its raw buffer.
Color video/display processing remains separate.

## Algorithms and boundaries

### Deep sky

`StarRegistration` estimates background/noise robustly, detects peaks, rejects
isolated hot pixels and elongated candidates, and computes subpixel centroids.
Pair-distance hypotheses and unique correspondences feed a rigid fit with repeated
outlier rejection. The model includes in-plane rotation and translation, without
scale, optical distortion or field-dependent warping. The first quality-eligible
frame becomes the fixed reference. Inadequate stars, elongation, lost transparency
or poor registration cause explicit rejection.

`DeepSkyStacker` inverse-resamples accepted frames with bilinear interpolation.
Only covered pixels gain weight, preventing dark borders from an inappropriate
full-frame divisor. Online float mean/variance and a gradient-aware residual test
reject late transients. The five-sample default warm-up cannot reject every early
outlier; persistent hot pixels or early satellite trails are not guaranteed to
disappear. This is bounded online rejection, not a stored-frame median. Default
quality gates include six detected stars, elongation 2.2, transparency 0.35 and
five-sigma online rejection. A calibration extension interface exists, but the
delivered default is **uncalibrated**: automatic dark/bias/flat application is not
claimed. The old `ShortExposureStacker` retains its phone-solver purpose.

### Planetary

Quality analysis and automatic reference choice precede global subpixel alignment.
Local alignment uses textured patches, confidence checks and spatially blended
displacements, with explicit rejection/fallback on failure. Coverage weights avoid
uncovered-edge dilution. The synthetic comparison is not a benchmark against
AutoStakkert or any external product. Planet derotation, drizzle, deconvolution
and arbitrary atmospheric distortions are outside this implementation. Low
texture, clipped limbs, a poor target ROI and motion beyond the configured search
radius can reduce alignment quality.

### Reversible processing and background correction

The original linear stack is immutable. RAW/NATURAL/INTENSE retain recipes and all
edits recompute from that original. Independent stretch, white-balance and
saturation switches preserve parameter values while disabled. Stretch off bypasses
black/white, midtone, asinh curve, brightness and highlights while background/detail
remain independent. WB off bypasses automatic and manual gains; saturation off
uses unity. RAW bypasses all processing. Older metadata without the switches
defaults them to true and preserves its earlier pixel behavior.

Detail enhancement uses a noise gate and bounded sharpening to limit ringing;
local contrast is separate. WB supports bounded gray-world automatic gains and
manual red/blue gains; auto WB is not color calibration for a colored target.
Stretching offers black/white, midtone, curve, brightness and highlight shoulder.
Saturation uses gamut compression to retain hue.

Background correction precedes stretching. Star masks, user exclusion rectangles,
tile percentiles and robust low-order smooth fitting estimate an additive
background. Coverage, residual, extrapolation and shadow-clipping checks reject
unreliable fits with a reason. Metadata includes masks, coefficients, anchors,
strength, sampling and before/after statistics. Large diffuse targets can resemble
background and need manual masks. This additive model is not multiplicative
flat-field calibration or proof of astrophysical surface brightness.

## Ownership, concurrency and resource bounds

The live queue holds at most two owned raw frames. Cancellation checks run during
registration/accumulation; the worker yields between frames. Pause lets a bounded
in-flight frame finish. Reset changes generation and clears the queue. Invalidation
clears queued work, requires reset, and prevents new snapshots from replacing the
retained result. Non-increasing frame IDs
cannot add exposure twice. Invalidation counts discarded queued frames. These
behaviors have deterministic coroutine tests.

Live/offline accumulation are mutually excluded. Render/export/task ownership is
serialized. Live snapshots are conflated: the active preview finishes, then at
most one pending render captures the latest product. This prevents repeated
snapshot cancellation from starving a slow renderer or retaining a coroutine per
snapshot. Recipe/mode/reset/cancel can cancel obsolete rendering. Export captures
a consistent image and recipe. Superseded products and cancelled quality indices
are released.

The coordinator budget is `min(256 MiB, 40% of maximum Java heap)`, plus a
4,194,304-pixel science-input cap and working-set estimates. Failure directs users
to a smaller capture ROI or a lower-resolution recording. A processing ROI alone
does not remove the cost of decoding the original source. Deep-sky estimates cover mean,
variance, weights, current/snapshot images and processing buffers. Pre-binning
estimates include full raw input, demosaic and binning; software binning cannot
make a large full-resolution input appear free. Planetary processing uses one
worker and a disk quality index. These gates are not measurements of graphics/
native allocation or the entire app's peak memory.

## Quantitative synthetic evidence

All figures use deterministic generated data, not real-sky acquisitions.

| Experiment | Measured result | Scope |
| --- | --- | --- |
| 192×160 stars, rotation 4.3°, translation (5.35, −3.7) px | Mean transform error 0.002350 px; matched RMS 0.014840 px; 28 inliers | Rigid registration fixture |
| 20 noisy frames | RMS 0.0039901 → 0.00087763 (0.21995×) | Independent synthetic noise |
| Late transient and transformed borders | Transient rejected; valid edge weights retained | Post-warm-up rejection/coverage tests |
| Masked gradient, weak nebula peak 0.012 | Grid spread 0.072178 → 0.00057529; truth residual RMS 0.0011402 | Nebula peak error also bounded to 0.002 |
| 256×256 RGB, 35 stars, noise σ 0.0008 | Spread 0.055550 → 0.00051847; noise 0.00062283 → 0.00062595 | Diagnostic seed 2701 |
| Planetary distortion | Global MSE 0.00460315 → local 0.000045736; 57/58 APs; seam derivative ratio 0.92715 | Analytic local-distortion fixture |

Planetary regional global/local MSE: limb 0.016241/0.00045719, polar
0.00016682/0.000011007, belts 0.00019239/0.0000037449, rings
0.00448642/0.00021039. Parameters: patch 20, global search 12, local search 5,
maximum 128 APs. Full values are in `planetary-metrics.txt` and
`planetary-region-metrics.txt` in the report directory.

An independent Python FITS reader verified BITPIX −32, planar RGB, 18 samples,
exposure/count metadata, UTF-8 metadata extension and un-clipped linear range.
Maximum float round-trip error was 4.7684×10⁻⁸; adjacent 16-bit values remained
distinct. It does not reuse the app's FITS parser. See
`build/reports/stacking/precision-rgb.verification.json`.

## Desktop performance evidence

Windows 11 amd64, JDK 17.0.20.1, synthetic data. Each deep-sky row has eight accepted
frames. Total includes frame generation. Sampled JVM heap includes generator,
garbage and JVM state: it is **not** true allocation peak or Android native memory.

| Deep-sky input | Frame min–max | Postprocess | Total | Sampled JVM heap | Estimated workspace |
| --- | --- | --- | --- | --- | --- |
| 1024×768 mono | 45–136 ms | 205 ms | 1134 ms | 99,065,792 B | 49,283,072 B |
| 1024×768 RGB | 41–370 ms | 162 ms | 2101 ms | 146,867,056 B | 99,614,720 B |
| 2048×2048 mono | 127–401 ms | 893 ms | 4759 ms | 208,290,232 B | 226,492,416 B |
| 1600×1200 RGB | 76–372 ms | 381 ms | 4375 ms | 137,504,136 B | 231,108,608 B |

Planetary: 640×480 mono/12 frames, analysis 285 ms, stack 1654 ms, sampled heap
72,813,192 B, estimated workspace 19,300,352 B, index 60 B. 2048×2048 mono/two
frames: analysis 572 ms, stack 970 ms, sampled heap 238,386,120 B, estimate
236,978,176 B, index 10 B. Both used one worker and multi-point alignment. Timing
varies with warm-up/GC. Algorithms introduce no native dependency. Camera/graphics
native memory and physical-device CPU/thermal behavior remain unmeasured; these
figures do not establish phone throughput.

On the Android 14/API 34 x86_64 emulator, the final end-to-end fixture used a
160×128, 12-frame MONO16 SER, selected/stacked six frames and used 48 alignment
points. Stack time was 1,652 ms; sampled Java/native maxima were 50,427,256 /
23,864,528 bytes. These task samples are not a process-wide profiler peak. The
emulator app's 192 MiB maximum Java heap produced a 76 MiB science budget. See
`build/reports/stacking/android/android-metrics.json`; this is synthetic emulator
evidence, not ARM64 or physical camera performance.

## Tests, artifacts and reproduction

The final full repository run passed **599 JVM tests** (zero failures, errors or
skips). It includes the existing image/recording/player tests and the new precision,
algorithm, ownership, metadata and integration fixtures. Earlier isolated runs
also produced the numeric diagnostics above:

- 18/18: `DeepSkyAlgorithmsTest` (6), `StackPostProcessorTest` (7),
  `StackResultMetadataTest` (5): quantitative registration/noise/background,
  non-neutral disabled controls, bit-identical restoration, legacy flags and
  actual process/serialize/replay equivalence.
- 12/12: `LiveStackSessionTest` (9), `StackConcurrencyTest` (3): ownership,
  lifecycle, continuous-queue fairness, monotonic IDs and invalidation accounting.
  Their results are included in the final full regression.
- Additional tests cover explicit camera layouts, capture/preview/recording buffer
  fan-out, 4,096 distinct 12-bit recording codes, RGB48 SER and RGGB12 PSER through
  selection/stack/export, external float/Bayer FITS compatibility and export cleanup.
- **6/6 Android tests passed**: generated SER analyze/select/stack/process/export/
  reopen; background cancel/cache cleanup/retry and malformed replacement source;
  custom ROI/selection through 380→800→380 dp and saved-page reentry; SER file action;
  active-player handoff after actual reader closure; float FITS image and metadata.
  PNG pixels exactly match the processed preview, and FITS restores the same recipe.
- Manual main-activity acceptance used Chinese red-night theme and a 128×96 RGB48
  six-frame SER. ROI `(10,0,108,96)`, analyzed state and enabled Align button survived
  portrait→landscape→portrait. `android/rotation-*` stores screenshots and UI trees.
  English/Chinese resources each contain 140 matching stacking keys; automated UI
  and earlier normal-theme passes complement this manual night-theme check.

Generated evidence in `build/reports/stacking/`:

- `deep-sky-raw.png`, `deep-sky-before.png`, `deep-sky-after.png`,
  `deep-sky-natural.png`, `deep-sky-intense.png`; `deep-sky-diagnostics.json` has
  seed 2701, exact recipes/masks, fitted model, statistics and timings.
- `planetary-reference.png`, `planetary-distorted.png`, `planetary-global.png`,
  `planetary-local.png`, ring reference/distorted/local images, metric/benchmark
  text files. These are synthetic images, not real-sky examples.
- `precision-rgb.fits`, `precision-rgb.verification.json`; additional generated
  SER/PSER and FITS/recipe fixtures under `integration/`.
- `android/`: passing instrumentation XML, screenshots, actual FITS/PNG/JSON exports,
  emulator metrics and manual rotation evidence. `android-jvm-final.log` and
  `standard-apk-final.log` contain the final executed build output.

From the repository root in PowerShell with project JDK/SDK configured:

```powershell
# Pure JVM deep-sky tests, images/JSON and measurements; no Gradle invocation.
.\scripts\Measure-Stacking.ps1 -JavaHome $env:JAVA_HOME

# Full JVM regression and Android x86_64 emulator fixtures, including player.
# Emulator builds explicitly enable Sequence for the existing regression suite;
# the standard distribution build below restores its disabled default.
$env:ANDROID_SERIAL = 'emulator-5560'
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest `
  -PemulatorTest=true -PsequenceEnabled=true -PstellariumNonCommercial=true `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.indigo.mobileobservatory.ui.StackingEndToEndTest,com.indigo.mobileobservatory.ui.StackingPlayerInstrumentedTest'

# Independent parser; use fixture output path when it differs.
python .\scripts\verify_stack_fits.py .\build\reports\stacking\precision-rgb.fits

# Standard distribution build; sequence entry points remain disabled.
.\Build.ps1 -NonCommercial
```

The diagnostic script also accepts `-GradleLib` for Gradle 8.5's installed `lib`
directory containing Kotlin/JUnit. Run Gradle tasks serially with other builds.
Installer target: `bin/Installer/IndigoObservatory_android.apk`. Namespace remains
`com.indigo.mobileobservatory`; no Android/JNI package rename is involved.

## Final integration/build record

Executed on 2026-10-13 with JDK 17.0.20.1, Gradle 8.5, SDK 34 and NDK 25.1.8937393.
The combined full JVM and six-test Android command completed successfully in 44 s;
the subsequent standard build completed in 10 s. Times include incremental reuse.

| Acceptance | Status / evidence |
| --- | --- |
| Complete JVM regression | 599/599 passed, zero failures/errors/skips; `app/build/test-results/testDebugUnitTest/` |
| SER/PSER formats/alignment/endian/truncation/>2 GiB and player regressions | Passed in full suite, including independent float FITS verifier |
| Android analyze/select/stack/render/export/reopen/cancel | 6/6 passed on API 34 x86_64 emulator; XML and exports under report `android/` |
| Bilingual UI, portrait/landscape, night theme | 140/140 resource keys; adaptive/reentry regression and manual main-activity rotation passed |
| Standard `Build.ps1 -NonCommercial` | Success; standard debug variant, ARM64 only, Stellarium included, Sequence disabled, emulatorTest false |
| Installer path, size, SHA-256 | `bin/Installer/IndigoObservatory_android.apk`; 40,131,329 bytes; checksum below |
| Frozen ZWO libusb, official 7,042,880-byte libASICamera2 and SONAMEs | Source binary hashes unchanged; extracted final APK has three distinct libusb SONAMEs and ZWO still needs `libusb-1.0.so` |
| DESIGN_NOTES/TODO consistency | Updated with entrances, contracts, verified delivery and remaining physical/real-sky work |

Final APK version is 1.0.7 / build 43, built 2026-10-13 04:52 UTC from working-tree
changes on base `567396c1bbee0d171a23b2606fbeec029c28da37`. The build-info commit is
the base commit, not a claim that uncommitted changes were committed. No commit,
push or release publication was performed.

```text
APK SHA-256:
d10bf69640f73573dc4e9cedb2d97511d2527759d51e93f0cfc1b04f1b0c9118
Frozen source libusb-1.0.so SHA-256 (79,656 bytes):
0ce8669ac9627cb4c7e0228d77cd0d7894db49436669e85dafea5f07814a4cce
Official source libASICamera2.so SHA-256 (7,042,880 bytes):
ef327f12863e56f48d7226a211ff9fd90a8b3a16ef71e26b0b2776c13478c988
APK libusb SONAMEs: libusb-1.0.so / libusb1.0.so / libusb-1.0-po.so
```

The libusb source submodule is dirty only because the standard build reapplies its
existing Android-FD patch, as required by `AGENTS.md`. Frozen prebuilt libraries and
vendor SDK binaries were not replaced.

## Remaining physical validation

No new physical camera/phone or real-sky run is claimed. Follow-up must cover each
SDK's actual samples, CFA phase after ROI/bin changes, reconnect/settings
invalidation, sustained raw recording/preview/stacking, low-memory behavior and
battery/thermal measurements. Real alt-az rotation, variable transparency,
crowded/sparse stars, broad nebulosity and planetary seeing/rings need representative
user data. Such tests may expose threshold/ROI tuning needs despite passing
fixtures. Emulator success cannot establish USB throughput, native-memory peak or
sensor linearity. No real-device benchmark is inferred from desktop timings.
