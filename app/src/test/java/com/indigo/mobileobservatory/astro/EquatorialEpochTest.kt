package com.indigo.mobileobservatory.astro

import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.mount.PrecisionGotoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EquatorialEpochTest {
    private val m31J2000 = EquatorialCoordinates(raDeg = 10.68458, decDeg = 41.26917)
    private val epoch2026 = Instant.parse("2026-09-21T00:00:00Z")

    @Test
    fun jnowFramePassesThrough() {
        val (raHours, decDeg) = EquatorialEpoch.toJnowHours(
            raHours = 5.5,
            decDeg = -12.25,
            frame = "JNOW",
            instant = epoch2026
        )
        assertEquals(5.5, raHours, 1e-12)
        assertEquals(-12.25, decDeg, 1e-12)
    }

    @Test
    fun catalogAndAstapTagsAreTreatedAsJ2000() {
        assertTrue(EquatorialEpoch.isJ2000("J2000"))
        assertTrue(EquatorialEpoch.isJ2000("icrf"))
        assertFalse(EquatorialEpoch.isJ2000("JNOW"))
        assertFalse(EquatorialEpoch.isJ2000(""))
    }

    @Test
    fun roundTripJ2000ThroughJnow() {
        val jnow = EquatorialEpoch.j2000ToJnow(m31J2000, epoch2026)
        val back = EquatorialEpoch.jnowToJ2000(jnow, epoch2026)
        assertEquals(m31J2000.raDeg, back.raDeg, 1e-8)
        assertEquals(m31J2000.decDeg, back.decDeg, 1e-8)
    }

    @Test
    fun mixingAstapJ2000WithJnowTargetExceedsDefaultStop() {
        val jnow = EquatorialEpoch.j2000ToJnow(m31J2000, epoch2026)
        val target = MountCoordinates(raHours = jnow.raDeg / 15.0, decDeg = jnow.decDeg)
        val solvedAsJ2000 = MountCoordinates(
            raHours = m31J2000.raDeg / 15.0,
            decDeg = m31J2000.decDeg
        )
        val mixedArcmin = PrecisionGotoMath.degreesToArcmin(
            PrecisionGotoMath.angularSeparationDeg(target, solvedAsJ2000)
        )
        assertTrue(
            "precession in 2026 must be larger than the 6′ stop; was $mixedArcmin′",
            mixedArcmin > PrecisionGotoMath.TOLERANCE_ARCMIN
        )
        assertTrue(mixedArcmin in 15.0..30.0)

        val (solvedRa, solvedDec) = EquatorialEpoch.j2000DegToJnowHours(
            m31J2000.raDeg,
            m31J2000.decDeg,
            epoch2026
        )
        val alignedArcmin = PrecisionGotoMath.degreesToArcmin(
            PrecisionGotoMath.angularSeparationDeg(
                target,
                MountCoordinates(solvedRa, solvedDec)
            )
        )
        assertTrue(alignedArcmin < 0.05)
        assertTrue(PrecisionGotoMath.withinTolerance(alignedArcmin))
    }

    @Test
    fun catalogIcrfConvertsToTheSameJnowAsJ2000() {
        val fromCatalog = EquatorialEpoch.toJnowHours(
            raHours = m31J2000.raDeg / 15.0,
            decDeg = m31J2000.decDeg,
            frame = "ICRF",
            instant = epoch2026
        )
        val fromAstap = EquatorialEpoch.j2000DegToJnowHours(
            m31J2000.raDeg,
            m31J2000.decDeg,
            epoch2026
        )
        assertEquals(fromAstap.first, fromCatalog.first, 1e-12)
        assertEquals(fromAstap.second, fromCatalog.second, 1e-12)
    }
}
