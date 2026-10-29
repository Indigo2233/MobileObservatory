package com.indigo.mobileobservatory.mount

import com.indigo.mobileobservatory.pointing.PointingMountCapabilities

/** Common control surface for protocol and in-process mount adapters. */
interface MountSession {
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
    fun startGuidePulse(direction: MountDirection, durationMs: Int) = startMove(direction)
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
    fun close() {}
}

/** Sky-Watcher hand-controller and motor Wi-Fi adapters share the mount control surface. */
interface SkyWatcherMountSession : MountSession
