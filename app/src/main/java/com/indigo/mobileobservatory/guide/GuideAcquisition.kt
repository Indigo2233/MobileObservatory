package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.Camera
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Serializes SDK writes and exposure requests, including a stop requested during a blocking write. */
class GuideAcquisition(
    private val mutex: Mutex,
    private val current: () -> Camera?,
    private val start: (Camera) -> Unit,
    private val retireFrames: () -> Unit,
    private val publish: (Boolean) -> Unit
) {
    private val desired = MutableStateFlow(false)
    val requestedState = desired.asStateFlow()
    var requested: Boolean
        get() = desired.value
        set(value) { desired.value = value }

    suspend fun reconcile(camera: Camera) = mutex.withLock {
        if (current() !== camera) return@withLock
        try {
            if (requested && !camera.isCapturing.value) start(camera)
            // Recheck after a blocking SDK start: a stop may have arrived in the meantime.
            if (!requested && current() === camera) {
                retireFrames()
                camera.stopCapture()
                check(camera.isCaptureStopped) { "Guide exposure did not stop" }
            }
        } finally { publish(current()?.isCapturing?.value == true) }
    }

    suspend fun update(camera: Camera?, write: (Camera?) -> Unit): Boolean = mutex.withLock {
        if (current() !== camera) return@withLock false
        val resume = requested && camera?.isCapturing?.value == true
        try {
            retireFrames()
            camera?.stopCapture()
            publish(false)
            check(camera?.isCaptureStopped != false) { "Guide exposure did not stop" }
            write(camera)
            if (resume && requested && camera != null && current() === camera) start(camera)
            if (!requested && camera?.isCapturing?.value == true) { retireFrames(); camera.stopCapture() }
            true
        } finally { publish(current()?.isCapturing?.value == true) }
    }

    /** A complete mount move owns the same lock as SDK writes. No exposure overlaps its pulses.
     * Stream adapters establish a new acquisition generation and flush startup frames on restart. */
    suspend fun <T> withMotion(camera: Camera, motion: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(current() === camera && requested) { "Guide camera session changed or exposure stopped" }
            var stopped = false
            var failure: Throwable? = null
            try {
                retireFrames()
                camera.stopCapture()
                publish(false)
                check(camera.isCaptureStopped) { "Guide exposure did not stop before mount motion" }
                stopped = true
                currentCoroutineContext().ensureActive()
                check(current() === camera && requested) { "Guide camera session changed or exposure stopped" }
                motion()
            } catch (error: Throwable) {
                failure = error
                if (error !is CancellationException && current() === camera) requested = false
                throw error
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (stopped && requested && current() === camera && camera.isOpen.value) {
                            start(camera)
                            check(camera.isCapturing.value) { camera.lastCaptureError ?: "Guide exposure did not restart after mount motion" }
                        }
                        if (!requested && current() === camera && camera.isCapturing.value) {
                            retireFrames(); camera.stopCapture()
                            check(camera.isCaptureStopped) { "Guide exposure did not stop" }
                        }
                    } catch (resumeFailure: Throwable) {
                        if (current() === camera) {
                            requested = false
                            try { retireFrames(); camera.stopCapture() }
                            catch (stopFailure: Throwable) { resumeFailure.addSuppressed(stopFailure) }
                        }
                        if (failure != null) failure.addSuppressed(resumeFailure) else throw resumeFailure
                    } finally { publish(current()?.isCapturing?.value == true) }
                }
            }
        }
    }
}
