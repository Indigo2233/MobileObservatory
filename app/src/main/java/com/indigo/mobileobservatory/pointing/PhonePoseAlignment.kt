package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.ObserverSite

/** Full optical attitude anchors all three IMU axes. No magnetic heading is treated as truth. */
internal class PhonePoseAlignment {
    private var worldFromImu: SkyPose? = null
    private var opticalInImu: SkyPose? = null
    private var site: ObserverSite? = null
    var observation: OpticalObservation? = null
        private set

    fun clear() { worldFromImu = null; opticalInImu = null; site = null; observation = null }

    fun align(observation: OpticalObservation, sensor: AttitudeSnapshot, observer: ObserverSite,
              handEye: HandEyeCalibration? = null) {
        val opticalPose = requireNotNull(observation.pose)
        val sensorPose = sensor.imuPose() ?: error("No full sensor attitude at exposure midpoint")
        val sensorElapsed = requireNotNull(sensor.elapsedMs) { "Unknown sensor clock" }
        require(observation.stamp.clockUncertaintyMs <= 250 &&
            kotlin.math.abs(sensorElapsed - observation.stamp.elapsedMidpointMs) <= 250 &&
            kotlin.math.abs((sensor.timestampMs - sensorElapsed) -
                (observation.stamp.utcMidpointMs - observation.stamp.elapsedMidpointMs)) <= 250) {
            "No synchronous sensor attitude at exposure midpoint"
        }
        val enu = SkyPoseFrames.j2000ToTrueEnu(opticalPose, observation.stamp.utcMidpointMs, observer)
        val extrinsic = handEye?.takeIf { it.appliesTo(observation) }?.opticalInImu
        opticalInImu = extrinsic
        worldFromImu = enu.apply((extrinsic?.let(sensorPose::apply) ?: sensorPose).inverseRotation())
        this.observation = observation
        site = observer
    }

    fun pose(sensor: AttitudeSnapshot, utcNowMs: Long, elapsedNowMs: Long): SkyPose? {
        val anchor = observation ?: return null
        val sampleAge = sensor.elapsedMs?.let { elapsedNowMs - it } ?: return null
        if (sampleAge !in 0..500) return null
        // Parity normalization and a measured installation rotation are separate operations.
        if (anchor.geometry?.parity != ImageParity.NORMAL && opticalInImu == null) return null
        val age = elapsedNowMs - anchor.stamp.elapsedMidpointMs
        if (age !in 0..60_000 || kotlin.math.abs((utcNowMs - elapsedNowMs) -
                (anchor.stamp.utcMidpointMs - anchor.stamp.elapsedMidpointMs)) > 250) return null
        val currentSensor = sensor.imuPose() ?: return null
        val enu = requireNotNull(worldFromImu).apply(currentSensor).let { p -> opticalInImu?.let(p::apply) ?: p }
        return SkyPoseFrames.trueEnuToJ2000(enu, utcNowMs, requireNotNull(site))
    }
}
