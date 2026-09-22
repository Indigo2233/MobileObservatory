package com.indigo.mobileobservatory.catalog

import android.content.Context
import com.indigo.mobileobservatory.astro.ObserverSite
import java.io.InputStream
import java.time.Instant

/**
 * Offline catalog shared by star-map search and the push-to target library.
 *
 * Base table is OpenNGC (`deepsky.csv`); named HYG stars and Caldwell / Chinese
 * aliases are merged on load so both UIs see the same objects.
 */
class AssetDeepSkyCatalog(private val context: Context) : DeepSkyCatalog {

    private val entries: List<CatalogObject> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val dso = runCatching {
            context.assets.open(ASSET_PATH).use(::parse)
        }.getOrElse { DemoCatalog.all() }
        val stars = runCatching {
            context.assets.open(NamedStarCatalog.ASSET_PATH).bufferedReader().use { reader ->
                NamedStarCatalog.parse(reader.readLines())
            }
        }.getOrDefault(emptyList())
        ObservingCatalogExtras.enrich(dso + stars)
    }

    private val byId: Map<String, CatalogObject> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val index = LinkedHashMap<String, CatalogObject>()
        for (obj in entries) {
            index[normalizeCatalogQuery(obj.id)] = obj
            for (alias in obj.aliases) {
                index.putIfAbsent(normalizeCatalogQuery(alias), obj)
            }
        }
        index
    }

    override fun all(): List<CatalogObject> = entries

    override fun findById(id: String): CatalogObject? = byId[normalizeCatalogQuery(id)]

    override fun search(query: String): List<CatalogObject> = CatalogSearch.search(entries, query)

    override fun suggest(
        site: ObserverSite?,
        instant: Instant,
        limit: Int,
        filter: CatalogBrowseFilter
    ): List<CatalogObject> = VisibilityRanker.suggest(entries, site, instant, limit, filter)

    companion object {
        const val ASSET_PATH = "catalog/deepsky.csv"

        fun parse(stream: InputStream): List<CatalogObject> =
            stream.bufferedReader().useLines { lines ->
                lines.mapNotNull(::parseLine).toList()
            }

        /** `id|type|raHours|decDeg|vmag|sizeArcmin|name1;name2;...` */
        fun parseLine(line: String): CatalogObject? {
            if (line.isBlank() || line.startsWith("#")) return null
            val parts = line.split('|')
            if (parts.size < 7) return null
            val id = parts[0].trim().ifEmpty { return null }
            val ra = parts[2].toDoubleOrNull() ?: return null
            val dec = parts[3].toDoubleOrNull() ?: return null
            val aliases = parts[6].split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .ifEmpty { listOf(id) }
            return CatalogObject(
                id = id,
                name = aliases.lastOrNull { it != id } ?: id,
                type = parts[1].trim(),
                raHours = ra,
                decDeg = dec,
                magnitude = parts[4].toDoubleOrNull(),
                sizeArcmin = parts[5].toDoubleOrNull(),
                aliases = aliases
            )
        }
    }
}
