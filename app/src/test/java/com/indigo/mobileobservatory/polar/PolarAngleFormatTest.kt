package com.indigo.mobileobservatory.polar

import org.junit.Assert.assertEquals
import org.junit.Test

class PolarAngleFormatTest {
    @Test fun displaysDegreesMinutesAndSeconds() {
        assertEquals("0° 30′ 00″", formatPolarAngle(.5))
        assertEquals("12° 34′ 56″", formatPolarAngle(12 + 34.0 / 60 + 56.0 / 3600))
        assertEquals("0° 00′ 00″", formatPolarAngle(0.0))
        assertEquals("−0° 00′ 03″", formatPolarAngle(-3.0 / 3600))
        assertEquals("+0° 00′ 03″", formatPolarAngle(3.0 / 3600, signed = true))
    }

    @Test fun roundingCarriesBeforeFormatting() {
        assertEquals("0° 01′ 00″", formatPolarAngle(59.6 / 3600))
        assertEquals("1° 00′ 00″", formatPolarAngle(3599.6 / 3600))
        assertEquals("−0° 00′ 03″", formatPolarAngle(-3.0 / 3600, signed = true))
        assertEquals("0° 00′ 00″", formatPolarAngle(-.4 / 3600, signed = true))
    }

    @Test fun ringLabelsUseWholeSexagesimalUnits() {
        assertEquals("30″", formatPolarAngle(.5 / 60, compact = true))
        assertEquals("1′", formatPolarAngle(1.0 / 60, compact = true))
        assertEquals("1′30″", formatPolarAngle(1.5 / 60, compact = true))
        assertEquals("1°", formatPolarAngle(1.0, compact = true))
    }
}
