# Capture correctness and stacking allocation follow-up

This follow-up to `b081540` updates the existing EAA/GC/DBE/calibration PR. The
default remains automatic gradient correction and DDFAPD for Bayer input. No
calibration master is mandatory and no new processing controls are added.

## Correctness

- Capture settings are frozen against a verified acquisition generation. A
  buffered callback cannot take the current UI exposure. Setting changes stop
  acquisition before SDK writes and restart it afterward; pause/resume shares
  the same transaction mutex. Late callbacks from a previous verified run are
  discarded. The first completed frame is required before starting a live stack.
- ZWO/Player One explicitly drain queued SDK images with bounded nonblocking
  reads, require an unambiguous empty result and discard the boundary image.
  ToupTek uses the bundled SDK's host/camera flush option. QHY single-frame
  cancellation discards exposure and readout; its 8-bit live API cannot prove
  empty SDK buffers, so ordinary preview/recording remains available but verified
  live stacking asks for a 16-bit format. Details and hardware evidence limits
  are in `app/src/main/java/com/indigo/mobileobservatory/camera/README.md`.
- One recording worker owns write, drain, close and finalization. Any write,
  drain, timeout, interruption or close failure is sticky. The initial invalid
  `.capture.json` can become valid only after successful finalization with
  unchanged verified capture settings. A timed-out worker keeps its file busy
  until it actually closes; a new recorder cannot reuse its state or buffers.
- `SERReader.open()` validates file structure independently of science budgets.
  Playback reads bounded previews (at most 1,048,576 pixels, longest side 2048)
  through a 128 KiB page cache, including packed PSER and high-bit Bayer/RGB.
  Full raw/science entry points retain their separate size and memory limits.
  Sparse 26 MP recordings and frame offsets beyond 2 GiB are tested.

## Allocation changes

Payload figures below are capacities for 1024 x 768, excluding object headers.
They describe removed allocations, not measured Android peak-heap reductions.

| Path | Change | Removed/reused payload |
| --- | --- | ---: |
| Raw recording | Normalize only the queue-owned copy in place; safe-copy API remains available | 1.5 MiB per 16-bit mono frame |
| DDFAPD | Worker-owned session workspace; release on reset/close or geometry change | 24.75 MiB scratch reused after warmup |
| Rejected live frame | Statistics change independently of image version; share immutable snapshot arrays and skip rendering | 12 MiB per redundant RGB snapshot |
| Natural processing | No full luminance plane or noise sort when spatial filters are off | 3 MiB luminance plane |
| Background model | Cache coefficients/statistics by immutable image identity and complete background parameters | No repeated star detection/fitting for tone-only changes |
| Preview/PNG | Same pixel mapper writes bounded rows directly to Bitmap | 9 MiB full RGB output, plus corrected RGB intermediate when GC applies |
| Planetary registration | Alignment and edge validation share one prepared grayscale plane | 3 MiB per validated frame |

DDFAPD still uses Double scratch and owns a separate Float RGB output. It has not
been tiled and its intrinsic scratch peak is unchanged. Filtered processing still
uses source-resolution luminance/blur planes to preserve spatial neighborhoods.
The background cache holds no full corrected image and does not retain an old
snapshot through its weak image key. Live statistics continue updating even when
the displayed image does not change.

## Numerical and lifecycle validation

Ten deterministic mono/RGB, natural/raw, auto/manual-background and filtered
recipes match the pre-change Float output SHA-256, background-model statistics
and white-balance gains exactly. DDFAPD reference fixtures are unchanged and
repeated workspace use is checked after resize, cancellation and invalid samples.
Bitmap tests compare full-size and downsampled previews and reopened PNG pixels.
Session tests exercise rejection-only periods, a pending accepted frame followed
by a rejection, reset and cancellation without modifying a published snapshot.

Preview restarts also serialize synchronous FrameProcessor calls and reject old
generation publications. Tests block the old converter while rapidly restarting
twice and verify exclusive conversion, one-time buffer returns and no old image
or callback after cancellation.

## Measurements

The [desktop comparison](testing/STACKING_PERFORMANCE_BENCHMARK.md) records
allocation/second, GC counters, sampled heap peak, per-frame P95 and queue drops
for seven real-code workloads against `b081540`. Inputs are 1024 x 768; each
version runs in a separate JDK 17/G1/512 MiB JVM. Final runs reverse execution
order. Source hashes and exact observations are in
[stacking-performance.json](testing/stacking-performance.json), with a reusable
PowerShell/Kotlin harness under `scripts/benchmark_stacking`.

Steady-state allocation per operation falls by 99.94% for owned recording
normalization, 73.32% for DDFAPD, 48.42% for planetary shared preparation and
58.87% for rejected-frame publication. The repeated cached preview avoids nearly
all full-frame allocation. This does **not** establish universal throughput or
peak-heap improvements: the uncached one-shot processor trades CPU work for lower
allocation; retained workspace and less-frequent collection can increase sampled
heap. The saturated desktop queue drops 224/300 frames (74.67%) in both versions.

The final Android 14 x86_64 emulator run records the current implementation only
in [stacking-performance-android.json](testing/stacking-performance-android.json).
Four warmups precede each short sample; Bitmap creation/upload is included.

| Workload | Frames | Allocation MiB/s | P95 ms | Sampled Java/native peak MiB | GC count / total ms / blocking ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Owned normalization | 24 | 0.167 | 4.37 | 44.87 / 22.67 | 0 / 0 / 0 |
| DDFAPD decode with workspace | 20 | 85.39 | 166.45 | 76.62 / 22.16 | 14 / 141 / 0 |
| Natural cached direct Bitmap | 24 | 0.087 | 307.31 | 74.92 / 25.17 | 0 / 0 / 0 |

Its separate 30 FPS synthetic decode-only queue accepts 17/60 and drops 43/60
(71.67%); this excludes registration, camera USB and disk. It is not comparable
to the desktop queue's full deep-sky workload. All performance samples may share
the Windows host with build work, and are not thermally controlled phone tests.

GC collection/blocking-time counters are **not individual stop-the-world pause
traces or pause P95**. A zero counter does not prove no pause occurred. Exact ART
pause and sustained heap/queue comparisons remain unmeasured and require device
tracing. Sampled peak heap includes retained inputs and uncollected garbage and
can miss short spikes. No physical Android camera/phone before/after comparison
or real-sky acceptance is claimed.

## Final acceptance (2026-10-13)

- JVM: **702 tests**, zero failures/errors.
- Android 14 API 34 x86_64 emulator: **42 tests**, zero failures/errors. This
  includes the full prior EAA/media/calibration/DBE/demosaic UI suite and the new
  provenance, pause/resume, large-file playback, streamed Bitmap, preview worker
  lifecycle and performance checks.
- Lint: **0 errors, 132 warnings, 6 informational findings**; no new suppression
  or baseline.
- `Build.ps1 -NonCommercial`: standard ARM64 debug APK, version 1.0.7 / build 43,
  Sequence hidden, emulator mode disabled, Stellarium included.
- APK size **40,437,211 bytes**, SHA-256
  `8534daa9c761c785350929dab341ff58a449f4fe10940fbcbbdc42793870c556`.
- Frozen source `libusb-1.0.so` and `libASICamera2.so` hashes/sizes unchanged;
  the custom JNI wrappers compile without replacing vendor binaries.

The prior intermittent Compose disposal failure remains a historical unresolved
device risk; its absence in this run does not establish a fix. Hardware SDK cache
behavior, raw layouts/temperature, sustained recording and low-memory phones
still need physical-device acceptance. The APK is a development build, not a
signed public release.
