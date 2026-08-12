package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.astro.TopocentricCoordinates
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class ImuAssistedWideFieldSolverTest {
    @Test
    fun solvesSyntheticFieldNearImuPrior() {
        val site = ObserverSite(30.0, 120.0)
        val instant = Instant.parse("2026-08-12T14:00:00Z")
        val center = Direction3.fromAltAz(48.0, 165.0)
        val horizontalStars = listOf(
            TopocentricCoordinates(50.0, 160.0),
            TopocentricCoordinates(45.5, 170.0),
            TopocentricCoordinates(52.0, 174.0),
            TopocentricCoordinates(43.0, 158.0),
            TopocentricCoordinates(47.0, 180.0)
        )
        val catalog = PhoneBrightStarCatalog.of(horizontalStars.mapIndexed { index, horizontal ->
            val eq = CoordinateTransform.topocentricToJ2000(horizontal, instant, site, refraction = null)
            PhoneCatalogStar(eq.raDeg, eq.decDeg, 2.0 + index * 0.1, index + 1, "S$index")
        })
        val width = 1600
        val height = 1200
        val fovWidth = 70.0
        val fovHeight = 53.0
        val basis = tangentBasis(center)
        val stars = horizontalStars.mapIndexed { index, horizontal ->
            val direction = Direction3.fromAltAz(horizontal.altitudeDeg, horizontal.azimuthDeg)
            val denominator = dot(direction, center)
            val x = dot(direction, basis.first) / denominator
            val y = dot(direction, basis.second) / denominator
            ExtractedStar(
                x = (((x / tan(Math.toRadians(fovWidth / 2.0))) + 1.0) * width / 2.0).toFloat(),
                y = ((1.0 - y / tan(Math.toRadians(fovHeight / 2.0))) * height / 2.0).toFloat(),
                peak = 1000f - index,
                flux = 500f,
                snr = 100f - index,
                background = 10f
            )
        }

        val result = ImuAssistedWideFieldSolver.solve(
            extraction = StarExtractionResult(stars, 32, 5f, null, 10f, 1f),
            frameWidth = width,
            frameHeight = height,
            fovWidthDeg = fovWidth,
            fovHeightDeg = fovHeight,
            imuDirection = center,
            instant = instant,
            site = site,
            catalog = catalog
        )

        assertTrue(result.message, result.success)
        assertTrue(result.matchedStars >= 4)
        assertNotNull(result.center)
    }

    private fun tangentBasis(center: Direction3): Pair<Direction3, Direction3> {
        val reference = if (kotlin.math.abs(center.up) < 0.9) Direction3(0.0, 0.0, 1.0) else Direction3(0.0, 1.0, 0.0)
        val x = cross(reference, center).unit()
        return x to cross(center, x).unit()
    }

    private fun dot(a: Direction3, b: Direction3) = a.east * b.east + a.north * b.north + a.up * b.up
    private fun cross(a: Direction3, b: Direction3) = Direction3(
        a.north * b.up - a.up * b.north,
        a.up * b.east - a.east * b.up,
        a.east * b.north - a.north * b.east
    )
}
