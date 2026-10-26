package com.indigo.mobileobservatory.astrometry

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AstapSolveResultTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failureExitCannotReuseAnOlderSuccessfulWcs() {
        val current = sidecar("current.ini", "PLTSOLVD=F\nERROR=Only 4 stars found in image. Abort")
        val previous = sidecar("previous.wcs", solvedHeader())
        assertNull(parseAstapSolution(1, listOf(current, previous), 3856, 2180))
    }

    @Test fun failedSummaryCannotBecomeSuccessEvenWithCoordinatesAndZeroExit() {
        val current = sidecar("current.ini", solvedHeader().replace("PLTSOLVD=T", "PLTSOLVD=F"))
        assertNull(parseAstapSolution(0, listOf(current), 3856, 2180))
    }

    @Test fun parserRejectsAFileExplicitlyMarkedUnsolved() {
        val failed = sidecar("failed.wcs", solvedHeader().replace("PLTSOLVD=T", "PLTSOLVD=F"))
        assertNull(FitsWcsParser.parseCandidates(listOf(failed), 3856, 2180))
    }

    @Test fun successfulExitRequiresAnExplicitlySuccessfulSummary() {
        val wcs = sidecar("current.wcs", solvedHeader())
        assertNull(parseAstapSolution(0, listOf(wcs), 3856, 2180))
        val ini = sidecar("current.ini", "PLTSOLVD=F")
        assertNull(parseAstapSolution(0, listOf(wcs, ini), 3856, 2180))
        ini.writeText("PLTSOLVD=T")
        assertNull(parseAstapSolution(1, listOf(wcs, ini), 3856, 2180))
        val result = requireNotNull(parseAstapSolution(0, listOf(wcs, ini), 3856, 2180))
        assertEquals(wcs, result.source)
        assertEquals(15.0, result.raDeg, 0.0)
        assertEquals(20.0, result.decDeg, 0.0)
        assertEquals(1.44, result.arcsecPerPixel!!, 1e-10)
    }

    @Test fun anUnrelatedWcsCannotCompleteAnEmptyCurrentSummary() {
        val summary = sidecar("current.ini", "PLTSOLVD=T")
        val other = sidecar("previous.wcs", solvedHeader())
        assertNull(parseAstapSolution(0, listOf(other, summary), 3856, 2180))
    }

    @Test fun repeatedSolvesHaveDistinctOutputsAndNeverScanImageOrCatalogHistory() {
        val first = createAstapOutputBase(temporary.root)
        val second = createAstapOutputBase(temporary.root)
        assertNotEquals(first.parentFile, second.parentFile)
        File(first.parentFile, "solution.ini").writeText(solvedHeader())
        File(first.parentFile, "solution.wcs").writeText(solvedHeader())
        sidecar("input.fits", solvedHeader())
        sidecar("previous.ini", solvedHeader())
        assertTrue(astapOutputFiles(second).isEmpty())
        assertNull(parseAstapSolution(0, astapOutputFiles(second), 3856, 2180))
        val ini = File(second.parentFile, "solution.ini").apply { writeText(solvedHeader()) }
        assertEquals(listOf(ini), astapOutputFiles(second))
        assertEquals(ini, parseAstapSolution(0, astapOutputFiles(second), 3856, 2180)!!.source)
    }

    @Test fun parserRejectsInvalidSkyCoordinates() {
        listOf("NaN" to "20", "Infinity" to "20", "15" to "91", "15" to "NaN").forEachIndexed { index, (ra, dec) ->
            val file = sidecar("invalid$index.wcs", solvedHeader().replace("CRVAL1=15", "CRVAL1=$ra").replace("CRVAL2=20", "CRVAL2=$dec"))
            assertNull(FitsWcsParser.parseCandidates(listOf(file), 3856, 2180))
        }
    }

    private fun sidecar(name: String, text: String): File = temporary.newFile(name).apply { writeText(text) }

    private fun solvedHeader(): String = """
        PLTSOLVD=T
        CRVAL1=15
        CRVAL2=20
        CRPIX1=1928.5
        CRPIX2=1090.5
        NAXIS1=3856
        NAXIS2=2180
        CD1_1=-0.0004
        CD1_2=0
        CD2_1=0
        CD2_2=0.0004
    """.trimIndent()
}
