package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.util.FitsHeader
import org.junit.Assert.assertEquals
import org.junit.Test

class SolveInputFormatTest {
    @Test fun rawBayerRemainsIdentifiedAsStoredMosaic() {
        val header = FitsHeader(mapOf("BITPIX" to "16", "NAXIS" to "2", "BAYERPAT" to "RGGB"), 2880)
        assertEquals("FITS · 16-bit integer · Bayer RGGB", header.solveInputFormat())
    }

    @Test fun importedFloatRgbIsIdentifiedByItsThreePlanes() {
        val header = FitsHeader(mapOf("BITPIX" to "-32", "NAXIS" to "3", "NAXIS3" to "3", "BAYERPAT" to "RGGB"), 2880)
        assertEquals("FITS · 32-bit float · RGB", header.solveInputFormat())
    }

    @Test fun monochromeAndNonRgbCubesKeepTheirLayout() {
        assertEquals("FITS · 64-bit float · MONO", FitsHeader(mapOf("BITPIX" to "-64", "NAXIS" to "2"), 2880).solveInputFormat())
        assertEquals("FITS · 16-bit integer · 5 planes", FitsHeader(mapOf("BITPIX" to "16", "NAXIS" to "3", "NAXIS3" to "5"), 2880).solveInputFormat())
    }
}
