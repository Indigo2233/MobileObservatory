package com.indigo.mobileobservatory.stacking

import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.*

/** Diagnostic storage model. FloatArray holds exact binary16 round trips, not packed data.
 * Arithmetic and the production integer-valued Float counts retain their original precision. */
enum class ProbePrecision(val halfInput: Boolean, val halfMean: Boolean, val halfM2: Boolean, val centered: Boolean = false) {
    FP32(false, false, false),
    INPUT16(true, false, false),
    MEAN16(false, true, false),
    M2_16(false, false, true),
    INPUT_M2_16(true, false, true),
    ALL16(true, true, true),
    CENTERED_INPUT_MEAN16(true, true, false, true),
    CENTERED16(true, true, true, true);

    companion object {
        fun half(value: Float): Float = java.lang.Float.float16ToFloat(java.lang.Float.floatToFloat16(value))
    }
}

/** Experimental alternative: freeze per-channel medians from the first input as origins.
 * Scale centered signals by 256 and M2 by 65536, both exact powers of two.
 * Range checks fail on overflow. These experiments do not establish universal safe ranges. */
class ProbeEncoding(val mode: ProbePrecision, private val origins: FloatArray = FloatArray(3)) {
    fun storeMean(value: Float, channel: Int): Float {
        if (!mode.halfMean) return value
        return signal(value, channel)
    }
    fun storeM2(value: Float): Float {
        if (!mode.halfM2) return value
        val stored = if (mode.centered) ProbePrecision.half(value * 65536f) / 65536f else ProbePrecision.half(value)
        check(stored.isFinite()) { "M2 binary16 overflow in ${mode.name}" }
        return stored
    }
    private fun signal(value: Float, channel: Int): Float {
        val stored = if (mode.centered) ProbePrecision.half((value - origins[channel]) * 256f) / 256f + origins[channel]
            else ProbePrecision.half(value)
        check(stored.isFinite()) { "Signal binary16 overflow in ${mode.name}" }
        return stored
    }
    fun input(image: LinearImage): LinearImage = if (!mode.halfInput) image else
        LinearImage(image.width, image.height, image.channels, FloatArray(image.pixels.size) {
            signal(image.pixels[it], it % image.channels)
        })
}

/** Uses the real detector, registration, inverse warp, Welford statistics and rejection.
 * The generated engine has only its mean/M2 stores replaced. Never included in the APK. */
object Fp16PrecisionProbe {
    private const val W = 128
    private const val H = 128
    private const val C = 3
    private const val SCALE = 65535.0
    private val checkpoints = setOf(16, 64, 256, 1024, 4096, 8192, 16384)
    private val colors = doubleArrayOf(1.08, 1.0, .92)
    private data class Star(val x: Double, val y: Double, val peak: Double, val faint: Boolean = false)
    private data class Scenario(val name: String, val background: Double, val noise: Double, val drift: Boolean,
                                val calibrated: Boolean = false, val adcStep: Double = 1.0,
                                val gradient: Double = 0.0, val skyDrift: Double = 0.0)
    private val scenarios = listOf(
        Scenario("dark", 1000.25, 8.0, true),
        Scenario("bright", 10000.25, 32.0, true),
        Scenario("low_noise", 128.25, 2.0, false),
        Scenario("calibrated", -2.25, 4.0, true, true),
        Scenario("midtones", 32768.25, 16.0, false),
        Scenario("raw12", 10000.0, 32.0, true, adcStep = 16.0),
        Scenario("gradient", 1000.25, 8.0, true, gradient = 200.0),
        Scenario("drifting_sky", 1000.25, 8.0, true, skyDrift = .02)
    )
    private val anchors = listOf(
        Star(16.2, 17.6, 4000.0), Star(42.7, 15.3, 6000.0), Star(77.6, 19.8, 3000.0),
        Star(109.2, 17.1, 8000.0), Star(16.8, 44.5, 7000.0), Star(109.3, 45.4, 4500.0),
        Star(17.1, 80.8, 6500.0), Star(109.5, 80.4, 3500.0), Star(18.7, 110.2, 9000.0),
        Star(48.6, 108.7, 5000.0), Star(78.4, 109.3, 7500.0), Star(110.4, 108.9, 5500.0)
    )
    private fun stars(s: Scenario): List<Star> = anchors + listOf(
        Star(43.2, 43.6, s.noise * .25, true), Star(76.3, 43.5, s.noise * .5, true),
        Star(61.2, 65.3, s.noise, true), Star(43.4, 86.2, s.noise * 2, true),
        Star(80.5, 84.1, s.noise * 4, true)
    )

