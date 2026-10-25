package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.Camera
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
                check(!camera.isCapturing.value) { "Guide exposure did not stop" }
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
            check(camera?.isCapturing?.value != true) { "Guide exposure did not stop" }
            write(camera)
            if (resume && requested && camera != null && current() === camera) start(camera)
            if (!requested && camera?.isCapturing?.value == true) { retireFrames(); camera.stopCapture() }
            true
        } finally { publish(current()?.isCapturing?.value == true) }
    }
}
