package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.Roi

object ReferencePixelMapper {
    /** Returns the physical sensor center in the delivered frame's one-based FITS coordinates. */
    fun sensorCenter(sensorWidth: Int, sensorHeight: Int, roi: Roi, roiSensorScale: Int,
                     outputWidth: Int, outputHeight: Int): Pair<Double, Double> {
        require(sensorWidth > 0 && sensorHeight > 0 && roiSensorScale > 0 && roi.width > 0 && roi.height > 0 && outputWidth > 0 && outputHeight > 0)
        val x = (sensorWidth / (2.0 * roiSensorScale) - roi.x) * outputWidth / roi.width + .5
        val y = (sensorHeight / (2.0 * roiSensorScale) - roi.y) * outputHeight / roi.height + .5
        require(x in .5..(outputWidth + .5) && y in .5..(outputHeight + .5)) { "ROI does not contain the optical reference pixel" }
        return x to y
    }
}
