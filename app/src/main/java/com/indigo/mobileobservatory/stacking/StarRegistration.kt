package com.indigo.mobileobservatory.stacking

import kotlin.math.*
import java.util.concurrent.CancellationException
import java.util.Arrays

data class DetectedStar(val x: Double, val y: Double, val flux: Double, val radius: Double, val elongation: Double)
data class StarField(val stars: List<DetectedStar>, val background: Double, val noise: Double, val medianElongation: Double)

/** Maps source coordinates onto the fixed reference canvas. No scale is fitted. */
data class RigidTransform(val angle: Double = 0.0, val dx: Double = 0.0, val dy: Double = 0.0) {
    val cosine: Double get() = cos(angle)
    val sine: Double get() = sin(angle)
    fun x(x: Double, y: Double): Double = cosine * x - sine * y + dx
    fun y(x: Double, y: Double): Double = sine * x + cosine * y + dy
}
data class StarMatch(val transform: RigidTransform, val inliers: Int, val rmsPixels: Double, val transparency: Double)

/** Pure Kotlin, bounded-star deterministic consensus registration. Operates on linear input. */
object StarRegistration {
    /** Worker-owned primitive median scratch. Detection results never retain these buffers. */
    internal class Workspace {
        private val samples = DoubleSamples()
        private val differences = DoubleSamples()
        private var inUse = false
        val retainedBytes: Long @Synchronized get() = samples.bytes+differences.bytes
        @Synchronized internal fun acquire(): DetectionScratch {
            check(!inUse) { "Star detection workspace is already in use" }
            samples.clear(); differences.clear(); inUse = true
            return DetectionScratch(samples,differences)
        }
        @Synchronized internal fun finish() { inUse = false }
        @Synchronized fun release() {
            check(!inUse) { "Cannot release star detection workspace while processing" }
            samples.release(); differences.release()
        }
    }

    internal data class DetectionScratch(val samples: DoubleSamples,val differences: DoubleSamples)

    internal class DoubleSamples(initialCapacity: Int = 256) {
        private var values = DoubleArray(initialCapacity)
        var size = 0
            private set
        val bytes: Long get() = values.size*8L
        fun clear() { size = 0 }
        fun add(value: Double) {
            if (size == values.size) values = values.copyOf(max(256,values.size*2))
            values[size++] = value
        }
        fun median(): Double {
            if (size == 0) return 0.0
            Arrays.sort(values,0,size)
            return if (size%2 == 1) values[size/2] else (values[size/2-1]+values[size/2])/2
        }
        fun release() { values = DoubleArray(0); size = 0 }
    }

    fun detect(image: LinearImage, maximumStars: Int = 100,
               cancelled: () -> Boolean = { false }): StarField =
        detect(image,maximumStars,Workspace(),cancelled)

    internal fun detect(image: LinearImage, maximumStars: Int = 100, workspace: Workspace,
                        cancelled: () -> Boolean = { false }): StarField {
        val scratch = workspace.acquire()
        try {
            return detectImpl(image,maximumStars,scratch,cancelled)
        } finally {
            workspace.finish()
        }
    }

