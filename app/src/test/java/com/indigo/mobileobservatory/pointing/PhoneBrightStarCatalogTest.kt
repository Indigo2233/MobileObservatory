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
}
