package com.indigo.mobileobservatory.camera

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HistogramData(
    val bins: IntArray,
    val maxCount: Int,
    val totalPixels: Int,
    val blackPoint: Int,
    val whitePoint: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HistogramData) return false
        return bins.contentEquals(other.bins) && maxCount == other.maxCount
    }
    override fun hashCode() = bins.contentHashCode()
}

internal data class HighBitLayout(
    val effectiveBits: Int,
    val shift: Int,
    val zeroBits: Int
)

internal fun detectHighBitLayout(maxValue: Int, lowBitsMask: Int, declaredBits: Int): HighBitLayout {
    if (maxValue <= 0) return HighBitLayout(declaredBits, 0, 0)

    val zeroBits = when {
        (lowBitsMask and 0x3F) == 0 -> 6
        (lowBitsMask and 0x0F) == 0 -> 4
        (lowBitsMask and 0x03) == 0 -> 2
        else -> 0
    }
    val effectiveBits = when {
        zeroBits >= 6 -> 10
        zeroBits >= 4 -> 12
        zeroBits >= 2 -> 14
        else -> declaredBits
    }
    val shift = when {
        zeroBits >= 2 -> 6
        else -> (declaredBits - 10).coerceAtLeast(0)
    }
    return HighBitLayout(effectiveBits, shift, zeroBits)
}

internal class HighBitLayoutDetector(
    private val stableFrameCount: Int = 12
) {
    var sampledFrames: Int = 0
        private set

    private var sampledMaxValue = 0
    private var sampledLowBitsMask = 0

    fun observe(maxValue: Int, lowBitsMask: Int, declaredBits: Int): HighBitLayout {
        sampledFrames++
        sampledMaxValue = maxOf(sampledMaxValue, maxValue)
        sampledLowBitsMask = sampledLowBitsMask or lowBitsMask
        val layout = detectHighBitLayout(sampledMaxValue, sampledLowBitsMask, declaredBits)
        return if (sampledFrames >= stableFrameCount) {
            layout
        } else {
            layout.copy(effectiveBits = declaredBits)
        }
    }

    fun reset() {
        sampledFrames = 0
        sampledMaxValue = 0
        sampledLowBitsMask = 0
    }
}

class FrameProcessor {
    private companion object {
        const val PREVIEW_BITMAP_BUFFER_COUNT = 3
        const val HISTOGRAM_PUBLISH_INTERVAL_MS = 200L
        const val HIGH_BIT_LAYOUT_STABLE_FRAMES = 12
        const val HIGH_BIT_LAYOUT_MAX_SAMPLE_FRAMES = 30
    }

    private val _histogram = MutableStateFlow<HistogramData?>(null)
    private var lastHistogramPublishMs = 0L
    private val histogramBins256 = IntArray(256)
    private val histogramBins1024 = IntArray(1024)
    val histogram: StateFlow<HistogramData?> = _histogram.asStateFlow()

    private val _focusScore = MutableStateFlow<Float?>(null)
    val focusScore: StateFlow<Float?> = _focusScore.asStateFlow()

    var focusAssistEnabled: Boolean = false
    private var focusScoreMin = Float.MAX_VALUE
    private var focusScoreMax = Float.MIN_VALUE
    private var lastFocusComputeMs = 0L

    var autoStretchEnabled: Boolean = true
    var manualBlackPoint: Int = 0
    var manualWhitePoint: Int = 255

    var wbRedGain: Float = 1.0f
    var wbGreenGain: Float = 1.0f
    var wbBlueGain: Float = 1.0f

    enum class AwbMode { OFF, ONCE, CONTINUOUS }
    var awbMode: AwbMode = AwbMode.OFF
    private var awbPending = false

    fun triggerAwbOnce() { awbPending = true }

    fun setAwbContinuous(on: Boolean) {
        awbMode = if (on) AwbMode.CONTINUOUS else AwbMode.OFF
    }

