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

    fun processFrame(frame: FrameData, camera: Camera) {
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
            val newExp = (currentExp * dampedRatio)
                .coerceIn(camera.exposureRange.min, camera.exposureRange.max)

            if (newExp != currentExp) {
                camera.setExposureTime(newExp)

                val atExpLimit = (newExp <= camera.exposureRange.min * 1.01f && ratio < 1f) ||
                        (newExp >= camera.exposureRange.max * 0.99f && ratio > 1f)
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
        val gainAdjDb = when {
            ratio > 2f -> 3.0f
            ratio > 1.5f -> 1.5f
            ratio > 1.1f -> 0.5f
            ratio < 0.5f -> -3.0f
            ratio < 0.7f -> -1.5f
            ratio < 0.9f -> -0.5f
            else -> return
        }
        val newGain = (currentGain + gainAdjDb)
            .coerceIn(camera.gainRange.min, camera.gainRange.max)
        if (newGain != currentGain) {
            camera.setGain(newGain)
        }
    }

    private data class FrameStats(val mean: Float, val median: Float, val maxValue: Float)

    private fun computeFrameStats(frame: FrameData): FrameStats {
        val total = frame.width * frame.height
        if (total == 0) return FrameStats(0f, 0f, 255f)

        val is10bit = frame.pixelFormat.is10bit
        val maxVal = if (is10bit) 1023f else 255f

        var sum = 0L
        val sampleStep = (total / 50000).coerceAtLeast(1)
        val sampleCount = total / sampleStep
        val numBins = if (is10bit) 1024 else 256
        val histogram = IntArray(numBins)

        if (is10bit) {
            for (i in 0 until total step sampleStep) {
                val lo = frame.data[i * 2].toInt() and 0xFF
                val hi = frame.data[i * 2 + 1].toInt() and 0xFF
                val v = ((hi shl 8) or lo).coerceIn(0, 1023)
                sum += v
                histogram[v]++
            }
        } else {
            for (i in 0 until total step sampleStep) {
                val v = frame.data[i].toInt() and 0xFF
                sum += v
                histogram[v]++
            }
        }

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
