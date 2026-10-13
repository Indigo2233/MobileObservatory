package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class WideFieldPositionAngleTest {
    @Test fun localAndBlindImageUpUseTheSameJ2000PositionAngleAtDifferentSitesAndTimes() {
        val cases = listOf(ObserverSite(30.0, 120.0) to Instant.parse("2026-08-12T14:00:00Z"),
            ObserverSite(-33.0, 18.0) to Instant.parse("2026-12-22T21:00:00Z"))
        for ((site, instant) in cases) for (roll in listOf(0.0, 47.0)) {
            val request = request(site, instant, roll)
            val center = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(40.0, 20.0), instant, site, null)
            val local = WideFieldSolver.solve(request.copy(imuDirection = Direction3.fromAltAz(center.altitudeDeg, center.azimuthDeg)))
            val blind = WideFieldSolver.solve(request)
            assertTrue("local: ${local.message}", local.success); assertTrue("blind: ${blind.message}", blind.success)
            assertTrue(local.quality.usedImuPrior); assertFalse(local.quality.blindFallbackUsed)
            assertTrue(shortestRotationDeltaDeg(roll, local.rotationDeg!!) < .03)
            assertTrue(shortestRotationDeltaDeg(roll, blind.rotationDeg!!) < .03)
            assertTrue(shortestRotationDeltaDeg(local.rotationDeg!!, blind.rotationDeg!!) < .03)
            assertEquals(40.0, local.raDeg!!, .03); assertEquals(20.0, local.decDeg!!, .03)
            assertNotNull(local.quality.diagnostics?.localElapsedNanos)
        }
    }

    @Test fun persistentAndRuntimeIndexesGiveTheSameSolutionAndAmbiguityDecision() {
        val site = ObserverSite(30.0, 120.0); val instant = Instant.parse("2026-08-12T14:00:00Z")
        val base = request(site, instant, 47.0)
        fun indexed(stars: List<PhoneCatalogStar>) = PhoneBrightStarCatalog.indexed(stars) { bucket ->
            ByteArrayInputStream(ByteArrayOutputStream().also {
                WideFieldIndexCodec.write(it, stars, bucket, WideFieldTriangleIndex.build(stars, bucket))
            }.toByteArray())
        }
        val runtime = WideFieldSolver.solve(base)
        val asset = WideFieldSolver.solve(base.copy(catalog = indexed(base.catalog.stars)))
        assertEquals(runtime.raDeg, asset.raDeg); assertEquals(runtime.decDeg, asset.decDeg)
        assertEquals(runtime.rotationDeg, asset.rotationDeg); assertEquals(runtime.quality.matchedStars, asset.quality.matchedStars)
        assertTrue(asset.quality.diagnostics!!.indexLoads.all { it.origin == "asset" })
        val twins = base.catalog.stars + base.catalog.stars.map { it.copy(raDeg = (it.raDeg + 180) % 360) }
        val ambiguous = WideFieldSolver.solve(base.copy(catalog = indexed(twins)))
        assertFalse(ambiguous.success); assertEquals(WideFieldSolveFailure.AMBIGUOUS_CANDIDATE, ambiguous.failure)
    }

    private fun request(site: ObserverSite, instant: Instant, roll: Double): WideFieldSolveRequest {
        val width = 1600; val height = 1200; val fovW = 40.0; val fovH = 30.0
        val points = listOf(40.0 to 20.0, 30.0 to 25.0, 50.0 to 16.0, 35.0 to 10.0, 47.0 to 29.0, 55.0 to 23.0)
        val catalog = points.mapIndexed { i, p -> PhoneCatalogStar(p.first, p.second, 1.0 + i * .2, i, null) }
        val r = Math.toRadians(40.0); val d = Math.toRadians(20.0); val theta = Math.toRadians(roll)
        val center = doubleArrayOf(cos(d) * cos(r), cos(d) * sin(r), sin(d))
        val east = doubleArrayOf(-sin(r), cos(r), 0.0)
        val north = doubleArrayOf(-cos(r) * sin(d), -sin(r) * sin(d), cos(d))
        fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
        val stars = catalog.mapIndexed { i, star ->
            val ra = Math.toRadians(star.raDeg); val dec = Math.toRadians(star.decDeg)
            val v = doubleArrayOf(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
            val tx = dot(v, east) / dot(v, center); val ty = dot(v, north) / dot(v, center)
            val x = tx * cos(theta) - ty * sin(theta); val y = tx * sin(theta) + ty * cos(theta)
            ExtractedStar(((x / tan(Math.toRadians(fovW / 2)) + 1) * width / 2).toFloat(),
                ((1 - y / tan(Math.toRadians(fovH / 2))) * height / 2).toFloat(), 1000f - i, 500f, 100f - i, 10f)
        }
        return WideFieldSolveRequest(StarExtractionResult(stars, 32, 5f, null, 10f, 1f), width, height,
            fovW, fovH, instant, site, PhoneBrightStarCatalog.of(catalog))
    }
}
