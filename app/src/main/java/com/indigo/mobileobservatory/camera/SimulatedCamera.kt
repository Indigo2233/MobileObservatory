package com.indigo.mobileobservatory.camera

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Explicit UI demo source. Never supplies scientific provenance or connects to hardware. */
class SimulatedCamera(displayName: String) : Camera {
    companion object { const val WIDTH = 640; const val HEIGHT = 480 }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val open = MutableStateFlow(true)
    private val capturing = MutableStateFlow(false)
    override val isOpen = open.asStateFlow()
    override val isCapturing = capturing.asStateFlow()
    override val cameraInfo = CameraInfo(displayName, "SIMULATION-ONLY", WIDTH, HEIGHT, 8, "SIMULATED")
    override val exposureRange get() = FloatRange(1_000f, 10_000_000f, currentExposureUs)
    override val gainRange get() = FloatRange(0f, 300f, currentGain)
    @Volatile override var currentExposureUs = 1_000_000f
        private set
    @Volatile override var currentGain = 100f
        private set
    @Volatile override var currentPixelFormat = PixelFormat.BAYER_RG8
        private set
    override val supportedPixelFormats = listOf(PixelFormat.RGB24, PixelFormat.MONO8, PixelFormat.BAYER_RG8)
    @Volatile override var currentRoi = Roi(0, 0, WIDTH, HEIGHT)
        private set
    override val cropInfo get() = CropInfo(currentRoi.x, currentRoi.y, WIDTH, HEIGHT)
    override val hwExposureMaxUs = 10_000_000f
    override var longExposureEnabled = false
    override val recordsLiveViewAsScience = false
    private var captureJob: Job? = null
    private val epoch = AtomicLong()
    private val frames = AtomicLong()
    val framesEmitted get() = frames.get()
    // Every format/ROI fits in a full RGB frame. Three leases survive setting changes.
    private val buffers = ArrayBlockingQueue<ByteArray>(3).apply { repeat(3) { offer(ByteArray(WIDTH * HEIGHT * 3)) } }
    private val leased = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ByteArray, Boolean>())
    private val sky = makeSky()

    @Synchronized override fun startCapture(callback: FrameCallback) {
        check(open.value)
        stopCapture()
        val run = epoch.incrementAndGet()
        capturing.value = true
        captureJob = scope.launch {
            try {
                while (isActive && open.value && epoch.get() == run) {
                    val data = synchronized(buffers) { buffers.poll()?.also { leased.add(it) } }
                    if (data == null) { delay(20); continue }
                    val roi = currentRoi
                    val format = currentPixelFormat
                    val brightness = (sqrt(currentExposureUs / 1_000_000.0) * 2.0.pow((currentGain - 100) / 100.0)).coerceIn(.03, 12.0)
                    val drift = (frames.get() / 8 % 5).toInt() - 2
                    var out = 0
                    for (y in 0 until roi.height) for (x in 0 until roi.width) {
                        val sx = (roi.x + x + drift).coerceIn(0, WIDTH - 1)
                        val sy = roi.y + y
                        val index = (sy * WIDTH + sx) * 3
                        fun channel(c: Int) = ((sky[index + c].toInt() and 255) * brightness).roundToInt().coerceIn(0, 255)
                        when (format) {
                            PixelFormat.RGB24 -> { data[out++] = channel(0).toByte(); data[out++] = channel(1).toByte(); data[out++] = channel(2).toByte() }
                            PixelFormat.BAYER_RG8 -> data[out++] = channel(if (sy % 2 == 0) { if ((roi.x + x) % 2 == 0) 0 else 1 }
                                else { if ((roi.x + x) % 2 == 0) 1 else 2 }).toByte()
                            else -> data[out++] = ((channel(0) + channel(1) + channel(2)) / 3).toByte()
                        }
                    }
                    if (!isActive || epoch.get() != run) { recycleBuffer(data); break }
                    val outputFormat = if (format != PixelFormat.BAYER_RG8) format else when {
                        roi.y % 2 == 0 && roi.x % 2 == 0 -> PixelFormat.BAYER_RG8
                        roi.y % 2 == 0 -> PixelFormat.BAYER_GR8
                        roi.x % 2 == 0 -> PixelFormat.BAYER_GB8
                        else -> PixelFormat.BAYER_BG8
                    }
                    // Serialize delivery with stop/restart. A retired run cannot publish to its old consumer.
                    synchronized(this@SimulatedCamera) {
                        if (epoch.get() == run && open.value) {
                            try {
                                callback.onFrame(FrameData(data, roi.width, roi.height, outputFormat, frames.incrementAndGet(), System.currentTimeMillis()))
                            } catch (failure: Exception) {
                                recycleBuffer(data)
                                android.util.Log.e("SimulatedCamera", "Preview consumer failed", failure)
                                stopCapture()
                            }
                        } else recycleBuffer(data)
                    }
                    // Accelerated preview for UI testing; deliberately not exposure-timed science.
                    delay(125)
                }
            } finally { if (epoch.get() == run) capturing.value = false }
        }
    }
    @Synchronized override fun stopCapture() { epoch.incrementAndGet(); captureJob?.cancel(); captureJob = null; capturing.value = false }
    @Synchronized override fun close() {
        stopCapture(); open.value = false; scope.cancel()
        synchronized(buffers) { buffers.clear(); leased.clear() }
    }
    override fun setExposureTime(us: Float) { require(us.isFinite()); currentExposureUs = us.coerceIn(exposureRange.min, exposureRange.max) }
    override fun setGain(value: Float) { require(value.isFinite()); currentGain = value.coerceIn(0f, 300f) }
    override fun setPixelFormat(format: PixelFormat) { require(format in supportedPixelFormats); currentPixelFormat = format }
    override fun setRoi(roi: Roi) {
        require(roi.x >= 0 && roi.y >= 0 && roi.width >= 8 && roi.height >= 8 &&
            roi.x.toLong() + roi.width <= WIDTH && roi.y.toLong() + roi.height <= HEIGHT)
        currentRoi = roi
    }
    override fun resetRoi() { currentRoi = Roi(0, 0, WIDTH, HEIGHT) }
    override fun recycleBuffer(buf: ByteArray) {
        synchronized(buffers) { if (leased.remove(buf) && open.value) buffers.offer(buf) }
    }

    private fun makeSky(): ByteArray {
        val rgb = ByteArray(WIDTH * HEIGHT * 3)
        val random = java.util.Random(24015)
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val u = (x - 320) / 120.0; val v = (y - 245) / 85.0
            val haze = exp(-(u * u + v * v) / 2) * (1 + .22 * sin(u * 4 + v * 2))
            val dust = exp(-((v + u * .3) * (v + u * .3) * 10 + u * u * .3))
            val i = (y * WIDTH + x) * 3
            val noise = random.nextInt(4)
            rgb[i] = (7 + noise + 68 * haze - 20 * dust).roundToInt().coerceIn(0, 255).toByte()
            rgb[i + 1] = (9 + noise + 35 * haze - 10 * dust).roundToInt().coerceIn(0, 255).toByte()
            rgb[i + 2] = (15 + noise + 80 * haze - 15 * dust).roundToInt().coerceIn(0, 255).toByte()
        }
        repeat(160) {
            val x = random.nextInt(WIDTH); val y = random.nextInt(HEIGHT)
            val strength = 45 + random.nextInt(210); val radius = if (strength > 220) 4 else 2
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (x + dx !in 0 until WIDTH || y + dy !in 0 until HEIGHT) continue
                val value = (strength * exp(-(dx * dx + dy * dy) / 2.0)).toInt()
                val i = ((y + dy) * WIDTH + x + dx) * 3
                repeat(3) { c -> rgb[i + c] = ((rgb[i + c].toInt() and 255) + value).coerceAtMost(255).toByte() }
            }
        }
        return rgb
    }
}
