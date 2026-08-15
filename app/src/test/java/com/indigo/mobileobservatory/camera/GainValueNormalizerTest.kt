package com.indigo.mobileobservatory.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GainValueNormalizerTest {

    @Test
    fun `continuous native gain clamps and snaps to device step`() {
        val capability = GainCapability(
            min = 100f,
            max = 10_000f,
            step = 5f,
            defaultValue = 100f
        )

        assertEquals(100f, GainValueNormalizer.normalize(capability, 1f), 0f)
        assertEquals(105f, GainValueNormalizer.normalize(capability, 103.6f), 0f)
        assertEquals(10_000f, GainValueNormalizer.normalize(capability, 12_000f), 0f)
    }

    @Test
    fun `iso selects nearest supported value`() {
        val capability = GainCapability(
            kind = GainControlKind.ISO,
            label = "ISO",
            min = 100f,
            max = 3200f,
            defaultValue = 100f,
            allowedValues = listOf(100f, 200f, 400f, 800f, 1600f, 3200f)
        )

        assertEquals(400f, GainValueNormalizer.normalize(capability, 520f), 0f)
        assertEquals(1600f, GainValueNormalizer.normalize(capability, 1900f), 0f)
    }

    @Test
    fun `presets discard out of range values and merge matching values`() {
        val capability = GainCapability(
            min = 0f,
            max = 300f,
            defaultValue = 0f,
            presets = listOf(
                GainPreset(100f, "Unity"),
                GainPreset(100f, "HCG"),
                GainPreset(500f, "HDR")
            )
        )

        val presets = GainValueNormalizer.filteredPresets(capability)

        assertEquals(1, presets.size)
        assertEquals(100f, presets.single().value, 0f)
        assertTrue(presets.single().label.contains("Unity"))
        assertTrue(presets.single().label.contains("HCG"))
    }

    @Test
    fun `non finite input and defaults fall back to the lower bound`() {
        val capability = GainCapability(
            min = 5f,
            max = 15f,
            step = 0.5f,
            defaultValue = Float.NaN
        )

        assertEquals(5f, GainValueNormalizer.normalize(capability, Float.NaN), 0f)
        assertEquals(5f, GainValueNormalizer.normalize(capability, Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun `reversed ranges retain a usable ascending normalization domain`() {
        val capability = GainCapability(
            min = 24f,
            max = 0f,
            step = 0.1f,
            defaultValue = 0f
        )

        assertEquals(6.0f, GainValueNormalizer.normalize(capability, 6.04f), 0.0001f)
    }

    @Test
    fun `continuous controls clamp without inventing a step`() {
        val capability = GainCapability(
            min = 0f,
            max = 24f,
            step = 0f,
            defaultValue = 0f,
            continuous = true
        )

        assertEquals(6.037f, GainValueNormalizer.normalize(capability, 6.037f), 0f)
        assertEquals(24f, GainValueNormalizer.normalize(capability, 25f), 0f)
    }

    @Test
    fun `decimal places follow device step`() {
        assertEquals(0, GainValueNormalizer.decimalPlacesForStep(1f))
        assertEquals(1, GainValueNormalizer.decimalPlacesForStep(0.1f))
        assertEquals(2, GainValueNormalizer.decimalPlacesForStep(0.01f))
        assertEquals(3, GainValueNormalizer.decimalPlacesForStep(0f))
    }

    @Test
    fun `iso exposure stops move at least one legal value`() {
        val capability = GainValueNormalizer.isoCapability(
            allowedValues = listOf(100f, 200f, 400f, 800f, 1600f, 3200f),
            current = 400f
        )

        assertEquals(800f, GainValueNormalizer.adjustForExposureStops(capability, 400f, 0.1f), 0f)
        assertEquals(200f, GainValueNormalizer.adjustForExposureStops(capability, 400f, -0.5f), 0f)
        assertEquals(3200f, GainValueNormalizer.adjustForExposureStops(capability, 3200f, 0.5f), 0f)
        assertEquals(100f, GainValueNormalizer.adjustForExposureStops(capability, 100f, -0.1f), 0f)
    }

    @Test
    fun `parse input rejects blank and non numeric text`() {
        assertEquals(12.5f, requireNotNull(GainValueNormalizer.parseInput(" 12.5 ")), 0f)
        assertEquals(null, GainValueNormalizer.parseInput("abc"))
        assertEquals(null, GainValueNormalizer.parseInput(""))
    }

    @Test
    fun `iso capability drops auto sentinels`() {
        val capability = GainValueNormalizer.isoCapability(
            allowedValues = listOf(0f, 100f, 200f, 65_535f),
            current = 100f
        )
        assertEquals(listOf(100f, 200f), capability.allowedValues)
        assertFalse(capability.allowedValues.contains(0f))
    }

    @Test
    fun `writable false is read only even when the range has more than one value`() {
        val capability = GainValueNormalizer.isoCapability(
            allowedValues = listOf(100f, 200f, 400f),
            current = 200f,
            writable = false
        )
        assertTrue(capability.isReadOnly)
        assertFalse(capability.writable)
    }
}
