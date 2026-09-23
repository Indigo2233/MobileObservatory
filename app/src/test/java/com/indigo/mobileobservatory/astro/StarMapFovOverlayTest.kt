package com.indigo.mobileobservatory.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarMapFovOverlayTest {
    private val circle = FovComputation(
        mode = FovInstrumentMode.EYEPIECE,
        circleDeg = 1.5
    )
    private val rect = FovComputation(
        mode = FovInstrumentMode.SENSOR,
        rectWidthDeg = 0.8,
        rectHeightDeg = 0.5
    )

    @Test
    fun hiddenOverlayClearsCurrentAndTarget() {
        val scripts = StarMapFovOverlay.scripts(
            showOverlay = false,
            computation = rect,
            currentLabel = "导星 当前 0.80°×0.50°",
            targetLabel = "导星 目标 0.80°×0.50°",
            alsoZoom = true,
            targetAnchor = FovSkyAnchor(1.0, 2.0)
        )
        assertEquals(2, scripts.size)
        assertTrue(scripts[0].contains("clearCurrentFovOverlay"))
        assertTrue(scripts[1].contains("clearTargetFovOverlay"))
        assertFalse(scripts.any { it.contains("setCurrent") })
        assertFalse(scripts.any { it.contains("setTarget") && !it.contains("clearTarget") })
    }

    @Test
    fun eyepieceTrainAlwaysPushesDashedTargetAtScreenCentre() {
        val scripts = StarMapFovOverlay.scripts(
            showOverlay = true,
            computation = circle,
            currentLabel = "主镜 当前 1.50°",
            targetLabel = "主镜 目标 1.50°",
            alsoZoom = true
        )
        assertTrue(scripts[0].contains("setCurrentCircleFovOverlay(1.50000000,true,\"主镜 当前 1.50°\")"))
        assertTrue(scripts[1].contains("setTargetCircleFovOverlay(1.50000000,\"主镜 目标 1.50°\")"))
        assertFalse(scripts[1].contains("clearTargetFovOverlay"))
        assertFalse(scripts.any { it.contains("setCurrentRect") })
    }

    @Test
    fun sensorTrainKeepsDashedTargetCenteredAndCurrentOnMount() {
        val scripts = StarMapFovOverlay.scripts(
            showOverlay = true,
            computation = rect,
            currentLabel = "导星 当前 0.80°×0.50°",
            targetLabel = "导星 目标 0.80°×0.50°",
            alsoZoom = false,
            currentAnchor = FovSkyAnchor(5.0, 10.0, "JNOW")
        )
        assertTrue(
            scripts[0].contains(
                "setCurrentRectFovOverlay(0.80000000,0.50000000,false,\"导星 当前 0.80°×0.50°\",5.00000000,10.00000000,\"JNOW\")"
            )
        )
        assertTrue(
            scripts[1].contains(
                "setTargetRectFovOverlay(0.80000000,0.50000000,\"导星 目标 0.80°×0.50°\")"
            )
        )
        assertFalse(scripts[1].contains("12.00000000"))
        assertFalse(scripts.any { it.contains("setCurrentCircle") })
    }

    @Test
    fun incompleteComputationClearsBothLayers() {
        val scripts = StarMapFovOverlay.scripts(
            showOverlay = true,
            computation = FovComputation(mode = FovInstrumentMode.SENSOR),
            currentLabel = "",
            targetLabel = "",
            alsoZoom = true
        )
        assertTrue(scripts[0].contains("clearCurrentFovOverlay"))
        assertTrue(scripts[1].contains("clearTargetFovOverlay"))
    }

    @Test
    fun overlayCaptionUsesTrainRoleAndShape() {
        assertEquals(
            "C8 + 25 mm · 50° 当前 1.50°",
            StarMapFovOverlay.overlayCaption("C8 + 25 mm · 50°", "当前", circle)
        )
        assertEquals(
            "C8 + ASI533 目标 0.80°×0.50°",
            StarMapFovOverlay.overlayCaption("C8 + ASI533", "目标", rect)
        )
    }
}