    fun resetWb() {
        wbRedGain = 1.0f
        wbGreenGain = 1.0f
        wbBlueGain = 1.0f
        awbMode = AwbMode.OFF
        awbPending = false
    }

    private val stretchPercentileLow = 0.001f
    private val stretchPercentileHigh = 0.999f

    private var cachedBitmaps: Array<Bitmap>? = null
    private var cachedPixels: IntArray? = null
    private var cachedW = 0
    private var cachedH = 0
    private var nextBitmapIndex = 0

    fun frameToBitmap(frame: FrameData): Bitmap {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0 || w > 20000 || h > 20000) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        val expectedBytes = w.toLong() * h * frame.pixelFormat.bytesPerPixel
        if (frame.data.size < expectedBytes) {
            android.util.Log.e("FrameProcessor",
                "Buffer too small: got ${frame.data.size}, need $expectedBytes for ${w}x${h} ${frame.pixelFormat.name}")
            val bpp = frame.pixelFormat.bytesPerPixel
            val safeW = (frame.data.size / bpp / h).coerceAtLeast(1)
            return frameToBitmap(frame.copy(width = safeW.coerceAtMost(w)))
        }

        val bitmap: Bitmap
        val pixels: IntArray
        try {
            val buffers = cachedBitmaps
            if (cachedW == w && cachedH == h && buffers != null && cachedPixels != null &&
                buffers.none { it.isRecycled }) {
                bitmap = buffers[nextBitmapIndex]
                pixels = cachedPixels!!
                nextBitmapIndex = (nextBitmapIndex + 1) % buffers.size
            } else {
                val created = Array(PREVIEW_BITMAP_BUFFER_COUNT) {
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                }
                bitmap = created.first()
                pixels = IntArray(w * h)
                cachedBitmaps = created
                cachedPixels = pixels
                cachedW = w
                cachedH = h
                nextBitmapIndex = 1
            }
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("FrameProcessor", "OOM creating bitmap ${w}x${h}", e)
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        if (frame.pixelFormat == PixelFormat.RGB48) {
            fillRgb48Pixels(frame, pixels, w, h)
        } else if (frame.pixelFormat == PixelFormat.RGB24) {
            fillRgb24Pixels(frame, pixels, w, h)
        } else if (frame.pixelFormat.isBayer) {
            fillBayerPixels(frame, pixels, w, h)
        } else {
            fillMonoPixels(frame, pixels, w, h)
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)

        if (focusAssistEnabled) {
            val now = System.currentTimeMillis()
            if (now - lastFocusComputeMs >= 200) {
                lastFocusComputeMs = now
                _focusScore.value = computeFocusScore(frame)
            }
        }

        return bitmap
    }

    fun computeFocusScore(frame: FrameData): Float {
        val w = frame.width
        val h = frame.height
        if (w < 5 || h < 5) return 0f

        if (frame.pixelFormat == PixelFormat.RGB24) {
            return computeRgbLumaFocusScore(frame, bytesPerPixel = 3, valueShift = 0)
        }
        if (frame.pixelFormat == PixelFormat.RGB48) {
            return computeRgbLumaFocusScore(frame, bytesPerPixel = 6, valueShift = 8)
        }

        val data = frame.data
        val is10 = frame.pixelFormat.is10bit
        val step = 4
        var sumL = 0.0
        var sumL2 = 0.0
        var count = 0

        for (y in 2 until h - 2 step step) {
            for (x in 2 until w - 2 step step) {
                val c = getRawPixel(data, x, y, w, is10)
                val l = getRawPixel(data, x - 1, y, w, is10)
                val r = getRawPixel(data, x + 1, y, w, is10)
                val t = getRawPixel(data, x, y - 1, w, is10)
                val b = getRawPixel(data, x, y + 1, w, is10)
                val lap = (4 * c - l - r - t - b).toDouble()
                sumL += lap
                sumL2 += lap * lap
                count++
            }
        }

        if (count == 0) return 0f
        val mean = sumL / count
        val variance = (sumL2 / count - mean * mean).toFloat().coerceAtLeast(0f)

        if (variance < focusScoreMin) focusScoreMin = variance
        if (variance > focusScoreMax) focusScoreMax = variance
        val range = focusScoreMax - focusScoreMin
        return if (range > 0f) ((variance - focusScoreMin) / range * 100f) else 50f
    }

