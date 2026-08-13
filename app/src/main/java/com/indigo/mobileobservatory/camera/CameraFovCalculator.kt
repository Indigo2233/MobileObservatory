package com.indigo.mobileobservatory.camera

import kotlin.math.atan

/** Values recorded for one Camera2 still capture, independent of Android framework classes. */
data class CameraFovInput(
    val focalLengthMm: Double,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val activeWidthPx: Int,
    val activeHeightPx: Int,
    val cropLeftPx: Int,
    val cropTopPx: Int,
    val cropWidthPx: Int,
    val cropHeightPx: Int,
    val outputWidthPx: Int,
    val outputHeightPx: Int
)

data class CameraFovEstimate(
    val widthDeg: Double,
    val heightDeg: Double,
    val effectiveSensorWidthMm: Double,
    val effectiveSensorHeightMm: Double,
    val arcsecPerPixel: Double
)

/** Computes the actual still-image field from focal length, Camera2 crop and output aspect ratio. */
object CameraFovCalculator {
    fun estimate(input: CameraFovInput): CameraFovEstimate? {
        if (input.focalLengthMm <= 0.0 || input.sensorWidthMm <= 0.0 || input.sensorHeightMm <= 0.0 ||
            input.activeWidthPx <= 0 || input.activeHeightPx <= 0 ||
            input.outputWidthPx <= 0 || input.outputHeightPx <= 0
        ) return null

        val cropWidth = input.cropWidthPx.coerceIn(1, input.activeWidthPx).toDouble()
        val cropHeight = input.cropHeightPx.coerceIn(1, input.activeHeightPx).toDouble()
        val cropAspect = cropWidth / cropHeight
        val outputAspect = input.outputWidthPx.toDouble() / input.outputHeightPx
        val (usedWidthPx, usedHeightPx) = if (outputAspect > cropAspect) {
            cropWidth to (cropWidth / outputAspect).coerceAtMost(cropHeight)
        } else {
            (cropHeight * outputAspect).coerceAtMost(cropWidth) to cropHeight
        }
        val sensorWidth = input.sensorWidthMm * usedWidthPx / input.activeWidthPx
        val sensorHeight = input.sensorHeightMm * usedHeightPx / input.activeHeightPx
        val width = fov(sensorWidth, input.focalLengthMm)
        val height = fov(sensorHeight, input.focalLengthMm)
        val scale = ((width / input.outputWidthPx) + (height / input.outputHeightPx)) * 0.5 * 3600.0
        return CameraFovEstimate(width, height, sensorWidth, sensorHeight, scale)
    }

    private fun fov(sensorSizeMm: Double, focalLengthMm: Double): Double =
        Math.toDegrees(2.0 * atan(sensorSizeMm / (2.0 * focalLengthMm)))
}
