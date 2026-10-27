package com.indigo.mobileobservatory.guide

import kotlin.math.abs
import kotlin.math.sign

/** Behavioral ports of PHD2 70e9fd1 guide_algorithm_{hysteresis,lowpass,lowpass2,resistswitch}.
 * Copyright Bret McKee (2012), Craig Stark (2006–2010), Open PHD Guiding contributors.
 * BSD notices are distributed in assets/THIRD_PARTY_PHD2.txt. All values here are guide pixels. */
data class GuideFilterParameters(
    val hysteresis: Double = .1,
    val slopeWeight: Double = 5.0,
    val fastSwitch: Boolean = true,
    val predictionGain: Double = .5,
    val periodSeconds: Double = 200.0,
    val estimatePeriod: Boolean = true,
    val exposureFactor: Double = 2.0
)

internal class Phd2GuideFilter(private val ra: Boolean) {
    private var selected: GuideAlgorithm? = null
    private var lastMove = 0.0
    private var currentSide = 0
    private var rejects = 0
    private val history = ArrayList<Double>(11)
    private var predictive: Phd2Predictive? = null
    private var zFactor = Double.NaN
    private var coefficients = doubleArrayOf()
    private var zX = doubleArrayOf()
    private var zY = doubleArrayOf()
    private var sumCorrections = 0.0

    fun reset() {
        selected = null; lastMove = 0.0; currentSide = 0; rejects = 0; history.clear()
        predictive?.close(); predictive = null
        zFactor = Double.NaN; coefficients = doubleArrayOf(); zX = doubleArrayOf(); zY = doubleArrayOf(); sumCorrections = 0.0
    }

    fun result(input: Double, algorithm: GuideAlgorithm, aggression: Double, minMove: Double,
        parameters: GuideFilterParameters, snr: Double, exposureSeconds: Double, timestampSeconds: Double): Double {
        val effective = if (!ra && algorithm == GuideAlgorithm.PREDICTIVE_RA) GuideAlgorithm.RESIST_SWITCH else algorithm
        if (effective != selected) {
            reset(); selected = effective
            if (effective == GuideAlgorithm.LOW_PASS || effective == GuideAlgorithm.RESIST_SWITCH) repeat(10) { history += 0.0 }
        }
        return when (effective) {
            GuideAlgorithm.IDENTITY -> input
            GuideAlgorithm.HYSTERESIS -> {
                val output = if (abs(input) < minMove) 0.0 else
                    ((1.0 - parameters.hysteresis) * input + parameters.hysteresis * lastMove) * aggression
                lastMove = output
                output
            }
            GuideAlgorithm.LOW_PASS -> {
                history += input
                val median = history.sorted()[5]
                history.removeAt(0)
                var output = median + parameters.slopeWeight * slope(history)
                if (abs(output) > abs(input)) output = input
                if (abs(input) < minMove) 0.0 else output
            }
            GuideAlgorithm.LOW_PASS_2 -> {
                history += input
                if (history.size > 10) history.removeAt(0)
                var output = if (history.size < 4) input * aggression else if (abs(input) > 4.0 * minMove) {
                    history.clear(); rejects = 0
                    input * aggression
                } else (slope(history) * history.size * aggression).let { if (input * it < 0) 0.0 else it }
                if (abs(output) > abs(input)) {
                    output = input * aggression
                    if (++rejects > 3) { history.clear(); rejects = 0 }
                } else rejects = 0
                if (abs(input) < minMove) 0.0 else output
            }
            GuideAlgorithm.RESIST_SWITCH -> {
                history += input; history.removeAt(0)
                if (abs(input) < minMove) return 0.0
                if (parameters.fastSwitch && sign(input).toInt() != currentSide && abs(input) > 3.0 * minMove) {
                    currentSide = 0
                    for (i in history.indices) history[i] = if (i < 7) 0.0 else input
                }
                val direction = history.filter { abs(it) > minMove }.sumOf { sign(it).toInt() }
                if (currentSide == 0 || sign(currentSide.toDouble()) == -sign(direction.toDouble())) {
                    if (abs(direction) < 3 || abs(history.takeLast(3).sum()) <= abs(history.take(3).sum())) return 0.0
                    currentSide = sign(direction.toDouble()).toInt()
                }
                if (currentSide != sign(input).toInt()) 0.0 else input * aggression
            }
            GuideAlgorithm.PREDICTIVE_RA -> {
                val model = predictive ?: Phd2Predictive().also { predictive = it }
                model.result(input, snr, exposureSeconds, timestampSeconds, aggression, minMove, parameters)
            }
            GuideAlgorithm.Z_FILTER -> {
                if (zFactor != parameters.exposureFactor) {
                    NativePhd2.load()
                    coefficients = NativePhd2.zfilterCoefficients(parameters.exposureFactor)
                    val size = (coefficients.size - 1) / 2
                    zX = DoubleArray(size); zY = DoubleArray(size); sumCorrections = 0.0
                    zFactor = parameters.exposureFactor
                }
                for (i in zX.lastIndex downTo 1) { zX[i] = zX[i - 1]; zY[i] = zY[i - 1] }
                zX[0] = (input + sumCorrections) / coefficients[0]
                zY[0] = zX.indices.sumOf { zX[it] * coefficients[1 + it] } +
                    (1..zY.lastIndex).sumOf { zY[it] * coefficients[1 + zX.size + it] }
                val output = (zY[0] - sumCorrections).let { if (abs(it) < minMove) 0.0 else it }
                sumCorrections += output
                output
            }
        }
    }

    private fun slope(values: List<Double>): Double {
        val center = (values.size - 1) / 2.0
        val mean = values.average()
        val variance = values.indices.sumOf { (it - center) * (it - center) }
        return if (variance == 0.0) 0.0 else values.indices.sumOf { (it - center) * (values[it] - mean) } / variance
    }
}

/** Executes the upstream GaussianProcessGuider implementation compiled into phd2_guiding. */
internal class Phd2Predictive : AutoCloseable {
    private var handle = 0L
    private var settings: GuideFilterParameters? = null
    fun result(input: Double, snr: Double, exposureSeconds: Double, timestampSeconds: Double,
        aggression: Double, minMove: Double, parameters: GuideFilterParameters): Double {
        if (handle == 0L) { NativePhd2.load(); handle = NativePhd2.create(); check(handle != 0L) }
        if (settings != parameters) {
            NativePhd2.configure(handle, parameters.predictionGain, parameters.periodSeconds, parameters.estimatePeriod)
            settings = parameters
        }
        return NativePhd2.result(handle, input, snr, exposureSeconds, timestampSeconds, aggression, minMove)
    }
    override fun close() { if (handle != 0L) { NativePhd2.destroy(handle); handle = 0L }; settings = null }
}

internal object NativePhd2 {
    private var loaded = false
    @Synchronized fun load() { if (!loaded) { System.loadLibrary("phd2_guiding"); loaded = true } }
    external fun create(): Long
    external fun destroy(handle: Long)
    external fun configure(handle: Long, predictionGain: Double, periodSeconds: Double, estimatePeriod: Boolean)
    external fun zfilterCoefficients(exposureFactor: Double): DoubleArray
    external fun result(handle: Long, input: Double, snr: Double, exposureSeconds: Double,
        timestampSeconds: Double, aggression: Double, minMove: Double): Double
}
