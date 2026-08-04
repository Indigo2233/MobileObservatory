package com.indigo.mobileobservatory.mount

import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

data class MountGotoCommands(val ra: String, val dec: String)

/** Pure protocol codec. Transport I/O stays in mount adapters. */
object MountProtocolCodec {
    fun parseLx200Coordinates(raResponse: String, decResponse: String): MountCoordinates =
        MountCoordinates(parseRaHours(raResponse), parseDmsDegrees(decResponse))

    fun parseIoptronCoordinates(response: String): MountCoordinates {
        require(response.length == 20) { "Invalid iOptron coordinate response: $response" }
        val dec = response.substring(0, 9).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid iOptron DEC response: $response")
        val ra = response.substring(9, 18).toLongOrNull()
            ?: throw IllegalArgumentException("Invalid iOptron RA response: $response")
        return MountCoordinates((ra / 5_400_000.0).mod(24.0), dec / 360_000.0)
    }

    fun encodeLx200Goto(coordinates: MountCoordinates): MountGotoCommands {
        require(coordinates.raHours.isFinite()) { "Target RA is invalid." }
        require(coordinates.decDeg.isFinite() && coordinates.decDeg in -90.0..90.0) {
            "Target Dec is invalid."
        }
        return MountGotoCommands(
            ra = ":Sr${formatRa(coordinates.raHours)}#",
            dec = ":Sd${formatDeclination(coordinates.decDeg)}#"
        )
    }

    fun parseDmsDegrees(value: String): Double {
        val cleaned = value.trim().trimEnd('#')
            .replace('\u00b0', '*')
            .replace('\u00ba', '*')
            .replace('d', '*')
        val sign = if (cleaned.startsWith("-")) -1.0 else 1.0
        val parts = cleaned.trimStart('+', '-').split('*', ':')
        require(parts.size >= 2) { "Invalid degree response: $value" }
        val degrees = parts[0].toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid degree response: $value")
        val minutes = parts[1].toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid degree response: $value")
        val seconds = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        require(minutes in 0.0..<60.0 && seconds in 0.0..<60.0) {
            "Invalid degree response: $value"
        }
        return sign * (degrees + minutes / 60.0 + seconds / 3600.0)
    }

    private fun parseRaHours(value: String): Double {
        val parts = value.trim().trimEnd('#').split(':')
        require(parts.size >= 2) { "Invalid RA response: $value" }
        val hours = parts[0].toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid RA response: $value")
        val minutes = parts[1].toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid RA response: $value")
        val seconds = parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        require(minutes in 0.0..<60.0 && seconds in 0.0..<60.0) {
            "Invalid RA response: $value"
        }
        return (hours + minutes / 60.0 + seconds / 3600.0).mod(24.0)
    }

    private fun formatRa(raHours: Double): String {
        val totalSeconds = (raHours.mod(24.0) * 3600.0).roundToInt().mod(24 * 3600)
        return "%02d:%02d:%02d".format(
            Locale.US,
            totalSeconds / 3600,
            totalSeconds % 3600 / 60,
            totalSeconds % 60
        )
    }

    private fun formatDeclination(decDeg: Double): String {
        val sign = if (decDeg < 0.0) "-" else "+"
        val totalArcSeconds = (decDeg.absoluteValue * 3600.0).roundToInt()
        return "%s%02d*%02d:%02d".format(
            Locale.US,
            sign,
            totalArcSeconds / 3600,
            totalArcSeconds % 3600 / 60,
            totalArcSeconds % 60
        )
    }
}
