package com.indigo.mobileobservatory.stacking

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

/**
 * Locks the bounded search down against the numbers it produced before the two-stage global
 * sweep, the temporal local hint and the compass refinement existed, and against the exact
 * accumulation that the previous per-input finiteness test implemented.
 */
class PlanetarySearchRegressionTest {
    private val width = 512
    private val height = 512
    private val roi = PixelRoi(0, 0, width, height)
    private val options = PlanetaryOptions(roi = roi, localAlignment = true,
        maxAlignmentPoints = 96, maxGlobalShift = 32, maxLocalShift = 5,
        minimumGlobalCorrelation = .7, minimumLocalCorrelation = .65, maximumEdgeResidual = .24,
        memoryBudgetBytes = 2L shl 30)
    private val shiftX = listOf(0.0, 0.55, 1.20, 1.85, 2.45, 3.10)
    private val shiftY = listOf(0.0, -0.45, -0.95, -1.40, -1.90, -2.35)
    private val base = FloatArray(width * height * 3)

    private fun localX(x: Double, y: Double, k: Int) =
        .45 * sin(2 * PI * (x / width + k * .11)) + .22 * cos(2 * PI * (y / height - k * .07))

    private fun localY(x: Double, y: Double, k: Int) =
        .40 * cos(2 * PI * (x / width - k * .09)) - .20 * sin(2 * PI * (y / height + k * .13))

