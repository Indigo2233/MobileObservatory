package com.indigo.mobileobservatory.mount

import com.indigo.mobileobservatory.pointing.MountModelRevision

internal data class SolarMountStatus(val pier: Char, val still: Boolean)

internal enum class SolarSweepRate(val mountRate: MountSlewRate, val minimumTravelArcsec: Double) {
    FIND(MountSlewRate.RATE_16X, 100.0),
    FAST(MountSlewRate.RATE_24X, 150.0)
}

internal data class SolarMountMotion(
    val revision: MountModelRevision,
    val rate: SolarSweepRate,
    val pier: Char,
    val prepare: suspend () -> Unit,
    val read: suspend () -> MountCoordinates,
    val start: suspend (MountDirection, Int) -> Long,
    val stop: suspend (MountDirection) -> Long,
    val abort: suspend () -> Unit,
    val isStill: suspend () -> Boolean,
    val verifyTracking: suspend () -> Unit
)
