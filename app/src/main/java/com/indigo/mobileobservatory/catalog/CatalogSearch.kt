package com.indigo.mobileobservatory.catalog

/**
 * Ranked lookup over an in-memory catalog. Exact catalog ids win, then prefix
 * matches, then substring matches; ties break on brightness so a search for
 * "orion" surfaces M 42 rather than an anonymous 15th magnitude galaxy.
 */
object CatalogSearch {
    private const val SCORE_EXACT = 0
    private const val SCORE_PREFIX = 1
    private const val SCORE_CONTAINS = 2
    private const val FAINTEST = 99.0

    fun search(objects: List<CatalogObject>, query: String, limit: Int = 40): List<CatalogObject> {
        val needle = normalizeCatalogQuery(query)
        if (needle.isEmpty()) return emptyList()

        val scored = ArrayList<Pair<Int, CatalogObject>>()
        for (obj in objects) {
            val score = scoreOf(obj, needle) ?: continue
            scored.add(score to obj)
            // Exact hits are rare; keep scanning so brightness ordering holds.
        }
        return scored
            .sortedWith(
                compareBy(
                    { it.first },
                    { it.second.magnitude ?: FAINTEST },
                    { it.second.id }
                )
            )
            .take(limit)
            .map { it.second }
    }

    private fun scoreOf(obj: CatalogObject, needle: String): Int? {
        var best: Int? = null
        for (alias in obj.aliases) {
            val candidate = normalizeCatalogQuery(alias)
            val score = when {
                candidate == needle -> SCORE_EXACT
                candidate.startsWith(needle) -> SCORE_PREFIX
                candidate.contains(needle) -> SCORE_CONTAINS
                else -> continue
            }
            if (best == null || score < best) best = score
            if (best == SCORE_EXACT) break
        }
        return best
    }
}
