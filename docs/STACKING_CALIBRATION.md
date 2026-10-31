# Calibration and background correction acceptance record

This records the initial calibration implementation at commit `65d8dea`.
For the later selectable Bayer algorithms and current build acceptance, see
[STACKING_DEMOSAICING.md](STACKING_DEMOSAICING.md).

Recorded 2026-10-13. This extends [STACKING_IMPLEMENTATION.md](STACKING_IMPLEMENTATION.md)
and [STACKING_USABILITY.md](STACKING_USABILITY.md). It describes the current local
implementation and synthetic evidence. Physical-camera, sustained low-end Android
memory/thermal, and real-sky acceptance remain outstanding.

The current budget and live-buffer policy is recorded in
[Colour binning and live buffer ownership](#colour-binning-and-live-buffer-ownership-2026-10-31).
It supersedes the earlier 60% budget and full-size live decoder/snapshot descriptions
in the dated acceptance sections below.

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

## Colour binning and live buffer ownership (2026-10-31)

Two independent restrictions caused the reported 3856×2180 IMX585 colour failures.
Even hardware Bayer binning was rejected when an adapter only described the sensor
CFA. The live decoder also retained full-size sensor/RGB and DDFAPD scratch before
software binning, while several full-size image/weight snapshots could coexist.
Offline full-resolution deep-sky already used bounded decode, but its 330.5 MiB
estimate exceeded the previous 307.2 MiB admission ceiling on a 512 MiB VM heap.

### Colour and capture ownership

`AppBinning` now requires both SDK factor readback and an accepted output layout.
If an even-bin colour layout is unknown, the camera is restored to verified Bin 1
and the requested total Bin uses software processing. Reconnection and reselecting
an already displayed unsafe Bin also apply this check. A failed native restore
cannot be reported as successful software binning. Verified output CFA continues
to permit native colour Bin 2. Vendor libraries are unchanged.

Live science applies calibration in native sensor coordinates, reconstructs linear
RGB, then averages the requested software-bin pixels. Camera preview CFA binning
and raw recording keep their existing ownership and semantics. SER/PSER recordings
retain the raw acquisition dimensions, including native Bin 1 when the fallback is
active; select the desired processing Bin for an offline result. This change does
not reconstruct colour already lost by a camera's mixed-colour hardware binning.

### Reusable live storage and export

`LiveFrameDecoder` uses fixed-extent tiles and demosaic halos, writes one reusable
Float32 output, and validates calibration against the original sensor geometry.
No full-size sensor or DDFAPD scratch plane is retained. The input queue admits
one owned raw packet in total across waiting/processing. Overflow increments the
drop count. Deep-sky channel mean/M2 arithmetic and planetary Double sums are
unchanged.

Published live image/weight snapshots are immutable and have a maximum side of
768 pixels. Their render, latest-product and replacement overlap is included in
admission. Full-resolution data stays in the accumulator. Export executes on the
same science worker, holds new input, clears/counts queued packets and releases
decoder storage before filter preparation. Deep-sky export borrows its mean and
coverage while accumulation is held; planetary export allocates one normalized
Float32 result and borrows coverage. Cancellation/failure releases the ingestion
hold. A live reset is refused while export owns the result.

FITS and 16-bit PNG dimensions follow the science output, including the selected
software Bin. Export metadata records source dimensions, hardware/software Bin,
the tiled processing domain and the complete working estimate. Live preview
filters/background fitting operate on the bounded image; export operates at
science resolution, so spatial-filter pixels need not match the preview exactly.

### Heap admission

The ceiling is 80% of `Runtime.maxMemory()`. Start checks also deduct current
non-stack Java objects and reserve `max(16 MiB, heap / 8)` for collection. Selected
calibration storage is counted in the science estimate and removed from the
non-stack term to avoid double-counting. The UI distinguishes the ceiling from
the start-time available budget. No physical-RAM percentage or heap-limit override
is introduced.

For 3856×2180 RAW16, DDFAPD, three colour channels, no calibration masters:

| Workflow | Previous estimate | Current estimate | Science output |
|---|---:|---:|---|
| Live deep sky, full resolution | 1491.1 MiB | 376.8 MiB | 3856×2180 |
| Live deep sky, full input / software Bin 2 | 709.5 MiB | 124.2 MiB | 1928×1090 |
| Live deep sky, verified native Bin 2 | 378.8 MiB | 112.3 MiB | 1928×1090 |
| Offline deep sky, full resolution | 330.5 MiB | 330.5 MiB | 3856×2180 |

The 512 MiB ceiling is now 409.6 MiB before current-use checks. Full-resolution
live planetary, selected large masters, or other retained app objects can still
exceed the available budget. Those cases continue to require an explicit smaller
output through a larger Bin or smaller ROI, with matching calibration geometry.

### Reproducible validation

The four 585 admission regressions failed against the original implementation
before the fix. Tiled live numerical tests compare all four CFA patterns, sensor
and output CFA declarations, three demosaic algorithms, Bin 1/2/4, odd offsets and
tile edges, calibration, mono/RGB endian/layout handling, cancellation and buffer
reuse. Compared output agrees within `1e-6`; input bytes and published snapshots
stay unchanged. A full native 585 software-Bin2 decode retains 26,472,640 bytes
across eight arrays and reuses those arrays on its next frame.

A separate desktop production-engine probe completed three RAW16/DDFAPD frames,
FITS plus 16-bit PNG export with sharpening and local contrast, and accumulation
after export under `-Xmx512m`. Sampled Java heap peaks were 447,597,264 bytes for
full resolution and 227,540,992 bytes for software Bin 2. These are sampled used
heap, including temporary objects awaiting collection. Physical-camera measurement
remains a separate acceptance step.

Validation completed with 511 JVM tests, 33 distinct API 34 Android tests and no
Lint errors. The Android process reported a 576 MiB heap. It completed full-size
and software-Bin2 live colour workflows, pause/export/resume, full-size offline
deep-sky processing, native CFA fallback on connection and reselect, and existing
camera/recording and export lifecycle regressions. Live FITS samples agree with
the immutable preview's corresponding science samples; PNG dimensions and exact
16-bit processing values are checked separately from its 8-bit display.

| Android case | Sampled Java heap peak | Sampled native heap peak |
|---|---:|---:|
| Full-resolution live, three accepted frames and filtered export | 421,939,888 bytes | 7,062,448 bytes |
| Software Bin 2, isolated fresh process, same workflow | 149,079,576 bytes | 9,014,928 bytes |
| Full-resolution offline, two frames and filtered export | 391,016,832 bytes | 10,450,752 bytes |

These sample the whole test process every 10 ms, including test fixture storage.
The Bin2 run in the shared multi-test process also recorded 427,147,520 bytes,
including carryover from the preceding full-size test before cleanup/collection.
The isolated run above measures Bin2 without that preceding workload.

Local logs, the capped-heap probe and Android workflow measurements are kept in
`build/diagnostics/stack-bin2-memory`. Physical IMX585 acquisition, vendor SDK
buffers, sustained capture and phone-specific memory/thermal acceptance remain
outstanding.

## FP16 live deep-sky precision assessment (2026-10-31)

This assessment keeps the application accumulation algorithm unchanged. Both live
and offline deep-sky processing use `DeepSkyStacker`: per-channel Float32 running
mean and M2, plus a Float32 effective count per pixel. The integer-valued counts
are exact throughout these experiments. Each accepted sample updates
`mean += (sample - mean) / nextCount`, followed by the Welford M2 update.
Registration support and rejected samples make counts vary between pixels.
Live planetary processing instead retains Float64 sums and divides by each
pixel's effective count for preview/export.

### Experimental controls and scope

`scripts/Measure-StackFp16.ps1` compiles the current production detector,
registration and deep-sky engine with a generated diagnostic engine. The
diagnostic changes only its constructor/class name and the mean/M2 storage
updates; input quantization happens in the harness. A 24-frame control verifies
bit-identical FP32 image/weight results and matching rejection/registration
statistics. Every run archives the harness and generated engine, records source
SHA-256 hashes and verifies that sources stayed unchanged.

Binary16 conversion uses JDK 21 `Float.floatToFloat16` / `float16ToFloat`, with
round-to-nearest-even. Boundary controls cover ties, subnormals, the maximum
finite value and counter stagnation at 2048. FloatArray shadow storage retains
the exact binary16 round-trip values; arithmetic remains FP32. This isolates
storage precision. It does not measure packed-half allocations or hardware
half-arithmetic performance.

Eight synthetic 128×128 RGB post-decode scenes cover dark/bright backgrounds,
low noise, fractional negative calibrated values, midtones, 12-bit-like ADC
quantization, a planar background gradient and rising sky brightness. Scenes
include bright registration stars, five faint stars, subpixel motion where
configured, and late transient pixels. The dark scene runs for 4096 frames;
the other seven run for 1024. Eight storage configurations produce 264
scene/checkpoint/configuration records. All selected runs completed with source
checks and the FP32 parity control passing.

Noise below means the standard deviation of background residuals after
subtracting the known scene background. It is measured in normalized 16-bit
code units (`1 unit = 1/65535` in the linear image). The CSV also records
background bias, per-pixel differences, faint-star amplitudes, rejection/count
differences and M2 errors, so a low noise number alone cannot establish accuracy.
These are synthetic numerical results; physical camera noise, Bayer decoding,
4K runtime and device heap consumption require separate validation.

### Direct half storage stops long-stack convergence

For the dark scene (background 1000.25, single-frame noise sigma 8), changing
input, mean and M2 storage to binary16 gives:

| Frames | FP32 background noise | FP16 background noise | FP16 / FP32 |
|---:|---:|---:|---:|
| 16 | 1.49105 | 1.53632 | 1.03 |
| 64 | 0.74289 | 1.02005 | 1.37 |
| 256 | 0.36497 | 1.01571 | 2.78 |
| 1024 | 0.18765 | 1.01571 | 5.41 |
| 4096 | 0.09293 | 1.01571 | 10.93 |

The mean-only half-storage arm gives a 10.92 noise ratio at 4096 frames,
identifying repeated mean rounding as the main failure in this scene.
Input-only half storage gives a 0.99997 noise ratio and a 0.00156% median
relative faint-star amplitude difference. Its background bias changes by
approximately -0.0153 code units, so noise equivalence does not imply identical
pixel values.

At 1024 frames, direct input/mean/M2 half storage gives noise ratios of 11.66
for the bright scene, 4.36 for low noise, 1.02 for the near-zero calibrated
background, 19.54 for midtones, 11.79 for the 12-bit-like input, 6.42 for the
gradient and 5.39 for the rising sky. The midtone scene's faint-star amplitude
difference is 22.13%. Input-only half storage also increases midtone noise by
20.40%, so a general input conversion still needs range/noise validation.

M2 has an independent precision problem. Keeping mean/input in FP32 while
storing unscaled M2 in half precision reduces the dark scene's median background
M2 to 43.12% of its FP32 value. In the low-noise and calibrated scenes the median
M2 becomes zero. The current rejection threshold also uses the detected frame
noise, which can mask this damage in output pixels. Matching final noise or
rejection counts therefore cannot validate the variance state.

### Sum first, divide for display

`scripts/stacking/fp16_accumulation_probe.py` isolates this question from
registration and sigma rejection. For each of four scenes it sends identical
FP32 samples to FP32/FP16 running means and sums, using an FP64 mean of those
same samples as reference. There are 1024 independent pixels and 4096 frames,
with seed 585. Arithmetic remains FP32 and the candidate accumulator is rounded
to its storage type after every update; the final division is FP32.

For background 1000.25 and noise sigma 8, mean absolute error against the
reference, in the same 16-bit code units, is:

| Frames | FP32 mean | FP32 sum / count | FP16 mean | FP16 sum / count |
|---:|---:|---:|---:|---:|
| 16 | 0.000033 | 0.000094 | 0.2975 | 0.4959 |
| 64 | 0.000067 | 0.000273 | 0.5380 | 1.0988 |
| 256 | 0.000126 | 0.000294 | 0.8442 | 15.0511 |
| 1024 | 0.000256 | 0.011257 | 0.9226 | 21.3521 |
| 4096 | 0.000705 | 0.014232 | 0.9448 | 486.8310 |

The last FP16 sum error is predominantly a negative bias (-486.8310). Bright
and midtone scenes also develop substantial negative sum bias at 4096 frames;
the near-zero calibrated scene instead has smaller FP16 sum error than FP16
mean error (0.01459 versus 0.03430). Sum and online mean thus have
data-dependent numerical behavior. These results provide no basis for a
general precision improvement from changing to an FP16 sum.

For a deterministic control, adding the exactly representable input 0.25 into
an FP16 sum 4096 times produces 512, then a mean of 0.125. The exact sum/mean
are 1024 and 0.25. At sum 512, the next 0.25 addition lands on a rounding tie
and rounds back to 512. No overflow or input quantization is needed for this
failure. Deferring division preserves the rounding errors accumulated during
each addition.

Binary16 has 11 significant binary digits for normal values. As a sum grows,
its absolute representable spacing grows with its exponent. A final division
by the count scales both the sum and its accumulated error. Keeping the sum
in FP32/FP64, or designing compensated/blocked accumulation, changes that
tradeoff and needs its own memory and numerical assessment. Each pixel still
requires its own effective count.

### Centering helps, with scene-dependent limits

Two additional diagnostic arms freeze each channel's first-frame median as an
origin, store `(value - origin) × 256` in half precision, and reconstruct in
FP32. One keeps M2 in FP32; the other stores `M2 × 65536` in half precision.
Both compute the M2 increment using the unrounded updated mean. All values
remain finite in the tested cases.

The centered mean/input with FP32 M2 preserves dark-scene background noise
within 0.063% at 4096 frames; the selected faint-star amplitude difference is
0.198%. Scaled half M2 still ends 7.48% below the FP32 reference in that run.
Most uniform-background cases show small background-noise changes, while
bright-star pixels retain larger errors: full covered-region RMS differences
are approximately 1.1 code units for the dark and bright cases, with maximum
differences around 31–33.

A 600-code-unit peak-to-peak spatial gradient raises centered half noise to
1.9102 times FP32 at 1024 frames. A sky increase of 0.02 code units per frame
raises it to 1.0113 times FP32. A fixed channel origin therefore does not
establish acceptable precision across the image or across changing conditions.
The separate centered-sum probe likewise demonstrates only its four scalar
background cases, without registration, stellar range or rejection validation.

The current evidence supports retaining FP32 mean/M2 in the science engine.
Further storage reductions should evaluate input encoding and spatial/block
residual schemes with explicit error/range controls, including gradients,
bright-star cores, changing backgrounds and long-sequence variance accuracy.
The centered candidates remain diagnostic prototypes.

### Memory implications and reproduction

For 3856×2180 RGB, one FP32 pixel array occupies 96.20 MiB; packed binary16 would
occupy 48.10 MiB. Input + mean + M2 payloads would fall from 288.60 to 144.30 MiB.
The effective-count array remains 32.07 MiB. Unchanged decoder, raw packet,
preview, rendering and other application storage prevent a whole-process 50%
reduction.

With the existing RAW16/DDFAPD phase model and no calibration masters, converting
all three RGB buffers gives an analytical stacking-phase estimate of
218.34 MiB versus 362.64 MiB. Export could reach 280.61 MiB versus 376.81 MiB
only if mean/M2 stay packed and export reads the mean by row or tile without
creating a full FP32 image. The existing FP32 export-filter workspaces remain
budgeted. These are conditional payload calculations; the numerical harness
retains FloatArray shadow storage.

Run with JDK 21 or newer and an existing Gradle Kotlin compiler cache:

```powershell
.\scripts\Measure-StackFp16.ps1 -JavaHome 'C:/Program Files/Java/jdk-21' -Frames 4096 -Scenarios dark -OutputDirectory build/diagnostics/fp16-precision/long-run
.\scripts\Measure-StackFp16.ps1 -JavaHome 'C:/Program Files/Java/jdk-21' -Frames 1024 -Scenarios 'bright,low_noise,calibrated,midtones,raw12' -OutputDirectory build/diagnostics/fp16-precision/contexts
.\scripts\Measure-StackFp16.ps1 -JavaHome 'C:/Program Files/Java/jdk-21' -Frames 1024 -Scenarios 'gradient,drifting_sky' -OutputDirectory build/diagnostics/fp16-precision/gradient
python scripts/stacking/summarize_fp16.py build/diagnostics/fp16-precision
python scripts/stacking/fp16_accumulation_probe.py build/diagnostics/fp16-precision
```

NumPy is required for the independent accumulation probe; matplotlib optionally
produces the comparison figure. Local artifacts are beneath
`build/diagnostics/fp16-precision`: `summary.json`, `combined.csv`,
`mean-versus-sum.json`/`.csv`, `precision-comparison.svg`/`.png`, and each run's
`control.json`, source snapshot, precision CSV and faint-star CSV. The independent
probe archives its script, runtime versions, source hash and exact-value controls.
No APK or Android version change accompanies this assessment.

## Display highlights, white balance and polar colour preview (2026-10-31)

The reported M31 display failure has a deterministic synthetic reproduction:
a linear 256×128 RGB galaxy has a central peak near 0.56, a faint elongated disk
and a 0.003 background. The original automatic 99.5th-percentile range selected
a white point of 0.01290. The renderer clipped every brighter sample to 1 before
the nonlinear curve, making the core and its immediate surroundings identical
white. The former highlight-compression expression increased bright values,
and new Natural/Intense presets left automatic white balance disabled.
Three focused regressions reproduced these failures before the fix.

New processing parameters use `displayVersion: 2`. The white point defines the
normalization range while positive signal above it is retained through the
asinh curve. Highlight strength `s > 0` places a unit-slope knee at `k = 1-s`;
for curved signal `x > k`, the shoulder is
`k + s * (x-k) / (x-k+s)`. This is continuous, strictly increasing for finite
signals above the knee, and approaches 1 without a hard cutoff. The midtone
and brightness controls follow it. Setting compression to zero retains a hard
display white. Display LUTs cover the ordinary range; brighter samples use
the same exact scalar shoulder as the 16-bit PNG export.

The same reproduction now retains a Natural core near 0.965 with distinct
neighbouring display levels. Tests also check a visible disk, moderate sky,
monotonic highlights, compression direction, finite/gamut-bounded output and
unchanged source samples for Natural and Intense. These are synthetic scene
checks. A physical-camera M31 frame has not been provided for this revision.

New Natural/Intense presets enable automatic white balance, with its existing
switch moved into the common processing controls. Version 2 uses the median
green/red and green/blue ratios of the 30th–70th percentile positive background
samples, excluding invalid and near-saturated RGB samples. Manual channel gains
remain multiplicative and the independent bypasses retain their behavior.
Automatic display-range estimation samples the same background-corrected,
balanced linear luminance as rendering and shares its small background-model
cache. No full-resolution scratch plane is introduced. This background balance
does not establish photometric or instrument colour calibration.

Recipe metadata records `displayVersion`. Missing values select version 1,
which retains the original tone curve and white-balance statistics. Unknown
versions are rejected. The archived pre-optimization pixel/background hashes
continue to exercise version 1; new recipes round-trip all display controls.
Linear accumulation, effective counts, calibration, and raw FITS samples are
unchanged.

For polar previews, integer single-plane camera FITS reductions previously
became RGB48 after area averaging. Live Bayer frames instead used a CFA
histogram and applied gains/clipping before demosaicing. That changed both the
histogram and clipping order at the size threshold. Reduced camera transport
now averages each CFA parity separately into a Bayer image, retaining native
valid depth and crop phase. It uses one source row and bounded row sums/counts.
The existing live renderer then applies the same recipe and processing order.
Mono reductions retain their native depth too. Album processing keeps its
existing RGB area-sampling path. Native polar crops retain the overview window.

The final night-build UI regression also exposed a blank preview while loading
state and decoded-image state were published separately. Diagnostic tracing
observed publication on an IO thread in that test environment. Polar preview
loading now returns one immutable image/format state from IO and publishes it
on `Dispatchers.Main.immediate`; the Bitmap is derived from that same prepared
image. Cancellation only recycles unpublished images. A rapid source/recipe
switching regression verifies that the final selected image becomes visible.

Raw transport tests cover all declared depths already exercised, CFA phase,
odd crop origins, reduction factors 2–4, incomplete edge cells, small stars,
source integrity and cancellation. Android regressions compare a reduced
2048×1536 colour FITS with the live camera preview, and exercise existing
native crop, thumbnail, LP700 and monochrome paths. Stacking checks include
prepared/exact rows, display LUT error, 16-bit PNG values, recipe replay,
automatic adaptation and export lifecycle. Local diagnostics are under
`build/diagnostics/display-highlights`; this change does not increment build 60.

Initial display-change validation: 275 related JVM tests passed with no failures/errors/skips;
17 API 34 emulator tests passed. Lint completed with zero errors and 336
warnings. The emulator crash buffer was empty. `acceptance.json` and the test/
Lint logs record these results. At that checkpoint the phone APK in `bin/Installer`
had not been replaced. The temporary emulator was stopped; automatic approval policy denied
temporary-image deletion, so those files remain in the diagnostics directory.

## RGB48 packet admission for the night test build (2026-10-31)

Final package review found a separate 32 MiB input-packet ceiling inherited from
mono/RAW16. A 3856×2180 RGB48 frame needs 50,436,480 bytes (48.10 MiB), so both
admission and the owned input queue rejected it even with sufficient working
memory. Four new regressions reproduce the packet and pixel-boundary behavior;
three failed before the fix.

The absolute packet ceiling now covers one RGB48 frame at the existing
16,777,216-pixel maximum (96 MiB). The queue also checks that pixel maximum
before copying, preserving it for mono and lower-byte formats. Explicit smaller
caller limits and truncated-buffer checks remain effective. Production still
owns at most one queued/processing packet. Per-format working estimates,
calibration storage, the 80% heap ceiling and start-time non-stack/GC reserves
remain unchanged.

For 3856×2180 RGB48 deep sky at full resolution, the current science estimate
is 413,918,928 bytes (394.74 MiB), before start-time non-stack deductions.
This is an allocation model, without a physical camera SDK measurement.
Regression checks cover admission at the calculated budget and rejection one
byte below it, calibrated budgets, an immutable full-size queued copy, overflow
drops, an explicit smaller packet budget and pre-copy rejection above the pixel
ceiling. A new Android fixture exercises full RGB48 input with software Bin 2,
filtered FITS/16-bit PNG export and resumed acquisition.

Night-build source snapshots, build/validation logs and artifact verification
are kept beneath `build/diagnostics/night-test-20261031`. The local test build
keeps v1.0.9 / build 60 and uses the existing maintainer/legacy signing lineage.

The final local signed APK was built on 2026-10-31 at 20:14 Asia/Shanghai and
replaced both installer APK names. Final validation passed all 1,393 Release JVM
tests (zero failures/errors/skips), 24 focused Android tests and Release Lint
with zero errors. Android coverage includes 20 native solve-cancellation
iterations, full-resolution RAW16 accumulation/export/resume, RGB48 input with
software Bin 2, and the final polar preview state/rapid recipe-change cases.
The crash buffer was empty. Initial fixture errors, the emulator System UI
overlay and preview-state failures remain archived separately.

APK SHA-256:
`40e223432367c0c5e067ccb177511b450d599f84468297d44cf9005c0ec037cd`.
`apk-verification.json` records both signing identities, the source snapshot,
ARM64-only packaging, disabled sequence entries and unchanged frozen ZWO
libraries. Physical-camera and real M31 validation remain for the night test.
