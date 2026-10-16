package com.indigo.mobileobservatory.pointing

/**
 * Absolute sky pointing source. Phone plate-solve, USB finder, or main camera
 * all implement this so guidance never hard-codes the sensor.
 *
 * Sources retain their physical role; a phone fix is not a telescope fix until a relationship applies.
 */
interface SkyAttitudeSource {
    val id: String
    val displayName: String

    /** Latest absolute fix, or null if none yet. */
    fun latestFix(): SkyAttitudeFix?
}

data class SkyAttitudeFix(
    /** Direction of [sourceId], J2000 RA hours. Never implicitly the telescope axis. */
    val raHours: Double? = null,
    /** J2000 Dec degrees. */
    val decDeg: Double? = null,
    /** Topocentric altitude degrees (preferred for Dob push-to). */
    val altDeg: Double,
    /** Topocentric azimuth degrees, north=0, east=90. */
    val azDeg: Double,
    val timestampMs: Long,
    val uncertaintyDeg: Double? = null,
    val sourceId: String,
    val lastAbsoluteTimestampMs: Long? = null,
    val opticalPathId: String = sourceId,
    val propagated: Boolean = true
)
