package com.indigo.mobileobservatory.astro

import java.time.Instant

/**
 * Time scale conversions required by the apparent-place reduction.
 *
 * UT1 is approximated by UTC. |DUT1| stays below 0.9 s, which is a rotation of at most 13.5" about
 * the celestial pole. Because the mount axis sits within roughly a degree of the pole, that rotation
 * displaces the derived axis by less than 0.3", far below the precision this pipeline targets.
 */
object AstroTime {
    const val JD_J2000 = 2451545.0
    const val DAYS_PER_JULIAN_CENTURY = 36525.0
    private const val TT_MINUS_TAI_SECONDS = 32.184

    // Julian date of each leap second introduction paired with the resulting TAI-UTC value.
    private val LEAP_SECONDS = listOf(
        2447161.5 to 22.0, // 1988-01-01
        2447892.5 to 23.0, // 1990-01-01
        2448257.5 to 24.0, // 1991-01-01
        2448804.5 to 25.0, // 1992-07-01
        2449169.5 to 26.0, // 1993-07-01
        2449534.5 to 27.0, // 1994-07-01
        2450083.5 to 28.0, // 1996-01-01
        2450630.5 to 29.0, // 1997-07-01
        2451179.5 to 30.0, // 1999-01-01
        2453736.5 to 31.0, // 2006-01-01
        2454832.5 to 32.0, // 2009-01-01
        2456109.5 to 33.0, // 2012-07-01
        2457204.5 to 34.0, // 2015-07-01
        2457754.5 to 35.0  // 2017-01-01
    )

    fun julianDateUtc(instant: Instant): Double =
        instant.toEpochMilli() / 86_400_000.0 + 2_440_587.5

    fun taiMinusUtcSeconds(julianDateUtc: Double): Double {
        var value = 21.0
        for ((jd, offset) in LEAP_SECONDS) {
            if (julianDateUtc >= jd) value = offset else break
        }
        return value
    }

    fun julianDateTt(instant: Instant): Double {
        val jdUtc = julianDateUtc(instant)
        return jdUtc + (taiMinusUtcSeconds(jdUtc) + TT_MINUS_TAI_SECONDS) / 86_400.0
    }

    /** Julian centuries of TT since J2000.0. */
    fun centuriesTt(instant: Instant): Double =
        (julianDateTt(instant) - JD_J2000) / DAYS_PER_JULIAN_CENTURY

    /** Earth rotation angle in radians (IAU 2000). */
    fun earthRotationAngle(instant: Instant): Double {
        val tu = julianDateUtc(instant) - JD_J2000
        val fraction = tu - Math.floor(tu)
        return normalizeRadians(
            2.0 * Math.PI * (fraction + 0.7790572732640 + 0.00273781191135448 * tu)
        )
    }

    /** Greenwich mean sidereal time in radians (IAU 2006). */
    fun greenwichMeanSiderealTime(instant: Instant): Double {
        val t = centuriesTt(instant)
        val polynomialArcsec = 0.014506 +
            t * (4612.156534 +
                t * (1.3915817 +
                    t * (-0.00000044 +
                        t * (-0.000029956 +
                            t * -0.0000000368))))
        return normalizeRadians(earthRotationAngle(instant) + polynomialArcsec * ARCSEC_TO_RAD)
    }

    /** Greenwich apparent sidereal time in radians. */
    fun greenwichApparentSiderealTime(instant: Instant, nutation: NutationAngles): Double {
        return normalizeRadians(
            greenwichMeanSiderealTime(instant) + equationOfTheEquinoxes(instant, nutation)
        )
    }

    /**
     * Equation of the equinoxes in radians. The two complementary terms contribute about 3 mas and
     * are kept so that the sidereal time stays consistent with the IAU 2000 resolutions.
     */
    fun equationOfTheEquinoxes(instant: Instant, nutation: NutationAngles): Double {
        val t = centuriesTt(instant)
        val omega = Nutation.moonAscendingNodeRad(t)
        val meanObliquity = Precession.meanObliquityRad(t)
        return nutation.deltaPsiRad * Math.cos(meanObliquity) +
            0.00264096 * ARCSEC_TO_RAD * Math.sin(omega) +
            0.00006352 * ARCSEC_TO_RAD * Math.sin(2.0 * omega)
    }
}
