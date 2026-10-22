# Calibration and background correction acceptance record

This records the initial calibration implementation at commit `65d8dea`.
For the later selectable Bayer algorithms and current build acceptance, see
[STACKING_DEMOSAICING.md](STACKING_DEMOSAICING.md).

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
Since 2026-10-19 the coordinator uses a fixed fraction of the actual VM maximum
heap without the former fixed 256 MiB cap. Since 2026-10-20 that fraction is 60%
and the manifest declares android:largeHeap, so the limit is dalvik.vm.heapsize
(512 MiB on 16 GB phones) rather than dalvik.vm.heapgrowthlimit (256 MiB). Since 2026-10-18 the coordinated processing paths no longer
use a universal 4 MP gate: byte admission decides usable dimensions, within the
16,777,216-pixel image representation ceiling and 32 MiB live raw-frame bound.
The conservative 4 MP defaults on standalone reader helpers remain; admitted
stacking/master paths pass their higher ceiling explicitly before allocating.

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

## Adaptive ROI planning (2026-10-18)

The old 4,194,304-pixel constant (2^22) originated in the initial stacking
implementation, commit `65d8dea`. It was an engineering safeguard, not a camera,
SER, or Android pixel-format rule. Android limits an app's heap separately from
the phone's total RAM; see the [Android memory overview](https://developer.android.com/topic/performance/memory-overview).
The original 256 MiB / 40% policy was an additional conservative application
budget; the fixed cap was removed on 2026-10-19 and the fraction became 60%
on 2026-10-20 (see below).

The byte estimate reserves simultaneous input, accumulation, registration,
snapshots, render/filter workspace, and selected calibration masters. For RGB
deep-sky output the engine estimate is 152 bytes per pixel plus 8 MiB; DDFAPD
adds four Double planes and a direction plane (33 bytes per native input pixel,
plus overhead). A 3856×2180 Bayer source without software binning is therefore
estimated at about 1.46 GiB. This is a conservative working-set estimate, not a
measured S23 Ultra peak or a promise of a particular frame rate.

`LiveStackAdmission` is shared by the actual start boundary and `StackRoiPlanner`.
The latter finds the largest aligned rectangle for Sensor, 1:1, 4:3, or 16:9
aspect at the current hardware/software Bin, algorithm, target, and calibration
budget. The native sensor domain is retained for calibration and demosaicing;
software Bin only reduces their downstream output. Choosing software Bin must
not divide all native working arrays by Bin². Unknown post-bin CFA layouts
continue to block admission rather than drawing a misleading usable box.

The same component is available in **Capture → capture settings → ROI** and
**Live stacking → Stacking range**. A solid outline shows the current camera
ROI, a dashed outline shows the suggested maximum, and tapping the sensor
diagram moves the proposal. It is a sensor-coordinate diagram, not a reticle
drawn over a potentially cropped/flipped/rotated live image. Input and stacked
output dimensions and actual hardware/software Bin are shown separately.
Memory estimates and the remaining ceilings live under “Why this limit?”.
Manual crop presets/sliders are expandable in the capture panel.

The SDK/UI width/height steps are honored (Player One 4×2, ZWO 8×2, conservative
8×8 default); positions snap to an eight-pixel grid to preserve CFA parity.
“Use this range” changes capture only on explicit click. Before writing, the
ViewModel rechecks camera/layout identity, bounds, alignment, budget, recording,
reference capture, stacking and calibration state. SDK read-back then updates
the displayed actual ROI. Switching camera/Bin/format while a proposal is open
cannot apply stale coordinates to the new configuration. Selected calibration
frames must be deselected before the guided crop action; no master is silently
removed or geometrically reinterpreted.

The old 4 MP gate is removed consistently from coordinated live/offline
stacking, sensor decoding, and master building/loading. Masters carry explicit
byte budgets before allocation, including old/new selection overlap. A tested
2056×2056 MONO8 input with software Bin 4 now passes admission and reaches the
live accumulator at 514×514; a master above 4 MP can also be built if its byte
budget fits. The 585 full-frame colour/DDFAPD case can still exceed the budget.
At this revision the offline planetary tracking ROI did not shrink full-frame
decode buffers. That limitation is superseded by the 2026-10-19 work below.

