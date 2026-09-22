package com.indigo.mobileobservatory.catalog

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import java.time.Instant

enum class CatalogBrowseFilter {
    ALL,
    STAR,
    CLUSTER,
    NEBULA,
    GALAXY;

    fun matches(obj: CatalogObject): Boolean = when (this) {
        ALL -> true
        STAR -> obj.type.contains("Star", ignoreCase = true)
        CLUSTER -> obj.type.contains("Cluster", ignoreCase = true) ||
            obj.type.contains("Association", ignoreCase = true)
        NEBULA -> obj.type.contains("Nebula", ignoreCase = true) ||
            obj.type.contains("HII", ignoreCase = true) ||
            obj.type.contains("Remnant", ignoreCase = true)
        GALAXY -> obj.type.contains("Galaxy", ignoreCase = true)
    }
}

/**
 * Empty-search ranking for the target library / star-map catalog. Prefers
 * showpiece visual objects; with a site, those currently above ~15° altitude.
 */
object VisibilityRanker {
    const val MIN_ALTITUDE_DEG = 15.0
    private const val FAINTEST = 99.0

    internal val SHOWPIECE_IDS = listOf(
        "M 42", "M 45", "M 31", "M 13", "M 51", "M 57", "M 27", "M 8", "M 17",
        "M 16", "M 20", "M 11", "M 6", "M 7", "M 22", "M 81", "M 82", "M 44",
        "M 33", "M 1", "M 104", "NGC 7000", "B 33", "C 14", "NGC 7293",
        "NGC 869", "NGC 2237", "NGC 6960", "Sirius", "Vega", "Polaris",
        "Capella", "Rigel", "Betelgeuse", "Altair", "Deneb", "Arcturus"
    )

    fun suggest(
        objects: List<CatalogObject>,
        site: ObserverSite?,
        instant: Instant = Instant.now(),
        limit: Int = 40,
        filter: CatalogBrowseFilter = CatalogBrowseFilter.ALL
    ): List<CatalogObject> {
        val pool = objects.filter { isVisualCandidate(it) && filter.matches(it) }
        if (pool.isEmpty()) return emptyList()
        if (site == null) {
            val byId = pool.associateBy { normalizeCatalogQuery(it.id) }
            val showpiece = SHOWPIECE_IDS.mapNotNull { byId[normalizeCatalogQuery(it)] }
                .filter { filter.matches(it) }
            if (showpiece.size >= limit) return showpiece.take(limit)
            val rest = pool.sortedWith(compareBy({ it.magnitude ?: FAINTEST }, { it.id }))
                .filter { candidate -> showpiece.none { it.id == candidate.id } }
            return (showpiece + rest).take(limit)
        }
        return pool.mapNotNull { obj ->
            val alt = CoordinateTransform.j2000ToTopocentric(
                EquatorialCoordinates(obj.raHours * 15.0, obj.decDeg),
                instant,
                site,
                refraction = null
            ).altitudeDeg
            if (alt < MIN_ALTITUDE_DEG) return@mapNotNull null
            Triple(score(obj, alt), -alt, obj)
        }
            .sortedWith(compareBy({ it.first }, { it.second }, { it.third.id }))
            .take(limit)
            .map { it.third }
    }

    internal fun isVisualCandidate(obj: CatalogObject): Boolean {
        if (obj.id.startsWith("M ")) return true
        val mag = obj.magnitude ?: FAINTEST
        val size = obj.sizeArcmin ?: 0.0
        return when {
            obj.type.equals("Star", ignoreCase = true) ||
                obj.type.equals("Double Star", ignoreCase = true) -> mag <= 2.5
            obj.type.contains("Cluster") ||
                obj.type.contains("Nebula") ||
                obj.type.contains("HII") ||
                obj.type.contains("Association") ||
                obj.type.contains("Remnant") -> mag <= 11.0 || size >= 8.0
            obj.type.contains("Galaxy") -> mag <= 9.5 && size >= 8.0
            else -> mag <= 8.0
        }
    }

    private fun score(obj: CatalogObject, altitudeDeg: Double): Double {
        val mag = obj.magnitude ?: 8.0
        val size = obj.sizeArcmin ?: 1.0
        val typeBonus = when {
            obj.type.contains("Nebula") || obj.type.contains("Cluster") -> 0.0
            obj.type == "Star" -> 1.0
            obj.type.contains("Galaxy") -> 2.0
            else -> 1.5
        }
        val showpieceBonus = if (SHOWPIECE_IDS.any { normalizeCatalogQuery(it) == normalizeCatalogQuery(obj.id) }) {
            -3.0
        } else {
            0.0
        }
        val zenithPenalty = if (altitudeDeg > 80.0) 1.0 else 0.0
        return mag + typeBonus + showpieceBonus + zenithPenalty - (size.coerceAtMost(60.0) / 40.0) -
            (altitudeDeg / 90.0)
    }
}