    private fun computeRgbLumaFocusScore(frame: FrameData, bytesPerPixel: Int, valueShift: Int): Float {
        val w = frame.width
        val h = frame.height
        val data = frame.data
        val step = 4
        var sumL = 0.0
        var sumL2 = 0.0
        var count = 0
        fun luma(x: Int, y: Int): Int {
            val base = (y * w + x) * bytesPerPixel
            val r: Int
            val g: Int
            val b: Int
            if (bytesPerPixel >= 6) {
                if (base + 5 >= data.size) return 0
                r = (data[base].toInt() and 0xFF) or ((data[base + 1].toInt() and 0xFF) shl 8)
                g = (data[base + 2].toInt() and 0xFF) or ((data[base + 3].toInt() and 0xFF) shl 8)
                b = (data[base + 4].toInt() and 0xFF) or ((data[base + 5].toInt() and 0xFF) shl 8)
            } else {
                if (base + 2 >= data.size) return 0
                r = data[base].toInt() and 0xFF
                g = data[base + 1].toInt() and 0xFF
                b = data[base + 2].toInt() and 0xFF
            }
            return ((r * 299 + g * 587 + b * 114) / 1000) shr valueShift
        }
        for (y in 2 until h - 2 step step) {
            for (x in 2 until w - 2 step step) {
                val c = luma(x, y)
                val lap = (4 * c - luma(x - 1, y) - luma(x + 1, y) - luma(x, y - 1) - luma(x, y + 1)).toDouble()
                sumL += lap
                sumL2 += lap * lap
                count++
            }
        }
        if (count == 0) return 0f
        val mean = sumL / count
        val variance = (sumL2 / count - mean * mean).toFloat().coerceAtLeast(0f)
        if (variance < focusScoreMin) focusScoreMin = variance
        if (variance > focusScoreMax) focusScoreMax = variance
        val range = focusScoreMax - focusScoreMin
        return if (range > 0f) ((variance - focusScoreMin) / range * 100f) else 50f
    }

    fun resetFocusRange() {
        focusScoreMin = Float.MAX_VALUE
        focusScoreMax = Float.MIN_VALUE
        _focusScore.value = null
    }

    fun resetBitShiftDetection(forceDeclaredLayout: Boolean = false) {
        forceDeclaredHighBitLayout = forceDeclaredLayout
        detectedBitShift = -1
        detectedEffectiveBits = 16
        highBitLayoutDetector.reset()
    }

    fun getDetectedEffectiveBits(): Int = detectedEffectiveBits

