package com.indigo.mobileobservatory.stacking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSkyReferenceSelectorTest {
    private fun field(stars: Int, elongation: Double, background: Double,
                      noise: Double, flux: Double): StarField {
        return StarField(
            List(stars) { index ->
                DetectedStar(
                    x = 20.0 + index % 10 * 16.0,
                    y = 20.0 + index / 10 * 16.0,
                    flux = flux,
                    radius = 1.4,
                    elongation = elongation
                )
            },
            background,
            noise,
            elongation
        )
    }

    @Test fun choosesAStrongStableFieldInsteadOfTheFirstFrame() {
        val usable = field(32, 1.08, .09, .003, 1.0)
        val samples = listOf(
            DeepSkyReferenceSample(0, field(9, 1.75, .24, .012, .42)),
            DeepSkyReferenceSample(1, usable),
            DeepSkyReferenceSample(2, field(30, 1.12, .10, .0035, .96)),
            DeepSkyReferenceSample(3, field(31, 1.15, .11, .004, .92))
        )
        val selected = DeepSkyReferenceSelector.choose(samples, DeepSkyConfig())
        assertEquals(1L, selected!!.frame)
        assertEquals(32, selected.starCount)
        assertTrue(selected.registrationSuccess >= .75)
        assertTrue(selected.score > .7)
    }

    @Test fun rejectsTrailingOnlyFieldsAndBoundsLongRecordingSamples() {
        val samples = List(20) {
            DeepSkyReferenceSample(it.toLong(), field(30, 3.4, .1, .004, 1.0))
        }
        assertNull(DeepSkyReferenceSelector.choose(samples, DeepSkyConfig()))
        val indices = OfflineDeepSkyStacker.referenceIndices(10_000)
        assertEquals(OfflineDeepSkyStacker.MAX_REFERENCE_SAMPLES, indices.size)
        assertEquals(0L, indices.first())
        assertEquals(9_999L, indices.last())
        assertTrue(indices.distinct().size == indices.size)
    }
}
