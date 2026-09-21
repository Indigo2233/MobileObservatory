package com.indigo.mobileobservatory.astro

import java.time.Instant

/**
 * Convert between ICRS/J2000 equatorial coordinates and the true equator and
 * equinox of date (JNOW). Mount protocols and star-map taps use JNOW; catalogs
 * and ASTAP WCS use J2000/ICRF. Precession is about 20′ in 2026, so mixing the
 * two frames makes a 6′ precision-GOTO stop condition unreachable.
 */
object EquatorialEpoch {
    fun isJ2000(frame: String): Boolean {
        val normalized = frame.trim().uppercase()
        return normalized == "J2000" || normalized == "ICRF"
    }

    fun j2000ToJnow(
        coordinates: EquatorialCoordinates,
        instant: Instant = Instant.now()
    ): EquatorialCoordinates = rotate(coordinates, transpose = false, instant)

    fun jnowToJ2000(
        coordinates: EquatorialCoordinates,
        instant: Instant = Instant.now()
    ): EquatorialCoordinates = rotate(coordinates, transpose = true, instant)

    /**
     * RA hours / Dec degrees expressed in the mount frame (JNOW). Catalog and
     * ASTAP values tagged J2000/ICRF are precessed; JNOW values pass through.
     */
    fun toJnowHours(
        raHours: Double,
        decDeg: Double,
        frame: String,
        instant: Instant = Instant.now()
    ): Pair<Double, Double> {
        val stored = EquatorialCoordinates(
            raDeg = raHours.mod(24.0) * 15.0,
            decDeg = decDeg.coerceIn(-90.0, 90.0)
        )
        val jnow = if (isJ2000(frame)) j2000ToJnow(stored, instant) else stored
        return (jnow.raDeg / 15.0).mod(24.0) to jnow.decDeg
    }

    fun j2000DegToJnowHours(
        raDeg: Double,
        decDeg: Double,
        instant: Instant = Instant.now()
    ): Pair<Double, Double> {
        val jnow = j2000ToJnow(EquatorialCoordinates(raDeg, decDeg), instant)
        return (jnow.raDeg / 15.0).mod(24.0) to jnow.decDeg
    }

    private fun rotate(
        coordinates: EquatorialCoordinates,
        transpose: Boolean,
        instant: Instant
    ): EquatorialCoordinates {
        val t = AstroTime.centuriesTt(instant)
        val matrix = Precession.biasPrecessionNutationMatrix(t, Nutation.compute(t))
        val rotated = (if (transpose) matrix.transpose() else matrix) * Vec3.ofSpherical(
            coordinates.raDeg.toRad(),
            coordinates.decDeg.toRad()
        )
        return EquatorialCoordinates(
            raDeg = normalize360(rotated.longitudeRad().toDeg()),
            decDeg = rotated.latitudeRad().toDeg()
        )
    }
}
