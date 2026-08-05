package com.indigo.mobileobservatory.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoCatalogSearchTest {
    @Test
    fun searchIgnoresSpacesAndCase() {
        val hits = DemoCatalog.search("m 42")
        assertEquals(1, hits.size)
        assertEquals("M42", hits[0].id)
    }

    @Test
    fun searchMatchesNameWithoutSpaces() {
        val hits = DemoCatalog.search("orionnebula")
        assertTrue(hits.any { it.id == "M42" })
    }

    @Test
    fun blankQueryReturnsAll() {
        assertEquals(DemoCatalog.all().size, DemoCatalog.search("   ").size)
    }
}
