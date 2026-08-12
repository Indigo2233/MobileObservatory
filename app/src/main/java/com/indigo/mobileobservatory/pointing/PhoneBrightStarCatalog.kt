package com.indigo.mobileobservatory.pointing

import android.content.Context
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.astro.TopocentricCoordinates
import com.indigo.mobileobservatory.astro.CoordinateTransform
import java.time.Instant

data class PhoneCatalogStar(
    val raDeg: Double,
    val decDeg: Double,
    val magnitude: Double,
    val hip: Int?,
    val name: String?
)

/** Small, attributable bright-star subset used by the phone wide-field matcher. */
class PhoneBrightStarCatalog private constructor(val stars: List<PhoneCatalogStar>) {
    fun around(
        center: TopocentricCoordinates,
        radiusDeg: Double,
        instant: Instant,
        site: ObserverSite,
        maximumMagnitude: Double = 6.0
    ): List<PhoneCatalogStar> = stars.asSequence()
        .filter { it.magnitude <= maximumMagnitude }
        .filter {
            val horizontal = CoordinateTransform.j2000ToTopocentric(
                EquatorialCoordinates(it.raDeg, it.decDeg), instant, site, refraction = null
            )
            angularDistanceDeg(center, horizontal) <= radiusDeg
        }
        .toList()

    companion object {
        const val ASSET_PATH = "catalog/phone_hyg_v41_m6.csv"

        fun load(context: Context): PhoneBrightStarCatalog =
            context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
                fromCsvLines(reader.readLines())
            }

        internal fun fromCsvLines(lines: List<String>): PhoneBrightStarCatalog {
            val stars = lines.asSequence()
                .filterNot { it.startsWith('#') || it.startsWith("ra_deg") || it.isBlank() }
                .mapNotNull { line ->
                    val values = line.split(',', limit = 5)
                    if (values.size < 4) return@mapNotNull null
                    val ra = values[0].toDoubleOrNull() ?: return@mapNotNull null
                    val dec = values[1].toDoubleOrNull() ?: return@mapNotNull null
                    val magnitude = values[2].toDoubleOrNull() ?: return@mapNotNull null
                    PhoneCatalogStar(
                        raDeg = ra,
                        decDeg = dec,
                        magnitude = magnitude,
                        hip = values[3].toIntOrNull(),
                        name = values.getOrNull(4)?.ifBlank { null }
                    )
                }.toList()
            require(stars.isNotEmpty()) { "Phone bright-star catalog is empty" }
            return PhoneBrightStarCatalog(stars)
        }

        internal fun of(stars: List<PhoneCatalogStar>): PhoneBrightStarCatalog {
            require(stars.isNotEmpty()) { "Phone bright-star catalog is empty" }
            return PhoneBrightStarCatalog(stars)
        }

    }

    private fun angularDistanceDeg(a: TopocentricCoordinates, b: TopocentricCoordinates): Double {
        val altA = Math.toRadians(a.altitudeDeg)
        val altB = Math.toRadians(b.altitudeDeg)
        val deltaAz = Math.toRadians(a.azimuthDeg - b.azimuthDeg)
        val cosine = kotlin.math.sin(altA) * kotlin.math.sin(altB) +
            kotlin.math.cos(altA) * kotlin.math.cos(altB) * kotlin.math.cos(deltaAz)
        return Math.toDegrees(kotlin.math.acos(cosine.coerceIn(-1.0, 1.0)))
    }
}
