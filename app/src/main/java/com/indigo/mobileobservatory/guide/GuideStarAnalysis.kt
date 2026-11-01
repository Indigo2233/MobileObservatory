package com.indigo.mobileobservatory.guide

import com.indigo.mobileobservatory.camera.FrameData
import kotlin.math.*

data class GuideStarProfile(val width: Int, val height: Int, val pixels: List<Float>, val hfrPx: Float,
    val flux: Float, val peak: Float, val background: Float, val radialIntensity: List<Float>, val encircledEnergy: List<Float>,
    val horizontalOffsets: List<Float> = emptyList(), val horizontalIntensity: List<Float> = emptyList(),
    val verticalOffsets: List<Float> = emptyList(), val verticalIntensity: List<Float> = emptyList(),
    val saturationLevel: Float = 255f, val saturated: Boolean = false, val isBayer: Boolean = false)

/**
 * Renders the star view the way PHD2 does: the raw view shows the sensor samples
 * one to one, while the interpolated view spreads them over the displayed pixels.
 * PHD2 switches between wxIMAGE_QUALITY_NEAREST and wxIMAGE_QUALITY_HIGH on a
 * click in the star window (src/star_profile.cpp, ProfileWindow::OnLClick and
 * ProfileWindow::OnPaint). The measured values never change with the display mode.
 */
object GuideStarView {
    /** The rendered star view and its pixel grid. */
    data class Rendered(val pixels: FloatArray, val width: Int, val height: Int)

    /** The luminance samples of the star view. Bayer cells are averaged over 2x2. */
    fun luminance(profile: GuideStarProfile): FloatArray {
        val count = profile.width * profile.height
        if (count <= 0) return FloatArray(0)
        val values = FloatArray(count)
        if (!profile.isBayer) {
            for (index in 0 until count) values[index] = profile.pixels[index]
            return values
        }
        val columns = min(2, profile.width); val rows = min(2, profile.height)
        for (index in 0 until count) {
            val x = min(index % profile.width / 2 * 2, profile.width - columns)
            val y = min(index / profile.width / 2 * 2, profile.height - rows)
            var sum = 0f
            for (dy in 0 until rows) for (dx in 0 until columns) sum += profile.pixels[(y + dy) * profile.width + x + dx]
            values[index] = sum / (columns * rows)
        }
        return values
    }

    /**
     * The raw view keeps every sensor sample in a hard block; the interpolated view
     * blends the neighbouring samples. PHD2 switches between wxIMAGE_QUALITY_NEAREST
     * and wxIMAGE_QUALITY_HIGH for the same subframe (src/star_profile.cpp,
     * ProfileWindow::OnPaint), so only the display changes and the measurements stay.
     */
    fun render(profile: GuideStarProfile, raw: Boolean, scale: Int = 4): Rendered {
        val source = luminance(profile)
        if (source.isEmpty()) return Rendered(source, 0, 0)
        val width = profile.width; val height = profile.height
        val factor = scale.coerceAtLeast(1)
        val outWidth = width * factor; val outHeight = height * factor
        val out = FloatArray(outWidth * outHeight)
        if (raw) {
            for (y in 0 until outHeight) for (x in 0 until outWidth)
                out[y * outWidth + x] = source[(y / factor) * width + (x / factor)]
            return Rendered(out, outWidth, outHeight)
        }
        for (y in 0 until outHeight) {
            // Sample at the centre of the output pixel, in source coordinates.
            val sourceY = ((y + .5f) / factor - .5f).coerceIn(0f, (height - 1).toFloat())
            val y0 = floor(sourceY).toInt().coerceIn(0, height - 1)
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val fy = sourceY - y0
            for (x in 0 until outWidth) {
                val sourceX = ((x + .5f) / factor - .5f).coerceIn(0f, (width - 1).toFloat())
                val x0 = floor(sourceX).toInt().coerceIn(0, width - 1)
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                val fx = sourceX - x0
                val top = source[y0 * width + x0] * (1 - fx) + source[y0 * width + x1] * fx
                val bottom = source[y1 * width + x0] * (1 - fx) + source[y1 * width + x1] * fx
                out[y * outWidth + x] = top * (1 - fy) + bottom * fy
            }
        }
        return Rendered(out, outWidth, outHeight)
    }
}

object GuideStarAnalysis {
    fun arcsecPerPixel(pixelSizeUm: Float?, focalLengthMm: Float): Float? =
        pixelSizeUm?.takeIf { it.isFinite() && it > 0 && focalLengthMm.isFinite() && focalLengthMm > 0 }
            ?.let { 206.264806f * it / focalLengthMm }

