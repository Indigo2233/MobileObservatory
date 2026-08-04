package com.indigo.mobileobservatory.pointing

/**
 * Absolute sky pointing source. Phone plate-solve, USB finder, or main camera
 * all implement this so guidance never hard-codes the sensor.
 *
 * M0–M2 wire real sources later; mock drives UI shells now.
 */
interface SkyAttitudeSource {
    val id: String
    val displayName: String
    val capabilityTier: CapabilityTier

    /** Latest absolute fix, or null if none yet. */
    fun latestFix(): SkyAttitudeFix?
}

enum class CapabilityTier {
    L1_PHONE,
    L2_PHONE_CALIBRATED,
    L3_FINDER,
    L4_MAIN_CAMERA,
    L5_OBSERVATORY
}

data class SkyAttitudeFix(
    /** Telescope optical axis after applying that source's calibration, J2000 RA hours. */
    val raHours: Double? = null,
    /** J2000 Dec degrees. */
    val decDeg: Double? = null,
    /** Topocentric altitude degrees (preferred for Dob push-to). */
    val altDeg: Double,
    /** Topocentric azimuth degrees, north=0, east=90. */
    val azDeg: Double,
    val timestampMs: Long,
    val uncertaintyDeg: Double? = null,
    val sourceId: String
)

/** Demo source: UI sliders write [setFix]; no camera. */
class MockSkyAttitudeSource(
    override val id: String = "mock",
    override val displayName: String = "Mock (demo)",
    override val capabilityTier: CapabilityTier = CapabilityTier.L1_PHONE
) : SkyAttitudeSource {
    @Volatile
    private var fix: SkyAttitudeFix? = null

    fun setFix(altDeg: Double, azDeg: Double, uncertaintyDeg: Double = 0.25) {
        fix = SkyAttitudeFix(
            altDeg = altDeg,
            azDeg = azDeg,
            timestampMs = System.currentTimeMillis(),
            uncertaintyDeg = uncertaintyDeg,
            sourceId = id
        )
    }

    override fun latestFix(): SkyAttitudeFix? = fix
}
