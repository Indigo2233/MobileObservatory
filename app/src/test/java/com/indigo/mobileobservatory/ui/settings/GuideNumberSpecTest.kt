package com.indigo.mobileobservatory.ui.settings

import org.junit.Assert.*
import org.junit.Test

class GuideNumberSpecTest {
    @Test fun integerSettingsRejectFractionalAndOutOfRangeValues() {
        val steps = GuideNumberSpec("5", "20", "1")
        assertEquals("12", steps.parse("12")?.toPlainString())
        listOf("7.5", "7.0", "4", "21", "", "NaN", "1e1", "Infinity").forEach {
            assertNull(it, steps.parse(it))
        }
        assertEquals("7", steps.format(7f))
    }

    @Test fun decimalSettingsAcceptWholePixelsAndBothDecimalSeparators() {
        val pixels = GuideNumberSpec("10", "50", "1", 2)
        assertEquals("20", pixels.parse("20")?.toPlainString())
        assertEquals("20.25", pixels.parse("20,25")?.toPlainString())
        assertEquals("20.25", pixels.parse("20.25")?.toPlainString())
        listOf("9.99", "50.01", "20.123", "1,2.3").forEach { assertNull(it, pixels.parse(it)) }
        assertEquals("20", pixels.format(20f))
    }

    @Test fun repeatedDecimalStepsReachExactEndpoints() {
        val movement = GuideNumberSpec("0.05", "1", "0.01", 2)
        var text = "0.05"
        repeat(95) { text = movement.format(movement.adjusted(text, 0.05f, 1)) }
        assertEquals("1", text)
        assertEquals("1", movement.format(movement.adjusted(text, 1f, 1)))
        repeat(95) { text = movement.format(movement.adjusted(text, 1f, -1)) }
        assertEquals("0.05", text)
        assertEquals("0.05", movement.format(movement.adjusted(text, 0.05f, -1)))
        assertEquals("0.15", movement.format(0.15000001f))
    }

    @Test fun pulseStepsKeepMillisecondsIntegralAndClampToLimits() {
        val pulse = GuideNumberSpec("300", "5000", "100")
        assertEquals("1300", pulse.format(pulse.adjusted("1200", 1200f, 1)))
        assertEquals("5000", pulse.format(pulse.adjusted("4951", 1200f, 1)))
        assertEquals("300", pulse.format(pulse.adjusted("301", 1200f, -1)))
        assertNull(pulse.parse("1200.1"))
    }

    @Test fun optionalFocalLengthPreservesFractionsAndSupportsResettingToPixels() {
        val focal = GuideNumberSpec("0", "10000", "1", 2, blankIsZero = true)
        assertEquals(0f, focal.parse("")!!.toFloat(), 0f)
        assertEquals("200.5", focal.format(200.5f))
        assertNull(focal.parse("-1"))
        assertNull(focal.parse("10000.01"))
    }
}
