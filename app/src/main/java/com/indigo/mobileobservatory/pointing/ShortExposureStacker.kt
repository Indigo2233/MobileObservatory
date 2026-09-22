package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import kotlin.math.tan

data class ShortExposureStackResult(
    val frame: FrameData,
    val inputFrameCount: Int,
    val rejectedHotPixelSamples: Int,
    val rejectedMotionFrames: Int = 0
)

internal data class BurstAttitude(
    val opticalAxis: Direction3,
    val imageUp: Direction3
)

/**
 * Robust pixel stack for a short-exposure burst. Frames that moved more than
 * [MAXIMUM_ALIGN_DEG] relative to the first are dropped. Remaining frames are
 * warped onto the first using the camera optical axis and image-up, then a
 * trimmed mean rejects isolated hot pixels.
 */
object ShortExposureStacker {
    private const val MAXIMUM_ALIGN_DEG = 0.45
    private const val MAXIMUM_ROLL_DEG = 2.5

    fun stack(frames: List<FrameData>): ShortExposureStackResult =
        stack(frames, emptyList(), null, null)

    internal fun stack(
        frames: List<FrameData>,
        attitudes: List<BurstAttitude?> = emptyList(),
        fovWidthDeg: Double? = null,
        fovHeightDeg: Double? = null
    ): ShortExposureStackResult {
        require(frames.isNotEmpty()) { "No frames to stack" }
        val first = frames.first()
        require(frames.all {
            it.width == first.width && it.height == first.height && it.pixelFormat == first.pixelFormat
        }) { "All burst frames must have matching geometry and pixel format" }
        if (frames.size == 1) return ShortExposureStackResult(first, 1, 0)

        var rejectedMotion = 0
        val aligned = ArrayList<FrameData>(frames.size)
        val reference = attitudes.getOrNull(0)
        val canWarp = reference != null && fovWidthDeg != null && fovHeightDeg != null &&
            fovWidthDeg > 1.0 && fovHeightDeg > 1.0
        for (index in frames.indices) {
            val frame = frames[index]
            val pose = attitudes.getOrNull(index)
            if (canWarp && pose != null) {
                val lookError = pose.opticalAxis.angleDeg(reference!!.opticalAxis)
                val rollError = pose.imageUp.angleDeg(reference.imageUp)
                if (lookError > MAXIMUM_ALIGN_DEG || rollError > MAXIMUM_ROLL_DEG) {
                    if (index == 0) aligned += frame else rejectedMotion++
                    continue
                }
                aligned += if (index == 0) frame else warp(
                    source = frame,
                    sourceAttitude = pose,
                    referenceAttitude = reference,
                    fovWidthDeg = fovWidthDeg!!,
                    fovHeightDeg = fovHeightDeg!!
                )
            } else {
                aligned += frame
            }
        }
        if (aligned.isEmpty()) aligned += first
        if (aligned.size == 1) {
            return ShortExposureStackResult(aligned[0], 1, 0, rejectedMotion)
        }

        val sampleCount = aligned.size
        val pixels = first.width * first.height
        val bpp = first.pixelFormat.bytesPerPixel
        require(bpp == 1 || bpp >= 2) { "Unsupported format ${first.pixelFormat}" }
        val out = ByteArray(pixels * bpp)
        val samples = IntArray(sampleCount)
        var rejected = 0
        for (pixel in 0 until pixels) {
            for (frameIndex in aligned.indices) {
                samples[frameIndex] = sample(aligned[frameIndex], pixel, bpp)
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
            rejectedHotPixelSamples = rejected,
            rejectedMotionFrames = rejectedMotion
        )
    }

    internal fun warp(
        source: FrameData,
        sourceAttitude: BurstAttitude,
        referenceAttitude: BurstAttitude,
        fovWidthDeg: Double,
        fovHeightDeg: Double
    ): FrameData {
        val bpp = source.pixelFormat.bytesPerPixel
        val width = source.width
        val height = source.height
        val out = ByteArray(width * height * bpp)
        val srcRight = basisRight(sourceAttitude)
        val srcUp = sourceAttitude.imageUp.unit()
        val srcLook = sourceAttitude.opticalAxis.unit()
        val refRight = basisRight(referenceAttitude)
        val refUp = referenceAttitude.imageUp.unit()
        val refLook = referenceAttitude.opticalAxis.unit()
        val tanW = tan(Math.toRadians(fovWidthDeg / 2.0))
        val tanH = tan(Math.toRadians(fovHeightDeg / 2.0))
        for (y in 0 until height) {
            val ny = (1.0 - (y + 0.5) / height * 2.0) * tanH
            for (x in 0 until width) {
                val nx = ((x + 0.5) / width * 2.0 - 1.0) * tanW
                val inv = 1.0 / kotlin.math.sqrt(nx * nx + ny * ny + 1.0)
                val camX = nx * inv
                val camY = ny * inv
                val camZ = inv
                val east = refRight.east * camX + refUp.east * camY + refLook.east * camZ
                val north = refRight.north * camX + refUp.north * camY + refLook.north * camZ
                val up = refRight.up * camX + refUp.up * camY + refLook.up * camZ
                val sX = east * srcRight.east + north * srcRight.north + up * srcRight.up
                val sY = east * srcUp.east + north * srcUp.north + up * srcUp.up
                val sZ = east * srcLook.east + north * srcLook.north + up * srcLook.up
                if (sZ <= 1e-6) continue
                val sx = ((sX / sZ) / tanW + 1.0) * 0.5 * width - 0.5
                val sy = (1.0 - (sY / sZ) / tanH) * 0.5 * height - 0.5
                val ix = sx.toInt()
                val iy = sy.toInt()
                if (ix !in 0 until width || iy !in 0 until height) continue
                copyPixel(source, ix, iy, out, x, y, bpp)
            }
        }
        return FrameData(out, width, height, source.pixelFormat, source.frameId, source.timestamp)
    }

    private fun basisRight(attitude: BurstAttitude): Direction3 =
        attitude.imageUp.cross(attitude.opticalAxis).unit()

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

    private fun copyPixel(
        source: FrameData,
        sx: Int,
        sy: Int,
        dest: ByteArray,
        dx: Int,
        dy: Int,
        bpp: Int
    ) {
        val src = (sy * source.width + sx) * bpp
        val dst = (dy * source.width + dx) * bpp
        dest[dst] = source.data[src]
        if (bpp >= 2) dest[dst + 1] = source.data[src + 1]
    }
}
