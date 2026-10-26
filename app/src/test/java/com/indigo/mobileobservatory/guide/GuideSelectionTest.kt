package com.indigo.mobileobservatory.guide

import org.junit.Assert.*
import org.junit.Test

class GuideSelectionTest {
    private val module = GuideModule()

    @Test fun selectedFainterStarRemainsThePrimaryAcrossNewMeasurements() {
        val selected = GuideStar(120f, 160f, 12f, 80f)
        val brighter = GuideStar(150f, 160f, 40f, 300f)
        val tracked = selected.copy(x = 124f, y = 158f, flux = 84f)
        assertEquals(tracked, module.matchStar(selected, listOf(brighter, tracked)))
        assertEquals(listOf(tracked), module.referenceStars(tracked, listOf(brighter, tracked), false))
    }

    @Test fun aLostSelectionDoesNotFallBackToTheBrightestStar() {
        val selected = GuideStar(120f, 160f, 12f, 80f)
        assertNull(module.matchStar(selected, listOf(selected.copy(x = 200f))))
        assertNull(module.matchStar(selected, listOf(selected.copy(flux = 800f))))
        assertNull(module.matchStar(selected.copy(flux = 0f), listOf(selected)))
    }

    @Test fun multiStarReferencesAlwaysContainTheSelectedPrimaryFirst() {
        val stars = (0..15).map { GuideStar(it * 30f, 100f, 10f, 200f - it * 5) }
        val selected = stars.last()
        val references = module.referenceStars(selected, stars, true)
        assertEquals(12, references.size)
        assertEquals(selected, references.first())
        assertEquals(1, references.count { it == selected })
    }
}
