package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat

data class ShortExposureStackResult(
    val frame: FrameData,
    val inputFrameCount: Int,
    val rejectedHotPixelSamples: Int
)

/**
 * Robust pixel stack for a stationary phone/optical assembly.
 *
 * The first implementation deliberately has no image registration: registration requires the
 * time-aligned IMU history delivered by P4. Removing the minimum and maximum sample per pixel
 * rejects isolated hot pixels and short transient artefacts while retaining the signal from stars.
 */
object ShortExposureStacker {
    fun stack(frames: List<FrameData>): ShortExposureStackResult {
        require(frames.isNotEmpty()) { "No frames to stack" }
        val first = frames.first()
        require(frames.all {
            it.width == first.width && it.height == first.height && it.pixelFormat == first.pixelFormat
        }) { "All burst frames must have matching geometry and pixel format" }
        if (frames.size == 1) return ShortExposureStackResult(first, 1, 0)

        val sampleCount = frames.size
        val pixels = first.width * first.height
        val bpp = first.pixelFormat.bytesPerPixel
        require(bpp == 1 || bpp >= 2) { "Unsupported format ${first.pixelFormat}" }
        val out = ByteArray(pixels * bpp)
        val samples = IntArray(sampleCount)
        var rejected = 0
        for (pixel in 0 until pixels) {
            for (frameIndex in frames.indices) {
                samples[frameIndex] = sample(frames[frameIndex], pixel, bpp)
            }
            samples.sort()
            val trim = if (sampleCount >= 4) 1 else 0
            val from = trim
            val until = sampleCount - trim
            var sum = 0L
            for (i in from until until) sum += samples[i]
            val value = (sum / (until - from)).toInt()
            if (trim != 0) rejected += 2
            write(out, pixel, bpp, value)
        }
        return ShortExposureStackResult(
            frame = FrameData(
                data = out,
                width = first.width,
                height = first.height,
                pixelFormat = first.pixelFormat,
                frameId = first.frameId,
                timestamp = first.timestamp
            ),
            inputFrameCount = sampleCount,
            rejectedHotPixelSamples = rejected
        )
    }

    private fun sample(frame: FrameData, pixel: Int, bpp: Int): Int {
        val offset = pixel * bpp
        return if (bpp == 1 || frame.pixelFormat == PixelFormat.MONO8) {
            frame.data[offset].toInt() and 0xFF
        } else {
            (frame.data[offset].toInt() and 0xFF) or
                ((frame.data[offset + 1].toInt() and 0xFF) shl 8)
        }
    }

    private fun write(data: ByteArray, pixel: Int, bpp: Int, value: Int) {
        val offset = pixel * bpp
        data[offset] = (value and 0xFF).toByte()
        if (bpp >= 2) data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
