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

/**
 * Previous frame's per-point displacement, stored in the point order of one registration.
 * Values are relative to that frame's global shift, which is re-measured every frame, so the
 * window only has to cover how much the local distortion field itself moved.
 * It is only a search starting guess: every value is re-measured each frame and a hint that no
 * longer explains its patch is discarded in favour of the bounded full window.
 */
internal class LocalShiftHint {
    private var dx = DoubleArray(0)
    private var dy = DoubleArray(0)
    private var filled = 0
    fun has(index: Int): Boolean = index < filled
    fun dxAt(index: Int): Double = dx[index]
    fun dyAt(index: Int): Double = dy[index]

    /** Records one frame's accepted shifts. Gaps and a changed point count stay unfilled. */
    fun record(points: List<PlanetaryAlignmentPoint>, alignment: PlanetaryAlignment) {
        if (dx.size != points.size) { dx = DoubleArray(points.size); dy = DoubleArray(points.size) }
        filled = 0
        for (shift in alignment.local) {
            val index = points.indexOfFirst { it === shift.point }
            if (index < 0) continue
            dx[index] = shift.shift.dx - alignment.global.dx
            dy[index] = shift.shift.dy - alignment.global.dy
            filled = maxOf(filled, index + 1)
        }
    }

    fun clear() { dx = DoubleArray(0); dy = DoubleArray(0); filled = 0 }
}