    private fun background(s: Scenario, x: Double, y: Double, channel: Int, frame: Double): Double {
        if (s.gradient == 0.0 && s.skyDrift == 0.0) return s.background * colors[channel]
        return (s.background + s.gradient * (2 * x / (W - 1) - 1) +
            .5 * s.gradient * (2 * y / (H - 1) - 1) + s.skyDrift * frame) * colors[channel]
    }

    private fun frame(s: Scenario, index: Int): LinearImage {
        val rng = Random(585000L + index)
        val angle = if (s.drift) Math.toRadians(.08 * sin(index * .17)) else 0.0
        val dx = if (s.drift) .27 * sin(index * .13) else 0.0
        val dy = if (s.drift) .23 * sin(index * .11) else 0.0
        val data = DoubleArray(W * H * C) { i ->
            val x = (i / C) % W; val y = (i / C) / W
            val worldX = cos(angle) * x - sin(angle) * y + dx
            val worldY = sin(angle) * x + cos(angle) * y + dy
            background(s, worldX, worldY, i % C, index.toDouble()) + rng.nextGaussian() * s.noise
        }
        for (star in stars(s)) {
            val sx = cos(angle) * (star.x - dx) + sin(angle) * (star.y - dy)
            val sy = -sin(angle) * (star.x - dx) + cos(angle) * (star.y - dy)
            for (y in max(0, floor(sy).toInt() - 7)..min(H - 1, floor(sy).toInt() + 7))
                for (x in max(0, floor(sx).toInt() - 7)..min(W - 1, floor(sx).toInt() + 7)) {
                    val signal = star.peak * exp(-((x - sx).pow(2) + (y - sy).pow(2)) / (2 * 1.3 * 1.3))
                    for (channel in 0 until C) data[(y * W + x) * C + channel] += signal * colors[channel]
                }
        }
        // Known late transients, away from bright registration stars.
        if (index >= 64 && index % 37 == 0) repeat(8) { event ->
            val p = (28 + event * 9) * W + 62
            for (channel in 0 until C) data[p * C + channel] += 1000.0
        }
        return LinearImage(W, H, C, FloatArray(data.size) { i ->
            // Calibrated scenario deliberately retains fractional, negative samples.
            val value = if (s.calibrated) data[i] else (round(data[i] / s.adcStep) * s.adcStep).coerceIn(0.0, SCALE)
            (value / SCALE).toFloat()
        })
    }

    private fun control(directory: File) {
        val cfg = DeepSkyConfig(memoryBudgetBytes = 512L shl 20)
        val original = DeepSkyStacker(cfg, livePreviewMaxSide = 768)
        val instrumented = PrecisionProbeStacker(cfg, livePreviewMaxSide = 768)
        for (n in 0 until 24) {
            val input = frame(scenarios.first(), n)
            val a = original.add(input, 5.0); val b = instrumented.add(input, 5.0)
            check(a.accepted == b.accepted && a.reason == b.reason)
            check(a.stats.rejectedPixelSamples == b.stats.rejectedPixelSamples)
            check(a.stats.registrationRmsPx == b.stats.registrationRmsPx)
            val left = original.snapshot(); val right = instrumented.snapshot()
            check(left != null && right != null) { "Control scene did not establish a reference." }
            check(left.image.pixels.contentEquals(right.image.pixels) && left.weights.contentEquals(right.weights))
        }
        check(ProbePrecision.half(1f + 1f / 2048) == 1f) // ties to even
        check(ProbePrecision.half(65504f) == 65504f)
        check(ProbePrecision.half(2048f + 1f) == 2048f)
        check(ProbePrecision.half(1f / 33554432) == 0f) // half the least positive subnormal
        File(directory, "control.json").writeText("""{"productionParityFrames":24,"pixelsBitIdentical":true,"weightsBitIdentical":true,"roundToNearestEven":true,"smallUpdatesAndCounterLimitsVerified":true}""")
        println("CONTROL production/instrumented FP32: 24 frames bit-identical; binary16 edge checks passed")
    }

