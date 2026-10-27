package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.FrameData
import kotlin.math.*

data class GuideStarProfile(val width: Int, val height: Int, val pixels: List<Float>, val hfrPx: Float,
    val flux: Float, val peak: Float, val background: Float, val radialIntensity: List<Float>, val encircledEnergy: List<Float>,
    val horizontalOffsets: List<Float> = emptyList(), val horizontalIntensity: List<Float> = emptyList(),
    val verticalOffsets: List<Float> = emptyList(), val verticalIntensity: List<Float> = emptyList(),
    val saturationLevel: Float = 255f, val saturated: Boolean = false, val isBayer: Boolean = false)

object GuideStarAnalysis {
    fun arcsecPerPixel(pixelSizeUm: Float?, focalLengthMm: Float): Float? =
        pixelSizeUm?.takeIf { it.isFinite() && it > 0 && focalLengthMm.isFinite() && focalLengthMm > 0 }
            ?.let { 206.264806f * it / focalLengthMm }

    fun measure(frame: FrameData, star: GuideStar, saturationLevel: Int? = null): GuideStarProfile? {
        if (!star.x.isFinite() || !star.y.isFinite() || star.x < 0 || star.y < 0 || star.x >= frame.width || star.y >= frame.height ||
            frame.data.size.toLong() < frame.width.toLong() * frame.height * frame.pixelFormat.bytesPerPixel) return null
        val reader = GuideModule()
        val radius = 12
        val x0 = (star.x.roundToInt() - radius).coerceAtLeast(0); val y0 = (star.y.roundToInt() - radius).coerceAtLeast(0)
        val x1 = (star.x.roundToInt() + radius).coerceAtMost(frame.width - 1); val y1 = (star.y.roundToInt() + radius).coerceAtMost(frame.height - 1)
        val bayer = frame.pixelFormat.isBayer
        fun phase(x: Int, y: Int) = if (bayer) (y and 1) * 2 + (x and 1) else 0
        val border = Array(if (bayer) 4 else 1) { mutableListOf<Float>() }; val pixels = mutableListOf<Float>()
        for (y in y0..y1) for (x in x0..x1) {
            val v = reader.rawValue(frame, x, y).toFloat(); pixels += v
            if (hypot(x - star.x, y - star.y) >= 10f) border[phase(x, y)] += v
        }
        if (border.any { it.isEmpty() }) return null
        val backgrounds = border.map { values ->
            val median = values.sorted()[values.size / 2]
            val noise = values.map { abs(it - median) }.sorted()[values.size / 2] * 1.4826f
            values.filter { abs(it - median) <= max(1f, noise * 3f) }.average().toFloat()
        }
        val background = backgrounds.average().toFloat()
        val radial = FloatArray(radius + 1); val counts = IntArray(radius + 1)
        val fluxes = mutableListOf<Pair<Float, Float>>()
        for (y in y0..y1) for (x in x0..x1) {
            val r = hypot(x - star.x, y - star.y)
            if (r > radius) continue
            // Keep signed background residuals: clipping negative noise inflates faint-star wings.
            val flux = reader.rawValue(frame, x, y) - backgrounds[phase(x, y)]
            fluxes += r to flux
            val bin = r.toInt(); radial[bin] += flux; counts[bin]++
        }
        val total = fluxes.sumOf { it.second.toDouble() }.toFloat()
        if (total <= 0f) return null
        var sum = 0f; var hfr = 0f; var previousRadius = 0f
        for ((r, value) in fluxes.sortedBy { it.first }) {
            if (value > 0f && sum + value >= total / 2) { hfr = previousRadius + (r - previousRadius) * (total / 2 - sum) / value; break }
            sum += value; previousRadius = r
        }
        var energy = 0f
        val energies = radial.indices.map { bin ->
            energy = max(energy, (fluxes.filter { it.first <= bin }.sumOf { it.second.toDouble() }.toFloat() / total).coerceIn(0f, 1f))
            energy
        }.toMutableList().apply { this[lastIndex] = 1f }
        val peak = pixels.max()
        val nominal = (1 shl frame.pixelFormat.nativeBits) - 1
        val maximum = saturationLevel ?: if (peak > nominal) 65535 else nominal
        val row = star.y.roundToInt().coerceIn(y0, y1); val column = star.x.roundToInt().coerceIn(x0, x1)
        return GuideStarProfile(x1 - x0 + 1, y1 - y0 + 1, pixels, hfr, total, pixels.max(), background,
            radial.indices.map { radial[it] / counts[it].coerceAtLeast(1) },
            energies, (x0..x1).map { it - star.x }, (x0..x1).map { reader.rawValue(frame, it, row).toFloat() },
            (y0..y1).map { it - star.y }, (y0..y1).map { reader.rawValue(frame, column, it).toFloat() },
            maximum.toFloat(), star.saturated || peak >= maximum - max(1, maximum / 1000), bayer)
    }
}

/** Both diagnostic plots consume exactly this trailing sample window. */
class GuidePlotWindow(history: List<GuideHistoryPoint>, val capacity: Int) {
    init { require(capacity in 2..1000) }
    val points = history.takeLast(capacity)
    fun xFraction(index: Int): Float = index.toFloat() / (capacity - 1)
}

/** The image and overlay share this reversible source-pixel transform. */
data class GuideViewport(val width: Float, val height: Float, val imageWidth: Int, val imageHeight: Int,
    val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    val scale = min(width / imageWidth.coerceAtLeast(1), height / imageHeight.coerceAtLeast(1)) * zoom
    val left = (width - imageWidth * scale) / 2 + panX
    val top = (height - imageHeight * scale) / 2 + panY
    fun contains(x: Float, y: Float) = x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < imageWidth && y < imageHeight
    fun toScreen(x: Float, y: Float) = GuideVector(left + x * scale, top + y * scale)
    fun toImage(x: Float, y: Float): GuideVector? {
        if (scale <= 0 || !scale.isFinite()) return null
        return GuideVector((x - left) / scale, (y - top) / scale).takeIf { contains(it.x, it.y) }
    }
}
