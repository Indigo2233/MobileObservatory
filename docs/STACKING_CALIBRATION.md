# Calibration and background correction acceptance record

Recorded 2026-10-13. This extends [STACKING_IMPLEMENTATION.md](STACKING_IMPLEMENTATION.md)
and [STACKING_USABILITY.md](STACKING_USABILITY.md). It describes the current local
implementation and synthetic evidence. Physical-camera, sustained low-end Android
memory/thermal, and real-sky acceptance remain outstanding.

## Calibration workflow

Calibration is optional and starts with **no masters selected**. The calibration
library is available in the stacking workspace before a result exists. Each type
has a three-step guide: prepare the scene, match the relevant camera settings, and
record a raw SER/PSER sequence. Return to the library, choose the recording, build its
master, then explicitly select that master. Creating a master does not select it.

- **Bias:** cover the telescope/camera and use the camera's shortest practical
  exposure with the intended gain, offset, sensor geometry and readout mode.
- **Dark:** cover the telescope/camera and match the light exposure and other
  capture settings. Stabilize sensor temperature before recording.
- **Flat:** use a uniformly illuminated field with the same optical arrangement,
  filter and sensor geometry. Avoid clipping. Select a compatible bias first if
  bias subtraction is wanted while creating the flat.

The inputs are raw **SER and PSER recordings**, including app recordings and
document-picker imports. Importing a recording builds a new master from its
sensor frames; it does not import a precomputed master. There is no FITS-master
import/export in this version, and the external picker does not copy the recording
into My recordings or import a separate capture-context sidecar.
Masters are versioned Float32 `.icm` files in the app-private `stack-calibration`
directory, with descriptors and a whole-file SHA-256 integrity check. They are
saved atomically; partial files are cleaned on failure/cancellation. Uninstalling
the app can remove this private library; it is not a portable FITS library.

The fixed science decoder uses RIGHT alignment by default for an entire SER/PSER
sequence, with explicit LEFT selection available. Per-frame AUTO alignment is
not used. Mono, interleaved RGB, and the four Bayer mosaics retain their declared
valid bit depth and CFA phase. Calibration runs on decoded sensor samples before
demosaicing or software binning. Unsupported/ambiguous CFA geometry is rejected.

## Calibration mathematics and compatibility

Master creation uses a streaming arithmetic mean with Double accumulation and
Float32 storage. Memory does not grow with the number of frames. This is not a
sigma-clipped or median master combiner: transient defects in the calibration
recording can enter the mean. There is no dark exposure scaling, dark optimization,
dedicated dark-flat workflow or bad-pixel interpolation in this implementation.

For each sensor sample, the applied operation is:

```text
calibrated = (light - selectedDarkOrBias) / normalizedFlatResponse
```

An absent subtraction master contributes zero; an absent flat contributes one.
Dark masters contain their bias, so selecting both a dark and a bias does not
subtract bias twice. A flat is optionally bias-subtracted when built, then
normalized separately for each RGB channel or Bayer parity group. Its descriptor
records the bias used at creation; selecting another bias later does not rebuild
the stored flat. Flat source
samples must be positive and below saturation; the normalized response must stay
finite and positive. A single invalid/clipped response is currently rejected
rather than repaired. Negative and above-white calibrated light values survive
into linear stacking/FITS; display processing happens later.

Geometry and valid depth must match. The compatibility checks compare camera,
gain, offset where known, ROI origin, hardware binning and readout. Darks also
require the same exposure and, when both temperatures are known, a difference no
greater than 2 degrees Celsius. Flats additionally check filter identity. Unknown
capture fields require the explicit matching-settings acknowledgement; known
mismatches are rejected. That acknowledgement cannot verify the optical train,
focus, dust, illumination quality, or actual thermal stability.

App recording capture-context sidecars (`<recording>.capture.json`) carry the
settings missing from SER. At recording start the sidecar is explicitly invalid
with reason `recording_in_progress`. Only the close/finalization path writes a
valid context after draining the recording worker and checking that the capture
settings remained unchanged. An interrupted recording keeps its invalid marker.
The file remains busy in the library until that sidecar finalization is complete.

Recording capture checks also track temperature against the initial known value:
a drift greater than 2 degrees Celsius, or loss of a previously known reading,
invalidates the context. Legacy adapters expose zero before a valid temperature
is available without a separate validity flag. The application therefore treats
zero as unknown, including an actual 0 degrees Celsius reading. A recording that
starts with unknown temperature retains that unknown provenance; it does not
silently claim thermal stability. This recording check is separate from the
2-degree master-to-light dark compatibility tolerance above.

