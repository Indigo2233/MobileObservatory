package com.indigo.mobileobservatory.pointing

import org.junit.Assert.assertEquals
import org.junit.Test

class StarCoordinateUndistorterTest {
    private val calibration = CameraLensCalibration(
        focalX = 1_000.0, focalY = 1_000.0, principalX = 1_000.0, principalY = 750.0,
        skew = 0.0, radialK1 = 0.12, radialK2 = -0.02, radialK3 = 0.0, tangentialP1 = 0.0, tangentialP2 = 0.0
    )

    @Test
    fun undistortsStarCentroidWithoutTouchingExtractionStatistics() {
        val distorted = star(1_400f, 750f)
        val result = StarCoordinateUndistorter.correct(
            extraction = StarExtractionResult(listOf(distorted), 32, 5f, 5f, 10f, 1f),
            calibration = calibration, cropLeftPx = 0, cropTopPx = 0, cropWidthPx = 2_000, cropHeightPx = 1_500,
            frameWidth = 2_000, frameHeight = 1_500, alreadyCorrectedByCamera = false
        )
        assertEquals(393.0, result.stars.single().x - 1_000.0, 0.5)
        assertEquals(distorted.snr, result.stars.single().snr)
        assertEquals(5f, result.estimatedLimitingMagnitude)
    }

    @Test
    fun preservesCameraCorrectedCoordinates() {
        val distorted = star(1_400f, 750f)
        val result = StarCoordinateUndistorter.correct(
            StarExtractionResult(listOf(distorted), 32, 5f, null, 10f, 1f), calibration,
            0, 0, 2_000, 1_500, 2_000, 1_500, alreadyCorrectedByCamera = true
        )
        assertEquals(distorted.x, result.stars.single().x)
    }

    @Test
    fun preservesCoordinatesWhenFrameIsInPostCorrectionDomain() {
        val distorted = star(1_400f, 750f)
        val result = StarCoordinateUndistorter.correct(
            StarExtractionResult(listOf(distorted), 32, 5f, null, 10f, 1f), calibration,
            0, 0, 2_000, 1_500, 2_000, 1_500, alreadyCorrectedByCamera = false,
            coordinateDomain = LensCalibrationCoordinateDomain.POST_CORRECTION
        )
        assertEquals(distorted.x, result.stars.single().x)
        assertEquals(distorted.y, result.stars.single().y)
    }

    @Test
    fun preservesCoordinatesWhenCalibrationDomainCannotBeEstablished() {
        val distorted = star(1_400f, 750f)
        val result = StarCoordinateUndistorter.correct(
            StarExtractionResult(listOf(distorted), 32, 5f, null, 10f, 1f), calibration,
            0, 0, 2_000, 1_500, 2_000, 1_500, alreadyCorrectedByCamera = false,
            coordinateDomain = LensCalibrationCoordinateDomain.UNKNOWN
        )
        assertEquals(distorted.x, result.stars.single().x)
        assertEquals(distorted.y, result.stars.single().y)
    }

    @Test
    fun mapsCalibrationThroughCenteredAspectCropBeforeUndistorting() {
        // 4:3 sensor crop delivered as a centred 16:9 stream. The star is distorted in the
        // original pre-correction coordinate system, then expressed in the 16:9 frame.
        val idealY = 350.0
        val normalizedY = (idealY - calibration.principalY) / calibration.focalY
        val radial = 1.0 + calibration.radialK1 * normalizedY * normalizedY +
            calibration.radialK2 * normalizedY * normalizedY * normalizedY * normalizedY
        val distortedSensorY = calibration.principalY + normalizedY * radial * calibration.focalY
        val frameY = (distortedSensorY - 187.5).toFloat()
        val result = StarCoordinateUndistorter.correct(
            StarExtractionResult(listOf(star(1_000f, frameY)), 32, 5f, null, 10f, 1f), calibration,
            0, 0, 2_000, 1_500, 2_000, 1_125, alreadyCorrectedByCamera = false
        )
        assertEquals(idealY - 187.5, result.stars.single().y.toDouble(), 0.5)
    }

    private fun star(x: Float, y: Float) = ExtractedStar(x, y, 100f, 50f, 20f, 10f)
}
