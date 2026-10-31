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
    val geometry: PolarCorrectionGeometry?,
    val referenceDirection: EquatorialCoordinates? = null,
    val reticleStepArcMin: Double = PolarErrorReticle.from(determination.initial, determination.site.latitudeDeg).stepArcMin
) {
    val file: File? get() = sample.file
    val projectionUnavailable get() = geometry == null && sample.result?.imageWcs != null
    companion object {
        fun create(determination: PolarErrorDetermination, current: PolarAlignmentResult, sample: PolarSample,
                   previous: PolarAdjustmentSnapshot? = null): PolarAdjustmentSnapshot {
            val retained = previous?.takeIf { it.determination === determination }
            val reference = correctionReference(determination, sample, retained?.referenceDirection)
            val scale = maxOf(retained?.reticleStepArcMin
                ?: PolarErrorReticle.from(determination.initial, determination.site.latitudeDeg).stepArcMin,
                PolarErrorReticle.from(current, determination.site.latitudeDeg).stepArcMin)
            return PolarAdjustmentSnapshot(determination, current, sample,
                correctionGeometry(determination, current, sample, reference), reference, scale)
        }
    }
}

/**
 * Follow one sky reference across images and project where it moves after each knob correction.
 * A star moves opposite to the boresight. For an already partially corrected camera, its virtual
 * direction is R(current) * inverse(R(destination)) * star.
 *
 * All four endpoints use their actual knob poses. At finite rotations their projection can differ
 * from an exact parallelogram. The anchor changes only when it leaves the current image.
 */
fun correctionGeometry(determination: PolarErrorDetermination, current: PolarAlignmentResult,
                       sample: PolarSample, referenceDirection: EquatorialCoordinates? = null): PolarCorrectionGeometry? {
    val wcs = sample.result?.takeIf { it.success }?.imageWcs ?: return null
    val at = sample.capturedAt ?: return null
    val initial = determination.initial
    val referenceSky = correctionReference(determination, sample, referenceDirection) ?: return null
    val reference = wcs.pixel(referenceSky) ?: return null
    val star = PolarVector3.fromTopocentric(CoordinateTransform.j2000ToTopocentric(
        referenceSky, at, determination.site, determination.refraction))
    fun destination(residualAzimuth: Double, residualAltitude: Double): ImagePoint? {
        val destinationRay = PolarMountRotation.inverseForResidual(star,
            initial.azimuthErrorDeg, initial.altitudeErrorDeg, residualAzimuth, residualAltitude)
        val virtual = PolarMountRotation.forResidual(destinationRay,
            initial.azimuthErrorDeg, initial.altitudeErrorDeg, current.azimuthErrorDeg, current.altitudeErrorDeg)
        return wcs.pixel(CoordinateTransform.topocentricToJ2000(virtual.toTopocentric(), at,
            determination.site, determination.refraction))
    }
    val azimuth = destination(0.0, current.altitudeErrorDeg) ?: return null
    val target = destination(0.0, 0.0) ?: return null
    val altitude = destination(current.azimuthErrorDeg, 0.0) ?: return null
    return PolarCorrectionGeometry(wcs.width, wcs.height, reference, azimuth, target, altitude)
}

private fun correctionReference(determination: PolarErrorDetermination, sample: PolarSample,
                                preferred: EquatorialCoordinates?): EquatorialCoordinates? {
    val reference = preferred ?: EquatorialCoordinates(
        determination.initialReferenceFrame.raDeg, determination.initialReferenceFrame.decDeg)
    val wcs = sample.result?.takeIf { it.success }?.imageWcs ?: return reference
    val pixel = wcs.pixel(reference)
    return if (pixel != null && pixel.x in -.5..(wcs.width - .5) && pixel.y in -.5..(wcs.height - .5)) reference
        else wcs.direction(ImagePoint((wcs.width - 1) / 2.0, (wcs.height - 1) / 2.0))
}