    private data class Metrics(val backgroundStd: Double, val backgroundBias: Double, val rmsVsFp32: Double,
                               val maxVsFp32: Double, val medianFluxRelative: Double, val rmsFluxDifference: Double,
                               val weightDisagreement: Double)

    private val momentField = PrecisionProbeStacker::class.java.getDeclaredField("m2").apply { isAccessible = true }
    private fun momentRatio(s: Scenario, engine: PrecisionProbeStacker, baseline: PrecisionProbeStacker): Pair<Double, Double> {
        val candidate = momentField.get(engine) as FloatArray
        val reference = momentField.get(baseline) as FloatArray
        val points = stars(s)
        val ratios = ArrayList<Double>(); var zero = 0
        for (y in 10 until H - 10 step 2) for (x in 10 until W - 10 step 2) {
            if (points.any { hypot(it.x - x, it.y - y) < 9 }) continue
            val i = (y * W + x) * C + 1
            if (reference[i] > 0f) {
                ratios += candidate[i].toDouble() / reference[i]
                if (candidate[i] <= 0f) zero++
            }
        }
        ratios.sort()
        return ratios[ratios.size / 2] to zero.toDouble() / ratios.size
    }

    private fun amplitude(image: LinearImage, star: Star): Double {
        var n = 0; var sumG = 0.0; var sumGG = 0.0; var sumV = 0.0; var sumGV = 0.0
        for (y in floor(star.y).toInt() - 5..floor(star.y).toInt() + 5)
            for (x in floor(star.x).toInt() - 5..floor(star.x).toInt() + 5) {
                val g = exp(-((x - star.x).pow(2) + (y - star.y).pow(2)) / (2 * 1.3 * 1.3))
                val value = image.pixels[(y * W + x) * C + 1] * SCALE
                n++; sumG += g; sumGG += g * g; sumV += value; sumGV += g * value
            }
        return (sumGV - sumG * sumV / n) / (sumGG - sumG * sumG / n)
    }

    private fun metrics(s: Scenario, snapshot: DeepSkySnapshot, reference: DeepSkySnapshot): Metrics {
        val points = stars(s)
        var n = 0L; var sum = 0.0; var sumSquare = 0.0; var errorSquare = 0.0; var maxError = 0.0
        var compared = 0L; var differentWeights = 0L
        for (y in 10 until H - 10) for (x in 10 until W - 10) {
            val p = y * W + x
            if (snapshot.weights[p] < snapshot.stats.accepted * .95 || reference.weights[p] < reference.stats.accepted * .95) continue
            compared++
            if (snapshot.weights[p] != reference.weights[p]) differentWeights++
            for (ch in 0 until C) {
                val d = (snapshot.image.pixels[p * C + ch].toDouble() - reference.image.pixels[p * C + ch]) * SCALE
                errorSquare += d * d; maxError = max(maxError, abs(d))
            }
            if (points.any { hypot(it.x - x, it.y - y) < 9 }) continue
            // Subtract the known scene background, not an estimated fit that could hide errors.
            val expected = background(s, x.toDouble(), y.toDouble(), 1, (snapshot.stats.received - 1) / 2.0)
            val residual = snapshot.image.pixels[p * C + 1] * SCALE - expected
            n++; sum += residual; sumSquare += residual * residual
        }
        check(n > 100 && compared > 100) { "Insufficient covered diagnostic pixels." }
        val faint = points.filter { it.faint }
        val differences = faint.map { amplitude(snapshot.image, it) - amplitude(reference.image, it) }
        // Relative photometry uses the three brighter probes; lower-SNR probes are still saved below.
        val relative = faint.takeLast(3).map {
            val expected = amplitude(reference.image, it)
            if (abs(expected) > .1) abs(amplitude(snapshot.image, it) - expected) / abs(expected) else 0.0
        }.sorted()[1]
        return Metrics(sqrt(max(0.0, sumSquare / n - (sum / n).pow(2))), sum / n,
            sqrt(errorSquare / (compared * C)), maxError, relative,
            sqrt(differences.sumOf { it * it } / differences.size), differentWeights.toDouble() / compared)
    }