Validation covers maximum-boundary search, each aspect/alignment, moving the
box at sensor edges, actual CFA contracts, software versus hardware Bin,
algorithm/calibration budget changes, admission beyond 4 MP, and rejection at
byte/representation bounds. Android tests exercise the real planner component,
capture/live entry points, a synthetic adapter's ROI write/read-back, stale-plan
rejection, and live accumulation beyond the former ceiling. Physical SDK ROI
rounding, device heap/RSS peaks and full-frame colour performance still require
on-device validation. Future memory reductions should measure actual overlap
and pursue tiled demosaicing/calibration without changing scientific ordering.

Local verification for this change: 1,041 JVM tests and 13 API 34 Android tests
passed with no skips; Android Lint reported no errors. The UI evidence uses the
real Compose component with synthetic 585 geometry and a simulated ROI adapter,
not a physical 585C or an S23 Ultra memory measurement.

## Tiled offline processing and heap budgets (2026-10-19)

The reported 3856×2180 RAW16 recording could not be used even after editing the
offline ROI. The old ROI only constrained quality/registration; import decoded
the whole image before showing any preview, and the start budget still described
the full native image. Large recordings now first show a bounded 262,144-pixel
display preview. This preview is never passed to the scientific pipeline.

Both offline targets share **Stacking region / Processing Bin 1–4**. Show output
dimensions, estimated work bytes and the app's science budget next to the action.
An over-budget action is disabled with a local recovery: increase processing Bin
to retain the field, or select a suggested smaller crop. Changes are explicit.
Numeric crop coordinates and the preview outline remain in original file pixels;
the crop is now the actual exported region, not just a quality scoring window.
The drag hint describes this operation instead of background-model protection.

Science reads contiguous native row spans from SER, or the corresponding bit
spans from PSER. Each output tile is at most 64×64 pixels. Native input is expanded
by the software Bin and a demosaic halo (DDFAPD 16, VNG 4, bilinear 2 pixels).
BIAS/DARK/FLAT are validated against original file geometry and indexed at the
original pixel positions. Then native samples are demosaiced with the correct
CFA phase, followed by RGB/mono averaging for software Bin. Raw Bayer samples
are never averaged across colours. The final cropped/binned float image alone
goes to registration/accumulation. Non-divisible Bin edges discard at most Bin−1
pixels at the right/bottom, matching the existing software-bin convention.

This separates full-field imaging from full-resolution processing. For example,
3856×2180 at Bin 4 retains the field at 964×545 output. Native Bin 1 full-field
accumulation can still exceed a device's heap; tiling does not make the final
accumulators free. Output images retain the 16,777,216-pixel representation bound.
Input file geometry no longer requires an entire raw frame allocation. Crop
boundaries are distinguished from physical frame edges during planetary quality
checks, so an intentional lunar/planetary crop is not falsely rejected as clipped.

The old large estimates were not file sizes or measured peaks. At this geometry,
RAW16 is about 16 MiB, a float RGB image about 96 MiB, and the former full-frame
DDFAPD scratch alone about 265 MiB. The old offline deep-sky calculation also
reserved live queues and three overlapping previews. Offline deep-sky now uses
`pixels × (channels × 20 + 24) + 8 MiB` for its sequential engine, with tile and
calibration overhead separately counted; live concurrency keeps its own formula.
Offline planetary sources with explicitly bounded decoder scratch use
`pixels × (channels × 24 + 12) + 8 MiB`: four Float channel-image reserves
(reference, old/new input, result), Double sums, two mono gray planes and coverage.
The current gray plane is reused across alignment/edge checks and frames. Generic
frame-source callers retain the old decoder-inclusive bound. Post-processing
starts after the science worker returns and fits within the above reserve.
Offline accumulators are explicitly released after the final snapshot. The tile
estimate reserves old/new scratch overlap across tile size changes. No Double
planes are changed to Float and no empirical safety factor is removed without
corresponding changes to the arrays' lifetime.

