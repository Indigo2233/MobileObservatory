package com.indigo.mobileobservatory.mount

import com.indigo.mobileobservatory.pointing.PointingMountCapabilities

/** Common Sky-Watcher control surface used by hand-controller and motor Wi-Fi adapters. */
interface SkyWatcherMountSession {
    val modelName: String
    val supportsSync: Boolean
    val supportsTimeSync: Boolean
        get() = false
    val pointingCapabilities: PointingMountCapabilities
        get() = PointingMountCapabilities(readCoordinates = true, goto = true, sync = supportsSync,
            tracking = true, manualMove = true, home = true, setHome = true, readSite = true,
            writeSite = true, readTime = supportsTimeSync, writeTime = supportsTimeSync)
    val coordinateMode: String get() = "MOUNT_MODEL"

    fun open(): MountCoordinates
    fun readCoordinates(): MountCoordinates
    fun readCoordinatesWithAxes(): MountSessionCoordinates = MountSessionCoordinates(readCoordinates())
    fun slewTo(targetNow: MountCoordinates)
    fun startMove(direction: MountDirection)
    fun stopMove(direction: MountDirection?)
    fun abortMotion() = stopMove(null)
    fun setMoveRate(rate: MountSlewRate)
    fun setTracking(enabled: Boolean)
    fun setTrackingRate(rate: MountTrackingRate) {
        setTracking(rate.tracks)
    }
    fun readSite(): MountSite
    fun setSite(site: MountSite)
    fun readTime(): MountTime {
        error("Sky-Watcher mount time synchronization is not supported by this protocol.")
    }
    fun setTime(time: MountTime) {
        error("Sky-Watcher mount time synchronization is not supported by this protocol.")
    }
    fun setHomeHere()
    fun goHome()
    fun syncTo(coordinates: MountCoordinates)
    fun emergencyStopPayloads(): List<ByteArray>
    fun refreshTracking() {}
}
