package com.indigo.mobileobservatory.guide

import kotlin.math.abs
import kotlin.math.sqrt

/** Numerical port of PHD2 70e9fd1 Star::Find(FIND_CENTROID) and hfr.
 * Craig Stark (2006–2010), Bret McKee (2012), BSD; see assets/THIRD_PARTY_PHD2.txt. */
internal object Phd2StarMeasurement {
    enum class Result { OK, SATURATED, LOW_SNR, LOW_MASS, LOW_HFD, HIGH_HFD, TOO_NEAR_EDGE, MASS_CHANGE, ERROR }
    data class Measurement(val x: Double, val y: Double, val mass: Double, val snr: Double,
        val hfd: Double, val peak: Int, val radius: Double, val result: Result) {
        val found get() = result == Result.OK || result == Result.SATURATED
    }
    private data class Flux(val x: Int, val y: Int, val mass: Double, var r2: Double = 0.0)

    fun find(image: GuideImage, baseX: Int, baseY: Int, search: Int = 3, maxADU: Int = 0,
        minHfd: Double = 1.5, maxHfd: Double = 20.0): Measurement {
        val sx = maxOf(baseX - search, 0); val ex = minOf(baseX + search, image.width - 1)
        val sy = maxOf(baseY - search, 0); val ey = minOf(baseY + search, image.height - 1)
        if (ex <= sx || ey <= sy) return Measurement(baseX.toDouble(), baseY.toDouble(), 0.0, 0.0, 0.0, 0, 0.0, Result.ERROR)
        var peakX = 0; var peakY = 0; var smoothPeak = 0
        val top = IntArray(3)
        for (y in sy + 1 until ey) for (x in sx + 1 until ex) {
            var p = image.value(x, y)
            val smooth = 4 * p + image.value(x - 1, y - 1) + image.value(x + 1, y - 1) +
                image.value(x - 1, y + 1) + image.value(x + 1, y + 1) +
                2 * (image.value(x, y - 1) + image.value(x - 1, y) + image.value(x + 1, y) + image.value(x, y + 1))
            if (smooth > smoothPeak) { smoothPeak = smooth; peakX = x; peakY = y }
            for (i in top.indices) if (p > top[i]) { val old = top[i]; top[i] = p; p = old }
        }
        smoothPeak /= 16
        var background = 0.0; var variance = 0.0; var sigma = 0.0; var backgroundCount = 0
        for (iteration in 0 until 9) {
            var sum = 0.0; var mean = 0.0; var q = 0.0
            backgroundCount = 0
            for (y in maxOf(peakY - 12, 0)..minOf(peakY + 12, image.height - 1)) {
                val dy = y - peakY
                for (x in maxOf(peakX - 12, 0)..minOf(peakX + 12, image.width - 1)) {
                    val dx = x - peakX; val r2 = dx * dx + dy * dy
                    if (r2 <= 49 || r2 > 144) continue
                    val value = image.value(x, y).toDouble()
                    if (iteration > 0 && (value < background - 2 * sigma || value > background + 2 * sigma)) continue
                    sum += value; backgroundCount++
                    val oldMean = mean
                    mean += (value - mean) / backgroundCount
                    q += (value - oldMean) * (value - mean)
                }
            }
            if (backgroundCount < 10) break
            val previous = background
            background = sum / backgroundCount
            variance = q / (backgroundCount - 1)
            sigma = sqrt(variance)
            if (iteration > 0 && abs(background - previous) < .5) break
        }
        val threshold = (background + 3 * sigma + .5).toInt() and 65535
        var cx = 0.0; var cy = 0.0; var mass = 0.0
        val flux = ArrayList<Flux>(149)
        for (y in maxOf(peakY - 7, 0)..minOf(peakY + 7, image.height - 1)) {
            val dy = y - peakY
            for (x in maxOf(peakX - 7, 0)..minOf(peakX + 7, image.width - 1)) {
                val dx = x - peakX
                if (dx * dx + dy * dy > 49) continue
                val value = image.value(x, y)
                if (value < threshold) continue
                val weight = value - background
                cx += dx * weight; cy += dy * weight; mass += weight
                flux += Flux(x, y, weight)
            }
        }
        var snr = if (flux.isNotEmpty()) mass / sqrt(mass / .5 + variance * flux.size * (1.0 + 1.0 / backgroundCount)) else 0.0
        if (smoothPeak <= threshold && snr >= 3.0) snr = 2.9
        if (mass < 10) return Measurement(baseX.toDouble(), baseY.toDouble(), mass, snr, 0.0, top[0], 0.0, Result.LOW_MASS)
        if (snr < 3) return Measurement(baseX.toDouble(), baseY.toDouble(), mass, snr, 0.0, top[0], 0.0, Result.LOW_SNR)
        val x = peakX + cx / mass; val y = peakY + cy / mass
        var moment = 0.0
        for (sample in flux) {
            sample.r2 = (sample.x - x) * (sample.x - x) + (sample.y - y) * (sample.y - y)
            moment += sample.mass * sample.r2
        }
        val hfd = if (flux.size == 1) .5 else {
            flux.sortBy { it.r2 }
            var previousRadius2 = 0.0; var radius2 = 0.0; var previousMass = 0.0; var sum = 0.0
            for (sample in flux) {
                previousRadius2 = radius2; previousMass = sum
                radius2 = sample.r2; sum += sample.mass
                if (sum > .5 * mass) break
            }
            2 * if (sum > previousMass) sqrt(previousRadius2) +
                (sqrt(radius2) - sqrt(previousRadius2)) / (sum - previousMass) * (.5 * mass - previousMass) else .25
        }
        val result = when {
            hfd < minHfd -> Result.LOW_HFD
            hfd > maxHfd -> Result.HIGH_HFD
            maxADU > 0 && top[0] >= maxADU -> Result.SATURATED
            maxADU <= 0 && (if (image.bits < 12) (top[0] - top[2]).toLong() * 191 < top[0] else
                (top[0] - top[2]).toLong() * 65535 < 32L * top[0]) -> Result.SATURATED
            else -> Result.OK
        }
        return Measurement(x, y, mass, snr, hfd, top[0], sqrt((moment / mass).coerceAtLeast(0.0)), result)
    }
}
