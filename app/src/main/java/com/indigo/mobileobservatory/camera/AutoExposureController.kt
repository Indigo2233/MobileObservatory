package com.indigo.mobileobservatory.camera

enum class AutoExposureMode { OFF, CONTINUOUS, SINGLE_SHOT }

class AutoExposureController {

    var mode: AutoExposureMode = AutoExposureMode.OFF
    var targetBrightness: Float = 70f
    var adjustExposure: Boolean = true
    var adjustGain: Boolean = true

    private var previousError = 0f
    private var singleShotDone = false
    private var settleCount = 0

    fun reset() {
        previousError = 0f
        singleShotDone = false
        settleCount = 0
    }

    fun processFrame(frame: FrameData, camera: Camera, exposureMaxUs: Float = camera.exposureRange.max) {
        if (mode == AutoExposureMode.OFF) return
        if (mode == AutoExposureMode.SINGLE_SHOT && singleShotDone) return

        val stats = computeFrameStats(frame)
        val meanNorm = stats.mean / stats.maxValue * 255f
        val medianNorm = stats.median / stats.maxValue * 255f
        val target = (meanNorm + medianNorm) / 2f

        val error = targetBrightness - target
        val absError = kotlin.math.abs(error)
        val errorRate = error - previousError
        previousError = error

        if (absError < 3f) {
            settleCount++
            if (mode == AutoExposureMode.SINGLE_SHOT && settleCount >= 3) {
                singleShotDone = true
            }
            return
        }
        settleCount = 0

        val ratio = targetBrightness / target.coerceAtLeast(0.5f)
        val dampedRatio = 1f + (ratio - 1f) * 0.4f + errorRate * 0.05f

        if (adjustExposure) {
            val currentExp = camera.currentExposureUs
            val maxUs = exposureMaxUs.coerceAtLeast(camera.exposureRange.min)
            val newExp = (currentExp * dampedRatio)
                .coerceIn(camera.exposureRange.min, maxUs)

            if (newExp != currentExp) {
                camera.setExposureTime(newExp)

                val atExpLimit = (newExp <= camera.exposureRange.min * 1.01f && ratio < 1f) ||
                        (newExp >= maxUs * 0.99f && ratio > 1f)
                if (adjustGain && atExpLimit) {
                    adjustGainStep(camera, ratio)
                }
                return
            }
        }

        if (adjustGain) {
            adjustGainStep(camera, ratio)
        }
    }

    private fun adjustGainStep(camera: Camera, ratio: Float) {
        val currentGain = camera.currentGain
        val gainAdjustmentStops = when {
            ratio > 2f -> 0.5f
            ratio > 1.5f -> 0.25f
            ratio > 1.1f -> 0.1f
            ratio < 0.5f -> -0.5f
            ratio < 0.7f -> -0.25f
            ratio < 0.9f -> -0.1f
            else -> return
        }
        val newGain = camera.adjustGainForExposure(gainAdjustmentStops)
        if (newGain != currentGain) {
            camera.setGain(newGain)
        }
    }

    private data class FrameStats(val mean: Float, val median: Float, val maxValue: Float)

    private fun computeFrameStats(frame: FrameData): FrameStats {
        val total = frame.width * frame.height
        if (total == 0) return FrameStats(0f, 0f, 255f)

        val pixelFormat = frame.pixelFormat
        val isRgb48 = pixelFormat == PixelFormat.RGB48
        val isRgb24 = pixelFormat == PixelFormat.RGB24
        val isHighBit = pixelFormat.bytesPerPixel >= 2 && !isRgb48 && !isRgb24
        val sampleStep = (total / 50000).coerceAtLeast(1)
        val highBitShift = if (isHighBit) {
            var maxValue = 0
            var lowBitsMask = 0
            for (index in 0 until total step sampleStep) {
                val offset = index * 2
                if (offset + 1 >= frame.data.size) break
                val raw = (frame.data[offset].toInt() and 0xFF) or
                    ((frame.data[offset + 1].toInt() and 0xFF) shl 8)
                maxValue = maxOf(maxValue, raw)
                lowBitsMask = lowBitsMask or raw
            }
            val declaredMax = (1 shl pixelFormat.nativeBits.coerceIn(1, 16)) - 1
            if (maxValue <= declaredMax) {
                (pixelFormat.nativeBits - 10).coerceAtLeast(0)
            } else {
                detectHighBitLayout(maxValue, lowBitsMask, pixelFormat.nativeBits).shift
            }
        } else {
            0
        }
        val maxVal = if (isHighBit || isRgb48) 1023f else 255f

        var sum = 0L
        val numBins = maxVal.toInt() + 1
        val histogram = IntArray(numBins)
        var sampleCount = 0

        if (isHighBit) {
            for (i in 0 until total step sampleStep) {
                val offset = i * 2
                if (offset + 1 >= frame.data.size) break
                val lo = frame.data[offset].toInt() and 0xFF
                val hi = frame.data[offset + 1].toInt() and 0xFF
                val raw = ((hi shl 8) or lo) shr highBitShift
                val v = raw.coerceIn(0, maxVal.toInt())
                sum += v
                histogram[v]++
                sampleCount++
            }
        } else if (isRgb48) {
            for (i in 0 until total step sampleStep) {
                val offset = i * 6
                if (offset + 5 >= frame.data.size) break
                val red = ((frame.data[offset + 1].toInt() and 0xFF) shl 8) or (frame.data[offset].toInt() and 0xFF)
                val green = ((frame.data[offset + 3].toInt() and 0xFF) shl 8) or (frame.data[offset + 2].toInt() and 0xFF)
                val blue = ((frame.data[offset + 5].toInt() and 0xFF) shl 8) or (frame.data[offset + 4].toInt() and 0xFF)
                val v = ((red + green + blue) / 3 shr 6).coerceIn(0, maxVal.toInt())
                sum += v
                histogram[v]++
                sampleCount++
            }
        } else if (isRgb24) {
            for (i in 0 until total step sampleStep) {
                val offset = i * 3
                if (offset + 2 >= frame.data.size) break
                val red = frame.data[offset].toInt() and 0xFF
                val green = frame.data[offset + 1].toInt() and 0xFF
                val blue = frame.data[offset + 2].toInt() and 0xFF
                val v = ((red * 299 + green * 587 + blue * 114) / 1000).coerceIn(0, maxVal.toInt())
                sum += v
                histogram[v]++
                sampleCount++
            }
        } else {
            for (i in 0 until total step sampleStep) {
                if (i >= frame.data.size) break
                val v = frame.data[i].toInt() and 0xFF
                sum += v
                histogram[v]++
                sampleCount++
            }
        }

        if (sampleCount == 0) return FrameStats(0f, 0f, maxVal)
        val mean = sum.toFloat() / sampleCount
        val medianTarget = sampleCount / 2
        var cumulative = 0
        var median = 0f
        for (i in histogram.indices) {
            cumulative += histogram[i]
            if (cumulative >= medianTarget) {
                median = i.toFloat()
                break
            }
        }

        return FrameStats(mean, median, maxVal)
    }
}