/** Inverse mapping: reference (x,y) samples source (x+dx,y+dy). No display stretch is used. */
class PlanetaryRegistration private constructor(
    private val width: Int,
    private val height: Int,
    private val channels: Int,
    private val ref: FloatArray,
    private val roi: PixelRoi,
    private val options: PlanetaryOptions,
    private val checkCancelled: () -> Unit
) {
    constructor(reference: LinearImage, roi: PixelRoi, options: PlanetaryOptions,
                checkCancelled: () -> Unit = {}) : this(reference.width, reference.height,
        reference.channels, PlanetarySampling.plane(reference, checkCancelled), roi, options, checkCancelled)

    companion object {
        /** Integer window a temporal hint is searched in, against [PlanetaryOptions.maxLocalShift]. */
        private const val HINT_RADIUS = 1
        /** Centre-plus-neighbour passes allowed per refinement level before the step halves. */
        private const val REFINE_SWEEPS = 3

        /** Transfers the caller's private luminance plane; it must not be overwritten. */
        internal fun fromLuminance(reference: LinearImage, inputChannels: Int, roi: PixelRoi,
                                   options: PlanetaryOptions, checkCancelled: () -> Unit): PlanetaryRegistration {
            require(reference.channels == 1 && inputChannels in listOf(1, 3))
            return PlanetaryRegistration(reference.width, reference.height, inputChannels, reference.pixels,
                roi, options, checkCancelled)
        }
    }

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

    /** A frame's luminance, owned by default or borrowed from explicit worker scratch. Finish
     * registration/edge checks before reusing scratch; no image-identity cache is retained. */
    internal class PreparedFrame internal constructor(internal val pixels: FloatArray, internal val owner: PlanetaryRegistration)

    internal fun prepareFrame(image: LinearImage): PreparedFrame {
        require(image.width == width && image.height == height && image.channels == channels)
        return PreparedFrame(PlanetarySampling.plane(image, checkCancelled),this)
    }

    /** Worker-owned scratch. The caller must finish alignment/edge checks before filling it again. */
    internal fun prepareFrame(image: LinearImage, scratch: FloatArray): PreparedFrame {
        require(image.width == width && image.height == height && image.channels == channels)
        PlanetarySampling.fillPlane(image, scratch, checkCancelled)
        return PreparedFrame(scratch, this)
    }

    fun align(image: LinearImage): PlanetaryAlignment = align(prepareFrame(image), null)

    internal fun align(frame: PreparedFrame): PlanetaryAlignment = align(frame, null)

    internal fun align(frame: PreparedFrame, hint: LocalShiftHint?): PlanetaryAlignment {
        require(frame.owner === this) { "Prepared frame belongs to another registration" }
        val src = frame.pixels
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
        val local = ArrayList<PlanetaryLocalShift>(points.size)
        fun acceptable(shift: PlanetaryShift) = shift.correlation >= options.minimumLocalCorrelation &&
            abs(shift.dx - global.dx) < options.maxLocalShift - .2 && abs(shift.dy - global.dy) < options.maxLocalShift - .2
        for (index in points.indices) {
            checkCancelled()
            val p = points[index]
            val area = PixelRoi(p.x.toInt() - p.radius, p.y.toInt() - p.radius, p.radius * 2, p.radius * 2)
            // Consecutive frames move the distortion field slowly, so the previous frame's
            // relative displacement for this point centres a far smaller window than the
            // global shift does, and it survives a uniform drift between the two frames.
            // A hint that no longer explains the patch fails acceptance and the bounded full
            // window is searched instead, so a lost hint costs time and never a correct answer.
            var shift = if (hint != null && hint.has(index))
                search(src, area, global.dx + hint.dxAt(index), global.dy + hint.dyAt(index), HINT_RADIUS, global = false)
                    .takeIf { acceptable(it) } else null
            if (shift == null) shift = search(src, area, global.dx, global.dy, options.maxLocalShift, global = false)
                .takeIf { acceptable(it) }
            if (shift != null) local += PlanetaryLocalShift(p, shift)
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
    internal fun edgeResidual(image: LinearImage, warp: PlanetaryWarp): Double = edgeResidual(prepareFrame(image),warp)

    internal fun edgeResidual(frame: PreparedFrame, warp: PlanetaryWarp): Double {
        require(frame.owner === this) { "Prepared frame belongs to another registration" }
        val src = frame.pixels
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

    /** One stride of a bounded integer sweep. The seed keeps the caller's running best. */
    private fun sweep(src: FloatArray, area: PixelRoi, fromX: Int, toX: Int, fromY: Int, toY: Int,
                      stride: Int, seed: PlanetaryShift, sampleStep: Int): PlanetaryShift {
        var bestX = seed.dx; var bestY = seed.dy; var best = seed.correlation
        for (dy in fromY..toY step stride) {
            checkCancelled()
            for (dx in fromX..toX step stride) {
                val score = correlation(src, area, dx.toDouble(), dy.toDouble(), sampleStep)
                if (score > best) { best = score; bestX = dx.toDouble(); bestY = dy.toDouble() }
            }
        }
        return PlanetaryShift(bestX, bestY, best)
    }

    private fun search(src: FloatArray, area: PixelRoi, startX: Double, startY: Double, radius: Int,
                       global: Boolean, coarseStep: Int = if (global) max(1, radius / 8) else 1): PlanetaryShift {
        val sampleStep = if (global) max(1, min(area.width, area.height) / 72) else max(1, area.width / 32)
        var bestX = startX.roundToInt().toDouble(); var bestY = startY.roundToInt().toDouble()
        var best = correlation(src, area, bestX, bestY, sampleStep)
        // Centroid handles large motion. The bounded full search also handles changes in target brightness.
        val fromX = if (global) -radius else (startX - radius).roundToInt()
        val toX = if (global) radius else (startX + radius).roundToInt()
        val fromY = if (global) -radius else (startY - radius).roundToInt()
        val toY = if (global) radius else (startY + radius).roundToInt()
        val start = PlanetaryShift(bestX, bestY, best)
        val seed = if (global && coarseStep > 1) {
            // A stride twice the resolution cannot resolve a peak, so the wide sweep runs
            // first and the original stride then resolves its neighbourhood in both axes.
            val wide = coarseStep * 2
            val coarse = sweep(src, area, fromX, toX, fromY, toY, wide, start, sampleStep)
            val nearX = floor(coarse.dx).toInt().coerceIn(fromX, toX)
            val nearY = floor(coarse.dy).toInt().coerceIn(fromY, toY)
            val half = 2 * coarseStep
            // An edge winner cannot certify the opposite side, so its window widens back to
            // the whole range and no candidate is dropped relative to the single fine sweep.
            sweep(src, area,
                if (nearX - half <= fromX || nearX + half >= toX) fromX else max(fromX, nearX - half),
                if (nearX - half <= fromX || nearX + half >= toX) toX else min(toX, nearX + half),
                if (nearY - half <= fromY || nearY + half >= toY) fromY else max(fromY, nearY - half),
                if (nearY - half <= fromY || nearY + half >= toY) toY else min(toY, nearY + half),
                coarseStep, coarse, sampleStep)
        } else sweep(src, area, fromX, toX, fromY, toY, coarseStep, start, sampleStep)
        bestX = seed.dx; bestY = seed.dy; best = seed.correlation
        // A centre-plus-eight-neighbour pattern reaches the same 0.125 px floor with a third of
        // the correlations a 5 x 5 lattice needs. The centre is the current best and cannot win.
        var step = if (global) max(1.0, coarseStep / 2.0) else .5
        while (step >= .125) {
            checkCancelled()
            var sweeps = 0
            while (sweeps < REFINE_SWEEPS) {
                checkCancelled(); sweeps++
                val cx = bestX; val cy = bestY
                var improved = false
                for (iy in -1..1) for (ix in -1..1) {
                    if (ix == 0 && iy == 0) continue
                    val dx = cx + ix * step; val dy = cy + iy * step
                    if (dx < fromX || dx > toX || dy < fromY || dy > toY) continue
                    val score = correlation(src, area, dx, dy, sampleStep)
                    if (score > best) { best = score; bestX = dx; bestY = dy; improved = true }
                }
                if (!improved) break
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

/**
 * Mesh indices and interpolation fractions resolved once per image coordinate. The mesh grid
 * depends only on the geometry, so one table serves every frame of a stack and removes the
 * per-pixel division, bounds test and rounding that a direct bilinear lookup repeats.
 */
internal class OffsetTable(val width: Int, val height: Int, val step: Int, val columns: Int, val rows: Int) {
    private val columnIndex = IntArray(width)
    private val columnNext = IntArray(width)
    private val columnFraction = DoubleArray(width)
    private val rowBase = IntArray(height)
    private val rowNextBase = IntArray(height)
    private val rowFraction = DoubleArray(height)
    init {
        for (x in 0 until width) {
            val grid = x.toDouble() / step
            val index = grid.toInt()
            columnIndex[x] = index
            columnNext[x] = min(columns - 1, index + 1)
            columnFraction[x] = grid - index
        }
        for (y in 0 until height) {
            val grid = y.toDouble() / step
            val index = grid.toInt()
            rowBase[y] = index * columns
            rowNextBase[y] = min(rows - 1, index + 1) * columns
            rowFraction[y] = grid - index
        }
    }
    fun rowBase(y: Int): Int = rowBase[y]
    fun rowNextBase(y: Int): Int = rowNextBase[y]
    fun rowFraction(y: Int): Double = rowFraction[y]
    fun columnIndex(x: Int): Int = columnIndex[x]
    fun columnNext(x: Int): Int = columnNext[x]
    fun columnFraction(x: Int): Double = columnFraction[x]
    fun offset(values: FloatArray, x: Int, y: Int): Double {
        val fx = columnFraction[x]; val ix = columnIndex[x]; val nx = columnNext[x]
        val fy = rowFraction[y]; val top = rowBase[y]; val bottom = rowNextBase[y]
        return (values[top + ix] * (1 - fx) + values[top + nx] * fx) * (1 - fy) +
            (values[bottom + ix] * (1 - fx) + values[bottom + nx] * fx) * fy
    }
}

/** Smooth partition-of-unity displacement interpolation, then bilinear mesh interpolation per pixel. */
internal class PlanetaryWarp(width: Int, height: Int, alignment: PlanetaryAlignment, checkCancelled: () -> Unit = {}, workspace: Workspace? = null) {
    val step = max(4, min(32, alignment.local.firstOrNull()?.point?.radius ?: 16))
    val columns = (width + step - 1) / step + 1
    val rows = (height + step - 1) / step + 1
    private val buffers = workspace?.buffers(columns * rows)
    val dx = buffers?.first ?: FloatArray(columns * rows)
    val dy = buffers?.second ?: FloatArray(columns * rows)
    val offsets: OffsetTable = workspace?.tables(width, height, step, columns, rows)
        ?: OffsetTable(width, height, step, columns, rows)
    var folded = false
        private set
    init {
        // A point's support radius, correlation weight and displacement relative to the global
        // shift are identical at every mesh node, so they are resolved once per alignment.
        val points = alignment.local
        val pointX = DoubleArray(points.size); val pointY = DoubleArray(points.size)
        val relativeX = DoubleArray(points.size); val relativeY = DoubleArray(points.size)
        val correlation4 = DoubleArray(points.size); val support = DoubleArray(points.size)
        for (i in points.indices) {
            val local = points[i]
            pointX[i] = local.point.x; pointY[i] = local.point.y
            relativeX[i] = local.shift.dx - alignment.global.dx
            relativeY[i] = local.shift.dy - alignment.global.dy
            correlation4[i] = local.shift.correlation.pow(4)
            support[i] = local.point.radius * 3.0
        }
        for (y in 0 until rows) {
          checkCancelled()
          val nodeY = y * step
          for (x in 0 until columns) {
            val nodeX = x * step
            var sx = 0.0; var sy = 0.0; var weight = 0.0
            for (i in points.indices) {
                val distance = hypot(nodeX - pointX[i], nodeY - pointY[i]) / support[i]
                if (distance < 1) {
                    val w = (1 - distance * distance).pow(3) * correlation4[i]
                    sx += relativeX[i] * w
                    sy += relativeY[i] * w
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
        offsets.offset(values, x, y)

    internal class Workspace {
        private var arrays = FloatArray(0) to FloatArray(0)
        private var table: OffsetTable? = null
        fun buffers(size: Int): Pair<FloatArray, FloatArray> {
            // Local/global fallback can change mesh dimensions. Keep the largest capacity so
            // a seeing fluctuation does not reallocate two large meshes on each mode change.
            if (arrays.first.size < size) arrays = FloatArray(size) to FloatArray(size)
            return arrays
        }
        /** Reused while the geometry holds; a step change from local/global mode rebuilds it. */
        fun tables(width: Int, height: Int, step: Int, columns: Int, rows: Int): OffsetTable {
            val current = table
            if (current == null || current.width != width || current.height != height ||
                current.step != step || current.columns != columns || current.rows != rows)
                table = OffsetTable(width, height, step, columns, rows)
            return checkNotNull(table)
        }
        fun clear() { arrays = FloatArray(0) to FloatArray(0); table = null }
    }
}
