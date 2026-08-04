package com.indigo.mobileobservatory.astro

import java.time.Instant
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class EquatorialCoordinates(val raDeg: Double, val decDeg: Double)

data class TopocentricCoordinates(val altitudeDeg: Double, val azimuthDeg: Double)

data class ObserverSite(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val elevationMeters: Double = 0.0
)

/**
 * Apparent place reduction between ICRS/J2000 equatorial coordinates and observed horizontal
 * coordinates, mirroring what NINA obtains from SOFA through `Coordinates.Transform`.
 *
 * The chain is bias-precession-nutation, annual and diurnal aberration, Earth rotation and finally
 * atmospheric refraction. Light deflection by the Sun, polar motion and stellar parallax are left
 * out because each stays below 0.01 arcseconds for the fields a polar alignment routine uses.
 */
object CoordinateTransform {
    private const val SPEED_OF_LIGHT_AU_PER_DAY = 173.1446326847
    private const val EARTH_EQUATORIAL_RADIUS_M = 6_378_137.0
    private const val EARTH_FLATTENING = 1.0 / 298.257223563
    private const val EARTH_ANGULAR_VELOCITY_RAD_PER_S = 7.292115e-5
    private const val METERS_PER_AU = 1.495978707e11

    fun j2000ToTopocentric(
        coordinates: EquatorialCoordinates,
        instant: Instant,
        site: ObserverSite,
        refraction: RefractionParameters?
    ): TopocentricCoordinates {
        val context = ReductionContext.of(instant, site)
        val apparent = context.matrix * Vec3.ofSpherical(
            coordinates.raDeg.toRad(),
            coordinates.decDeg.toRad()
        )
        val aberrated = (apparent + context.velocityOverC).unit()

        val hourAngle = context.localApparentSiderealTime - aberrated.longitudeRad()
        val horizontal = equatorialToHorizontal(hourAngle, aberrated.latitudeRad(), site.latitudeDeg)

        val altitude = if (refraction != null) {
            Refraction.refractedAltitudeDeg(horizontal.altitudeDeg, refraction)
        } else {
            horizontal.altitudeDeg
        }
        return TopocentricCoordinates(altitude, horizontal.azimuthDeg)
    }

    fun topocentricToJ2000(
        coordinates: TopocentricCoordinates,
        instant: Instant,
        site: ObserverSite,
        refraction: RefractionParameters?
    ): EquatorialCoordinates {
        val context = ReductionContext.of(instant, site)
        val altitude = if (refraction != null) {
            Refraction.trueAltitudeDeg(coordinates.altitudeDeg, Refraction.constants(refraction))
        } else {
            coordinates.altitudeDeg
        }

        val equatorial = horizontalToEquatorial(altitude, coordinates.azimuthDeg, site.latitudeDeg)
        val rightAscension = context.localApparentSiderealTime - equatorial.hourAngleRad
        val aberrated = Vec3.ofSpherical(rightAscension, equatorial.declinationRad)

        val apparent = (aberrated - context.velocityOverC).unit()
        val icrs = context.matrix.transpose() * apparent

        return EquatorialCoordinates(
            raDeg = normalize360(icrs.longitudeRad().toDeg()),
            decDeg = icrs.latitudeRad().toDeg()
        )
    }

    private data class HourAngleCoordinates(val hourAngleRad: Double, val declinationRad: Double)

    private fun equatorialToHorizontal(
        hourAngleRad: Double,
        declinationRad: Double,
        latitudeDeg: Double
    ): TopocentricCoordinates {
        val latitude = latitudeDeg.toRad()
        val sinAltitude = sin(declinationRad) * sin(latitude) +
            cos(declinationRad) * cos(latitude) * cos(hourAngleRad)
        val altitude = asin(sinAltitude.coerceIn(-1.0, 1.0))
        val azimuth = atan2(
            -sin(hourAngleRad) * cos(declinationRad),
            sin(declinationRad) * cos(latitude) - cos(declinationRad) * sin(latitude) * cos(hourAngleRad)
        )
        return TopocentricCoordinates(altitude.toDeg(), normalize360(azimuth.toDeg()))
    }