    private fun buildBase() {
        val random = Random(20261020L)
        for (i in 0 until width * height) base[i * 3] = random.nextDouble().toFloat()
        val row = FloatArray(width * height)
        repeat(3) {
            for (y in 0 until height) for (x in 0 until width) {
                var sum = 0f; var n = 0
                for (dy in -3..3) for (dx in -3..3) {
                    val xx = x + dx; val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= width || yy >= height) continue
                    sum += base[(yy * width + xx) * 3]; n++
                }
                row[y * width + x] = sum / n
            }
            for (i in 0 until width * height) base[i * 3] = row[i]
        }
        for (y in 0 until height) for (x in 0 until width) {
            val dx = (x - width / 2.0) / (width * .34); val dy = (y - height / 2.0) / (height * .34)
            val limb = .5 * (1 - tanh((hypot(dx, dy) - 1) * 40))
            val detail = 1 + .22 * sin(x * .21) * cos(y * .17) + .14 * sin((x + y) * .09)
            val v = .012 + limb * (.34 * detail) + .006 * base[(y * width + x) * 3]
            val p = y * width + x
            base[p * 3] = v.toFloat()
            base[p * 3 + 1] = (v * .9).toFloat()
            base[p * 3 + 2] = (v * .82).toFloat()
        }
    }

    private fun sample(channel: Int, fx: Double, fy: Double): Float {
        val ix = floor(fx).toInt(); val iy = floor(fy).toInt()
        val nx = min(width - 1, ix + 1); val ny = min(height - 1, iy + 1)
        val ax = fx - ix; val ay = fy - iy
        return ((base[(iy * width + ix) * 3 + channel] * (1 - ax) + base[(iy * width + nx) * 3 + channel] * ax) * (1 - ay) +
            (base[(ny * width + ix) * 3 + channel] * (1 - ax) + base[(ny * width + nx) * 3 + channel] * ax) * ay).toFloat()
    }

    /** Output(x,y) samples the source at (x,y) minus the displacement, matching the inverse map. */
    private fun frame(k: Int): LinearImage {
        val pixels = FloatArray(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val p = y * width + x
            val sx = x - (shiftX[k] + localX(x.toDouble(), y.toDouble(), k))
            val sy = y - (shiftY[k] + localY(x.toDouble(), y.toDouble(), k))
            if (sx < 0 || sy < 0 || sx > width - 1 || sy > height - 1) continue
            for (channel in 0 until 3) pixels[p * 3 + channel] = sample(channel, sx, sy)
        }
        return LinearImage(width, height, 3, pixels)
    }

    private fun registration() = PlanetaryRegistration(frame(1), roi, options)

    /** Values captured from the single-sweep, lattice-refined, hint-free implementation. */
    @Test fun globalAlignmentMatchesTheRecordedFixture() {
        buildBase()
        val registration = registration()
        assertEquals(52, registration.points.size)
        val expected = listOf(
            Triple(-0.500000, 0.375000, 0.999925126),
            Triple(0.000000, 0.000000, 1.000000000),
            Triple(0.625000, -0.375000, 0.999939252),
            Triple(1.375000, -0.750000, 0.999797143),
            Triple(2.000000, -1.125000, 0.999635952),
            Triple(2.750000, -1.500000, 0.999532468))
        for (k in 0 until shiftX.size) {
            val alignment = registration.align(frame(k))
            assertEquals("frame $k mode", PlanetaryRegistrationMode.MULTIPOINT, alignment.mode)
            assertEquals("frame $k local count", 52, alignment.local.size)
            val (dx, dy, correlation) = expected[k]
            assertEquals("frame $k dx", dx, alignment.global.dx, 1e-9)
            assertEquals("frame $k dy", dy, alignment.global.dy, 1e-9)
            assertEquals("frame $k correlation", correlation, alignment.global.correlation, 1e-6)
        }
    }

    @Test fun aLocalHintNeverChangesTheResultAndAStaleOneFallsBack() {
        buildBase()
        val registration = registration()
        val images = shiftX.indices.map { frame(it) }
        val plain = images.map { registration.align(it) }
        val hint = LocalShiftHint()
        val hinted = images.map {
            val alignment = registration.align(registration.prepareFrame(it), hint)
            hint.record(registration.points, alignment)
            alignment
        }
        for (k in images.indices) {
            assertEquals("frame $k mode", plain[k].mode, hinted[k].mode)
            assertEquals("frame $k local count", plain[k].local.size, hinted[k].local.size)
            for (i in plain[k].local.indices) {
                assertEquals("frame $k point $i dx", plain[k].local[i].shift.dx, hinted[k].local[i].shift.dx, .125)
                assertEquals("frame $k point $i dy", plain[k].local[i].shift.dy, hinted[k].local[i].shift.dy, .125)
            }
        }
        // A hint displaced far outside every patch's window must be rejected, not trusted.
        val stale = LocalShiftHint()
        val point = registration.points.first()
        stale.record(registration.points, PlanetaryAlignment(PlanetaryShift(17.0, -13.0, 1.0),
            listOf(PlanetaryLocalShift(point, PlanetaryShift(17.0, -13.0, 1.0))),
            registration.points.size, PlanetaryRegistrationMode.MULTIPOINT))
        for (k in images.indices) {
            val alignment = registration.align(registration.prepareFrame(images[k]), stale)
            assertEquals("stale frame $k mode", plain[k].mode, alignment.mode)
            assertEquals("stale frame $k dx", plain[k].global.dx, alignment.global.dx, 1e-9)
            assertEquals("stale frame $k dy", plain[k].global.dy, alignment.global.dy, 1e-9)
            assertEquals("stale frame $k local count", plain[k].local.size, alignment.local.size)
        }
        stale.clear()
        assertFalse(stale.has(0))
    }

    @Test fun meshOffsetTableReproducesDirectBilinearLookup() {
        buildBase()
        val registration = registration()
        val warp = PlanetaryWarp(width, height, registration.align(frame(3)))
        val values = FloatArray(warp.dx.size) { base[it % base.size] }
        var mismatches = 0
        for (y in 0 until height) for (x in 0 until width)
            if (PlanetarySampling.bilinear(values, warp.columns, warp.rows,
                    x.toDouble() / warp.step, y.toDouble() / warp.step) != warp.offset(values, x, y)) mismatches++
        assertEquals("the mesh offset table must be bit-identical to the direct lookup", 0, mismatches)
    }

    /** The accumulation the per-input finiteness test implemented, kept here as the oracle. */
    private fun referenceAccumulate(image: LinearImage, sums: DoubleArray, weights: FloatArray, warp: PlanetaryWarp) {
        val w = image.width; val h = image.height; val channels = image.channels
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x + warp.offset(warp.dx, x, y); val sy = y + warp.offset(warp.dy, x, y)
            if (sx < 0 || sy < 0 || sx > w - 1 || sy > h - 1) continue
            val ix = sx.toInt(); val iy = sy.toInt()
            val nx = min(w - 1, ix + 1); val ny = min(h - 1, iy + 1)
            val fx = sx - ix; val fy = sy - iy
            val a = (iy * w + ix) * channels; val b = (iy * w + nx) * channels
            val c = (ny * w + ix) * channels; val d = (ny * w + nx) * channels
            var finite = true
            for (channel in 0 until channels) {
                if (!image.pixels[a + channel].isFinite() || !image.pixels[b + channel].isFinite() ||
                    !image.pixels[c + channel].isFinite() || !image.pixels[d + channel].isFinite()) { finite = false; break }
            }
            if (!finite) continue
            val p = y * w + x
            for (channel in 0 until channels)
                sums[p * channels + channel] += (image.pixels[a + channel] * (1 - fx) + image.pixels[b + channel] * fx) * (1 - fy) +
                    (image.pixels[c + channel] * (1 - fx) + image.pixels[d + channel] * fx) * fy
            weights[p]++
        }
    }

    @Test fun accumulationIsBitIdenticalIncludingNonFiniteCoverage() {
        buildBase()
        val registration = registration()
        for (channels in listOf(1, 3)) {
            val alignment = if (channels == 3) registration.align(frame(3))
                else PlanetaryAlignment(PlanetaryShift(1.375, -.75, .99), emptyList(), 0, PlanetaryRegistrationMode.GLOBAL)
            val warp = PlanetaryWarp(width, height, alignment)
            val rgb = frame(3)
            val image = if (channels == 3) rgb else LinearImage(width, height, 1, FloatArray(width * height) { rgb.pixels[it * 3] })
            for (poisoned in listOf(false, true)) {
                val subject = if (poisoned) {
                    val copy = image.deepCopy()
                    val p = (height / 2) * width + width / 2
                    copy.pixels[p * channels + channels - 1] = Float.NaN
                    copy
                } else image
                val expectedSums = DoubleArray(width * height * channels)
                val expectedWeights = FloatArray(width * height)
                referenceAccumulate(subject, expectedSums, expectedWeights, warp)
                val sums = DoubleArray(width * height * channels)
                val weights = FloatArray(width * height)
                PlanetaryStacker().accumulate(subject, sums, weights, warp) {}
                assertArrayEquals("channels=$channels poisoned=$poisoned sums", expectedSums, sums, 0.0)
                assertArrayEquals("channels=$channels poisoned=$poisoned weights", expectedWeights, weights, 0f)
            }
        }
    }
}
