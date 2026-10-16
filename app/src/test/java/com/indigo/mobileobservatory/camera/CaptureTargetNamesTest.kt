package com.indigo.mobileobservatory.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureTargetNamesTest {
    @Test
    fun `legacy placeholders and abbreviations become complete internal names`() {
        assertEquals("Jupiter", CaptureTargetNames.canonical("Pla"))
        assertEquals("Jupiter", CaptureTargetNames.canonical("Ju"))
        assertEquals("Saturn", CaptureTargetNames.canonical("Sa"))
        assertEquals("Mars", CaptureTargetNames.canonical("Ma"))
        assertEquals("Venus", CaptureTargetNames.canonical("Ve"))
        assertEquals("Mercury", CaptureTargetNames.canonical("Me"))
        assertEquals("Moon", CaptureTargetNames.canonical("Mo"))
        assertEquals("Sun", CaptureTargetNames.canonical("Su"))
    }

    @Test
    fun `blank defaults while catalog targets remain intact`() {
        assertEquals(CaptureTargetNames.DEFAULT, CaptureTargetNames.canonical("  "))
        assertEquals("M42 · Orion Nebula", CaptureTargetNames.canonical(" M42 · Orion Nebula "))
    }
}
