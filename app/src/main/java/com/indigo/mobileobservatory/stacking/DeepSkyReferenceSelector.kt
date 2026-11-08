package com.indigo.mobileobservatory.stacking

import kotlin.math.max
import kotlin.math.min

internal data class DeepSkyReferenceSample(val frame: Long, val field: StarField)

data class DeepSkyReferenceQuality(
    val frame: Long,
    val field: StarField,
    val score: Double,
    val starCount: Int,
    val elongation: Double,
    val background: Double,
    val noise: Double,
    val relativeStarFlux: Double,
    val registrationSuccess: Double,
    val registrationRmsPx: Double,
    val comparedFrames: Int
)

/** Chooses a stable offline reference without retaining frame pixels. The evaluation is bounded
 * so long recordings still use a small star-descriptor working set. */
internal object DeepSkyReferenceSelector {
    private const val MAX_CANDIDATES = 12
    private const val MAX_COMPARISONS = 256

    fun choose(samples: List<DeepSkyReferenceSample>, config: DeepSkyConfig): DeepSkyReferenceQuality? {
        if (samples.isEmpty()) return null
        val measurable = samples.filter {
            it.field.stars.size >= config.minimumStars &&
                it.field.medianElongation <= config.maxElongation
        }
        if (measurable.isEmpty()) return null
        val fields = measurable.map { it.field }
        val medianFlux = median(fields.map(::starFlux).filter { it > 0.0 })
        val maximumStars = max(1, measurable.maxOf { it.field.stars.size })
        val maximumBackground = max(1e-9, measurable.maxOf { it.field.background })
        val maximumNoise = max(1e-9, measurable.maxOf { it.field.noise })
        val preRanked = measurable.sortedByDescending {
            fieldScore(it.field, maximumStars, maximumBackground, maximumNoise, medianFlux, config)
        }
        val comparisonStride = max(1, (measurable.size + MAX_COMPARISONS - 1) / MAX_COMPARISONS)
        val comparisons = measurable.filterIndexed { index, _ -> index % comparisonStride == 0 }
            .take(MAX_COMPARISONS)
        return preRanked.take(MAX_CANDIDATES).map { candidate ->
            val matches = comparisons.mapNotNull { other ->
                if (other.frame == candidate.frame) StarMatch(RigidTransform(), candidate.field.stars.size, 0.0, 1.0)
                else StarRegistration.match(candidate.field, other.field)
            }
            val success = matches.size.toDouble() / comparisons.size.coerceAtLeast(1)
            val rms = if (matches.isEmpty()) 1.2 else median(matches.map { it.rmsPixels })
            val rmsScore = (1.0 - rms / 1.2).coerceIn(0.0, 1.0)
            val base = fieldScore(candidate.field, maximumStars, maximumBackground, maximumNoise, medianFlux, config)
            val score = base * .80 + success * .15 + rmsScore * .05
            DeepSkyReferenceQuality(
                candidate.frame, candidate.field, score, candidate.field.stars.size,
                candidate.field.medianElongation, candidate.field.background, candidate.field.noise,
                (starFlux(candidate.field) / medianFlux.coerceAtLeast(1e-12)).coerceIn(0.0, 1.0),
                success, rms, comparisons.size
            )
        }.maxWithOrNull(compareBy<DeepSkyReferenceQuality> { it.score }.thenByDescending { -it.frame })
    }

    private fun fieldScore(field: StarField, maximumStars: Int, maximumBackground: Double,
                           maximumNoise: Double, medianFlux: Double, config: DeepSkyConfig): Double {
        val stars = field.stars.size.toDouble() / maximumStars
        val elongation = ((config.maxElongation - field.medianElongation) /
            max(1e-6, config.maxElongation - 1.0)).coerceIn(0.0, 1.0)
        val background = (1.0 - field.background / maximumBackground).coerceIn(0.0, 1.0)
        val noise = (1.0 - field.noise / maximumNoise).coerceIn(0.0, 1.0)
        val transparency = (starFlux(field) / medianFlux.coerceAtLeast(1e-12)).coerceIn(0.0, 1.0)
        return stars * .30 + elongation * .15 + background * .15 + noise * .10 +
            transparency * .20 + min(1.0, field.stars.size / 30.0) * .10
    }

    private fun starFlux(field: StarField): Double =
        median(field.stars.map { it.flux })

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }
}
