package com.indigo.mobileobservatory.pointing

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A measured axis offset in the solved, reflection-normalized camera basis.
 * Positive horizontal is camera-right and positive vertical is camera-up. These are
 * optical coordinates, not display pixels or instructions for a bracket screw.
 * A single measurement helps rough alignment but does not establish a calibration.
 */
data class CalibrationAlignmentOffset(
    val separationDeg: Double,
    val horizontalDeg: Double,
    val verticalDeg: Double,
    val uncertaintyDeg: Double
) {
    companion object {
        fun fromPair(pair: CalibrationPair): CalibrationAlignmentOffset {
            pair.validate()
            val local = requireNotNull(pair.camera.pose).unproject(pair.referenceDirection.vector())
            return CalibrationAlignmentOffset(
                SkyVector(0.0, 0.0, 1.0).angleDeg(local),
                Math.toDegrees(atan2(local.x, local.z)),
                Math.toDegrees(atan2(local.y, sqrt(local.x * local.x + local.z * local.z))),
                pair.uncertaintyDeg
            )
        }
    }
}
