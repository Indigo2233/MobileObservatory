package com.indigo.mobileobservatory.catalog

import android.content.Context
import java.io.InputStream

/**
 * Full offline NGC / IC / Messier / Caldwell catalog, generated from OpenNGC by
 * `scripts/generate_deepsky_catalog.py` into `assets/catalog/deepsky.csv`.
 *
 * The asset is parsed on first use; callers should touch it off the main thread.
 */
class AssetDeepSkyCatalog(private val context: Context) : DeepSkyCatalog {

    private val entries: List<CatalogObject> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            context.assets.open(ASSET_PATH).use(::parse)
        }.getOrElse { DemoCatalog.all() }
    }

    private val byId: Map<String, CatalogObject> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        entries.associateBy { normalizeCatalogQuery(it.id) }
    }

    override fun all(): List<CatalogObject> = entries

    override fun findById(id: String): CatalogObject? = byId[normalizeCatalogQuery(id)]

    override fun search(query: String): List<CatalogObject> = CatalogSearch.search(entries, query)

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