    fun measure(frame: FrameData, star: GuideStar, saturationLevel: Int? = null): GuideStarProfile? {
        if (!star.x.isFinite() || !star.y.isFinite() || star.x < 0 || star.y < 0 || star.x >= frame.width || star.y >= frame.height ||
            frame.data.size.toLong() < frame.width.toLong() * frame.height * frame.pixelFormat.bytesPerPixel) return null
        val radius = 12
        val x0 = (star.x.roundToInt() - radius).coerceAtLeast(0); val y0 = (star.y.roundToInt() - radius).coerceAtLeast(0)
        val x1 = (star.x.roundToInt() + radius).coerceAtMost(frame.width - 1); val y1 = (star.y.roundToInt() + radius).coerceAtMost(frame.height - 1)
        val bayer = frame.pixelFormat.isBayer
        fun phase(x: Int, y: Int) = if (bayer) (y and 1) * 2 + (x and 1) else 0
        val width = x1 - x0 + 1; val height = y1 - y0 + 1
        val capacity = width * height
        val phaseCount = if (bayer) 4 else 1
        val borderCapacity = if (bayer) ((width + 1) / 2) * ((height + 1) / 2) else capacity
        val border = Array(phaseCount) { FloatArray(borderCapacity) }
        val borderCounts = IntArray(phaseCount)
        val pixels = FloatArray(capacity)
        var peak = 0f
        for (y in y0..y1) for (x in x0..x1) {
            val value = GuidePixelAccess.raw(frame, x, y).toFloat()
            pixels[(y - y0) * width + x - x0] = value
            peak = max(peak, value)
            if (hypot(x - star.x, y - star.y) >= 10f) {
                val index = phase(x, y)
                border[index][borderCounts[index]++] = value
            }
        }
        if (borderCounts.any { it == 0 }) return null
        val deviations = FloatArray(borderCapacity)
        val backgrounds = FloatArray(phaseCount) { index ->
            val values = border[index]; val count = borderCounts[index]
            java.util.Arrays.sort(values, 0, count)
            val median = values[count / 2]
            for (i in 0 until count) deviations[i] = abs(values[i] - median)
            java.util.Arrays.sort(deviations, 0, count)
            val tolerance = max(1f, deviations[count / 2] * 1.4826f * 3f)
            var sum = 0.0; var kept = 0
            for (i in 0 until count) if (abs(values[i] - median) <= tolerance) { sum += values[i]; kept++ }
            (sum / kept).toFloat()
        }
        val background = backgrounds.average().toFloat()
        val radial = FloatArray(radius + 1); val counts = IntArray(radius + 1)
        val radii = FloatArray(capacity); val fluxes = FloatArray(capacity)
        val order = LongArray(capacity)
        val energyBins = DoubleArray(radius + 1)
        var samples = 0; var totalFlux = 0.0
        for (y in y0..y1) for (x in x0..x1) {
            val r = hypot(x - star.x, y - star.y)
            if (r > radius) continue
            // Keep signed background residuals: clipping negative noise inflates faint-star wings.
            val flux = pixels[(y - y0) * width + x - x0] - backgrounds[phase(x, y)]
            radii[samples] = r; fluxes[samples] = flux
            // Non-negative Float bits preserve radius order; the index preserves stable ties.
            order[samples] = (r.toRawBits().toLong() shl 32) or samples.toLong()
            samples++
            totalFlux += flux.toDouble()
            energyBins[ceil(r.toDouble()).toInt()] += flux.toDouble()
            val bin = r.toInt(); radial[bin] += flux; counts[bin]++
        }
        val total = totalFlux.toFloat()
        if (total <= 0f) return null
        java.util.Arrays.sort(order, 0, samples)
        var sum = 0f; var hfr = 0f; var previousRadius = 0f
        for (i in 0 until samples) {
            val index = order[i].toInt()
            val r = radii[index]; val value = fluxes[index]
            if (value > 0f && sum + value >= total / 2) { hfr = previousRadius + (r - previousRadius) * (total / 2 - sum) / value; break }
            sum += value; previousRadius = r
        }
        var energy = 0f; var enclosed = 0.0
        val energies = FloatArray(radius + 1) { bin ->
            enclosed += energyBins[bin]
            energy = max(energy, (enclosed.toFloat() / total).coerceIn(0f, 1f))
            energy
        }.apply { this[lastIndex] = 1f }
        val nominal = (1 shl frame.pixelFormat.nativeBits) - 1
        val maximum = saturationLevel ?: if (peak > nominal) 65535 else nominal
        val row = star.y.roundToInt().coerceIn(y0, y1); val column = star.x.roundToInt().coerceIn(x0, x1)
        return GuideStarProfile(width, height, pixels.asList(), hfr, total, peak, background,
            radial.indices.map { radial[it] / counts[it].coerceAtLeast(1) },
            energies.asList(), (x0..x1).map { it - star.x }, (x0..x1).map { pixels[(row - y0) * width + it - x0] },
            (y0..y1).map { it - star.y }, (y0..y1).map { pixels[(it - y0) * width + column - x0] },
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

    /** Touch tolerance is in screen pixels and stays usable on reduced full-frame previews. */
    fun starAt(stars: List<GuideStar>, x: Float, y: Float, touchRadius: Float): GuideStar? {
        val point = toImage(x, y) ?: return null
        return stars.filter { contains(it.x, it.y) }.minByOrNull { hypot(it.x - point.x, it.y - point.y) }
            ?.takeIf { hypot(it.x - point.x, it.y - point.y) * scale <= touchRadius }
    }
}
