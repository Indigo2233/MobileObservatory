package com.indigo.mobileobservatory.mount

import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-level SynScan protocol adapter. The mount must be aligned by the
 * hand controller or SynScan App before this adapter connects.
 */
class SkyWatcherAdapter(
    private val exchange: (payload: ByteArray, fixedResponseLength: Int?) -> ByteArray
) {
    var modelName: String = "Sky-Watcher SynScan"
        private set
    var aligned: Boolean = false
        private set
    private var slewRate = 6
    private var homeCoordinates: MountCoordinates? = null

    fun open(): MountCoordinates {
        val echo = command("Kx")
        require(echo.firstOrNull()?.toInt()?.toChar() == 'x') {
            "No Sky-Watcher SynScan response."
        }
        aligned = command("J").firstOrNull()?.toInt() != 0
        require(aligned) {
            "Sky-Watcher mount is not aligned. Complete alignment in the SynScan hand controller or SynScan App first."
        }
        command("m").firstOrNull()?.toInt()?.and(0xff)?.let {
            modelName = MODELS[it] ?: when (it) {
                in 128..143 -> "Sky-Watcher AZ GOTO"
                in 144..159 -> "Sky-Watcher Dob GOTO"
                else -> "Sky-Watcher SynScan ($it)"
            }
        }
        return readCoordinates()
    }

    fun readCoordinates(): MountCoordinates {
        val response = commandText("e")
        val parts = response.split(',')
        require(parts.size == 2) { "Invalid SynScan coordinate response: $response" }
        val raRaw = parts[0].toULong(16)
        val decRaw = parts[1].toULong(16)
        val raJ2000 = raRaw.toDouble() / UINT32_RANGE * 24.0
        var decJ2000 = decRaw.toDouble() / UINT32_RANGE * 360.0
        if (decJ2000 > 180.0) decJ2000 -= 360.0
        return precessJ2000ToNow(MountCoordinates(raJ2000, decJ2000))
    }

    fun slewTo(targetNow: MountCoordinates) {
        val target = precessNowToJ2000(targetNow)
        val raRaw = ((target.raHours.mod(24.0) / 24.0) * UINT32_RANGE)
            .toLong().toULong() and 0xffffffffu
        val dec360 = if (target.decDeg < 0) target.decDeg + 360.0 else target.decDeg
        val decRaw = ((dec360 / 360.0) * UINT32_RANGE)
            .toLong().toULong() and 0xffffffffu
        val response = commandText(
            "r%08X,%08X".format(Locale.US, raRaw.toLong(), decRaw.toLong())
        )
        require(response.isEmpty() || response == "1") {
            "SynScan rejected GOTO: $response"
        }
    }

    fun startMove(direction: MountDirection) {
        val pierWest = runCatching { commandText("p").firstOrNull() == 'W' }
            .getOrDefault(false)
        val synDirection = when (direction) {
            MountDirection.NORTH -> if (pierWest) Direction.NORTH else Direction.SOUTH
            MountDirection.SOUTH -> if (pierWest) Direction.SOUTH else Direction.NORTH
            MountDirection.WEST -> Direction.WEST
            MountDirection.EAST -> Direction.EAST
        }
        sendFixedRate(synDirection, slewRate)
    }

    fun stopMove(direction: MountDirection?) {
        when (direction) {
            MountDirection.NORTH, MountDirection.SOUTH -> {
                sendFixedRate(Direction.NORTH, 0)
                sendFixedRate(Direction.SOUTH, 0)
            }
            MountDirection.EAST, MountDirection.WEST -> {
                sendFixedRate(Direction.EAST, 0)
                sendFixedRate(Direction.WEST, 0)
            }
            null -> Direction.entries.forEach { sendFixedRate(it, 0) }
        }
    }

    fun setMoveRate(rate: MountSlewRate) {
        slewRate = rate.skyWatcherRate
    }

    fun setTracking(enabled: Boolean) {
        val mode = if (enabled) 2 else 0 // 2 = sidereal EQ tracking.
        command(byteArrayOf('T'.code.toByte(), mode.toByte()))
    }

    fun readSite(): MountSite {
        val data = command("w")
        require(data.size >= 8) { "Invalid SynScan location response." }
        val latitude = dms(data[0], data[1], data[2], data[3].toInt() != 0)
        var longitude = dms(data[4], data[5], data[6], false)
        if (data[7].toInt() != 0) longitude = -longitude
        return MountSite(latitude, longitude)
    }

    fun setSite(site: MountSite) {
        val lat = toDms(site.latitudeDeg)
        val lon = toDms(site.longitudeDeg)
        command(
            byteArrayOf(
                'W'.code.toByte(),
                lat.degrees.toByte(), lat.minutes.toByte(), lat.seconds.toByte(),
                if (site.latitudeDeg < 0) 1 else 0,
                lon.degrees.toByte(), lon.minutes.toByte(), lon.seconds.toByte(),
                if (site.longitudeDeg < 0) 1 else 0
            )
        )
    }

    fun setHomeHere() {
        homeCoordinates = readCoordinates()
    }

    fun goHome() {
        val home = homeCoordinates
        if (home != null) {
            slewTo(home)
            return
        }
        val site = readSite()
        val pole = if (site.latitudeDeg >= 0) 90.0 else -90.0
        slewTo(MountCoordinates(localSiderealHours(site.longitudeDeg), pole))
    }

    private fun sendFixedRate(direction: Direction, rate: Int) {
        val axis = if (direction == Direction.NORTH || direction == Direction.SOUTH) 17 else 16
        val positive = direction == Direction.NORTH || direction == Direction.WEST
        val command = byteArrayOf(
            'P'.code.toByte(),
            2,
            axis.toByte(),
            if (positive) 36 else 37,
            rate.coerceIn(0, 9).toByte(),
            0,
            0,
            0
        )
        exchange(command, 8)
    }

    private fun command(text: String): ByteArray =
        exchange(text.toByteArray(Charsets.US_ASCII), null).trimTerminator()

    private fun command(payload: ByteArray): ByteArray =
        exchange(payload, null).trimTerminator()

    private fun commandText(text: String): String =
        command(text).toString(Charsets.US_ASCII).trim()

    private enum class Direction { NORTH, SOUTH, EAST, WEST }

    private data class Dms(val degrees: Int, val minutes: Int, val seconds: Int)

    private fun toDms(value: Double): Dms {
        val absolute = kotlin.math.abs(value)
        val degrees = absolute.toInt()
        val minutesValue = (absolute - degrees) * 60.0
        val minutes = minutesValue.toInt()
        val seconds = ((minutesValue - minutes) * 60.0).toInt().coerceIn(0, 59)
        return Dms(degrees, minutes, seconds)
    }

    private fun dms(degrees: Byte, minutes: Byte, seconds: Byte, negative: Boolean): Double {
        val value = (degrees.toInt() and 0xff) +
            (minutes.toInt() and 0xff) / 60.0 +
            (seconds.toInt() and 0xff) / 3600.0
        return if (negative) -value else value
    }

    private fun localSiderealHours(longitudeDeg: Double): Double {
        val jd = System.currentTimeMillis() / 86400000.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        val gmst = 280.46061837 + 360.98564736629 * (jd - 2451545.0) +
            0.000387933 * t * t - t * t * t / 38710000.0
        return ((gmst + longitudeDeg) / 15.0).mod(24.0)
    }

    private fun precessJ2000ToNow(value: MountCoordinates): MountCoordinates {
        val t = julianCenturies()
        val zeta = Math.toRadians(
            (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) / 3600.0
        )
        val z = Math.toRadians(
            (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) / 3600.0
        )
        val theta = Math.toRadians(
            (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) / 3600.0
        )
        val ra = Math.toRadians(value.raHours * 15.0)
        val dec = Math.toRadians(value.decDeg)
        val a = cos(dec) * sin(ra + zeta)
        val b = cos(theta) * cos(dec) * cos(ra + zeta) - sin(theta) * sin(dec)
        val c = sin(theta) * cos(dec) * cos(ra + zeta) + cos(theta) * sin(dec)
        return MountCoordinates(
            Math.toDegrees(atan2(a, b) + z).div(15.0).mod(24.0),
            Math.toDegrees(asin(c.coerceIn(-1.0, 1.0)))
        )
    }

    private fun precessNowToJ2000(value: MountCoordinates): MountCoordinates {
        var estimate = value
        repeat(5) {
            val projected = precessJ2000ToNow(estimate)
            var raError = projected.raHours - value.raHours
            if (raError > 12) raError -= 24.0
            if (raError < -12) raError += 24.0
            estimate = MountCoordinates(
                (estimate.raHours - raError).mod(24.0),
                (estimate.decDeg - (projected.decDeg - value.decDeg)).coerceIn(-90.0, 90.0)
            )
        }
        return estimate
    }

    private fun julianCenturies(): Double {
        val jd = System.currentTimeMillis() / 86400000.0 + 2440587.5
        return (jd - 2451545.0) / 36525.0
    }

    companion object {
        private const val UINT32_RANGE = 4294967296.0
        private val MODELS = mapOf(
            0 to "EQ6 GOTO", 1 to "HEQ5 GOTO", 2 to "EQ5 GOTO",
            3 to "EQ3 GOTO", 4 to "EQ8 GOTO", 5 to "AZ-EQ6 GOTO",
            6 to "AZ-EQ5 GOTO", 160 to "AllView GOTO",
            161 to "Virtuoso Alt/Az", 165 to "AZ-GTi GOTO"
        )
    }
}

private fun ByteArray.trimTerminator(): ByteArray {
    var end = size
    while (end > 0 && (this[end - 1] == '#'.code.toByte() ||
            this[end - 1] == '\r'.code.toByte() ||
            this[end - 1] == '\n'.code.toByte())) {
        end--
    }
    return copyOf(end)
}
