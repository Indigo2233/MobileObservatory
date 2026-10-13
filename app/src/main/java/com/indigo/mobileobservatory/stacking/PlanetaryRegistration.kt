package com.indigo.mobileobservatory.stacking

import kotlin.math.*

enum class PlanetaryRegistrationMode { GLOBAL, MULTIPOINT, GLOBAL_LOW_TEXTURE, GLOBAL_LOCAL_FALLBACK }
data class PlanetaryShift(val dx: Double, val dy: Double, val correlation: Double)
data class PlanetaryAlignmentPoint(val x: Double, val y: Double, val radius: Int)
data class PlanetaryLocalShift(val point: PlanetaryAlignmentPoint, val shift: PlanetaryShift)
data class PlanetaryAlignment(
    val global: PlanetaryShift,
    val local: List<PlanetaryLocalShift>,
    val pointCount: Int,
    val mode: PlanetaryRegistrationMode,
    val rejection: PlanetaryRejection = PlanetaryRejection.NONE
)

/** Inverse mapping: reference (x,y) samples source (x+dx,y+dy). No display stretch is used. */
class PlanetaryRegistration(
    private val reference: LinearImage,
    private val roi: PixelRoi,
    private val options: PlanetaryOptions,
    private val checkCancelled: () -> Unit = {}
) {
    private val width = reference.width
    private val height = reference.height
    private val ref = PlanetarySampling.plane(reference, checkCancelled)
    private val referenceCenter = center(ref)
    val points: List<PlanetaryAlignmentPoint> = makePoints()

    private fun center(pixels: FloatArray): Pair<Double, Double> {
        var sx = 0.0; var sy = 0.0; var sw = 0.0
        var minimum = 1.0
        val step = max(1, min(roi.width, roi.height) / 100)
        for (y in roi.top until roi.bottom step step) {
            checkCancelled()
            for (x in roi.left until roi.right step step) minimum = min(minimum, pixels[y * width + x].toDouble())
        }
        for (y in roi.top until roi.bottom step step) {
            checkCancelled()
            for (x in roi.left until roi.right step step) {
                val weight = max(0.0, pixels[y * width + x] - minimum - .01)
                sx += x * weight; sy += y * weight; sw += weight
            }
        }
        return if (sw > 1e-8) sx / sw to sy / sw else roi.left + roi.width / 2.0 to roi.top + roi.height / 2.0
    }

    private fun makePoints(): List<PlanetaryAlignmentPoint> {
        val radius = options.alignmentPatchSize?.let { it / 2 }
            ?: (min(roi.width, roi.height) / 10).coerceIn(8, 32)
        val spacing = max(radius, ceil(sqrt(roi.width.toDouble() * roi.height / options.maxAlignmentPoints)).toInt())
        val result = ArrayList<PlanetaryAlignmentPoint>()
        for (y in roi.top + radius until roi.bottom - radius step spacing) {
            checkCancelled()
            for (x in roi.left + radius until roi.right - radius step spacing) {
                val area = PixelRoi(x - radius, y - radius, radius * 2, radius * 2)
                var sum = 0.0; var sq = 0.0; var gradient = 0.0; var n = 0
                for (py in area.top + 1 until area.bottom - 1 step 2) for (px in area.left + 1 until area.right - 1 step 2) {
                    val v = ref[py * width + px].toDouble()
                    val gx = ref[py * width + px + 1] - ref[py * width + px - 1]
                    val gy = ref[(py + 1) * width + px] - ref[(py - 1) * width + px]
                    sum += v; sq += v * v; gradient += gx * gx + gy * gy; n++
                }
                if (n > 0 && sq / n - (sum / n).pow(2) > .000025 && gradient / n > .000003) {
                    result += PlanetaryAlignmentPoint(x.toDouble(), y.toDouble(), radius)
                    if (result.size == options.maxAlignmentPoints) return result
                }
            }
        }
        return result
    }

    fun align(image: LinearImage): PlanetaryAlignment {
        require(image.width == width && image.height == height && image.channels == reference.channels)
        val src = PlanetarySampling.plane(image, checkCancelled)
        val currentCenter = center(src)
        val startX = (currentCenter.first - referenceCenter.first).coerceIn(-options.maxGlobalShift.toDouble(), options.maxGlobalShift.toDouble())
        val startY = (currentCenter.second - referenceCenter.second).coerceIn(-options.maxGlobalShift.toDouble(), options.maxGlobalShift.toDouble())
        val global = search(src, roi, startX, startY, options.maxGlobalShift, global = true)
        if (global.correlation < options.minimumGlobalCorrelation ||
            abs(global.dx) >= options.maxGlobalShift - .2 || abs(global.dy) >= options.maxGlobalShift - .2) {
            return PlanetaryAlignment(global, emptyList(), points.size, PlanetaryRegistrationMode.GLOBAL, PlanetaryRejection.GLOBAL_MATCH)
        }
        if (!options.localAlignment) return PlanetaryAlignment(global, emptyList(), points.size, PlanetaryRegistrationMode.GLOBAL)
        if (points.size < 3) return PlanetaryAlignment(global, emptyList(), points.size, PlanetaryRegistrationMode.GLOBAL_LOW_TEXTURE)
        val local = ArrayList<PlanetaryLocalShift>()
        for (p in points) {
            checkCancelled()
            val area = PixelRoi(p.x.toInt() - p.radius, p.y.toInt() - p.radius, p.radius * 2, p.radius * 2)
            val shift = search(src, area, global.dx, global.dy, options.maxLocalShift, global = false)
            if (shift.correlation >= options.minimumLocalCorrelation &&
                abs(shift.dx - global.dx) < options.maxLocalShift - .2 && abs(shift.dy - global.dy) < options.maxLocalShift - .2) {
                local += PlanetaryLocalShift(p, shift)
            }
        }
        // Grossly contradictory patches cause double edges, so reject rather than hiding them behind blending.
        val consistent = local.filter { a ->
            val neighbors = local.filter { b -> b !== a && hypot(a.point.x - b.point.x, a.point.y - b.point.y) < a.point.radius * 3 }
            neighbors.isEmpty() || neighbors.count { b -> hypot(a.shift.dx - b.shift.dx, a.shift.dy - b.shift.dy) <= options.maxLocalShift * 1.2 } >= (neighbors.size + 1) / 2
        }
        if (consistent.size < max(3, points.size / 3)) {
            return if (global.correlation >= .94) PlanetaryAlignment(global, emptyList(), points.size, PlanetaryRegistrationMode.GLOBAL_LOCAL_FALLBACK)
            else PlanetaryAlignment(global, emptyList(), points.size, PlanetaryRegistrationMode.GLOBAL, PlanetaryRejection.LOCAL_MATCH)
        }
        return PlanetaryAlignment(global, consistent, points.size, PlanetaryRegistrationMode.MULTIPOINT)
    }

    /** Strong limb/detail edges are checked after local warping; a split edge can have deceptively high NCC. */
    internal fun edgeResidual(image: LinearImage, warp: PlanetaryWarp): Double {
        val src = PlanetarySampling.plane(image, checkCancelled)
        val step = max(1, min(roi.width, roi.height) / 128)
        var peakGradient = 0.0
        for (y in max(roi.top, 1) until min(roi.bottom, height - 1) step step) {
            checkCancelled()
            for (x in max(roi.left, 1) until min(roi.right, width - 1) step step) {
                val gx = ref[y * width + x + 1] - ref[y * width + x - 1]
                val gy = ref[(y + 1) * width + x] - ref[(y - 1) * width + x]
                peakGradient = max(peakGradient, hypot(gx.toDouble(), gy.toDouble()))
            }
        }
        if (peakGradient < .02) return 0.0
        var squared = 0.0; var n = 0
        for (y in max(roi.top, 4) until min(roi.bottom, height - 4) step step) {
            checkCancelled()
            for (x in max(roi.left, 4) until min(roi.right, width - 4) step step) {
                val gx = (ref[y * width + x + 1] - ref[y * width + x - 1]).toDouble()
                val gy = (ref[(y + 1) * width + x] - ref[(y - 1) * width + x]).toDouble()
                val gradient = hypot(gx, gy)
                if (gradient < peakGradient * .45) continue
                val nx = gx / gradient; val ny = gy / gradient
                val dx = warp.offset(warp.dx, x, y); val dy = warp.offset(warp.dy, x, y)
                val refLow = PlanetarySampling.bilinear(ref, width, height, x - nx * 3, y - ny * 3)
                val refHigh = PlanetarySampling.bilinear(ref, width, height, x + nx * 3, y + ny * 3)
                val srcLow = PlanetarySampling.bilinear(src, width, height, x + dx - nx * 3, y + dy - ny * 3)
                val srcHigh = PlanetarySampling.bilinear(src, width, height, x + dx + nx * 3, y + dy + ny * 3)
                val refRange = refHigh - refLow; val srcRange = srcHigh - srcLow
                if (!srcRange.isFinite() || refRange < .02) continue
                // Low edge response is itself evidence of a doubled or badly smeared limb.
                if (srcRange < refRange * .25) { squared += .25; n++; continue }
                for (offset in -2..2) {
                    val r = PlanetarySampling.bilinear(ref, width, height, x + nx * offset, y + ny * offset)
                    val s = PlanetarySampling.bilinear(src, width, height, x + dx + nx * offset, y + dy + ny * offset)
                    val difference = (r - refLow) / refRange - (s - srcLow) / srcRange
                    squared += difference * difference; n++
                }
            }
        }
        return if (n >= 20) sqrt(squared / n) else 0.0
    }

    private fun search(src: FloatArray, area: PixelRoi, startX: Double, startY: Double, radius: Int, global: Boolean): PlanetaryShift {
        val sampleStep = if (global) max(1, min(area.width, area.height) / 72) else max(1, area.width / 32)
        var bestX = startX.roundToInt().toDouble(); var bestY = startY.roundToInt().toDouble()
        var best = correlation(src, area, bestX, bestY, sampleStep)
        val initialStep = if (global) max(1, radius / 8) else 1
        // Centroid handles large motion. The bounded full search also handles changes in target brightness.
        val fromX = if (global) -radius else (startX - radius).roundToInt()
        val toX = if (global) radius else (startX + radius).roundToInt()
        val fromY = if (global) -radius else (startY - radius).roundToInt()
        val toY = if (global) radius else (startY + radius).roundToInt()
        for (dy in fromY..toY step initialStep) {
            checkCancelled()
            for (dx in fromX..toX step initialStep) {
                val score = correlation(src, area, dx.toDouble(), dy.toDouble(), sampleStep)
                if (score > best) { best = score; bestX = dx.toDouble(); bestY = dy.toDouble() }
            }
        }
        var step = if (global) max(1.0, initialStep / 2.0) else .5
        while (step >= .125) {
            checkCancelled()
            val cx = bestX; val cy = bestY
            for (iy in -2..2) for (ix in -2..2) {
                val dx = cx + ix * step; val dy = cy + iy * step
                if (dx < fromX || dx > toX || dy < fromY || dy > toY) continue
                val score = correlation(src, area, dx, dy, sampleStep)
                if (score > best) { best = score; bestX = dx; bestY = dy }
            }
            step *= .5
        }
        return PlanetaryShift(bestX, bestY, best)
    }

    private fun correlation(src: FloatArray, area: PixelRoi, dx: Double, dy: Double, step: Int): Double {
        var sr = 0.0; var ss = 0.0; var rr = 0.0; var tt = 0.0; var rs = 0.0; var n = 0; var expected = 0
        for (y in area.top until area.bottom step step) for (x in area.left until area.right step step) {
            expected++
            val a = ref[y * width + x].toDouble()
            val b = PlanetarySampling.bilinear(src, width, height, x + dx, y + dy)
            if (!a.isFinite() || !b.isFinite() || a >= .995 || b >= .995) continue
            sr += a; ss += b; rr += a * a; tt += b * b; rs += a * b; n++
        }
        if (n < 24 || n < expected * .7) return -1.0
        val vr = rr - sr * sr / n; val vs = tt - ss * ss / n
        if (vr <= 1e-9 || vs <= 1e-9) return -1.0
        return ((rs - sr * ss / n) / sqrt(vr * vs)).coerceIn(-1.0, 1.0)
    }
}

