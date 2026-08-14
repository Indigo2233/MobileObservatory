package com.indigo.mobileobservatory.pointing

/**
 * Brown-Conrady lens calibration reported by Camera2. Intrinsics are in pre-correction-array pixels;
 * distortion maps ideal normalized coordinates to the captured, distorted coordinates.
 */
data class CameraLensCalibration(
    val focalX: Double,
    val focalY: Double,
    val principalX: Double,
    val principalY: Double,
    val skew: Double,
    val radialK1: Double,
    val radialK2: Double,
    val radialK3: Double,
    val tangentialP1: Double,
    val tangentialP2: Double
) {
    init {
        require(focalX > 0.0 && focalY > 0.0)
    }
}

/** Pixel coordinate system used by a captured frame and its crop metadata. */
enum class LensCalibrationCoordinateDomain {
    PRE_CORRECTION,
    POST_CORRECTION,
    UNKNOWN
}

/** Corrects extracted star centroids only; the displayed capture remains untouched. */
object StarCoordinateUndistorter {
    fun correct(
        extraction: StarExtractionResult,
        calibration: CameraLensCalibration?,
        cropLeftPx: Int?,
        cropTopPx: Int?,
        cropWidthPx: Int?,
        cropHeightPx: Int?,
        frameWidth: Int,
        frameHeight: Int,
        alreadyCorrectedByCamera: Boolean,
        coordinateDomain: LensCalibrationCoordinateDomain = LensCalibrationCoordinateDomain.PRE_CORRECTION
    ): StarExtractionResult {
        if (calibration == null || alreadyCorrectedByCamera || coordinateDomain != LensCalibrationCoordinateDomain.PRE_CORRECTION ||
            cropLeftPx == null || cropTopPx == null || cropWidthPx == null || cropHeightPx == null ||
            cropWidthPx <= 0 || cropHeightPx <= 0 || frameWidth <= 0 || frameHeight <= 0
        ) return extraction
        val scaled = calibration.scaleToCrop(
            cropLeftPx, cropTopPx, cropWidthPx, cropHeightPx, frameWidth, frameHeight
        )
        return extraction.copy(stars = extraction.stars.map { star -> star.copyPosition(scaled) })
    }

    private fun CameraLensCalibration.scaleToCrop(
        cropLeft: Int, cropTop: Int, cropWidth: Int, cropHeight: Int, frameWidth: Int, frameHeight: Int
    ): CameraLensCalibration {
        // Processed streams may crop the sensor centre to match their requested aspect ratio.
        // Apply that content rectangle before mapping the pre-correction calibration to frame pixels.
        val cropAspect = cropWidth.toDouble() / cropHeight
        val frameAspect = frameWidth.toDouble() / frameHeight
        val (usedWidth, usedHeight) = if (frameAspect > cropAspect) {
            cropWidth.toDouble() to cropWidth / frameAspect
        } else {
            cropHeight * frameAspect to cropHeight.toDouble()
        }
        val contentLeft = cropLeft + (cropWidth - usedWidth) / 2.0
        val contentTop = cropTop + (cropHeight - usedHeight) / 2.0
        val scaleX = frameWidth / usedWidth
        val scaleY = frameHeight / usedHeight
        return copy(
            focalX = focalX * scaleX,
            focalY = focalY * scaleY,
            principalX = (principalX - contentLeft) * scaleX,
            principalY = (principalY - contentTop) * scaleY,
            skew = skew * scaleX
        )
    }

    private fun ExtractedStar.copyPosition(calibration: CameraLensCalibration): ExtractedStar {
        // Convert distorted pixel coordinates to normalized camera coordinates, then iteratively
        // invert the Camera2 ideal-to-distorted Brown-Conrady polynomial.
        val yd = (y - calibration.principalY) / calibration.focalY
        val xd = ((x - calibration.principalX) - calibration.skew * yd) / calibration.focalX
        var xu = xd
        var yu = yd
        repeat(8) {
            val r2 = xu * xu + yu * yu
            val radial = 1.0 + calibration.radialK1 * r2 + calibration.radialK2 * r2 * r2 +
                calibration.radialK3 * r2 * r2 * r2
            val dx = 2.0 * calibration.tangentialP1 * xu * yu + calibration.tangentialP2 * (r2 + 2.0 * xu * xu)
            val dy = calibration.tangentialP1 * (r2 + 2.0 * yu * yu) + 2.0 * calibration.tangentialP2 * xu * yu
            if (radial <= 1e-8) return@repeat
            xu = (xd - dx) / radial
            yu = (yd - dy) / radial
        }
        return copy(
            x = (calibration.focalX * xu + calibration.skew * yu + calibration.principalX).toFloat(),
            y = (calibration.focalY * yu + calibration.principalY).toFloat()
        )
    }
}
