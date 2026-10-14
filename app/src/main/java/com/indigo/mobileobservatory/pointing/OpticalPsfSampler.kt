package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.stacking.PixelTraits
import java.nio.ByteOrder
import kotlin.math.*

/** Unstretched science pixels only. CFA cells are summed before centroid/PSF measurement. */
object OpticalPsfSampler {
    fun sample(frame: FrameData, traits: PixelTraits?, centers: List<Pair<Double, Double>>? = null): List<OpticalStarPsf> {
        require(!frame.pixelFormat.isRgb) { "Use a raw auxiliary pixel format" }
        require(frame.data.size.toLong() >= frame.width.toLong() * frame.height * frame.bytesPerPixel)
        val step = if (frame.pixelFormat.isBayer) 2 else 1
        val width = frame.width / step; val height = frame.height / step
        require(width >= 32 && height >= 32)
        fun value(x: Int, y: Int): Double {
            var sum = 0.0
            for (dy in 0 until step) for (dx in 0 until step) {
                val index = ((y * step + dy) * frame.width + x * step + dx) * frame.bytesPerPixel
                val first = frame.data[index].toInt() and 255
                sum += if (frame.bytesPerPixel == 1) first else {
                    val second = frame.data[index + 1].toInt() and 255
                    if (traits?.byteOrder == ByteOrder.BIG_ENDIAN) (first shl 8) or second else first or (second shl 8)
                }
            }
            return sum / (step * step)
        }
        var total = 0.0; var squares = 0.0; var count = 0
        for (y in 0 until height step 7) for (x in 0 until width step 7) {
            val v = value(x, y); total += v; squares += v * v; count++
        }
        val mean = total / count
        val noise = sqrt(max(0.0, squares / count - mean * mean)).coerceAtLeast(1.0)
        val threshold = mean + noise * 5
        data class Peak(val x: Int, val y: Int, val value: Double)
        val peaks = ArrayList<Peak>()
        if (centers != null) centers.forEach { (cx, cy) ->
            val x = (cx / step).roundToInt(); val y = (cy / step).roundToInt()
            if (x in 7 until width - 7 && y in 7 until height - 7) {
                val v = value(x, y)
                if (v > threshold) peaks += Peak(x, y, v)
            }
        } else for (y in 7 until height - 7) for (x in 7 until width - 7) {
            val v = value(x, y)
            if (v > threshold && v > value(x - 1, y) && v >= value(x + 1, y) &&
                v > value(x, y - 1) && v >= value(x, y + 1)) peaks += Peak(x, y, v)
        }
        val stars = ArrayList<OpticalStarPsf>()
        for (peak in peaks.sortedByDescending { it.value }) {
            if (stars.size == 24) break
            if (stars.any { hypot(it.x / step - peak.x, it.y / step - peak.y) < 14 }) continue
            var flux = 0.0; var sx = 0.0; var sy = 0.0
            for (y in peak.y - 6..peak.y + 6) for (x in peak.x - 6..peak.x + 6) {
                val w = (value(x, y) - mean - noise * 2).coerceAtLeast(0.0)
                flux += w; sx += w * x; sy += w * y
            }
            if (flux <= 0) continue
            val cx = sx / flux; val cy = sy / flux
            var xx = 0.0; var yy = 0.0; var xy = 0.0
            for (y in peak.y - 6..peak.y + 6) for (x in peak.x - 6..peak.x + 6) {
                val w = (value(x, y) - mean - noise * 2).coerceAtLeast(0.0)
                xx += w * (x - cx).pow(2); yy += w * (y - cy).pow(2); xy += w * (x - cx) * (y - cy)
            }
            xx /= flux; yy /= flux; xy /= flux
            val split = hypot(xx - yy, 2 * xy)
            val major = sqrt(((xx + yy + split) / 2).coerceAtLeast(.25)) * step
            val minor = sqrt(((xx + yy - split) / 2).coerceAtLeast(.25)) * step
            // Aperture truncation cannot serve as a bound for long trails or clipped stars.
            if (major > 3.5 * step || abs(cx - peak.x) > 2 || abs(cy - peak.y) > 2) continue
            stars += OpticalStarPsf((cx + .5) * step - .5, (cy + .5) * step - .5,
                major, minor, (peak.value - mean) / noise, flux)
        }
        return stars
    }
}
