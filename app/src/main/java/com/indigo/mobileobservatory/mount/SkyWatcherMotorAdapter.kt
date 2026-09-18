package com.indigo.mobileobservatory.mount

import java.time.Instant

/**
 * Direct motor-controller session for Sky-Watcher / SynScan Wi-Fi modules.
 * Talks UDP 11880 (`:e1` / `:j1` …), not the SynScan App high-level command set.
 */
internal class SkyWatcherMotorAdapter(
    private val exchange: (payload: ByteArray) -> ByteArray,
    private var site: MountSite = MountSite(0.0, 0.0),
    private val mode: SkyWatcherMountMode = SkyWatcherMountMode.EQUATORIAL,
    private val now: () -> Instant = { Instant.now() }
) : SkyWatcherMountSession {
    override var modelName: String = "Sky-Watcher Wi-Fi"
        private set
    override val supportsSync: Boolean = true

    private lateinit var geometry: SkyWatcherMountGeometry
    private var slewRate = MountSlewRate.DEFAULT
    private var trackingEnabled = false
    private var pierSide = SkyWatcherPierSide.WEST
    private var homeCoordinates: MountCoordinates? = null
    private val axisMotionSign = mutableMapOf<SkyWatcherAxis, Int>()

    override fun open(): MountCoordinates {
        val firmware = queryLong('e', SkyWatcherAxis.RA)
        val raSteps = queryLong('a', SkyWatcherAxis.RA)
        val decSteps = queryLong('a', SkyWatcherAxis.DEC)
        require(raSteps > 0 && decSteps > 0) {
            "Sky-Watcher motor board did not report axis resolution."
        }
        geometry = SkyWatcherMountGeometry(
            ra = SkyWatcherAxisGeometry(
                totalSteps = raSteps,
                timerFreq = queryLong('b', SkyWatcherAxis.RA).coerceAtLeast(1),
                highSpeedRatio = runCatching { queryLong('g', SkyWatcherAxis.RA) }.getOrDefault(16).coerceAtLeast(1)
            ),
            dec = SkyWatcherAxisGeometry(
                totalSteps = decSteps,
                timerFreq = queryLong('b', SkyWatcherAxis.DEC).coerceAtLeast(1),
                highSpeedRatio = runCatching { queryLong('g', SkyWatcherAxis.DEC) }.getOrDefault(16).coerceAtLeast(1)
            )
        )
        initializeAxis(SkyWatcherAxis.RA)
        initializeAxis(SkyWatcherAxis.DEC)
        modelName = modelNameFor(firmware)
        return readCoordinates()
    }

    override fun readCoordinates(): MountCoordinates {
        val raSteps = queryPosition(SkyWatcherAxis.RA)
        val decSteps = queryPosition(SkyWatcherAxis.DEC)
        val lst = SkyWatcherEquatorialMath.localSiderealHours(site.longitudeDeg, now())
        return if (mode == SkyWatcherMountMode.ALTAZ) {
            val altAz = SkyWatcherEquatorialMath.encoderToAltAz(geometry, raSteps, decSteps)
            val (hourAngle, decDeg) = SkyWatcherEquatorialMath.horizontalToEq(altAz, site.latitudeDeg)
            MountCoordinates(
                raHours = SkyWatcherEquatorialMath.raHours(lst, hourAngle),
                decDeg = decDeg.coerceIn(-90.0, 90.0)
            )
        } else {
            val eq = SkyWatcherEquatorialMath.encoderToEq(
                geometry = geometry,
                raSteps = raSteps,
                decSteps = decSteps,
                southernHemisphere = site.latitudeDeg < 0.0
            )
            pierSide = eq.pierSide
            MountCoordinates(
                raHours = SkyWatcherEquatorialMath.raHours(lst, eq.hourAngleHours),
                decDeg = eq.decDeg.coerceIn(-90.0, 90.0)
            )
        }
    }

    override fun slewTo(targetNow: MountCoordinates) {
        val target = encoderTargetFor(targetNow)
        trackingEnabled = false
        gotoAxis(SkyWatcherAxis.RA, target.raSteps)
        gotoAxis(SkyWatcherAxis.DEC, target.decSteps)
    }

    override fun startMove(direction: MountDirection) {
        val synDirection = if (mode == SkyWatcherMountMode.ALTAZ) {
            direction
        } else {
            when (direction) {
                MountDirection.NORTH -> if (pierSide == SkyWatcherPierSide.WEST) {
                    MountDirection.SOUTH
                } else {
                    MountDirection.NORTH
                }
                MountDirection.SOUTH -> if (pierSide == SkyWatcherPierSide.WEST) {
                    MountDirection.NORTH
                } else {
                    MountDirection.SOUTH
                }
                MountDirection.WEST, MountDirection.EAST -> direction
            }
        }
        val axis = if (synDirection == MountDirection.NORTH || synDirection == MountDirection.SOUTH) {
            SkyWatcherAxis.DEC
        } else {
            SkyWatcherAxis.RA
        }
        val positive = synDirection == MountDirection.NORTH || synDirection == MountDirection.WEST
        val rate = slewRateArcsecPerSec()
        slewAxisAtRate(axis, if (positive) rate else -rate)
    }

    override fun stopMove(direction: MountDirection?) {
        when (direction) {
            MountDirection.NORTH, MountDirection.SOUTH -> stopAxis(SkyWatcherAxis.DEC)
            MountDirection.EAST, MountDirection.WEST -> stopAxis(SkyWatcherAxis.RA)
            null -> {
                stopAxis(SkyWatcherAxis.RA)
                stopAxis(SkyWatcherAxis.DEC)
            }
        }
        if (trackingEnabled) {
            if (mode == SkyWatcherMountMode.ALTAZ ||
                direction == null ||
                direction == MountDirection.EAST ||
                direction == MountDirection.WEST
            ) {
                applyTracking(true)
            }
        }
    }

    override fun setMoveRate(rate: MountSlewRate) {
        slewRate = rate
    }

    override fun setTracking(enabled: Boolean) {
        trackingEnabled = enabled
        applyTracking(enabled)
    }

    override fun readSite(): MountSite = site

    override fun setSite(site: MountSite) {
        this.site = site
    }

    override fun setHomeHere() {
        homeCoordinates = readCoordinates()
    }

    override fun goHome() {
        val home = homeCoordinates
        if (home != null) {
            slewTo(home)
            return
        }
        trackingEnabled = false
        if (mode == SkyWatcherMountMode.ALTAZ) {
            gotoAxis(SkyWatcherAxis.RA, SkyWatcherMotorCodec.POSITION_OFFSET)
            gotoAxis(SkyWatcherAxis.DEC, SkyWatcherMotorCodec.POSITION_OFFSET)
        } else {
            gotoAxis(SkyWatcherAxis.RA, geometry.raHomePosition)
            gotoAxis(SkyWatcherAxis.DEC, geometry.decHomePosition)
        }
    }

    override fun syncTo(coordinates: MountCoordinates) {
        val target = encoderTargetFor(coordinates)
        command('E', SkyWatcherAxis.RA, target.raSteps)
        command('E', SkyWatcherAxis.DEC, target.decSteps)
    }

    override fun refreshTracking() {
        if (trackingEnabled && mode == SkyWatcherMountMode.ALTAZ) {
            applyAltazTracking(restart = false)
        }
    }

    override fun emergencyStopPayloads(): List<ByteArray> = listOf(
        SkyWatcherMotorCodec.instantStop(SkyWatcherAxis.RA),
        SkyWatcherMotorCodec.instantStop(SkyWatcherAxis.DEC)
    )

    private fun applyTracking(enabled: Boolean) {
        if (!enabled) {
            stopAxis(SkyWatcherAxis.RA)
            stopAxis(SkyWatcherAxis.DEC)
            axisMotionSign.clear()
            return
        }
        if (mode == SkyWatcherMountMode.ALTAZ) {
            applyAltazTracking(restart = true)
            return
        }
        var rate = SkyWatcherEquatorialMath.SIDEREAL_ARCSEC_PER_SEC
        if (site.latitudeDeg < 0.0) rate = -rate
        slewAxisAtRate(SkyWatcherAxis.RA, rate)
    }

    private fun applyAltazTracking(restart: Boolean) {
        val coordinates = readCoordinates()
        val (azimuthRate, altitudeRate) = SkyWatcherEquatorialMath.altazTrackingRatesArcsecPerSec(
            raHours = coordinates.raHours,
            decDeg = coordinates.decDeg,
            latitudeDeg = site.latitudeDeg,
            longitudeDeg = site.longitudeDeg,
            instant = now()
        )
        slewAxisAtRate(SkyWatcherAxis.RA, azimuthRate, restart = restart)
        slewAxisAtRate(SkyWatcherAxis.DEC, altitudeRate, restart = restart)
    }

    private fun encoderTargetFor(targetNow: MountCoordinates): SkyWatcherEncoderTarget {
        val lst = SkyWatcherEquatorialMath.localSiderealHours(site.longitudeDeg, now())
        val hourAngle = SkyWatcherEquatorialMath.hourAngleHours(lst, targetNow.raHours)
        return if (mode == SkyWatcherMountMode.ALTAZ) {
            SkyWatcherEquatorialMath.altAzToEncoder(
                geometry,
                SkyWatcherEquatorialMath.eqToHorizontal(hourAngle, targetNow.decDeg, site.latitudeDeg)
            )
        } else {
            SkyWatcherEquatorialMath.eqToEncoder(
                geometry = geometry,
                hourAngleHours = hourAngle,
                decDeg = targetNow.decDeg,
                southernHemisphere = site.latitudeDeg < 0.0
            )
        }
    }

    private fun slewAxisAtRate(axis: SkyWatcherAxis, arcsecPerSec: Double, restart: Boolean = true) {
        val sign = when {
            arcsecPerSec > 1e-6 -> 1
            arcsecPerSec < -1e-6 -> -1
            else -> 0
        }
        if (sign == 0) {
            stopAxis(axis)
            return
        }
        val geometryForAxis = if (axis == SkyWatcherAxis.RA) geometry.ra else geometry.dec
        val (turbo, code) = SkyWatcherEquatorialMath.rateCode(arcsecPerSec, geometryForAxis)
        val canUpdateInPlace = !restart && axisMotionSign[axis] == sign
        if (canUpdateInPlace) {
            command('I', axis, code)
            return
        }
        stopAndWait(axis)
        val modeChar = if (turbo) '3' else '1'
        val direction = if (sign < 0) '1' else '0'
        SkyWatcherMotorCodec.parseReply(
            exchange(SkyWatcherMotorCodec.command('G', axis, modeChar, direction))
        )
        command('I', axis, code)
        command('J', axis)
        axisMotionSign[axis] = sign
    }

    private fun gotoAxis(axis: SkyWatcherAxis, targetSteps: Long) {
        val current = queryPosition(axis)
        val delta = targetSteps - current
        if (delta == 0L) return
        stopAndWait(axis)
        val direction = if (delta < 0) '1' else '0'
        val distance = kotlin.math.abs(delta)
        val slowdown = if (distance > 80_000L) distance - 80_000L else distance / 2
        exchange(SkyWatcherMotorCodec.command('G', axis, '0', direction)).let {
            SkyWatcherMotorCodec.parseReply(it)
        }
        command('H', axis, distance)
        command('M', axis, slowdown.coerceAtLeast(1L))
        command('J', axis)
        axisMotionSign.remove(axis)
    }

    private fun initializeAxis(axis: SkyWatcherAxis) {
        val status = queryStatus(axis)
        if (SkyWatcherMotorCodec.needsInitialize(status)) {
            command('F', axis)
        }
    }

    private fun stopAxis(axis: SkyWatcherAxis) {
        command('K', axis)
        stopAndWait(axis)
        axisMotionSign.remove(axis)
    }

    private fun stopAndWait(axis: SkyWatcherAxis) {
        if (SkyWatcherMotorCodec.isAxisActive(queryStatus(axis))) {
            command('K', axis)
        }
        val deadline = System.currentTimeMillis() + 4_000L
        while (System.currentTimeMillis() < deadline) {
            if (!SkyWatcherMotorCodec.isAxisActive(queryStatus(axis))) return
            Thread.sleep(50)
        }
    }

    private fun queryPosition(axis: SkyWatcherAxis): Long = queryLong('j', axis)

    private fun queryStatus(axis: SkyWatcherAxis): Long {
        val reply = SkyWatcherMotorCodec.parseReply(exchange(SkyWatcherMotorCodec.command('f', axis)))
        return SkyWatcherMotorCodec.hexToInt(reply)
    }

    private fun queryLong(cmd: Char, axis: SkyWatcherAxis): Long {
        val reply = SkyWatcherMotorCodec.parseReply(exchange(SkyWatcherMotorCodec.command(cmd, axis)))
        return SkyWatcherMotorCodec.hexToInt(reply)
    }

    private fun command(cmd: Char, axis: SkyWatcherAxis, data: Long? = null) {
        SkyWatcherMotorCodec.parseReply(exchange(SkyWatcherMotorCodec.command(cmd, axis, data)))
    }

    private fun slewRateArcsecPerSec(): Double {
        val sidereal = SkyWatcherEquatorialMath.SIDEREAL_ARCSEC_PER_SEC
        return when (slewRate) {
            MountSlewRate.RATE_0_25X -> sidereal * 0.25
            MountSlewRate.RATE_0_5X -> sidereal * 0.5
            MountSlewRate.RATE_1X -> sidereal
            MountSlewRate.RATE_2X -> sidereal * 2.0
            MountSlewRate.RATE_4X -> sidereal * 4.0
            MountSlewRate.RATE_8X -> sidereal * 8.0
            MountSlewRate.RATE_16X -> sidereal * 16.0
            MountSlewRate.RATE_24X -> sidereal * 24.0
            MountSlewRate.RATE_HALF -> sidereal * 400.0
            MountSlewRate.RATE_MAX -> sidereal * 800.0
        }
    }

    private fun modelNameFor(firmware: Long): String {
        val model = (firmware shr 16).toInt() and 0xff
        return MODELS[model] ?: "Sky-Watcher Wi-Fi (${firmware.toString(16)})"
    }

    companion object {
        private val MODELS = mapOf(
            0 to "EQ6 GOTO", 1 to "HEQ5 GOTO", 2 to "EQ5 GOTO",
            3 to "EQ3 GOTO", 4 to "EQ8 GOTO", 5 to "AZ-EQ6 GOTO",
            6 to "AZ-EQ5 GOTO", 160 to "AllView GOTO",
            161 to "Virtuoso Alt/Az", 165 to "AZ-GTi GOTO"
        )
    }
}
