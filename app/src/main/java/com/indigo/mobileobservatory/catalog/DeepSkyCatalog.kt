package com.indigo.mobileobservatory.catalog

/**
 * Offline deep-sky catalog entry. OpenNGC import lands later; shell uses [DemoCatalog].
 */
data class CatalogObject(
    val id: String,
    val name: String,
    val type: String,
    val raHours: Double,
    val decDeg: Double,
    /** Surface brightness proxy / V mag for ranking. */
    val magnitude: Double?,
    val sizeArcmin: Double? = null
)

interface DeepSkyCatalog {
    fun all(): List<CatalogObject>
    fun findById(id: String): CatalogObject?
    fun search(query: String): List<CatalogObject>
}

object DemoCatalog : DeepSkyCatalog {
    private val objects = listOf(
        CatalogObject("M31", "Andromeda Galaxy", "Galaxy", 0.712, 41.269, 3.4, 190.0),
        CatalogObject("M42", "Orion Nebula", "Nebula", 5.588, -5.391, 4.0, 85.0),
        CatalogObject("M45", "Pleiades", "Cluster", 3.791, 24.105, 1.6, 110.0),
        CatalogObject("M13", "Hercules Cluster", "Cluster", 16.695, 36.460, 5.8, 20.0),
        CatalogObject("M51", "Whirlpool Galaxy", "Galaxy", 13.498, 47.195, 8.4, 11.0),
        CatalogObject("M57", "Ring Nebula", "Nebula", 18.893, 33.029, 8.8, 1.4),
        CatalogObject("M81", "Bode's Galaxy", "Galaxy", 9.926, 69.065, 6.9, 26.0),
        CatalogObject("M104", "Sombrero Galaxy", "Galaxy", 12.667, -11.623, 8.0, 9.0),
        CatalogObject("NGC7000", "North America Nebula", "Nebula", 20.971, 44.332, 4.0, 120.0),
        CatalogObject("C20", "North America (Caldwell)", "Nebula", 20.971, 44.332, 4.0, 120.0)
    )

    override fun all(): List<CatalogObject> = objects

    override fun findById(id: String): CatalogObject? =
        objects.firstOrNull { it.id.equals(id, ignoreCase = true) }

    override fun search(query: String): List<CatalogObject> {
        val q = query.trim()
        if (q.isEmpty()) return objects
        return objects.filter {
            it.id.contains(q, ignoreCase = true) ||
                it.name.contains(q, ignoreCase = true) ||
                it.type.contains(q, ignoreCase = true)
        }
    }
}
