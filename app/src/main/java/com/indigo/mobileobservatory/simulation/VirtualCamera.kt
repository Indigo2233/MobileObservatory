package com.indigo.mobileobservatory.simulation

import com.indigo.mobileobservatory.camera.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Exposure-timed preview backed by the shared sky. Synthetic pixels carry no science provenance. */
class VirtualCamera(val observatory: VirtualObservatory, private val sky: ByteArray, name: String) : Camera {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val open = MutableStateFlow(true)
    private val capturing = MutableStateFlow(false)
    override val isOpen = open.asStateFlow()
    override val isCapturing = capturing.asStateFlow()
    override val cameraInfo = CameraInfo(name, "VIRTUAL-$name", VirtualObservatory.WIDTH, VirtualObservatory.HEIGHT, 8,
        "SIMULATION", VirtualObservatory.PIXEL_UM)
    override val recordsLiveViewAsScience = false
    override val supportsHostRoi = false
    @Volatile override var currentExposureUs = 500_000f; private set
    @Volatile override var currentGain = 100f; private set
    override val currentPixelFormat = PixelFormat.MONO8
    override val supportedPixelFormats = listOf(PixelFormat.MONO8)
    override val currentRoi = Roi(0, 0, VirtualObservatory.WIDTH, VirtualObservatory.HEIGHT)
    override val cropInfo = CropInfo(0, 0, VirtualObservatory.WIDTH, VirtualObservatory.HEIGHT)
    override val exposureRange get() = FloatRange(100_000f, 5_000_000f, currentExposureUs)
    override val gainRange get() = FloatRange(0f, 300f, currentGain)
    override val hwExposureMaxUs = 5_000_000f
    override var longExposureEnabled = false
    private val epoch = AtomicLong()
    private val sequence = AtomicLong()
    private var job: Job? = null
    @Volatile private var captureError: String? = null
    override val lastCaptureError get() = captureError
    private val buffers = ArrayBlockingQueue<ByteArray>(3).apply { repeat(3) { offer(ByteArray(sky.size)) } }
    private val leased = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ByteArray, Boolean>())

    @Synchronized override fun startCapture(callback: FrameCallback) {
        check(open.value)
        stopCapture()
        val run = epoch.incrementAndGet()
        captureError = null
        capturing.value = true
        job = scope.launch {
            try {
                while (isActive) {
                    val exposure = currentExposureUs
                    delay((exposure / 2000).toLong())
                    val snapshot = observatory.snapshot()
                    delay((exposure / 2000).toLong())
                    val data = synchronized(buffers) { buffers.poll()?.also { leased.add(it) } } ?: continue
                    render(snapshot, data)
                    synchronized(this@VirtualCamera) {
                        if (open.value && epoch.get() == run) try {
                            callback.onFrame(FrameData(data, currentRoi.width, currentRoi.height, PixelFormat.MONO8,
                                sequence.incrementAndGet(), System.currentTimeMillis()))
                        } catch (failure: Exception) {
                            recycleBuffer(data); captureError = failure.message; stopCapture()
                        }
                        else recycleBuffer(data)
                    }
                }
            } finally { if (epoch.get() == run) capturing.value = false }
        }
    }
    fun render(snapshot: VirtualSkySnapshot, data: ByteArray = ByteArray(sky.size)): ByteArray {
        if (!observatory.starsVisible) { data.fill(20); return data }
        val w = currentRoi.width; val h = currentRoi.height
        val dx = snapshot.x.mod(w.toDouble()); val dy = snapshot.y.mod(h.toDouble())
        val ix = floor(dx).toInt(); val iy = floor(dy).toInt()
        val fx = dx - ix; val fy = dy - iy
        val brightness = sqrt(currentExposureUs / 500_000.0) * 2.0.pow((currentGain - 100) / 100.0)
        for (y in 0 until h) {
            val sy = (y + iy) % h; val ny = (sy + 1) % h
            for (x in 0 until w) {
                val sx = (x + ix) % w; val nx = (sx + 1) % w
                val a = (sky[sy*w+sx].toInt() and 255) * (1-fx) + (sky[sy*w+nx].toInt() and 255) * fx
                val b = (sky[ny*w+sx].toInt() and 255) * (1-fx) + (sky[ny*w+nx].toInt() and 255) * fx
                data[y*w+x] = (((a*(1-fy)+b*fy-20)*brightness)+20).roundToInt().coerceIn(0,255).toByte()
            }
        }
        return data
    }
    @Synchronized override fun stopCapture() { epoch.incrementAndGet(); job?.cancel(); job = null; capturing.value = false }
    @Synchronized override fun close() { stopCapture(); open.value = false; scope.cancel() }
    override fun setExposureTime(us: Float) { require(us.isFinite()); currentExposureUs = us.coerceIn(exposureRange.min, exposureRange.max) }
    override fun setGain(value: Float) { require(value.isFinite()); currentGain = value.coerceIn(0f,300f) }
    override fun setPixelFormat(format: PixelFormat) { require(format == PixelFormat.MONO8) }
    override fun setRoi(roi: Roi) { require(roi == currentRoi) }
    override fun resetRoi() {}
    override fun recycleBuffer(buf: ByteArray) { synchronized(buffers) { if (leased.remove(buf) && open.value) buffers.offer(buf) } }
}
