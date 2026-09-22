package com.indigo.mobileobservatory.pointing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneBrightStarCatalogTest {
    @Test
    fun parsesAttributableCatalogRows() {
        val catalog = PhoneBrightStarCatalog.fromCsvLines(
            listOf(
                "# source",
                "ra_deg,dec_deg,mag,hip,name",
                "279.2346000,38.7836920,0.03,91262,Vega"
            )
        )

        assertEquals(1, catalog.stars.size)
        assertEquals("Vega", catalog.stars.single().name)
        assertTrue(catalog.stars.single().magnitude < 1.0)
    }

    @Test
    fun injectsSiriusIntoAFullSkySubsetThatDroppedIt() {
        val lines = listOf("ra_deg,dec_deg,mag,hip,name") + (1..120).map { index ->
            "${index.toDouble()},0.0,1.0,$index,S$index"
        }
        val catalog = PhoneBrightStarCatalog.fromCsvLines(lines)
        val sirius = catalog.stars.single { it.hip == PhoneBrightStarCatalog.SIRIUS.hip }
        assertEquals("Sirius", sirius.name)
        assertEquals(-1.46, sirius.magnitude, 1e-6)
        assertEquals(101.287155, sirius.raDeg, 1e-6)
    }

    @Test
    fun doesNotDuplicateSiriusWhenAlreadyPresent() {
        val catalog = PhoneBrightStarCatalog.fromCsvLines(
            listOf(
                "ra_deg,dec_deg,mag,hip,name",
                "101.2871550,-16.7161160,-1.46,32349,Sirius"
            ) + (1..120).map { index -> "${index.toDouble()},0.0,1.0,$index,S$index" }
        )
        assertEquals(1, catalog.stars.count { it.hip == 32349 })
    }
}