    @JvmStatic fun main(args: Array<String>) {
        Locale.setDefault(Locale.ROOT)
        val directory = File(args[0]).apply { mkdirs() }
        val frames = args.getOrNull(1)?.toInt() ?: 4096
        val selected = args.getOrNull(2)?.split(",")?.toSet() ?: setOf("dark")
        control(directory)
        val statsFile = File(directory, "precision.csv")
        val fluxFile = File(directory, "faint-stars.csv")
        statsFile.bufferedWriter().use { writer ->
            fluxFile.bufferedWriter().use { flux ->
                writer.appendLine("scenario,frames,mode,accepted,rejected_frames,rejected_pixel_samples,background_std_adu,background_bias_adu,noise_ratio_to_fp32,rms_difference_adu,max_difference_adu,median_faint_flux_relative_error,rms_faint_amplitude_difference_adu,weight_disagreement_fraction,registration_rms_px,median_background_m2_ratio,m2_nonpositive_fraction")
                flux.appendLine("scenario,frames,mode,x,y,input_peak_adu,recovered_amplitude_adu,fp32_amplitude_adu")
                for (s in scenarios.filter { it.name in selected }) {
                    val first = frame(s, 0)
                    val origins = FloatArray(C) { channel ->
                        val values = FloatArray(W * H) { first.pixels[it * C + channel] }
                        values.sort()
                        values[values.size / 2]
                    }
                    val encodings = ProbePrecision.entries.associateWith { ProbeEncoding(it, origins) }
                    val engines = encodings.mapValues { (_, encoding) ->
                        PrecisionProbeStacker(DeepSkyConfig(memoryBudgetBytes = 512L shl 20), livePreviewMaxSide = 768, precision = encoding)
                    }
                    val started = System.nanoTime()
                    for (index in 0 until frames) {
                        val input = frame(s, index)
                        val halfInput = encodings.getValue(ProbePrecision.INPUT16).input(input)
                        val centeredInput = encodings.getValue(ProbePrecision.CENTERED16).input(input)
                        for ((mode, engine) in engines) engine.add(when {
                            mode.centered -> centeredInput
                            mode.halfInput -> halfInput
                            else -> input
                        }, 5.0)
                        val count = index + 1
                        if (count !in checkpoints && count != frames) continue
                        val baseline = checkNotNull(engines.getValue(ProbePrecision.FP32).snapshot())
                        check(baseline.stats.accepted >= count * .95) { "Baseline rejected too many frames in ${s.name}." }
                        val baselineMetrics = metrics(s, baseline, baseline)
                        for ((mode, engine) in engines) {
                            val snapshot = checkNotNull(engine.snapshot()) { "No result for ${s.name}/$mode" }
                            val m = metrics(s, snapshot, baseline)
                            val moments = momentRatio(s, engine, engines.getValue(ProbePrecision.FP32))
                            writer.appendLine(listOf(s.name, count, mode.name, snapshot.stats.accepted, snapshot.stats.rejected,
                                snapshot.stats.rejectedPixelSamples, m.backgroundStd, m.backgroundBias,
                                m.backgroundStd / baselineMetrics.backgroundStd, m.rmsVsFp32, m.maxVsFp32,
                                m.medianFluxRelative, m.rmsFluxDifference, m.weightDisagreement,
                                snapshot.stats.registrationRmsPx, moments.first, moments.second).joinToString(","))
                            for (star in stars(s).filter { it.faint })
                                flux.appendLine(listOf(s.name, count, mode.name, star.x, star.y, star.peak,
                                    amplitude(snapshot.image, star), amplitude(baseline.image, star)).joinToString(","))
                        }
                        writer.flush(); flux.flush()
                        val all = checkNotNull(engines.getValue(ProbePrecision.ALL16).snapshot())
                        val m = metrics(s, all, baseline)
                        println("CASE ${s.name} frames=$count accepted=${all.stats.accepted} FP32-noise=${"%.5f".format(baselineMetrics.backgroundStd)} ALL16-noise=${"%.5f".format(m.backgroundStd)} ratio=${"%.3f".format(m.backgroundStd / baselineMetrics.backgroundStd)} faint-flux-difference=${"%.2f".format(m.medianFluxRelative * 100)}% elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                    }
                }
            }
        }
        println("Wrote numerical results to ${directory.absolutePath}; no Android/heap/performance claim.")
    }
}
