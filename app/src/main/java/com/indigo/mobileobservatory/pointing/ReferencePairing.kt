package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.*
import java.time.Instant
import kotlin.math.abs

object ReferencePairing {
    /** Preserve raw observations; the derived reference explicitly carries the pairing error bound. */
    fun pair(phone: OpticalObservation, reference: OpticalObservation, site: ObserverSite,
             trackingSky: Boolean, measuredMotionDeg: Double,
             opticalStability: OpticalPairStabilityEvidence? = null): CalibrationPair {
        fun convert(v: SkyVector): SkyVector {
                if (trackingSky) return v
                val p = v.direction()
                val h = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(p.raDeg, p.decDeg),
                    Instant.ofEpochMilli(reference.stamp.utcMidpointMs), site, null)
                val next = CoordinateTransform.topocentricToJ2000(h, Instant.ofEpochMilli(phone.stamp.utcMidpointMs), site, null)
                return SkyDirection(next.raDeg, next.decDeg).vector()
        }
        val direction = convert(reference.direction.vector()).direction()
        val adjusted = reference.pose?.let { pose ->
            val f = direction.vector()
            val nearby = convert((pose.forward + pose.up * .001).unit())
            val up = (nearby - f * nearby.dot(f)).unit()
            SkyPose(up.cross(f).unit(), up, f)
        }
        val timeBound = abs(phone.stamp.elapsedMidpointMs - reference.stamp.elapsedMidpointMs) +
            phone.stamp.clockUncertaintyMs + reference.stamp.clockUncertaintyMs
        return CalibrationPair(phone, reference, timeBound * .0042 / 1000 + measuredMotionDeg,
            adjusted, measuredMotionDeg, direction, opticalStability).also { it.validate() }
    }
}
