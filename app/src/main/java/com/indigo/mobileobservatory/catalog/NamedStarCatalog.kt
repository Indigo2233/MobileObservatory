package com.indigo.mobileobservatory.catalog

/**
 * Named bright stars from the same HYG v4.1 subset the wide-field solver uses.
 * Unnamed HIP rows stay in the matcher catalog only — they would drown search.
 */
object NamedStarCatalog {
    const val ASSET_PATH = "catalog/phone_hyg_v41_m6.csv"

    /** Sirius is mag −1.46 and was dropped by the solver subset (mag ≥ −1). */
    internal val SIRIUS = CatalogObject(
        id = "Sirius",
        name = "Sirius",
        type = "Star",
        raHours = 6.752477,
        decDeg = -16.716116,
        magnitude = -1.46,
        aliases = listOf("Sirius", "HIP 32349", "α CMa", "Alpha Canis Majoris")
    )

    fun parse(lines: List<String>): List<CatalogObject> {
        val stars = ArrayList<CatalogObject>()
        var hasSirius = false
        for (line in lines) {
            if (line.isBlank() || line.startsWith('#') || line.startsWith("ra_deg")) continue
            val obj = parseLine(line) ?: continue
            stars += obj
            if (normalizeCatalogQuery(obj.id) == "sirius") hasSirius = true
        }
        if (!hasSirius) stars.add(0, SIRIUS)
        return stars
    }

    internal fun parseLine(line: String): CatalogObject? {
        val values = line.split(',', limit = 5)
        if (values.size < 5) return null
        val name = values[4].trim()
        if (name.isEmpty()) return null
        val raDeg = values[0].toDoubleOrNull() ?: return null
        val decDeg = values[1].toDoubleOrNull() ?: return null
        val mag = values[2].toDoubleOrNull()
        val hip = values[3].trim().toIntOrNull()
        val aliases = buildList {
            add(name)
            if (hip != null) add("HIP $hip")
        }
        return CatalogObject(
            id = name,
            name = name,
            type = "Star",
            raHours = raDeg / 15.0,
            decDeg = decDeg,
            magnitude = mag,
            aliases = aliases
        )
    }
}