    private fun fillRgb24Pixels(frame: FrameData, pixels: IntArray, w: Int, h: Int) {
        val hist = computeHistogram(frame)
        publishHistogram(hist)
        val data = frame.data
        val totalPixels = w * h
        for (i in 0 until totalPixels) {
            val base = i * 3
            if (base + 2 >= data.size) break
            val r = data[base].toInt() and 0xFF
            val g = data[base + 1].toInt() and 0xFF
            val b = data[base + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun fillRgb48Pixels(frame: FrameData, pixels: IntArray, w: Int, h: Int) {
        val numBins = 1024
        val bins = reusableHistogramBins(numBins)
        val data = frame.data
        val totalPixels = w * h

        var bp = 0; var wp = 65535
        val sampleCount = totalPixels.coerceAtMost(20000)
        val step = (totalPixels / sampleCount).coerceAtLeast(1)
        var si = 0
        for (i in 0 until totalPixels step step) {
            if (si >= sampleCount) break
            val base = i * 6
            if (base + 5 >= data.size) break
            val r = (data[base].toInt() and 0xFF) or ((data[base + 1].toInt() and 0xFF) shl 8)
            val g = (data[base + 2].toInt() and 0xFF) or ((data[base + 3].toInt() and 0xFF) shl 8)
            val b = (data[base + 4].toInt() and 0xFF) or ((data[base + 5].toInt() and 0xFF) shl 8)
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            si++
            bins[(lum shr 6).coerceIn(0, 1023)]++
        }

        val maxCount = bins.max()
        val hist = computeStretchPoints(bins, numBins, maxCount, si)
        publishHistogram(hist)

        if (autoStretchEnabled) {
            bp = hist.blackPoint shl 6
            wp = hist.whitePoint shl 6
        } else {
            bp = manualBlackPoint * 65535 / 255
            wp = manualWhitePoint * 65535 / 255
        }
        val range = (wp - bp).coerceAtLeast(1)

        for (i in 0 until totalPixels) {
            val base = i * 6
            if (base + 5 >= data.size) break
            var r = (data[base].toInt() and 0xFF) or ((data[base + 1].toInt() and 0xFF) shl 8)
            var g = (data[base + 2].toInt() and 0xFF) or ((data[base + 3].toInt() and 0xFF) shl 8)
            var b = (data[base + 4].toInt() and 0xFF) or ((data[base + 5].toInt() and 0xFF) shl 8)
            r = (r * wbRedGain).toInt()
            g = (g * wbGreenGain).toInt()
            b = (b * wbBlueGain).toInt()
            r = ((r - bp) * 255 / range).coerceIn(0, 255)
            g = ((g - bp) * 255 / range).coerceIn(0, 255)
            b = ((b - bp) * 255 / range).coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        if (awbPending || awbMode == AwbMode.CONTINUOUS) {
            computeRgb48Wb(frame, w, h)
            awbPending = false
        }
    }

    private fun computeRgb48Wb(frame: FrameData, w: Int, h: Int) {
        val data = frame.data
        val step = 8
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
        var count = 0
        for (i in 0 until w * h step step) {
            val base = i * 6
            if (base + 5 >= data.size) break
            val r = (data[base].toInt() and 0xFF) or ((data[base + 1].toInt() and 0xFF) shl 8)
            val g = (data[base + 2].toInt() and 0xFF) or ((data[base + 3].toInt() and 0xFF) shl 8)
            val b = (data[base + 4].toInt() and 0xFF) or ((data[base + 5].toInt() and 0xFF) shl 8)
            sumR += r; sumG += g; sumB += b
            count++
        }
        if (count == 0) return
        val avgR = sumR / count; val avgG = sumG / count; val avgB = sumB / count
        if (avgR > 0 && avgB > 0) {
            wbRedGain = (avgG / avgR).toFloat().coerceIn(0.2f, 5.0f)
            wbGreenGain = 1.0f
            wbBlueGain = (avgG / avgB).toFloat().coerceIn(0.2f, 5.0f)
            android.util.Log.i("FrameProcessor", "RGB48 AWB: R=%.3f G=1.0 B=%.3f".format(wbRedGain, wbBlueGain))
        }
    }

    private fun fillMonoPixels(frame: FrameData, pixels: IntArray, w: Int, h: Int) {
        val hist = computeHistogram(frame)
        publishHistogram(hist)

        val maxVal = if (frame.pixelFormat.is10bit) 1023 else 255
        val bp: Int
        val wp: Int
        if (autoStretchEnabled) {
            bp = hist.blackPoint
            wp = hist.whitePoint
        } else {
            bp = manualBlackPoint * maxVal / 255
            wp = manualWhitePoint * maxVal / 255
        }
        val range = (wp - bp).coerceAtLeast(1)

        if (frame.pixelFormat.is10bit) {
            for (i in 0 until w * h) {
                val lo = frame.data[i * 2].toInt() and 0xFF
                val hi = frame.data[i * 2 + 1].toInt() and 0xFF
                var raw = (hi shl 8) or lo
                if (detectedBitShift > 0) raw = raw shr detectedBitShift
                val stretched = ((raw - bp) * 255 / range).coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (stretched shl 16) or (stretched shl 8) or stretched
            }
        } else {
            for (i in 0 until w * h) {
                val raw = frame.data[i].toInt() and 0xFF
                val stretched = ((raw - bp) * 255 / range).coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (stretched shl 16) or (stretched shl 8) or stretched
            }
        }
    }

    private fun fillBayerPixels(frame: FrameData, pixels: IntArray, w: Int, h: Int) {
        val hist = computeHistogram(frame)
        publishHistogram(hist)

        val maxVal = if (frame.pixelFormat.is10bit) 1023 else 255
        val bp: Int
        val wp: Int
        if (autoStretchEnabled) {
            bp = hist.blackPoint
            wp = hist.whitePoint
        } else {
            bp = manualBlackPoint * maxVal / 255
            wp = manualWhitePoint * maxVal / 255
        }
        val range = (wp - bp).coerceAtLeast(1)

        val pfName = frame.pixelFormat.name
        val isRG = pfName.startsWith("BAYER_RG")
        val isGR = pfName.startsWith("BAYER_GR")
        val isGB = pfName.startsWith("BAYER_GB")

        val rX: Int; val rY: Int
        when {
            isRG -> { rX = 0; rY = 0 }
            isGR -> { rX = 1; rY = 0 }
            isGB -> { rX = 0; rY = 1 }
            else -> { rX = 1; rY = 1 }
        }

        val is10 = frame.pixelFormat.is10bit
        val data = frame.data

        val wrGain = (wbRedGain * 256).toInt()
        val wgGain = (wbGreenGain * 256).toInt()
        val wbGain = (wbBlueGain * 256).toInt()
        val scale = (255 * 256) / range

        // Fast 2x2 block debayer: each 2x2 Bayer block maps directly to one R,G,G,B set
        val w2 = w and 1.inv()
        val h2 = h and 1.inv()

        for (y in 0 until h2 step 2) {
            val row0 = y * w
            val row1 = (y + 1) * w
            for (x in 0 until w2 step 2) {
                val v00 = getRawPixelFast(data, row0 + x, is10)
                val v10 = getRawPixelFast(data, row0 + x + 1, is10)
                val v01 = getRawPixelFast(data, row1 + x, is10)
                val v11 = getRawPixelFast(data, row1 + x + 1, is10)

                val r: Int; val g: Int; val b: Int
                when {
                    rX == 0 && rY == 0 -> { r = v00; g = (v10 + v01) shr 1; b = v11 }
                    rX == 1 && rY == 0 -> { g = (v00 + v11) shr 1; r = v10; b = v01 }
                    rX == 0 && rY == 1 -> { g = (v00 + v11) shr 1; b = v10; r = v01 }
                    else -> { b = v00; g = (v10 + v01) shr 1; r = v11 }
                }

                val rw = (r * wrGain) shr 8
                val gw = (g * wgGain) shr 8
                val bw = (b * wbGain) shr 8
                val rs = (((rw - bp) * scale) shr 8).coerceIn(0, 255)
                val gs = (((gw - bp) * scale) shr 8).coerceIn(0, 255)
                val bs = (((bw - bp) * scale) shr 8).coerceIn(0, 255)
                val pixel = (0xFF shl 24) or (rs shl 16) or (gs shl 8) or bs

                pixels[row0 + x] = pixel
                pixels[row0 + x + 1] = pixel
                pixels[row1 + x] = pixel
                pixels[row1 + x + 1] = pixel
            }
        }

        if (awbPending || awbMode == AwbMode.CONTINUOUS) {
            computeGrayWorldWb(frame, w, h)
            awbPending = false
        }
    }

    private fun getRawPixelFast(data: ByteArray, idx: Int, is10: Boolean): Int {
        return if (is10) {
            val byteIdx = idx * 2
            if (byteIdx + 1 >= data.size) return 0
            var v = (data[byteIdx].toInt() and 0xFF) or ((data[byteIdx + 1].toInt() and 0xFF) shl 8)
            if (detectedBitShift > 0) v = v shr detectedBitShift
            v
        } else {
            if (idx >= data.size) 0 else data[idx].toInt() and 0xFF
        }
    }

    private fun computeGrayWorldWb(frame: FrameData, w: Int, h: Int) {
        val pfName = frame.pixelFormat.name
        val isRG = pfName.startsWith("BAYER_RG")
        val isGR = pfName.startsWith("BAYER_GR")
        val isGB = pfName.startsWith("BAYER_GB")

        val rX: Int; val rY: Int
        when {
            isRG -> { rX = 0; rY = 0 }
            isGR -> { rX = 1; rY = 0 }
            isGB -> { rX = 0; rY = 1 }
            else -> { rX = 1; rY = 1 }
        }

        val is10 = frame.pixelFormat.is10bit
        val data = frame.data
        val step = 4
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
        var count = 0

        for (y in 0 until h - 1 step (step * 2)) {
            for (x in 0 until w - 1 step (step * 2)) {
                val x0 = x; val x1 = x + 1; val y0 = y; val y1 = y + 1
                val v00 = getRawPixel(data, x0, y0, w, is10)
                val v10 = getRawPixel(data, x1, y0, w, is10)
                val v01 = getRawPixel(data, x0, y1, w, is10)
                val v11 = getRawPixel(data, x1, y1, w, is10)

                val r: Int; val g1: Int; val g2: Int; val b: Int
                when {
                    rX == 0 && rY == 0 -> { r = v00; g1 = v10; g2 = v01; b = v11 }
                    rX == 1 && rY == 0 -> { g1 = v00; r = v10; b = v01; g2 = v11 }
                    rX == 0 && rY == 1 -> { g1 = v00; b = v10; r = v01; g2 = v11 }
                    else -> { b = v00; g1 = v10; g2 = v01; r = v11 }
                }
                sumR += r; sumG += (g1 + g2) * 0.5; sumB += b
                count++
            }
        }

        if (count == 0) return
        val avgR = sumR / count
        val avgG = sumG / count
        val avgB = sumB / count

        if (avgR > 0 && avgB > 0) {
            wbRedGain = (avgG / avgR).toFloat().coerceIn(0.2f, 5.0f)
            wbGreenGain = 1.0f
            wbBlueGain = (avgG / avgB).toFloat().coerceIn(0.2f, 5.0f)
            android.util.Log.i("FrameProcessor", "AWB: R=%.3f G=1.0 B=%.3f (avgR=%.0f avgG=%.0f avgB=%.0f)".format(
                wbRedGain, wbBlueGain, avgR, avgG, avgB))
        }
    }

    private fun getRawPixel(data: ByteArray, x: Int, y: Int, w: Int, is10: Boolean): Int {
        return if (is10) {
            val idx = (y * w + x) * 2
            if (idx + 1 >= data.size) return 0
            var v = (data[idx].toInt() and 0xFF) or ((data[idx + 1].toInt() and 0xFF) shl 8)
            if (detectedBitShift > 0) v = v shr detectedBitShift
            v
        } else {
            val idx = y * w + x
            if (idx >= data.size) 0 else data[idx].toInt() and 0xFF
        }
    }

    private fun avgNeighborsG(data: ByteArray, x: Int, y: Int, w: Int, h: Int, is10: Boolean): Int {
        var sum = 0; var count = 0
        if (x > 0)     { sum += getRawPixel(data, x - 1, y, w, is10); count++ }
        if (x < w - 1) { sum += getRawPixel(data, x + 1, y, w, is10); count++ }
        if (y > 0)     { sum += getRawPixel(data, x, y - 1, w, is10); count++ }
        if (y < h - 1) { sum += getRawPixel(data, x, y + 1, w, is10); count++ }
        return if (count > 0) sum / count else 0
    }

    private fun avgDiagonal(data: ByteArray, x: Int, y: Int, w: Int, h: Int, is10: Boolean): Int {
        var sum = 0; var count = 0
        if (x > 0 && y > 0)         { sum += getRawPixel(data, x - 1, y - 1, w, is10); count++ }
        if (x < w - 1 && y > 0)     { sum += getRawPixel(data, x + 1, y - 1, w, is10); count++ }
        if (x > 0 && y < h - 1)     { sum += getRawPixel(data, x - 1, y + 1, w, is10); count++ }
        if (x < w - 1 && y < h - 1) { sum += getRawPixel(data, x + 1, y + 1, w, is10); count++ }
        return if (count > 0) sum / count else 0
    }

    @Suppress("UNUSED_PARAMETER")
    private fun avgHorizontal(data: ByteArray, x: Int, y: Int, w: Int, h: Int, is10: Boolean): Int {
        var sum = 0; var count = 0
        if (x > 0)     { sum += getRawPixel(data, x - 1, y, w, is10); count++ }
        if (x < w - 1) { sum += getRawPixel(data, x + 1, y, w, is10); count++ }
        return if (count > 0) sum / count else 0
    }

    private fun avgVertical(data: ByteArray, x: Int, y: Int, w: Int, h: Int, is10: Boolean): Int {
        var sum = 0; var count = 0
        if (y > 0)     { sum += getRawPixel(data, x, y - 1, w, is10); count++ }
        if (y < h - 1) { sum += getRawPixel(data, x, y + 1, w, is10); count++ }
        return if (count > 0) sum / count else 0
    }

    private var detectedBitShift = -1
    private var detectedEffectiveBits = 16
    private var forceDeclaredHighBitLayout = false
    private val highBitLayoutDetector = HighBitLayoutDetector(HIGH_BIT_LAYOUT_STABLE_FRAMES)

    fun computeHistogram(frame: FrameData): HistogramData {
        val totalPixels = frame.width * frame.height

        if (frame.pixelFormat == PixelFormat.RGB48) {
            val numBins = 1024
            val bins = reusableHistogramBins(numBins)
            for (i in 0 until totalPixels) {
                val base = i * 6
                if (base + 5 >= frame.data.size) break
                val r = (frame.data[base].toInt() and 0xFF) or ((frame.data[base + 1].toInt() and 0xFF) shl 8)
                val g = (frame.data[base + 2].toInt() and 0xFF) or ((frame.data[base + 3].toInt() and 0xFF) shl 8)
                val b = (frame.data[base + 4].toInt() and 0xFF) or ((frame.data[base + 5].toInt() and 0xFF) shl 8)
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                bins[(lum shr 6).coerceIn(0, 1023)]++
            }
            val maxCount = bins.max()
            return computeStretchPoints(bins, numBins, maxCount, totalPixels)
        }

        if (frame.pixelFormat == PixelFormat.RGB24) {
            val numBins = 256
            val bins = reusableHistogramBins(numBins)
            for (i in 0 until totalPixels) {
                val base = i * 3
                if (base + 2 >= frame.data.size) break
                val r = frame.data[base].toInt() and 0xFF
                val g = frame.data[base + 1].toInt() and 0xFF
                val b = frame.data[base + 2].toInt() and 0xFF
                val lum = ((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)
                bins[lum]++
            }
            val maxCount = bins.max()
            return computeStretchPoints(bins, numBins, maxCount, totalPixels)
        }

        if (frame.pixelFormat.is10bit) {
            if (forceDeclaredHighBitLayout) {
                detectedEffectiveBits = frame.pixelFormat.nativeBits
                detectedBitShift = (frame.pixelFormat.nativeBits - 10).coerceAtLeast(0)
            } else if (highBitLayoutDetector.sampledFrames < HIGH_BIT_LAYOUT_MAX_SAMPLE_FRAMES) {
                var maxVal = 0
                var lowBitsMask = 0
                val sampleCount = totalPixels.coerceAtMost(10000)
                for (i in 0 until sampleCount) {
                    val lo = frame.data[i * 2].toInt() and 0xFF
                    val hi = frame.data[i * 2 + 1].toInt() and 0xFF
                    val v = (hi shl 8) or lo
                    if (v > maxVal) maxVal = v
                    lowBitsMask = lowBitsMask or v
                }
                val previousBits = detectedEffectiveBits
                val layout = highBitLayoutDetector.observe(maxVal, lowBitsMask, frame.pixelFormat.nativeBits)
                detectedBitShift = layout.shift
                detectedEffectiveBits = layout.effectiveBits

                if (highBitLayoutDetector.sampledFrames == HIGH_BIT_LAYOUT_STABLE_FRAMES ||
                    detectedEffectiveBits != previousBits
                ) {
                    android.util.Log.i(
                        "FrameProcessor",
                        "Detected: frames=${highBitLayoutDetector.sampledFrames} maxVal=$maxVal " +
                            "zeroBits=${layout.zeroBits} effectiveBits=${layout.effectiveBits} shift=${layout.shift}"
                    )
                }
            }

            val numBins = 1024
            val bins = reusableHistogramBins(numBins)
            for (i in 0 until totalPixels) {
                val lo = frame.data[i * 2].toInt() and 0xFF
                val hi = frame.data[i * 2 + 1].toInt() and 0xFF
                var value = (hi shl 8) or lo
                if (detectedBitShift > 0) value = value shr detectedBitShift
                bins[value.coerceIn(0, 1023)]++
            }
            val maxCount = bins.max()
            return computeStretchPoints(bins, numBins, maxCount, totalPixels)
        } else {
            val numBins = 256
            val bins = reusableHistogramBins(numBins)
            for (i in 0 until totalPixels) {
                val value = frame.data[i].toInt() and 0xFF
                bins[value]++
            }
            val maxCount = bins.max()
            return computeStretchPoints(bins, numBins, maxCount, totalPixels)
        }
    }

    private fun reusableHistogramBins(size: Int): IntArray {

        val bins = if (size == 256) histogramBins256 else histogramBins1024

        java.util.Arrays.fill(bins, 0)

        return bins

    }

    private fun publishHistogram(histogram: HistogramData) {

        val now = System.currentTimeMillis()

        if (now - lastHistogramPublishMs >= HISTOGRAM_PUBLISH_INTERVAL_MS) {

            lastHistogramPublishMs = now

            _histogram.value = histogram.copy(bins = histogram.bins.copyOf())

        }

    }

    private fun computeStretchPoints(
        bins: IntArray,
        numBins: Int,
        maxCount: Int,
        totalPixels: Int
    ): HistogramData {
        val lowThreshold = (totalPixels * stretchPercentileLow).toInt()
        val highThreshold = (totalPixels * stretchPercentileHigh).toInt()

        var cumulative = 0
        var blackPoint = 0
        for (i in bins.indices) {
            cumulative += bins[i]
            if (cumulative >= lowThreshold) {
                blackPoint = i
                break
            }
        }

        cumulative = 0
        var whitePoint = numBins - 1
        for (i in bins.indices) {
            cumulative += bins[i]
            if (cumulative >= highThreshold) {
                whitePoint = i
                break
            }
        }

        return HistogramData(bins, maxCount, totalPixels, blackPoint, whitePoint)
    }
}
