package com.indigo.mobileobservatory.camera

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one acquisition. A private snapshot is returned only after the camera has stopped.
 * Stream-only adapters use a bounded acquisition and retain their existing startup flush. */
suspend fun captureSingleExposureFrame(
    camera: Camera,
    timeoutMs: Long,
    elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
    checkCamera: () -> Unit = {},
    acceptFrame: (FrameData) -> Boolean = { true },
    snapshot: (FrameData) -> CameraCfaLayout.Snapshot = { CameraCfaLayout.ownedSnapshot(it, camera.sciencePixelTraits) }
): CameraCfaLayout.Snapshot? {
    require(timeoutMs > 0)
    check(camera.isCaptureStopped) { "Previous camera acquisition has not stopped" }
    val received = CompletableDeferred<CameraCfaLayout.Snapshot>()
    val closed = AtomicBoolean(false)
    val claimed = AtomicBoolean(false)
    var failure: Throwable? = null
    try {
        currentCoroutineContext().ensureActive()
        checkCamera()
        val deadline = elapsedMs() + timeoutMs
        val callback = object : FrameCallback {
            override fun onFrame(frame: FrameData) {
                try {
                    if (closed.get() || !acceptFrame(frame) || !claimed.compareAndSet(false, true)) return
                    checkCamera()
                    val owned = snapshot(frame)
                    if (!closed.get()) received.complete(owned)
                } catch (error: Throwable) { received.completeExceptionally(error) }
                finally { camera.recycleBuffer(frame.data) }
            }
        }
        if (camera is CameraSingleExposureCapable) camera.startSingleExposure(callback)
        else camera.startCapture(callback)
        while (elapsedMs() < deadline) {
            currentCoroutineContext().ensureActive()
            checkCamera()
            if (!camera.isCapturing.value && !claimed.get() && !received.isCompleted) {
                error(camera.lastCaptureError ?: "Camera acquisition did not start or stopped before delivering an image")
            }
            val result = withTimeoutOrNull(minOf(1000L, deadline - elapsedMs()).coerceAtLeast(1)) { received.await() }
            if (result != null) {
                currentCoroutineContext().ensureActive()
                checkCamera()
                return result
            }
        }
        return null
    } catch (error: Throwable) { failure = error; throw error }
    finally {
        closed.set(true)
        received.cancel()
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                camera.stopCapture()
                check(camera.isCaptureStopped) { "Camera acquisition could not be stopped" }
            }
        } catch (stopFailure: Throwable) {
            if (failure != null) failure.addSuppressed(stopFailure) else throw stopFailure
        }
    }
}
