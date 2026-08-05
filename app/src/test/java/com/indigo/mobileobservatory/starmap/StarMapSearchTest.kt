package com.indigo.mobileobservatory.starmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarMapSearchTest {
    @Test
    fun selectScriptCarriesDesignationsAsJson() {
        val script = StarMapSearch.selectScript(listOf("M 31", "NGC 224", "NAME Andromeda Galaxy"))

        assertTrue(script.startsWith("window.MercStarMap && "))
        assertTrue(script.contains("selectByDesignations("))
        // The JSON array reaches JS intact after one level of escaping.
        assertTrue(script.contains("""\"M 31\",\"NGC 224\",\"NAME Andromeda Galaxy\""""))
    }

    @Test
    fun quoteEscapesInjectionCharacters() {
        assertEquals("\"M 31\"", StarMapSearch.quote("M 31"))
        assertEquals("\"a\\\"b\"", StarMapSearch.quote("a\"b"))
        assertEquals("\"a\\\\b\"", StarMapSearch.quote("a\\b"))
        assertEquals("\"\\u003cscript\\u003e\"", StarMapSearch.quote("<script>"))
    }

    @Test
    fun quotedScriptSurvivesInjectionAttempt() {
        val script = StarMapSearch.selectScript(listOf("\"); alert(1); //"))

        assertTrue(script.count { it == ';' } >= 1)
        assertTrue(script.endsWith(");"))
        // No unescaped quote can terminate the JS literal early.
        assertTrue(script.contains("\\\\\""))
    }
}