    private fun horizontalToEquatorial(
        altitudeDeg: Double,
        azimuthDeg: Double,
        latitudeDeg: Double
    ): HourAngleCoordinates {
        val altitude = altitudeDeg.toRad()
        val azimuth = azimuthDeg.toRad()
        val latitude = latitudeDeg.toRad()

        val sinDeclination = sin(altitude) * sin(latitude) + cos(altitude) * cos(latitude) * cos(azimuth)
        val declination = asin(sinDeclination.coerceIn(-1.0, 1.0))
        val hourAngle = atan2(
            -sin(azimuth) * cos(altitude),
            sin(altitude) * cos(latitude) - cos(altitude) * sin(latitude) * cos(azimuth)
        )
        return HourAngleCoordinates(hourAngle, declination)
    }

    /** Everything that only depends on the instant and the site, shared by both directions. */
    private class ReductionContext(
        val matrix: Mat3,
        val localApparentSiderealTime: Double,
        val velocityOverC: Vec3
    ) {
        companion object {
            fun of(instant: Instant, site: ObserverSite): ReductionContext {
                val t = AstroTime.centuriesTt(instant)
                val nutation = Nutation.compute(t)
                val matrix = Precession.biasPrecessionNutationMatrix(t, nutation)
                val gast = AstroTime.greenwichApparentSiderealTime(instant, nutation)
                val last = normalizeRadians(gast + site.longitudeDeg.toRad())

                val obliquity = Precession.meanObliquityRad(t) + nutation.deltaEpsilonRad
                val velocity = annualVelocityAuPerDay(t, obliquity) + diurnalVelocityAuPerDay(site, last)

                return ReductionContext(matrix, last, velocity * (1.0 / SPEED_OF_LIGHT_AU_PER_DAY))
            }

            /**
             * Barycentric velocity of the Earth from a Keplerian orbit using the Sun's mean elements.
             * Lunar and planetary perturbations shift the aberration by well under 0.02 arcseconds.
             */
            private fun annualVelocityAuPerDay(t: Double, obliquityRad: Double): Vec3 {
                val meanLongitude = 280.46646 + t * (36000.76983 + t * 0.0003032)
                val meanAnomaly = (357.52911 + t * (35999.05029 - t * 0.0001537)).toRad()
                val equationOfCenter =
                    (1.914602 - t * (0.004817 + t * 0.000014)) * sin(meanAnomaly) +
                        (0.019993 - t * 0.000101) * sin(2.0 * meanAnomaly) +
                        0.000289 * sin(3.0 * meanAnomaly)
                val trueLongitude = (meanLongitude + equationOfCenter).toRad()
                val perihelionLongitude = (102.93735 + t * (1.71946 + t * 0.00046)).toRad()
                val eccentricity = 0.016708634 - t * (0.000042037 + t * 0.0000001267)

                // The Earth sits opposite the Sun, so its true longitude is the solar longitude
                // plus 180 degrees, which is what flips the signs of the two leading terms.
                val speed = 0.017202098 / sqrt(1.0 - eccentricity * eccentricity)
                val eclipticX = speed * (sin(trueLongitude) - eccentricity * sin(perihelionLongitude))
                val eclipticY = -speed * (cos(trueLongitude) - eccentricity * cos(perihelionLongitude))

                return Vec3(
                    eclipticX,
                    eclipticY * cos(obliquityRad),
                    eclipticY * sin(obliquityRad)
                )
            }

            /** Velocity of the observer caused by the Earth's rotation, pointing due east. */
            private fun diurnalVelocityAuPerDay(site: ObserverSite, localSiderealTime: Double): Vec3 {
                val latitude = site.latitudeDeg.toRad()
                val eccentricitySquared = EARTH_FLATTENING * (2.0 - EARTH_FLATTENING)
                val primeVertical = EARTH_EQUATORIAL_RADIUS_M /
                    sqrt(1.0 - eccentricitySquared * sin(latitude) * sin(latitude))
                val axialDistance = (primeVertical + site.elevationMeters) * cos(latitude)
                val speedAuPerDay =
                    EARTH_ANGULAR_VELOCITY_RAD_PER_S * axialDistance * 86_400.0 / METERS_PER_AU

                return Vec3(
                    -speedAuPerDay * sin(localSiderealTime),
                    speedAuPerDay * cos(localSiderealTime),
                    0.0
                )
            }
        }
    }
}
