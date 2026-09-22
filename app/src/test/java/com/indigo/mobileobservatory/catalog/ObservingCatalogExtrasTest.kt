package com.indigo.mobileobservatory.catalog

import com.indigo.mobileobservatory.astro.ObserverSite
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservingCatalogExtrasTest {
    @Test
    fun attachesCaldwellAndChineseAliases() {
        val veil = CatalogObject(
            id = "NGC 6992",
            name = "Eastern Veil",
            type = "Supernova Remnant",
            raHours = 20.94,
            decDeg = 31.74,
            magnitude = 7.0,
            sizeArcmin = 60.0,
            aliases = listOf("NGC 6992", "Eastern Veil")
        )
        val enriched = ObservingCatalogExtras.enrich(listOf(veil)).single()

        assertTrue(enriched.aliases.contains("C 33"))
        assertTrue(enriched.aliases.contains("东面纱星云"))
        assertEquals("NGC 6992", CatalogSearch.search(listOf(enriched), "c33").first().id)
        assertEquals("NGC 6992", CatalogSearch.search(listOf(enriched), "面纱").first().id)
    }

    @Test
    fun namedStarsGetChineseAliases() {
        val vega = CatalogObject(
            id = "Vega",
            name = "Vega",
            type = "Star",
            raHours = 18.61564,
            decDeg = 38.7837,
            magnitude = 0.03,
            aliases = listOf("Vega", "HIP 91262")
        )
        val enriched = ObservingCatalogExtras.enrich(listOf(vega)).single()
        assertEquals("Vega", CatalogSearch.search(listOf(enriched), "织女").first().id)
        assertEquals("Vega", CatalogSearch.search(listOf(enriched), "vega").first().id)
    }
}

class NamedStarCatalogTest {
    @Test
    fun parsesNamedHygRowAndInjectsSirius() {
        val stars = NamedStarCatalog.parse(
            listOf(
                "ra_deg,dec_deg,mag,hip,name",
                "279.2346000,38.7836920,0.03,91262,Vega",
                "95.9879250,-52.6956600,-0.62,30438,Canopus"
            )
        )
        assertTrue(stars.any { it.id == "Sirius" })
        assertEquals("Vega", stars.first { it.id == "Vega" }.id)
        assertEquals(279.2346 / 15.0, stars.first { it.id == "Vega" }.raHours, 1e-6)
    }
}

class VisibilityRankerTest {
    @Test
    fun emptySearchPrefersShowpieceThenBright() {
        val m42 = CatalogObject("M 42", "Orion", "Nebula", 5.588, -5.39, 4.0, 85.0)
        val faint = CatalogObject("NGC 9999", "Faint", "Galaxy", 0.0, 0.0, 15.0, 0.4)
        val vega = CatalogObject("Vega", "Vega", "Star", 18.6156, 38.78, 0.03)

        val suggested = VisibilityRanker.suggest(listOf(faint, vega, m42), site = null, limit = 10)

        assertEquals("M 42", suggested.first().id)
        assertTrue(suggested.any { it.id == "Vega" })
        assertTrue(suggested.none { it.id == "NGC 9999" })
    }

    @Test
    fun siteRankingDropsObjectsBelowHorizon() {
        val polaris = CatalogObject("Polaris", "Polaris", "Star", 2.53, 89.26, 2.0)
        val south = CatalogObject("M 7", "Ptolemy", "Open Cluster", 17.64, -34.8, 3.3, 80.0)
        val beijing = ObserverSite(39.9, 116.4)
        val instant = Instant.parse("2026-01-15T12:00:00Z")

        val suggested = VisibilityRanker.suggest(
            listOf(polaris, south),
            site = beijing,
            instant = instant,
            limit = 10
        )

        assertTrue(suggested.any { it.id == "Polaris" })
    }
}
