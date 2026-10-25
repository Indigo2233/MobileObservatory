package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.FrameData
import kotlin.math.*

data class GuideStarProfile(val width: Int, val height: Int, val pixels: List<Float>, val hfrPx: Float,
    val flux: Float, val peak: Float, val background: Float, val radialIntensity: List<Float>, val encircledEnergy: List<Float>)

object GuideStarAnalysis {
    fun arcsecPerPixel(pixelSizeUm: Float?, focalLengthMm: Float): Float? =
        pixelSizeUm?.takeIf { it.isFinite() && it > 0 && focalLengthMm.isFinite() && focalLengthMm > 0 }
            ?.let { 206.264806f * it / focalLengthMm }

    fun measure(frame: FrameData, star: GuideStar): GuideStarProfile? {
        if (!star.x.isFinite() || !star.y.isFinite() || star.x < 0 || star.y < 0 || star.x >= frame.width || star.y >= frame.height ||
            frame.data.size.toLong() < frame.width.toLong() * frame.height * frame.pixelFormat.bytesPerPixel) return null
        val reader = GuideModule()
        val radius = 12
        val x0 = (star.x.roundToInt() - radius).coerceAtLeast(0); val y0 = (star.y.roundToInt() - radius).coerceAtLeast(0)
        val x1 = (star.x.roundToInt() + radius).coerceAtMost(frame.width - 1); val y1 = (star.y.roundToInt() + radius).coerceAtMost(frame.height - 1)
        val border = mutableListOf<Float>(); val pixels = mutableListOf<Float>()
        for (y in y0..y1) for (x in x0..x1) {
            val v = reader.rawValue(frame, x, y).toFloat(); pixels += v
            if (hypot(x - star.x, y - star.y) >= 10f) border += v
        }
        if (border.isEmpty()) return null
        val background = border.sorted()[border.size / 2]
        val radial = FloatArray(radius + 1); val counts = IntArray(radius + 1)
        val fluxes = mutableListOf<Pair<Float, Float>>()
        for (y in y0..y1) for (x in x0..x1) {
            val r = hypot(x - star.x, y - star.y)
            if (r > radius) continue
            val flux = (reader.rawValue(frame, x, y) - background).coerceAtLeast(0f)
            fluxes += r to flux
            val bin = r.toInt(); radial[bin] += flux; counts[bin]++
        }
        val total = fluxes.sumOf { it.second.toDouble() }.toFloat()
        if (total <= 0f) return null
        var sum = 0f; var hfr = 0f; var previousRadius = 0f
        for ((r, value) in fluxes.sortedBy { it.first }) {
            if (sum + value >= total / 2) { hfr = previousRadius + (r - previousRadius) * (total / 2 - sum) / value.coerceAtLeast(.0001f); break }
            sum += value; previousRadius = r
        }
        return GuideStarProfile(x1 - x0 + 1, y1 - y0 + 1, pixels, hfr, total, pixels.max(), background,
            radial.indices.map { radial[it] / counts[it].coerceAtLeast(1) },
            radial.indices.map { bin -> fluxes.filter { it.first <= bin }.sumOf { it.second.toDouble() }.toFloat() / total })
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
