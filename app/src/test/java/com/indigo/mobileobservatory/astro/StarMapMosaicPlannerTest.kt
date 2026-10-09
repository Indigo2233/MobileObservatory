package com.indigo.mobileobservatory.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarMapMosaicPlannerTest {
    @Test
    fun traversalAndCornerDetermineExecutionOrder() {
        assertEquals(
            listOf(0 to 0, 0 to 1, 0 to 2, 1 to 2, 1 to 1, 1 to 0),
            StarMapMosaicPlanner.orderedCells(
                StarMapMosaicConfig(2, 3, traversal = MosaicTraversal.SNAKE)
            )
        )
        assertEquals(
            listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1, 0 to 2, 1 to 2),
            StarMapMosaicPlanner.orderedCells(
                StarMapMosaicConfig(2, 3, traversal = MosaicTraversal.COLUMNS)
            )
        )
        assertEquals(
            listOf(1 to 2, 1 to 1, 1 to 0, 0 to 2, 0 to 1, 0 to 0),
            StarMapMosaicPlanner.orderedCells(
                StarMapMosaicConfig(
                    2,
                    3,
                    traversal = MosaicTraversal.ROWS,
                    startCorner = MosaicStartCorner.BOTTOM_RIGHT
                )
            )
        )
    }

    @Test
    fun projectedPanelCentersStayFiniteNearPoleAndRaWrap() {
        val panels = StarMapMosaicPlanner.panels(
            centerRaHours = 23.9,
            centerDecDegrees = 88.5,
            widthDeg = 24.0,
            heightDeg = 16.0,
            positionAngleDeg = 37.0,
            config = StarMapMosaicConfig(2, 3, 10, traversal = MosaicTraversal.SNAKE)
        )
        assertEquals(6, panels.size)
        assertEquals((1..6).toList(), panels.map { it.number })
        panels.forEach {
            assertTrue(it.raHours.isFinite() && it.raHours in 0.0..<24.0)
            assertTrue(it.decDegrees.isFinite() && it.decDegrees in -90.0..90.0)
        }
    }
}
