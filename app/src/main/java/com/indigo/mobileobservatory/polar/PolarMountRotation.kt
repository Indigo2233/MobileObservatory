package com.indigo.mobileobservatory.polar

/**
 * Mechanical knob rotation in north/west/zenith coordinates.
 *
 * Align the initial azimuth with the pole's meridian, change altitude about its horizontal
 * adjustment axis, then restore the residual azimuth. The signed altitude convention already
 * accounts for the hemisphere. Applied to the measured mount axis, this gives the axis described
 * by the supplied residuals, including simultaneous azimuth and altitude corrections.
 */
internal object PolarMountRotation {
    fun forResidual(
        vector: PolarVector3,
        initialAzimuthErrorDeg: Double,
        initialAltitudeErrorDeg: Double,
        residualAzimuthErrorDeg: Double,
        residualAltitudeErrorDeg: Double
    ): PolarVector3 {
        val meridian = PolarVector3.rotateByRodrigues(
            vector, PolarErrorDetermination.ZENITH_AXIS, initialAzimuthErrorDeg)
        val altitude = PolarVector3.rotateByRodrigues(
            meridian, PolarErrorDetermination.WEST_AXIS, initialAltitudeErrorDeg - residualAltitudeErrorDeg)
        return PolarVector3.rotateByRodrigues(
            altitude, PolarErrorDetermination.ZENITH_AXIS, -residualAzimuthErrorDeg)
    }

    fun inverseForResidual(
        vector: PolarVector3,
        initialAzimuthErrorDeg: Double,
        initialAltitudeErrorDeg: Double,
        residualAzimuthErrorDeg: Double,
        residualAltitudeErrorDeg: Double
    ): PolarVector3 {
        val meridian = PolarVector3.rotateByRodrigues(
            vector, PolarErrorDetermination.ZENITH_AXIS, residualAzimuthErrorDeg)
        val altitude = PolarVector3.rotateByRodrigues(
            meridian, PolarErrorDetermination.WEST_AXIS, residualAltitudeErrorDeg - initialAltitudeErrorDeg)
        return PolarVector3.rotateByRodrigues(
            altitude, PolarErrorDetermination.ZENITH_AXIS, -initialAzimuthErrorDeg)
    }
}
