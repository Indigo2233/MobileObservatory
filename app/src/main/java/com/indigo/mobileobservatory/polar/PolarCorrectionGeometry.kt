package com.indigo.mobileobservatory.polar

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astrometry.ImagePoint
import java.io.File

data class PolarCorrectionGeometry(
    val width: Int, val height: Int,
    val reference: ImagePoint, val azimuth: ImagePoint, val target: ImagePoint, val altitude: ImagePoint
) {
    val vertices get() = listOf(reference, azimuth, target, altitude)
    val targetOutsideImage get() = target.x < -.5 || target.y < -.5 || target.x > width - .5 || target.y > height - .5
}

/** A successful estimate and its exact image stay together while a later exposure/solve is pending. */
data class PolarAdjustmentSnapshot(
    val determination: PolarErrorDetermination,
    val current: PolarAlignmentResult,
    val sample: PolarSample,
    val geometry: PolarCorrectionGeometry?
) {
    val file: File? get() = sample.file
    val projectionUnavailable get() = geometry == null && sample.result?.imageWcs != null
    companion object {
        fun create(determination: PolarErrorDetermination, current: PolarAlignmentResult, sample: PolarSample) =
            PolarAdjustmentSnapshot(determination, current, sample, correctionGeometry(determination, current, sample))
    }
}

/**
 * Project a fixed reference direction as it will appear AFTER the physical knob corrections.
 * A star moves opposite to the boresight. For an already partially corrected camera, its virtual
 * direction is R(current) * inverse(R(destination)) * star. This also keeps the final target fixed
 * as new WCS frames follow the moving reference point towards it.
 *
 * The two solid legs are the exact azimuth-then-altitude rotation. Their translated opposite sides
 * form a parallelogram; the fourth vertex is a component endpoint, not another measured star.
 */
fun correctionGeometry(determination: PolarErrorDetermination, current: PolarAlignmentResult,
                       sample: PolarSample): PolarCorrectionGeometry? {
    val wcs = sample.result?.takeIf { it.success }?.imageWcs ?: return null
    val at = sample.capturedAt ?: return null
    val initial = determination.initial
    val referenceSky = EquatorialCoordinates(determination.initialReferenceFrame.raDeg, determination.initialReferenceFrame.decDeg)
    val reference = wcs.pixel(referenceSky) ?: return null
    val star = PolarVector3.fromTopocentric(CoordinateTransform.j2000ToTopocentric(
        referenceSky, at, determination.site, determination.refraction))
    val currentAzimuth = initial.azimuthErrorDeg - current.azimuthErrorDeg
    val currentAltitude = initial.altitudeErrorDeg - current.altitudeErrorDeg
    fun rotate(v: PolarVector3, az: Double, alt: Double) = PolarVector3.rotateByRodrigues(
        PolarVector3.rotateByRodrigues(v, PolarErrorDetermination.WEST_AXIS, alt), PolarErrorDetermination.ZENITH_AXIS, az)
    fun inverse(v: PolarVector3, az: Double, alt: Double) = PolarVector3.rotateByRodrigues(
        PolarVector3.rotateByRodrigues(v, PolarErrorDetermination.ZENITH_AXIS, -az), PolarErrorDetermination.WEST_AXIS, -alt)
    fun destination(az: Double, alt: Double): ImagePoint? {
        val virtual = rotate(inverse(star, az, alt), currentAzimuth, currentAltitude)
        return wcs.pixel(CoordinateTransform.topocentricToJ2000(virtual.toTopocentric(), at,
            determination.site, determination.refraction))
    }
    val azimuth = destination(initial.azimuthErrorDeg, currentAltitude) ?: return null
    val target = destination(initial.azimuthErrorDeg, initial.altitudeErrorDeg) ?: return null
    return PolarCorrectionGeometry(wcs.width, wcs.height, reference, azimuth, target, reference + target - azimuth)
}
