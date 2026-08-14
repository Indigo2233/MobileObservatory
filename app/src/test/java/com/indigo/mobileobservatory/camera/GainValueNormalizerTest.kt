package com.indigo.mobileobservatory.camera

import org.junit.Assert.assertEquals
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
}
