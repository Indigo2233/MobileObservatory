package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.ObserverSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class WideFieldSolverTest {
    @Test
    fun solvesSyntheticFieldWithoutImuPrior() {
        val centerRa = 40.0
        val centerDec = 20.0
        val catalogStars = listOf(
            40.0 to 20.0, 30.0 to 25.0, 50.0 to 16.0, 35.0 to 10.0, 47.0 to 29.0, 55.0 to 23.0
        ).mapIndexed { index, (ra, dec) -> PhoneCatalogStar(ra, dec, 1.0 + index * 0.2, index, "S$index") }
        val width = 1600
        val height = 1200
        val fovWidth = 70.0
        val fovHeight = 53.0
        val stars = catalogStars.mapIndexed { index, star ->
            val local = localCoordinates(centerRa, centerDec, star.raDeg, star.decDeg)
            ExtractedStar(
                x = (((local.first / local.third) / tan(Math.toRadians(fovWidth / 2.0)) + 1.0) * width / 2.0).toFloat(),
                y = ((1.0 - (local.second / local.third) / tan(Math.toRadians(fovHeight / 2.0))) * height / 2.0).toFloat(),
                peak = 1000f - index, flux = 500f, snr = 100f - index, background = 10f
            )
        }
        val result = WideFieldSolver.solve(WideFieldSolveRequest(
            extraction = StarExtractionResult(stars, 32, 5f, null, 10f, 1f),
            frameWidth = width, frameHeight = height, initialFovWidthDeg = fovWidth, initialFovHeightDeg = fovHeight,
            observationTime = Instant.parse("2026-08-12T14:00:00Z"), site = ObserverSite(30.0, 120.0),
            catalog = PhoneBrightStarCatalog.of(catalogStars)
        ))
        assertTrue(result.message, result.success)
        assertEquals(centerRa, result.raDeg!!, 0.2)
        assertEquals(centerDec, result.decDeg!!, 0.2)
        assertTrue(result.quality.matchedStars >= 4)
    }

    private fun localCoordinates(centerRaDeg: Double, centerDecDeg: Double, raDeg: Double, decDeg: Double): Triple<Double, Double, Double> {
        fun vector(ra: Double, dec: Double): Triple<Double, Double, Double> {
            val r = Math.toRadians(ra); val d = Math.toRadians(dec)
            return Triple(cos(d) * cos(r), cos(d) * sin(r), sin(d))
        }
        val center = vector(centerRaDeg, centerDecDeg)
        val east = Triple(-sin(Math.toRadians(centerRaDeg)), cos(Math.toRadians(centerRaDeg)), 0.0)
        val north = Triple(-cos(Math.toRadians(centerRaDeg)) * sin(Math.toRadians(centerDecDeg)),
            -sin(Math.toRadians(centerRaDeg)) * sin(Math.toRadians(centerDecDeg)), cos(Math.toRadians(centerDecDeg)))
        val star = vector(raDeg, decDeg)
        fun dot(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>) = a.first * b.first + a.second * b.second + a.third * b.third
        return Triple(dot(star, east), dot(star, north), dot(star, center))
    }
}
