package com.indigo.mobileobservatory.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpticsFovTest {
    @Test
    fun plateScaleMatchesCommonRuleOfThumb() {
        // 3.75 um pixels, 500 mm focal → 1.547 arcsec/px
        val scale = OpticsFov.plateScaleArcsecPerPixel(3.75, 500.0)!!
        assertEquals(1.547, scale, 0.001)
    }

    @Test
    fun rectangleUsesRoiPixels() {
        val fov = OpticsFov.rectangleDegrees(
            pixelSizeUm = 3.75,
            focalLengthMm = 500.0,
            widthPx = 1920,
            heightPx = 1080
        )!!
        assertEquals(0.825, fov.first, 0.002)
        assertEquals(0.464, fov.second, 0.002)
    }

    @Test
    fun binningScalesPlateScale() {
        val b1 = OpticsFov.plateScaleArcsecPerPixel(3.75, 500.0, 1)!!
        val b2 = OpticsFov.plateScaleArcsecPerPixel(3.75, 500.0, 2)!!
        assertEquals(b1 * 2.0, b2, 1e-9)
    }

    @Test
    fun rejectsNonPositiveInputs() {
        assertNull(OpticsFov.rectangleDegrees(0.0, 500.0, 100, 100))
        assertNull(OpticsFov.rectangleDegrees(3.75, 0.0, 100, 100))
        assertNull(OpticsFov.rectangleDegrees(3.75, 500.0, 0, 100))
    }
}
