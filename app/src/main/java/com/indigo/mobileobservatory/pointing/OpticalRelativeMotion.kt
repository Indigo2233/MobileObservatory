package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.*
import java.time.Instant
import kotlin.math.*

/** The predicted image of a fixed celestial star in a rigid, stationary terrestrial camera.
 * This removes only known Earth rotation. Observed residuals and PSF widths remain in the bound. */
object OpticalRelativeMotion {
    fun predictedPixel(star: OpticalStarPsf, anchor: OpticalObservation, elapsedMs: Long,
        site: ObserverSite?, trackingSky: Boolean): Pair<Double, Double> {
        if (trackingSky) return star.x to star.y
        val observer = requireNotNull(site)
        val pose = requireNotNull(anchor.pose); val geometry = requireNotNull(anchor.geometry)
        val scale = Math.toRadians(requireNotNull(anchor.plateScaleDegPerPixel))
        val parity = if (geometry.parity == ImageParity.MIRRORED) -1 else 1
        val starDirection = pose.project(SkyVector((star.x - geometry.axisPixelX) * scale * parity,
            -(star.y - geometry.axisPixelY) * scale, 1.0).unit())
        val nextUtc = anchor.stamp.utcMidpointMs + elapsedMs - anchor.stamp.elapsedMidpointMs
        fun staticDirection(vector: SkyVector): SkyVector {
            val p = vector.direction()
            val horizontal = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(p.raDeg, p.decDeg),
                Instant.ofEpochMilli(anchor.stamp.utcMidpointMs), observer, null)
            val next = CoordinateTransform.topocentricToJ2000(horizontal, Instant.ofEpochMilli(nextUtc), observer, null)
            return SkyDirection(next.raDeg, next.decDeg).vector()
        }
        val f = staticDirection(pose.forward)
        val nearbyUp = staticDirection((pose.forward + pose.up * .001).unit())
        val up = (nearbyUp - f * nearbyUp.dot(f)).unit()
        val nextPose = SkyPose(up.cross(f).unit(), up, f)
        val local = nextPose.unproject(starDirection)
        require(local.z > .01)
        return geometry.axisPixelX + local.x / local.z / scale * parity to
            geometry.axisPixelY - local.y / local.z / scale
    }
}
