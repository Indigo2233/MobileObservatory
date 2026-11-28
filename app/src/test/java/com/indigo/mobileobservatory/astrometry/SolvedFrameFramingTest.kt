package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.astro.FovInstrumentMode
import com.indigo.mobileobservatory.astro.MapOpticsPrefs
import com.indigo.mobileobservatory.astro.OpticsTrainId
import com.indigo.mobileobservatory.astro.PointingOpticsConfiguration
import com.indigo.mobileobservatory.astro.StarMapFovOverlay
import com.indigo.mobileobservatory.astro.StarMapOpticsPrefs
import org.junit.Assert.*
import org.junit.Test

class SolvedFrameFramingTest {
    private val wcs = requireNotNull(SolvedImageWcs.parse(mapOf(
        "CTYPE1" to "RA---TAN", "CTYPE2" to "DEC--TAN",
        "CRVAL1" to "120", "CRVAL2" to "20",
        "CRPIX1" to "350.5", "CRPIX2" to "200.5",
        "CD1_1" to "-0.001", "CD1_2" to "0", "CD2_1" to "0", "CD2_2" to "-0.001"
    ), 800, 400))
    private val solved = PlateSolveResult(true, "Solved", raDeg = 120.0, decDeg = 20.0,
        arcsecPerPixel = 3.6, imageWcs = wcs)

    @Test fun offCenterReferencePixelDoesNotCenterTheFrameOnCrval() {
        val framing = requireNotNull(SolvedFrameFraming.fromCurrentFrame(solved, FitsSolveHints(), "Camera"))
        val expected = wcs.direction(ImagePoint(399.5, 199.5))
        assertEquals(expected.raDeg / 15, framing.anchor.raHours, 1e-9)
        assertEquals(expected.decDeg, framing.anchor.decDegrees, 1e-9)
        assertNotEquals(8.0, framing.anchor.raHours, 0.001)
        assertEquals(0.8, requireNotNull(framing.field.rectWidthDeg), 0.0001)
        assertEquals(0.4, requireNotNull(framing.field.rectHeightDeg), 0.0001)
        assertNull(framing.sensor)
        assertNull(framing.focalLengthMm)
    }

    @Test fun applyingOpticsUsesCapturedRoiAndEffectivePixelSizeAndReusesTheCamera() {
        val hints = FitsSolveHints(pixelSizeUm = 3.76, binning = 2, instrumentName = "Capture camera")
        val framing = requireNotNull(SolvedFrameFraming.fromCurrentFrame(solved, hints, "Connected camera"))
        val configuration = PointingOpticsConfiguration.load(MapOpticsPrefs()).copy(
            confirmedTrains = setOf(OpticsTrainId.PRIMARY, OpticsTrainId.SECONDARY))
        val updated = requireNotNull(framing.updatedPrimaryOptics(configuration))
        val camera = updated.cameras.single { it.id == updated.primary.sensorId }
        assertEquals("Capture camera", camera.name)
        assertEquals(800, camera.widthPx)
        assertEquals(400, camera.heightPx)
        assertEquals(7.52, camera.pixelSizeUm, 1e-9)
        assertEquals(206.265 * 7.52 / 3.6, requireNotNull(updated.primary.focalLengthMm(updated.telescopes)), 1e-9)
        assertEquals(FovInstrumentMode.SENSOR, updated.primary.mode)
        assertEquals(configuration.secondary, updated.secondary)
        assertEquals(setOf(OpticsTrainId.SECONDARY), updated.confirmedTrains)
        assertEquals(updated, framing.updatedPrimaryOptics(updated))
        val saved = StarMapOpticsPrefs.primaryFramingSnapshot(updated.primary)
        assertFalse(saved.strings.keys.any { it.startsWith("star_map_secondary") })
        assertFalse(StarMapOpticsPrefs.PLATE_FOCAL_LENGTH_MM in saved.strings)
    }

    @Test fun measuredFrameCanUseItsOwnSizeWhileTheTargetKeepsItsPlannedSize() {
        val framing = requireNotNull(SolvedFrameFraming.fromCurrentFrame(solved, FitsSolveHints(), "Camera"))
        val plan = framing.field.copy(rectWidthDeg = 2.0, rectHeightDeg = 1.0)
        val scripts = StarMapFovOverlay.scripts(true, plan, "Measured", "Target", false,
            currentAnchor = framing.anchor, currentComputation = framing.field)
        assertTrue(scripts[0].contains("setCurrentRectFovOverlay(0.799"))
        assertTrue(scripts[1].contains("setTargetRectFovOverlay(2.00000000"))
        val hidden = StarMapFovOverlay.scripts(false, plan, "Measured", "Target", false,
            currentAnchor = framing.anchor, currentComputation = framing.field, showCurrentWhenOverlayHidden = true)
        assertTrue(hidden[0].contains("setCurrentRectFovOverlay"))
        assertTrue(hidden[1].contains("clearTargetFovOverlay"))
    }
}
