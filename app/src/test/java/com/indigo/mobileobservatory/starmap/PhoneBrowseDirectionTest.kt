package com.indigo.mobileobservatory.starmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneBrowseDirectionTest {
    @Test fun rearCameraAxisUsesNorthEastUpAndCorrectsMagneticDeclination() {
        val north = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        val east = floatArrayOf(0f, 0f, -1f, 0f, 1f, 0f, 1f, 0f, 0f)
        val up = floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f)
        assertEquals(0.0, PhoneBrowseDirection.fromRotationMatrix(north)!!.azimuthDeg, 1e-8)
        assertEquals(0.0, PhoneBrowseDirection.fromRotationMatrix(north)!!.altitudeDeg, 1e-8)
        assertEquals(90.0, PhoneBrowseDirection.fromRotationMatrix(east)!!.azimuthDeg, 1e-8)
        assertEquals(90.0, PhoneBrowseDirection.fromRotationMatrix(up)!!.altitudeDeg, 1e-8)
        assertEquals(355.0, PhoneBrowseDirection.fromRotationMatrix(north, -5.0)!!.azimuthDeg, 1e-8)
        assertEquals(95.0, PhoneBrowseDirection.fromRotationMatrix(east, 5.0)!!.azimuthDeg, 1e-8)
    }

    @Test fun malformedAndNonFiniteDirectionsCannotDriveTheBrowsingCamera() {
        assertNull(PhoneBrowseDirection.fromRotationMatrix(FloatArray(8)))
        assertNull(PhoneBrowseDirection.fromRotationMatrix(FloatArray(9)))
        assertNull(PhoneBrowseDirection.fromRotationMatrix(FloatArray(9) { Float.NaN }))
        assertNull(PhoneBrowseDirection.fromRotationMatrix(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), Double.NaN))
    }
}
