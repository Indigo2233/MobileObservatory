package com.indigo.mobileobservatory.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBinningTest {
    @Test
    fun usesHardwareWhenCameraListsTheFactor() {
        val plan = AppBinning.plan(2, listOf(1, 2, 4))
        assertEquals(2, plan.appBin)
        assertEquals(2, plan.hardwareBin)
        assertEquals(1, plan.softwareBin)
        assertTrue(plan.usesHardware)
        assertFalse(plan.usesSoftware)
    }

    @Test
    fun usesLargestHardwareDivisorThenSoftware() {
        val plan = AppBinning.plan(4, listOf(1, 2))
        assertEquals(4, plan.appBin)
        assertEquals(2, plan.hardwareBin)
        assertEquals(2, plan.softwareBin)
        assertTrue(plan.usesHardware)
        assertTrue(plan.usesSoftware)
    }

    @Test
    fun skipsHardwareThatDoesNotDivide() {
        val plan = AppBinning.plan(3, listOf(1, 2, 4))
        assertEquals(3, plan.appBin)
        assertEquals(1, plan.hardwareBin)
        assertEquals(3, plan.softwareBin)
        assertFalse(plan.usesHardware)
        assertTrue(plan.usesSoftware)
    }

    @Test
    fun bin1NeverUsesSoftware() {
        val plan = AppBinning.plan(1, emptyList())
        assertEquals(1, plan.hardwareBin)
        assertEquals(1, plan.softwareBin)
        assertFalse(plan.usesHardware)
        assertFalse(plan.usesSoftware)
    }

    @Test
    fun hardwareCandidatesAreDescendingDivisors() {
        assertEquals(listOf(4, 2, 1), AppBinning.hardwareCandidates(4, listOf(1, 2, 3, 4)))
        assertEquals(listOf(1), AppBinning.hardwareCandidates(3, listOf(1, 2, 4)))
    }
}
