package com.indigo.mobileobservatory.guide

/** PHD2 70e9fd1 guider_multistar.cpp MassChecker. BSD notices: assets/THIRD_PARTY_PHD2.txt.
 * A primitive ring and reusable selection buffer avoid per-frame history objects and sorting.
 * The caller supplies monotonic milliseconds; the upstream clock is replaced for Android. */
internal class Phd2MassChecker {
    private var times = LongArray(64)
    private var masses = DoubleArray(64)
    private var head = 0
    private var count = 0
    private var scratch = DoubleArray(0)
    private var high = 0.0
    private var low = 9e99
    private var exposure = 0
    private var automatic = false

    fun reset() { head = 0; count = 0; high = 0.0; low = 9e99 }

    fun setExposure(milliseconds: Int, autoExposure: Boolean = false) {
        require(milliseconds > 0)
        if (autoExposure != automatic) {
            automatic = autoExposure; exposure = milliseconds; reset()
        } else if (milliseconds != exposure) {
            exposure = milliseconds
            if (!automatic) reset()
        }
    }

    fun append(mass: Double, nowMs: Long) {
        val oldest = nowMs - 45_000L
        while (count > 0 && times[head] < oldest) {
            head = (head + 1) % times.size
            count--
        }
        if (count == times.size) {
            val nextTimes = LongArray(times.size * 2)
            val nextMasses = DoubleArray(masses.size * 2)
            for (i in 0 until count) {
                val index = (head + i) % times.size
                nextTimes[i] = times[index]; nextMasses[i] = masses[index]
            }
            times = nextTimes; masses = nextMasses; head = 0
        }
        val index = (head + count) % times.size
        times[index] = nowMs
        masses[index] = adjusted(mass)
        count++
    }

    fun checkMass(mass: Double, threshold: Double, limits: DoubleArray): Boolean {
        if (count < 5) return false
        if (scratch.size < count) scratch = DoubleArray(count + 10)
        for (i in 0 until count) scratch[i] = masses[(head + i) % masses.size]
        val median = selectMedian(count)
        if (median > high) high = median
        if (median < low) low = median
        low += .05 * (median - low)
        limits[0] = low * (1 - threshold)
        limits[1] = median
        limits[2] = high * (1 + threshold)
        limits[3] = median * (1 + 2 * threshold)
        val value = adjusted(mass)
        val reject = value < limits[0] || value > limits[2] || value > limits[3]
        if (reject && automatic) for (i in 0..3) limits[i] *= exposure.toDouble()
        return reject
    }

    private fun adjusted(mass: Double) = if (automatic) mass / exposure else mass

    private fun selectMedian(size: Int): Double {
        val middle = size / 2 // PHD2 uses the upper median for an even number of samples.
        var left = 0; var right = size - 1
        while (left < right) {
            val pivot = scratch[(left + right) ushr 1]
            var i = left; var j = right
            while (i <= j) {
                while (scratch[i] < pivot) i++
                while (scratch[j] > pivot) j--
                if (i <= j) {
                    val value = scratch[i]; scratch[i] = scratch[j]; scratch[j] = value
                    i++; j--
                }
            }
            if (middle <= j) right = j else if (middle >= i) left = i else break
        }
        return scratch[middle]
    }
}
