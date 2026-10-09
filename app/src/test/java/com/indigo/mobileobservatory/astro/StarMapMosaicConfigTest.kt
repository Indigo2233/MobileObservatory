package com.indigo.mobileobservatory.astro

import org.junit.Assert.assertEquals
import org.junit.Test

class StarMapMosaicConfigTest {
    @Test
    fun normalizesPanelCountsAndOverlap() {
        val normalized = StarMapMosaicConfig(
            rows = 0,
            columns = 25,
            overlapPercent = 120
        ).normalized()
        assertEquals(1, normalized.rows)
        assertEquals(10, normalized.columns)
        assertEquals(90, normalized.overlapPercent)
        assertEquals(10, normalized.panelCount)
    }

    @Test
    fun targetFramingScriptCarriesMosaicAndPositionAngle() {
        val scripts = StarMapFovOverlay.targetSensorFramingScripts(
            positionAngleDeg = -1.5,
            mosaic = StarMapMosaicConfig(2, 3, 15, true),
            alsoZoom = true
        )
        assertEquals(2, scripts.size)
        assertEquals(
            "window.MercStarMap && window.MercStarMap.setTargetFovRotation(358.50000000);",
            scripts[0]
        )
        assertEquals(
            "window.MercStarMap && window.MercStarMap.setTargetFovMosaic(2,3,15,true,true);",
            scripts[1]
        )
    }
}