/** Smooth partition-of-unity displacement interpolation, then bilinear mesh interpolation per pixel. */
internal class PlanetaryWarp(width: Int, height: Int, alignment: PlanetaryAlignment, checkCancelled: () -> Unit = {}) {
    val step = max(4, min(32, alignment.local.firstOrNull()?.point?.radius ?: 16))
    val columns = (width + step - 1) / step + 1
    val rows = (height + step - 1) / step + 1
    val dx = FloatArray(columns * rows)
    val dy = FloatArray(columns * rows)
    var folded = false
        private set
    init {
        for (y in 0 until rows) {
          checkCancelled()
          for (x in 0 until columns) {
            var sx = 0.0; var sy = 0.0; var weight = 0.0
            for (ap in alignment.local) {
                val distance = hypot(x * step - ap.point.x, y * step - ap.point.y) / (ap.point.radius * 3.0)
                if (distance < 1) {
                    val w = (1 - distance * distance).pow(3) * ap.shift.correlation.pow(4)
                    sx += (ap.shift.dx - alignment.global.dx) * w
                    sy += (ap.shift.dy - alignment.global.dy) * w
                    weight += w
                }
            }
            val i = y * columns + x
            // A tiny global anchor makes the boundary transition smooth in areas with no AP coverage.
            dx[i] = (alignment.global.dx + sx / (weight + .002)).toFloat()
            dy[i] = (alignment.global.dy + sy / (weight + .002)).toFloat()
          }
        }
        for (y in 0 until rows - 1) {
          checkCancelled()
          for (x in 0 until columns - 1) {
            val i = y * columns + x
            val ax = 1 + (dx[i + 1] - dx[i]) / step
            val ay = (dy[i + 1] - dy[i]) / step
            val bx = (dx[i + columns] - dx[i]) / step
            val by = 1 + (dy[i + columns] - dy[i]) / step
            if (ax * by - ay * bx < .35) folded = true
          }
        }
    }
    fun offset(values: FloatArray, x: Int, y: Int): Double =
        PlanetarySampling.bilinear(values, columns, rows, x.toDouble() / step, y.toDouble() / step)
}
