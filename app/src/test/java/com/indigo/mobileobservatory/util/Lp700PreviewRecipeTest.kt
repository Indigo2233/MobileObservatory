package com.indigo.mobileobservatory.util

import org.junit.Assert.assertEquals
import org.junit.Test

class Lp700PreviewRecipeTest {
    @Test fun nativeCropReusesTheWholeImagesColourAndBackgroundBalance() {
        val width = 96
        val source = IntArray(width * 64) { i ->
            val x = i % width; val y = i / width
            (0xFF shl 24) or ((30 + x * 2) shl 16) or ((20 + y * 3) shl 8) or (15 + x + y)
        }
        val full = source.copyOf()
        val recipe = Lp700PreviewMapping.applyToArgb(full)
        val crop = IntArray(24 * 16) { i -> source[(i / 24 + 21) * width + i % 24 + 31] }
        Lp700PreviewMapping.applyToArgb(crop, recipe = recipe)
        for (i in crop.indices) assertEquals(full[(i / 24 + 21) * width + i % 24 + 31], crop[i])
    }
}
