# Scientific capture provenance

The four astronomy adapters expose `Camera.scienceCaptureGeneration` and attach
the acquisition run's immutable token to `FrameData.captureGeneration`. This is
a run boundary, **not** a device measurement of each frame's exposure duration.

Before a run, exposure and gain are read back from the SDK. Failed or invalid
read-back leaves the generation null for scientific consumers while ordinary
preview may continue. Host auto-exposure and all exposure, gain, offset, ROI,
format, bin and readout writes invalidate the run's scientific provenance before
the SDK write. Receiving more frames cannot restore it. Stop and restart capture
after settings settle, then bind the token to one immutable settings snapshot.

The ViewModel must require that the frame token, snapshot token, and camera's
current verified token all agree. It must never label an arriving frame using
mutable current exposure/gain values. Late frames from an earlier SDK read or
Player One delivery queue retain the earlier token and cannot enter a new stack
or establish valid calibration recording metadata.

Stopping invalidates the token and stops the SDK before joining readers. A reader
that fails to terminate, or a failed SDK stop, prevents another capture start.
Player One delivery slots retain their original token. ToupTek flushes both host
and camera caches with `TOUPCAM_OPTION_FLUSH=0x3d` (the bundled SDK header), and
requires flush success for scientific provenance. QHY single-frame cancellation
uses `CancelQHYCCDExposingAndReadout`, which discards exposure and readout, rather
than the weaker API that still requires consuming the previous image.

QHY scientific provenance is supported only by its high-bit (>8-bit)
single-frame capture path. Its 8-bit live path continues ordinary preview and
recording but has null scientific provenance: `GetQHYCCDLiveFrame` does not
distinguish an empty SDK queue from a generic read failure, and `StopQHYCCDLive`
does not document a queue flush. `requiresHighBitScienceCapture` lets the UI
explain that a 16-bit format must be selected before live stacking. Recorded live
data cannot establish a verified calibration-source sidecar. Single-frame
exposure starts and cancellation are serialized so a late reader cannot start
another exposure after stop has cancelled it.

ZWO and Player One additionally drain the new run's SDK queue before publishing
scientific provenance. ZWO uses `ASIGetVideoData(..., timeout=0)` until an explicit
`ASI_ERROR_TIMEOUT`; Player One uses `isImageReady()` / `POAImageReady` and consumes
ready images with a non-blocking read. The startup operation is bounded to 32 reads
and 250 ms. Other errors, exhausted limits, or invalid settings read-back leave
the run preview-only. Their reader discards the first further complete image
before callback delivery, including Player One's delivery-slot enqueue; timeouts
do not consume this discard. Native/direct buffers are reused by the capture loop.

SDK contracts used here: the bundled Player One API HTML documents stop as
stopping exposure and transmission and exposes readiness/non-blocking image reads;
the vendor's [PlayerOneCamera.h](https://sources.debian.org/src/libplayerone/3.10.0%2B20260316154023-1/PlayerOneCamera.h)
describes readiness as available image data. The vendor's
[ASICamera2.h](https://sources.debian.org/src/libasi/1.27%2B20221218230335-3/ASICamera2.h)
defines timeout as no image obtained and its video read consumes queued images.
Neither contract explicitly promises that stop/start clears every internal camera
DDR/USB cache. The empty-queue plus one-image discard is a conservative host-side
boundary, not verified per-frame exposure telemetry or proof for arbitrary firmware.
Testing exposure/gain/ROI switches on supported physical models remains required.

`CaptureGenerationTest` deterministically models delayed SDK delivery, settings
invalidation, restart, failed read-back and multiple cameras. SDK/device stop,
cache and control read-back behavior still needs hardware verification for each
supported camera; these unit tests cannot establish firmware behavior.
`CaptureStartupBarrierTest` additionally covers multiple stale queued images,
the boundary-image discard, explicit SDK failures, both limits and cancellation.