External/legacy recordings without a sidecar have unknown settings. A present
invalid, malformed or stale context is rejected when building masters or using
calibration on a light sequence; matching-settings acknowledgement cannot bypass
that rejection. If writing an invalid marker fails, invalidation attempts to
truncate the old sidecar so it cannot remain a valid-looking context. It never
deletes the sidecar to turn a known-invalid recording into an unknown one.
The selected master descriptors, source identities, subtraction choice, unknown
setting acknowledgement and calibration order are carried into result metadata.
Library selections are locked while a source, analysis, result or live session is
in use; reset/clear the workspace before changing them.

## Automatic GC and manual DBE

New Natural and Intense display recipes enable automatic background correction
by default. Raw bypasses all display processing. Old recipes that explicitly
stored background `enabled: false` remain off. A recipe without the new mode
field uses AUTO and an empty manual-point list.

Both background modes fit a robust, smooth, additive quadratic model per channel
and subtract its variation around a median model anchor. This is this app's own
implementation; the GC/DBE labels do not imply the exact algorithms or numerical
behavior of PixInsight or another imaging package. Background correction is not
flat-field calibration and does not remove multiplicative vignetting in the same
way as a measured flat.

- **AUTO:** sample a configurable 6–24 tile grid using each tile's 20th percentile,
  exclude detected star wings and user masks, reject outlying samples, then fit.
- **MANUAL:** place 12–128 points on genuinely empty background, distributed across
  the image. Circular sample-patch medians feed the same conservative fit. The
  radius is a fraction of the shorter image side, default 0.025, range 0.005–0.10.
- **OFF:** retain the original linear background. Strength zero also applies no
  correction. Switching recipes/strength always reprocesses the original stack.

Manual points use normalized image coordinates. They must cover both sides of
each axis, including outer quarters, with at least two points per quadrant.
Duplicates, out-of-range/nonfinite points, too few usable patches, insufficient
coverage, and ill-conditioned fits are rejected. At least 12 usable distributed
patches must remain after rejection. A ring of points alone can underconstrain a
quadratic and is intentionally rejected. The fitter also refuses irregular or
unsafe extrapolations and corrections that would excessively depress shadows.
On rejection, the input image remains unchanged and the UI reports the reason.

The editor keeps a draft against a fixed preview until Apply. In manual mode,
tapping an empty location creates a **candidate**, not a committed sample. A local
loupe appears in the opposite image corner and shows the candidate's sample circle
and hollow crosshair. Tap inside the magnified crop to refine the position, then
choose Add to commit it, or Cancel to discard it. Crops at image edges stay within
the actual preview and preserve the source-coordinate mapping. Tapping near an
existing committed point removes that point. Undo and Clear act on the draft.
Apply is disabled while a candidate remains unconfirmed; manual mode additionally
requires at least 12 committed points before the backend checks sample quality
and coverage. The maximum is 128 points.

Taps/rectangles map through the displayed image bounds, excluding letterboxing.
The loupe magnifies the displayed preview; calibration and fitting still use the
original linear stack. Points and exclusion
masks do not alter the stored linear pixels. The recipe and model metadata save
mode, strength, grid, radius, points, masks, coefficients, anchors, statistics and
application/rejection status, so the display can be reproduced from the original
stack. A large diffuse target can resemble a smooth gradient; place points/masks
carefully and compare Raw/Off before accepting a correction. There is no guarantee
that automatic fitting can distinguish arbitrary extended nebulae from background.

## Verified evidence

The independent pure-JVM run passed **32 tests** across `BackgroundManualTest`,
`DeepSkyAlgorithmsTest`, `StackPostProcessorTest`, `StackAutoStretchTest` and
`StackResultMetadataTest`. This count is the focused backend run, not the final
whole-app regression total. It checks manual sampling/degeneracy, gradient and
nebula behavior, deterministic replay, old recipe compatibility, registration,
processing controls and auto stretch.

For the deterministic 192 × 160 manual-point fixture with a weak 0.012-amplitude
nebula and Gaussian noise sigma 0.00035:

| Measurement | Result |
| --- | --- |
| Background grid spread before | 0.062084294855594635 |
| Background grid spread after | 0.00022299387617229216 |
| Residual RMS against known scene | 0.0003530190349585809 |
| Nebula peak preservation | within 0.001 absolute signal |
| Manual coverage | 16-point grid and 12-point 4 × 3 layout accepted |
| Invalid layouts | too few, duplicate, invalid, clustered, diagonal and conic rejected |
| Recipe round trip | bit-identical processed pixels |

