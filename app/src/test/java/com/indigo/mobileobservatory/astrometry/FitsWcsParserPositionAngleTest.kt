package com.indigo.mobileobservatory.astrometry

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class FitsWcsParserPositionAngleTest {
    @Test
    fun cdMatrixIsConvertedToSensorPositionAngle() {
        val northUp = wcs(
            """
            CRVAL1  = 15
            CRVAL2  = 20
            NAXIS1  = 1000
            NAXIS2  = 500
            CD1_1   = -0.001
            CD1_2   = 0
            CD2_1   = 0
            CD2_2   = 0.001
            """.trimIndent()
        )
        val eastUp = wcs(
            """
            CRVAL1  = 15
            CRVAL2  = 20
            NAXIS1  = 1000
            NAXIS2  = 500
            CD1_1   = 0
            CD1_2   = 0.001
            CD2_1   = 0.001
            CD2_2   = 0
            """.trimIndent()
        )

        assertEquals(0.0, FitsWcsParser.parseCandidates(listOf(northUp), 0, 0)!!.rotationDeg!!, 1e-9)
        assertEquals(90.0, FitsWcsParser.parseCandidates(listOf(eastUp), 0, 0)!!.rotationDeg!!, 1e-9)
    }

    private fun wcs(text: String) = Files.createTempFile("position-angle", ".wcs")
        .toFile()
        .apply { writeText(text) }
}