`StackMemoryBudget` derives 60% from `Runtime.maxMemory()`. Since 2026-10-20 the
manifest declares `android:largeHeap`, so the VM limit is `dalvik.vm.heapsize`
(512 MiB on a 16 GB phone) instead of `dalvik.vm.heapgrowthlimit` (256 MiB): the
budget rises from 102.4 MiB to 307.2 MiB on such a device. A 1 GiB VM heap gives
about 614 MiB. The remaining 40% leaves room for the rest of the app and
allocation/GC headroom. This is an app policy, not a claim that Android assigns
60% of the phone's total RAM, and it stays below the vendor-set heap ceiling. The
expanded details show both actual VM heap limit and science budget. See Android's
[per-app heap documentation](https://developer.android.com/topic/performance/memory-overview).
Declaring `largeHeap` trades an earlier `OutOfMemoryError` for a higher likelihood of
being killed under memory pressure; the offline pass writes to temporary files and can be
restarted, so the failure mode is recoverable. Device RSS/PSS and GC pause
measurements remain part of phone acceptance.

Exports record original dimensions, source region, software Bin, decoding identity
and processing order. Original recordings and calibration masters are not modified.
Regression fixtures compare all four CFA patterns, all three demosaic algorithms,
odd crop origins, tile seams, calibration and Bin against full-frame processing;
also cover packed row bit offsets, endian/alignment, RGB/BGR and a source above
the full-frame allocation ceiling. Android acceptance uses synthetic 585-sized
data, real preview/analysis/stack/export and UI recovery. Actual device memory,
thermal behaviour and sustained performance remain field acceptance items.

Local acceptance: 1,048 JVM tests and 17 API 34 instrumentation tests passed with
no skips; Lint reported no errors. On the 192 MiB-heap emulator, the 585-sized
synthetic planetary fixture completed preview/analysis/stack/export at Bin 4
(964×545), with a 76.8 MiB science budget and 58.4 MiB working-set estimate.
Progress-boundary sampling recorded 34.2 MiB Java heap used and 19.7 MiB native
heap used; these are sampled process values, not continuous peaks, RSS/PSS, or a
before/after device benchmark. Evidence is in local `build/reports/offline-region-*`.

## Reusable offline planetary buffers (2026-10-19)

The follow-up keeps the requested native region, processing Bin, Float image
samples and Double accumulation precision unchanged. The SER/PSER source exposes
an explicit caller-owned buffer contract alongside the existing independently
owned `readFrame` API. Other consumers retain their existing ownership rules.

- Quality analysis fills one reusable luminance plane directly from calibrated,
  demosaiced and binned tiles. Its Float values match the previous RGB-to-luminance
  operation, including channel rounding; no full RGB image is built for this pass.
- Registration retains only private reference luminance. Selected RGB frames are
  reread in their original order into one reusable full-resolution input. The
  reference still receives its identity alignment when selected. Quality ranking,
  tied-score selection, exposure accounting and rejection rules remain unchanged.
- After the final contribution, normalization overwrites that input and transfers
  it to the result. Uncovered pixels are explicitly zeroed. The source never owns
  the destination; retrying or starting another stack cannot overwrite a previous
  result. Cancellation publishes no partial image and leaves valid analysis usable.
- Offline displacement meshes are reused across frames. Native tiles use fixed
  read extents, clamped at physical frame boundaries, so edge/tail tiles retain
  demosaic scratch and borrowed RGB output instead of changing its geometry.
  Sensor reads and non-DDFAPD decoders still have bounded tile allocations.

For this explicitly buffered source, the engine estimate is now
`pixels × (channels × 12 + 12) + maximum dx/dy mesh + 8 MiB`.
It covers one Float channel image, Double sums, coverage and two gray planes;
tile/calibration/master storage remains separately budgeted. The existing
decoder-inclusive and bounded-but-owned estimates remain for generic sources.
The post-processing reserve fits within the same bound after the worker returns.
At 3856×2180 Bayer RAW16, full field, Bin 1 and DDFAPD without calibration masters,
the full pipeline estimate changes from about 682.6 MiB to 398.0 MiB. Heap policy,
representation bounds and output dimensions are unchanged. This is an allocation
model, not a measured Android peak.

`PlanetaryBufferReuseTest` compares owned/buffered mono and RGB paths with global
and local registration, multiple quality selections, pixel/weight/statistics
identity, destination reuse, previous-result independence and cancellation/retry.
`SerRegionDecoderTest` additionally checks RGB/mono/luminance destinations, all
three demosaic algorithms, Bin 1/2/4, physical boundaries and stable DDFAPD tile
allocations. Existing whole-frame reference fixtures cover calibration, CFA
phase, tile seams and packed SER interpretation.

The optional desktop `PlanetaryBufferBenchmark` generates each frame on demand.
Its HotSpot thread-allocation counters report cumulative bytes allocated, and its
phase-boundary heap values are samples, with neither interpreted as Android/RSS
peaks. On a 1024×768 RGB six-frame fixture, quality-analysis allocations fell from
about 54.9 MiB to 4.0 MiB and stack allocations from 90.3 MiB to 36.3 MiB. Increasing
three frames to six leaves the buffered stack allocation near 36.3 MiB; the owned
path adds about 27 MiB. A 3856×2180 RGB three-frame full-field Bin 1 fixture also
completed under a 512 MiB desktop VM heap. Reports are generated beneath
`build/reports/stacking/planetary-buffer-*.txt`. The isolated benchmark admits up
to 90% of its desktop heap; the app retains its 40% policy, so this run does not
claim full-field admission on a phone with a 512 MiB VM heap. Real file I/O, actual phone peak
heap/RSS, GC pauses and sustained thermal performance remain device acceptance.

Validation: 1,052 JVM tests passed with no failures, errors or skips;
`compileDebugAndroidTestKotlin` passed, and `lintDebug` reported zero errors
(246 warnings across the project). Android instrumentation was compiled but was
not executed on a compatible phone in this follow-up. No APK was rebuilt or
published as part of these optimization checks.

## Bounded registration search convergence (2026-10-20)

Three changes reduce the work per accepted frame without altering the alignment
that a frame resolves to:

- The global coarse sweep runs at twice the resolution stride over the whole
  ±[PlanetaryOptions.maxGlobalShift] window, then the original stride resolves the
  winner's neighbourhood in both axes. A winner within one wide stride of an edge
  widens the window back to the full range, so no candidate the single fine sweep
  would have scored is dropped.
- Local points are searched from the previous frame's displacement relative to
  that frame's global shift, in a ±1 integer window. Relative storage means a
  uniform drift between frames moves the guess with the frame. A hint that no
  longer explains its patch fails the existing acceptance test and the bounded
  full window is searched instead, so a lost hint costs time and never a result.
- Refinement uses a centre-plus-eight-neighbour pattern instead of a 5 x 5
  lattice, up to three passes per level before the step halves. The floor stays
  0.125 px.

Accumulation resolves the displacement mesh once per image row and column
instead of once per pixel per axis, and tests the interpolated sample for
finiteness. A non-finite tap always propagates into its weighted sum, so this is
equivalent to the previous four-input test per channel and keeps every RGB
channel on one coverage decision. The mesh also hoists the per-point correlation
fourth power, support radius and relative displacement out of the node loop.

Measured on the desktop synthetic harness, 2048x1152 RGB, six frames, full
field, 52 alignment points, local alignment. A build switch isolated the
two-stage global sweep and the temporal hint, with the compass refinement
present in both arms: correlation evaluations fell from 39,610 to 15,399 and
evaluated samples from 54.1M to 21.8M per stack. With local alignment disabled
the global stage alone fell from 1,650 evaluations to 735. The compass
refinement is not in that ratio: it replaces 25 evaluations per level with at
most eight, and the hinted local window replaces a 121-point coarse grid with
nine. These are deterministic counts, not timings; wall-clock differences on
this host varied by about 15 percent between repeated runs.

Numerical comparison used a fixed 512x512 six-frame fixture whose frames carry a
known global drift and a spatially varying local distortion. Every frame's global
dx, dy and correlation matched to all printed digits, the mode counts, rejection
counts and mean correlation (0.999804990) were unchanged, and all six frames
resolved 52 local points in both builds. Four of 312 local shifts differed, each
by exactly one 0.125 px refinement step. The stacked image sum moved by 3.9e-6
relative and the weight sum by 9.3e-5, which is edge coverage following from
those sub-step differences.

PlanetarySearchRegressionTest locks the recorded per-frame values, asserts that a
hint never changes a result and a stale hint falls back, checks the mesh offset
table against the direct bilinear lookup bit for bit, and compares accumulation
against the previous finiteness implementation bit for bit for mono and RGB,
including a pixel carrying a NaN.

Validation: 1,056 JVM tests passed with no failures, errors or skips, and
compileDebugAndroidTestKotlin passed. Android instrumentation was compiled but not
executed, no APK was rebuilt or published, and phone timing, peak heap and GC
remain device acceptance.

## SER decode path (2026-10-20)

The native stage used to read one tile at a time, allocating a byte array per
tile, a sensor float array per tile inside the decoder, an RGB array per tile for
BILINEAR and VNG, and a calibrated float array per tile when calibration was on.
It also issued one seek plus one read per tile row.

The decoder now walks one output row at a time. OfflineRegionPlan.bandRegion
defines the native extent that carries every tile read in that row; it is the
same definition the memory budget uses, so a band can never be narrower than a
tile read and the reserve cannot be too small. SerRegionDecoder.Workspace holds
the band bytes, one sensor tile, the calibrated tile, the demosaiced RGB tile and
the DDFAPD scratch, all reused across frames. BILINEAR, VNG and DDFAPD write into
the same caller-owned RGB buffer; the public demosaic entry points keep their
independent-ownership behaviour. A new region form of the calibration borrow
keeps the calibrated tile in the same workspace.

SERReader gained readSensorRegionInto and sensorRegionByteSize so a worker can
size and reuse the native buffer, and LinearPixelDecoder gained
decodeSensorRegionBorrowed, which decodes a strided sub-rectangle of the band
without copying it first. Sample order, CFA origin, byte order, bit alignment and
the automatic left/right alignment scan are unchanged, so calibration offsets and
demosaic phases are carried exactly as before.

Desktop synthetic measurement, 3856x2180, DDFAPD, Bin 1, full field, three
frames, HotSpot thread allocation counters:

| | BAYER_RG16 before | after | MONO16 before | after |
|---|---|---|---|---|
| allocated per frame | 441.5 MiB | 1.06 MiB | 81.4 MiB | 0.85 MiB |
| decode per frame | 1977 ms | 1717 ms | 439 ms | 138 ms |
| estimated working set | 683 MiB | 399 MiB | 297 MiB | 205 MiB |
| native row reads per frame | 204,960 | 3,360 | 204,960 | 2,240 |

The Bayer decode time barely moves because DDFAPD reconstruction dominates that
path and was not touched; MONO has no demosaic, so the read and allocation savings
show directly. These are host counters, not Android peak heap, RSS or GC pauses.

Writing the benchmark caught a real defect before it shipped: demosaicInto was
constructed with a null workspace, so every tile rebuilt the five DDFAPD scratch
planes and the Bayer path allocated 620 MiB per frame. The workspace is now passed
through and only the RGB output is caller-owned.

SerRegionDecoderTest gained a case that asserts the per-frame row-read count
equals the sum of band heights and beats the per-tile count, that the workspace
stops allocating across repeated decodes, and that the band buffer keeps its size.
The existing whole-frame versus tiled comparison over four Bayer phases, three
demosaic algorithms and Bin 1/2/4 still passes unchanged, which is the byte-level
evidence that banding did not move a sample.

Validation: 1,057 JVM tests passed with no failures, errors or skips,
compileDebugAndroidTestKotlin passed, and lintDebug reported zero errors (246
warnings). No APK was rebuilt or published.

## Offline deep-sky buffer ownership and phase budgets (2026-10-23)

Deep-sky offline processing now uses the same explicit caller-owned input contract
as offline planetary processing. A SER/PSER pass allocates one output-resolution
Float input, then fills it from the existing native band/tile decoder for every
frame. Star detection and accumulation finish synchronously before that input is
overwritten. The reference keeps star coordinates/statistics, never input pixels.
Generic frame sources retain the independently owned `readFrame` API and an
old/new input overlap reserve. Their decoder workspaces must still be budgeted by
the caller.

After the last frame, `DeepSkyStacker.takeResult()` transfers its mean and coverage
arrays to the result and releases M2, registration scratch and engine references.
It is restricted to the sequential offline engine. Live `snapshot()` still copies
its pixels and weights; changing or resetting an engine cannot mutate a previously
published result. Cancellation, including cancellation at finalization, publishes
no partial product. Rigid registration, Float Welford accumulation, warmup/sigma
rejection, CFA/calibration/Bin order, unknown exposure and source ownership are
unchanged.

`OfflineDeepSkyMemory` estimates three phases and admits their **maximum**, not
their sum. UI ROI/Bin planning, admission, progress, final statistics and export
metadata use that same model. The coordinator joins the preceding renderer before
stacking/export and closes the decoder before scheduling the result preview.

| Phase | Retained arrays and temporary storage |
|---|---|
| Stacking | Float mean + M2 + one reusable input + Float weights: `pixels × (12 × channels + 4)`; a generic owned source reserves one further input. Native band/tile scratch is added here. |
| Preview | Transferred result and weights; the larger of four mono filter planes during preparation or three mono planes plus the new bounded Bitmap during rendering; an old preview, and one Float/ARGB row. |
| Export | Transferred result and weights; the larger of four mono filter planes or three planes plus a full-size ARGB Bitmap; the old bounded preview and row buffers. FITS writes scalar samples. |

Calibration masters remain budgeted in **every** phase. The existing 8 MiB
small-workspace/allocation reserve remains; old preview storage is conservatively
bounded by 1280² ARGB pixels even if the previous aspect ratio differs. The model
always covers sharpening and local contrast together, so enabling them after a
stack does not invalidate admission. It does not reserve obsolete full-size RGB
background/processed copies. Export `memoryEstimate` records all three phase
values, their maximum, and the input/result ownership contract.

For 3856×2180 Bayer RAW16 with DDFAPD, full field and no calibration masters:

| Processing Bin | Output | Previous estimate | Current estimate |
|---|---|---:|---:|
| 1 | 3856×2180 | 683.3 MiB | 330.5 MiB |
| 2 | 1928×1090 | 180.3 MiB | 92.1 MiB |
| 4 | 964×545 | 60.5 MiB | 38.5 MiB |

These are allocation models, not measured Android peaks. Full-size Bin 1 still
exceeds the 307.2 MiB stacking budget of a 512 MiB app heap. This work does not raise
the existing 60% budget or change the live full-frame decoder. The real VM heap
limit is read at runtime; `largeHeap` does not guarantee the same limit on every
phone. The live ROI detail text is corrected from 40% to the existing 60% policy.

`OfflineDeepSkyBufferTest` compares buffered and owned inputs with the copying
science engine, including mono/RGB, drift, rejected frames, unknown exposure,
warmup and pixel rejection. It checks one input identity per pass, source/old
result independence, destructive finalization, cancellation/retry, a broken buffer
contract, calibration/decoder lifetime accounting, and admission one byte below
the full pipeline estimate. The existing tiled decoder tests cover native CFA,
calibration, seams, PSER and output values.

`DeepSkyBufferBenchmark` is an optional desktop harness. Its `legacy` arm reproduces
the previous owned-read plus copying-snapshot call pattern with the same science
engine; `owned` uses the new result transfer, and `buffered` also reuses input.
At 1024×768 RGB, six synthetic frames, cumulative thread allocations were
116.3 MiB / 95.0 MiB / 50.7 MiB respectively, with identical image and weight sums.
The buffered three-frame pass allocated 44.9 MiB: registration still allocates
small objects, but extending a pass no longer adds a complete RGB array per frame.
A 3856×2180 RGB three-frame buffered run completed in a 512 MiB desktop VM with
373.6 MiB cumulative allocations and a 366.3 MiB phase-boundary used-heap sample.
This isolated harness permits 90% of its desktop heap, unlike the app's 60% policy;
it does **not** claim full-field admission on a phone with a 512 MiB heap. Used-heap
samples include unreachable allocations awaiting GC and other VM objects, so they
are not a measurement of the model's simultaneously retained arrays. No Android
RSS/PSS, GC pause or sustained device performance claim follows from these runs.

After `:app:compileDebugUnitTestKotlin`, run the benchmark main class with
`app/build/tmp/kotlin-classes/debug`, `app/build/tmp/kotlin-classes/debugUnitTest`
and the toolchain's Kotlin standard library on the classpath:

```text
java -Xmx512m -XX:ActiveProcessorCount=2 -cp <classpath> com.indigo.mobileobservatory.stacking.DeepSkyBufferBenchmark legacy 1024 768 6
java -Xmx512m -XX:ActiveProcessorCount=2 -cp <classpath> com.indigo.mobileobservatory.stacking.DeepSkyBufferBenchmark owned 1024 768 6
java -Xmx512m -XX:ActiveProcessorCount=2 -cp <classpath> com.indigo.mobileobservatory.stacking.DeepSkyBufferBenchmark buffered 1024 768 6
java -Xmx512m -XX:ActiveProcessorCount=2 -cp <classpath> com.indigo.mobileobservatory.stacking.DeepSkyBufferBenchmark buffered 3856 2180 3
```

Reports are written beneath `build/reports/stacking/deep-sky-buffer-*.txt`.

Validation: 1,063 JVM tests passed without failures/errors/skips, and Lint reported
zero errors (246 existing warnings). Seventeen API 34 emulator tests passed across
offline source processing, calibration, CFA, large-input admission and the stacking
screen's end-to-end workflow. The new case processes three 3856×2180 RAW16 star
frames at full-field Bin 2, compares admission with final statistics, renders with
both local contrast and sharpening, exports FITS/PNG, and checks output dimensions
and phase-budget metadata. Actual phone/recording acceptance remains outstanding.
