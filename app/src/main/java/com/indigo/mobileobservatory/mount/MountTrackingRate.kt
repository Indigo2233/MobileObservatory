package com.indigo.mobileobservatory.mount

/**
 * Sidereal-family tracking rates. [OFF] stops tracking.
 * Manual slew rates ([MountSlewRate]) are separate and do not change this.
 */
enum class MountTrackingRate {
    OFF,
    SIDEREAL,
    SOLAR,
    LUNAR;

    /** OnStep / LX200 select command. [OFF] uses `:Td#` instead. */
    val lx200SelectCommand: String
        get() = when (this) {
            OFF -> ":Td#"
            SIDEREAL -> ":TQ#"
            SOLAR -> ":TS#"
            LUNAR -> ":TL#"
        }

    /** Fraction of the sidereal RA rate. */
    val siderealFraction: Double
        get() = when (this) {
            OFF -> 0.0
            SIDEREAL -> 1.0
            SOLAR -> 86164.0905 / 86400.0
            LUNAR -> 0.9661368
        }

    val tracks: Boolean get() = this != OFF
}