The existing AUTO fixture retained its prior grid-spread result, approximately
0.0721782 to 0.000575286, and residual RMS approximately 0.0011402.
These are generated scenes with known truth, not measurements of real sky data.

## Memory and desktop timing

Live input retains at most two waiting raw frames plus the current input. A slow
renderer finishes one preview and then takes the newest available stack; it does
not accumulate a queue of processed images. The memory estimate now reserves
`pixels * (channels * 40 + 32) + 8 MiB` for live stacking, including the transient
case of a renderer holding snapshot A while B is current and C is being created,
automatic-background output, and simultaneous sharpening/local-contrast scratch.
Calibration masters and pre-binning input expansion are added separately.
The coordinator caps the working budget at the smaller of 256 MiB and 40% of the
Java maximum heap. The 4 MP pixel ceiling alone does not guarantee admission;
reduce camera ROI/binning when the byte budget rejects a configuration.

An independent Java 17 desktop benchmark on the deterministic gradient fixture
gave the following indicative timings. These are JIT/host-dependent runs while
development work was active, not Android frame-rate or thermal claims:

| Image | AUTO correction | Entire Natural processing | Entire Intense processing |
| --- | --- | --- | --- |
| 1024² RGB | about 67–82 ms | about 187–206 ms | about 171–206 ms |
| 2048² mono | about 0.61–0.77 s | about 0.62–0.74 s | about 0.88–0.90 s |
| 2048² RGB | about 0.58–0.60 s | about 0.86 s | about 1.02 s |

The 2048² RGB benchmark bypassed the coordinator for measurement; the application
budget rejects that live configuration. Star masking scans the full image, so
low-end Android processing can take substantially longer. The local benchmark
source/output are `build/BackgroundAuditBenchmark.kt` and
`build/background-audit-benchmark.txt`; they are development artifacts, not shipped
assets. Backend quantitative fixtures live in the checked-in test sources.

## Final integration acceptance

The final pre-PR run passed **636 JVM tests** and **25 Android instrumentation
tests** on the Android 14 emulator. Earlier complete 24-test runs and focused
four-test calibration UI runs also passed. The Android checks include
real SER master creation/selection/persistence, calibration before demosaicing,
offline export, invalid-context rejection, optional calibration guidance, and
manual DBE candidate refinement/confirmation/cancellation, duplicate-point
prevention after loupe refinement, state restoration, and a Chinese night-mode
edge crop. Screenshots were captured from the rendered dialog for visual review.
The earlier 32-test backend count describes its separate
focused run and is not the whole-app total.

The pre-PR `lintDebug` check passed with **0 errors and 132 warnings**. Four
blocking reads of `StateFlow.value` during composition were corrected to observe
Compose state; related parent/child state reads now keep button and result states
reactive. No lint suppression or baseline was introduced.

The stacking workspace keeps the preview and controls in two fixed composition
subtrees. A custom `Layout` changes their measurement and placement for wide,
narrow and observing-only views; it does not move the controls with
`movableContentOf`. The observing-only view retains the settings subtree while
leaving it unmeasured/unplaced. Page reentry uses `SaveableStateHolder` for saved
analysis configuration.

`Build.ps1 -NonCommercial` successfully produced the standard debug ARM64 APK,
version **1.0.7-build43**, with Stellarium included, Sequence hidden and emulator
mode disabled. The two frozen prebuilt camera/USB libraries retain their original
sizes and SHA-256 values.

- APK: `bin/Installer/IndigoObservatory_android.apk` (40,351,953 bytes).
- SHA-256: `3ba2687d13601350cc959fdc32da616441e8ae733d70d3c65b12e2e67bd1448c`.
- Local evidence: `build/reports/stacking/calibration/acceptance.json`, JVM/Android
  XML reports, build/test logs, and five screenshots in `screenshots/`.

An earlier combined run reproduced an intermittent Compose `SlotWriter` exception
while disposing the existing FITS player test. Its failure log/XML are retained
under `regressions/`. The isolated test then passed eight consecutive runs, and
the complete 24-test sequence passed twice after the fixed-subtree layout change.
The final 25-test pre-PR sequence also passed after the observable-state fix.
Those results do not establish the root cause or prove the exception eliminated;
the isolated player does not itself use the stacking layout. This remains an
explicit regression risk for device testing. No physical-camera or real-sky
acceptance was performed for this record.
