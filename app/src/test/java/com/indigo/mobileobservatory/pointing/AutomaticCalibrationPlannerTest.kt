package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.astro.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AutomaticCalibrationPlannerTest {
    private val instant = Instant.parse("2026-10-14T12:00:00Z")
    private val site = ObserverSite(31.0, 121.0)
    private fun direction(altitude: Double, azimuth: Double): SkyDirection {
        val p = CoordinateTransform.topocentricToJ2000(TopocentricCoordinates(altitude, azimuth), instant, site, null)
        return SkyDirection(p.raDeg, p.decDeg)
    }
    private fun star(id: Int, altitude: Double, azimuth: Double, magnitude: Double = 1.0) = direction(altitude, azimuth).let {
        PhoneCatalogStar(it.raDeg, it.decDeg, magnitude, id, "star-$id")
    }
    private val quartet get() = listOf(star(1, 40.0, 130.0), star(2, 50.0, 140.0), star(3, 42.0, 158.0), star(4, 55.0, 160.0))

    @Test fun ordinaryPlanContainsTwoIndependentVisibleTargetsWithinTheInitialAndAdjacentMoveBounds() {
        val plan = requireNotNull(AutomaticCalibrationPlanner.plan(quartet, site, instant, 2, direction(45.0, 145.0)))
        assertEquals(2, plan.targets.size)
        assertTrue(plan.targets.all { it.altitudeDeg in 25.0..75.0 })
        val separation = SkyDirection(plan.targets[0].star.raDeg, plan.targets[0].star.decDeg).vector()
            .angleDeg(SkyDirection(plan.targets[1].star.raDeg, plan.targets[1].star.decDeg).vector())
        assertTrue(separation in 10.0..35.0)
        assertTrue(plan.canStart(site, instant, direction(45.0, 145.0)))
    }
    @Test fun mirrorPlanHasThreeNoncollinearTrainingTargetsAndADistinctFourthVerification() {
        val plan = requireNotNull(AutomaticCalibrationPlanner.plan(quartet, site, instant, 4, direction(45.0, 145.0)))
        assertEquals(4, plan.targets.map { it.star.hip }.toSet().size)
        val vectors = plan.targets.map { SkyDirection(it.star.raDeg, it.star.decDeg).vector() }
        for (i in vectors.indices) for (j in i+1 until vectors.size) assertTrue(vectors[i].angleDeg(vectors[j]) >= 10.0)
        val a = vectors[0].cross(vectors[1]).unit(); val b = vectors[0].cross(vectors[2]).unit()
        assertTrue(kotlin.math.sqrt(a.cross(b).let { it.dot(it) }) >= kotlin.math.sin(Math.toRadians(20.0)))
    }
    @Test fun daylightUnsafeAltitudesUnknownStartAndAnExcessiveFirstMoveFallBackToManual() {
        assertNull(AutomaticCalibrationPlanner.plan(quartet, site, instant, 2, null))
        assertNull(AutomaticCalibrationPlanner.plan(quartet, site, instant, 2, direction(45.0, 350.0)))
        assertNull(AutomaticCalibrationPlanner.plan(listOf(star(1, -10.0, 120.0), star(2, 89.0, 140.0), star(3, 20.0, 155.0)),
            site, instant, 2, direction(45.0, 145.0)))
        assertNull(AutomaticCalibrationPlanner.plan(quartet, site, instant.plusSeconds(12 * 3600), 2, direction(45.0, 145.0)))
    }
    @Test fun aSingleGreatCircleSweepOrTooFewDistinctTargetsCannotProvideMirrorCalibration() {
        val sameAxis = listOf(star(1, 30.0, 180.0), star(2, 42.0, 180.0), star(3, 55.0, 180.0), star(4, 70.0, 180.0))
        assertNull(AutomaticCalibrationPlanner.plan(sameAxis, site, instant, 4, direction(45.0, 180.0)))
        assertNull(AutomaticCalibrationPlanner.plan(quartet.take(3), site, instant, 4, direction(45.0, 145.0)))
        assertNull(AutomaticCalibrationPlanner.plan(quartet.take(1) + quartet.take(1), site, instant, 2, direction(45.0, 145.0)))
    }
    @Test fun azimuthWrapIsMeasuredOnTheSphereAndConfirmationExpiresOrRejectsAChangedStart() {
        val plan = requireNotNull(AutomaticCalibrationPlanner.plan(listOf(star(1, 45.0, 350.0), star(2, 45.0, 15.0)),
            site, instant, 2, direction(45.0, 355.0)))
        assertTrue(plan.canStart(site, instant, direction(45.0, 355.0)))
        assertFalse(plan.canStart(site, instant.plusMillis(120_001), direction(45.0, 355.0)))
        assertFalse(plan.canStart(site.copy(latitudeDeg = 30.0), instant, direction(45.0, 355.0)))
        assertFalse(plan.canStart(site, instant, direction(45.0, 180.0)))
    }
}
