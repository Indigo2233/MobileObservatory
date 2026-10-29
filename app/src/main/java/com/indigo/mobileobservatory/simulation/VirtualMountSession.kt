package com.indigo.mobileobservatory.simulation

import com.indigo.mobileobservatory.mount.*

/** In-process mount adapter; this session has no physical transport. */
class VirtualMountSession(val observatory: VirtualObservatory) : MountSession {
    override val modelName = "Virtual observatory / SIMULATION"
    override val supportsSync = true
    override val supportsTimeSync = true
    override val coordinateMode = "SIMULATION"
    private var rate = MountSlewRate.RATE_MAX
    private var home = observatory.coordinates()
    override fun open() = readCoordinates()
    override fun readCoordinates() = observatory.coordinates()
    override fun slewTo(targetNow: MountCoordinates) = observatory.slew(targetNow)
    override fun startMove(direction: MountDirection) = observatory.start(direction, rate == MountSlewRate.GUIDE)
    override fun startGuidePulse(direction: MountDirection, durationMs: Int) = observatory.start(direction, true, durationMs)
    override fun stopMove(direction: MountDirection?) = observatory.stop()
    override fun setMoveRate(rate: MountSlewRate) { this.rate = rate }
    override fun setTracking(enabled: Boolean) = observatory.tracking(enabled)
    override fun readSite() = MountSite(observatory.site.latitudeDeg, observatory.site.longitudeDeg)
    override fun setSite(site: MountSite) { require(site == readSite()) { "Virtual site is fixed at 30 N, 120 E" } }
    override fun readTime() = MountTime(System.currentTimeMillis())
    override fun setTime(time: MountTime) {}
    override fun setHomeHere() { home = readCoordinates() }
    override fun goHome() = slewTo(home)
    override fun syncTo(coordinates: MountCoordinates) = observatory.sync(coordinates)
    override fun emergencyStopPayloads(): List<ByteArray> { observatory.stop(); return emptyList() }
    override fun close() = observatory.stop()
}