    private fun detectImpl(image: LinearImage, maximumStars: Int, scratch: DetectionScratch,
                           cancelled: () -> Boolean): StarField {
        val w = image.width; val h = image.height
        if (w < 16 || h < 16) return StarField(emptyList(), 0.0, 0.0, 1.0)
        val samples = scratch.samples
        val differences = scratch.differences
        val stride = max(1, sqrt(w.toDouble() * h / 12000).toInt())
        for (y in 1 until h - 1 step stride) for (x in 1 until w - 1 step stride) {
            val value = luminance(image, y * w + x)
            samples.add(value)
            differences.add(abs(value - luminance(image, y * w + x + 1)))
        }
        val background = samples.median()
        // Median absolute adjacent difference: Gaussian sigma = MAD / (0.67449 sqrt(2)).
        val noise = max(1.0 / 65535.0, differences.median() / 0.9538726)
        val cell = 48; val gw = (w + cell - 1) / cell; val gh = (h + cell - 1) / cell
        val localBackground = DoubleArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            samples.clear()
            for (y in gy * cell until min(h, (gy + 1) * cell) step 3)
                for (x in gx * cell until min(w, (gx + 1) * cell) step 3) samples.add(luminance(image, y * w + x))
            localBackground[gy * gw + gx] = samples.median()
        }
        val candidates = ArrayList<DetectedStar>()
        for (y in 6 until h - 6) for (x in 6 until w - 6) {
            if (x == 6 && y % 16 == 0 && cancelled()) throw CancellationException("Star detection cancelled")
            val peak = luminance(image, y * w + x)
            if (peak < localBackground[y / cell * gw + x / cell] + noise * 5.0) continue
            var maximum = true
            for (oy in -1..1) for (ox in -1..1) if ((ox != 0 || oy != 0) && luminance(image, (y + oy) * w + x + ox) >= peak) maximum = false
            if (!maximum) continue
            samples.clear()
            for (oy in -6..6) for (ox in -6..6) if (abs(ox) == 6 || abs(oy) == 6)
                samples.add(luminance(image, (y + oy) * w + x + ox))
            val base = samples.median()
            var flux = 0.0; var mx = 0.0; var my = 0.0; var neighborMaximum = 0.0
            for (oy in -3..3) for (ox in -3..3) {
                val signal = max(0.0, luminance(image, (y + oy) * w + x + ox) - base - noise)
                if (ox != 0 || oy != 0) neighborMaximum = max(neighborMaximum, signal)
                flux += signal; mx += ox * signal; my += oy * signal
            }
            // Single-pixel defects cannot establish a reference or bias the registration.
            if (flux < noise * 20 || peak - base > neighborMaximum * 4.5 + noise * 5) continue
            val cx = mx / flux; val cy = my / flux
            var xx = 0.0; var xy = 0.0; var yy = 0.0; var shapeFlux = 0.0
            // Wider shape aperture is essential: a 7x7 centroid window makes long trails
            // appear round by cutting off their ends.
            for (oy in -5..5) for (ox in -5..5) {
                val signal = max(0.0, luminance(image, (y + oy) * w + x + ox) - base - 2 * noise)
                shapeFlux += signal
                xx += (ox - cx).pow(2) * signal; yy += (oy - cy).pow(2) * signal; xy += (ox - cx) * (oy - cy) * signal
            }
            if (shapeFlux <= 0) continue
            xx /= shapeFlux; yy /= shapeFlux; xy /= shapeFlux
            val delta = sqrt((xx - yy).pow(2) + 4 * xy * xy)
            val major = (xx + yy + delta) / 2; val minor = (xx + yy - delta) / 2
            if (minor < 0.12 || abs(cx) > 1.5 || abs(cy) > 1.5) continue
            candidates += DetectedStar(x + cx, y + cy, flux, sqrt(xx + yy), sqrt(major / max(minor, 0.01)))
        }
        val chosen = ArrayList<DetectedStar>()
        for (star in candidates.sortedByDescending { it.flux }) {
            if (chosen.none { hypot(it.x - star.x, it.y - star.y) < 7 }) chosen += star
            if (chosen.size >= maximumStars) break
        }
        return StarField(chosen, background, noise, median(chosen.map { it.elongation }))
    }

    fun match(reference: StarField, source: StarField, tolerancePixels: Double = 2.5, cancelled: () -> Boolean = { false }): StarMatch? {
        if (reference.stars.size < 5 || source.stars.size < 5) return null
        val ref = reference.stars.take(70); val src = source.stars.take(70)
        val seedRef = ref.take(22); val seedSrc = src.take(22)
        data class PairSeed(val a: Int, val b: Int, val length: Double, val angle: Double)
        fun pairs(stars: List<DetectedStar>): List<PairSeed> = buildList {
            for (a in stars.indices) for (b in a + 1 until stars.size) {
                val dx = stars[b].x - stars[a].x; val dy = stars[b].y - stars[a].y
                if (hypot(dx, dy) > 20) add(PairSeed(a, b, hypot(dx, dy), atan2(dy, dx)))
            }
        }.sortedByDescending { it.length }
        val refPairs = pairs(seedRef); val srcPairs = pairs(seedSrc)
        var best: RigidTransform? = null; var bestMatches = emptyList<Pair<Int, Int>>(); var bestError = Double.POSITIVE_INFINITY
        var attempts = 0
        outer@ for (rp in refPairs) for (sp in srcPairs) {
            if (cancelled()) throw CancellationException("Star registration cancelled")
            if (abs(rp.length - sp.length) > 1.6) continue
            for (reverse in 0..1) {
                val angle = rp.angle - sp.angle + reverse * PI
                val a = seedSrc[if (reverse == 0) sp.a else sp.b]; val r = seedRef[rp.a]
                val candidate = RigidTransform(angle, r.x - cos(angle) * a.x + sin(angle) * a.y, r.y - sin(angle) * a.x - cos(angle) * a.y)
                val matches = correspondences(ref, src, candidate, tolerancePixels)
                val error = matches.sumOf { (i, j) -> (candidate.x(src[j].x, src[j].y) - ref[i].x).pow(2) + (candidate.y(src[j].x, src[j].y) - ref[i].y).pow(2) }
                if (matches.size > bestMatches.size || matches.size == bestMatches.size && error < bestError) {
                    best = candidate; bestMatches = matches; bestError = error
                }
                attempts++
                if (bestMatches.size >= max(6, (min(ref.size, src.size) * 0.8).toInt()) && bestError / bestMatches.size < 0.35 || attempts >= 1800) break@outer
            }
        }
        var transform = best ?: return null
        if (bestMatches.size < 5) return null
        repeat(3) {
            transform = fit(ref, src, bestMatches)
            val residuals = bestMatches.map { (i,j) -> hypot(transform.x(src[j].x, src[j].y) - ref[i].x, transform.y(src[j].x, src[j].y) - ref[i].y) }
            val radius = max(0.45, min(tolerancePixels, median(residuals) * 3.5))
            bestMatches = correspondences(ref, src, transform, radius)
            if (bestMatches.size < 5) return null
        }
        transform = fit(ref, src, bestMatches)
        val rms = sqrt(bestMatches.sumOf { (i,j) -> (transform.x(src[j].x, src[j].y) - ref[i].x).pow(2) + (transform.y(src[j].x, src[j].y) - ref[i].y).pow(2) } / bestMatches.size)
        if (rms > 1.2 || bestMatches.size < min(ref.size, src.size) * 0.3) return null
        return StarMatch(transform, bestMatches.size, rms, median(bestMatches.map { (i,j) -> src[j].flux / ref[i].flux }))
    }

    private fun correspondences(ref: List<DetectedStar>, src: List<DetectedStar>, t: RigidTransform, tolerance: Double): List<Pair<Int,Int>> {
        val used = BooleanArray(ref.size); val result = ArrayList<Pair<Int,Int>>()
        val c = t.cosine; val s = t.sine
        for (j in src.indices) {
            val x = c * src[j].x - s * src[j].y + t.dx; val y = s * src[j].x + c * src[j].y + t.dy
            var nearest = -1; var distance = tolerance * tolerance
            for (i in ref.indices) if (!used[i]) {
                val d = (ref[i].x - x).pow(2) + (ref[i].y - y).pow(2)
                if (d < distance) { nearest = i; distance = d }
            }
            if (nearest >= 0) { used[nearest] = true; result += nearest to j }
        }
        return result
    }

    private fun fit(ref: List<DetectedStar>, src: List<DetectedStar>, matches: List<Pair<Int,Int>>): RigidTransform {
        val rx = matches.sumOf { ref[it.first].x } / matches.size; val ry = matches.sumOf { ref[it.first].y } / matches.size
        val sx = matches.sumOf { src[it.second].x } / matches.size; val sy = matches.sumOf { src[it.second].y } / matches.size
        var dot = 0.0; var cross = 0.0
        for ((i,j) in matches) { val ax = src[j].x - sx; val ay = src[j].y - sy; val bx = ref[i].x - rx; val by = ref[i].y - ry
            dot += ax * bx + ay * by; cross += ax * by - ay * bx }
        val angle = atan2(cross, dot)
        return RigidTransform(angle, rx - cos(angle) * sx + sin(angle) * sy, ry - sin(angle) * sx - cos(angle) * sy)
    }

    internal fun luminance(image: LinearImage, pixel: Int): Double = if (image.channels == 1) image.pixels[pixel].toDouble() else {
        val i = pixel * image.channels
        image.pixels[i] * 0.2126 + image.pixels[i + 1] * 0.7152 + image.pixels[i + 2] * 0.0722
    }
    internal fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted(); return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
}
