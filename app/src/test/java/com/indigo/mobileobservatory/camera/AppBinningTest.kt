package com.indigo.mobileobservatory.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBinningTest {
    private class Camera : CameraBinningCapable {
        override val supportedHardwareBins = listOf(1, 2, 4)
        override var currentHardwareBin = 1
        val calls = mutableListOf<Int>()
        var ignoreFour = false
        var rejectReset = false
        override var binningStateVerified = true
        override fun setHardwareBin(bin: Int): Boolean {
            calls += bin
            if (bin == 1 && rejectReset) return false
            if (bin == 4 && ignoreFour) return true
            currentHardwareBin = bin
            binningStateVerified = true
            return true
        }
    }

    @Test fun anUnverifiedCachedFactorMustBeReappliedBeforeUse() {
        val camera = Camera().apply { currentHardwareBin = 2; binningStateVerified = false }
        assertEquals(AppBinPlan(2, 2, 1), AppBinning.apply(2, camera))
        assertEquals(listOf(2), camera.calls)
    }

    @Test fun sdkSuccessRequiresMatchingReadbackBeforeSoftwareRemainderIsCalculated() {
        val camera = Camera().apply { ignoreFour = true }
        assertEquals(AppBinPlan(4, 2, 2), AppBinning.apply(4, camera))
        assertEquals(listOf(4, 2), camera.calls)
    }

    @Test fun restoringAnUndivisibleNativeBinPrecedesSoftwareFallback() {
        val camera = Camera().apply { currentHardwareBin = 2 }
        assertEquals(AppBinPlan(3, 1, 3), AppBinning.apply(3, camera))
        assertEquals(listOf(1), camera.calls)
    }

    @Test fun failedNativeResetCannotBeReportedAsSoftwareOnlyBinning() {
        val camera = Camera().apply { currentHardwareBin = 2; rejectReset = true }
        try { AppBinning.apply(3, camera); org.junit.Assert.fail("Native bin must be known") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("restore")) }
    }

    @Test fun unverifiedColourOutputFallsBackToNativeBin1AndSoftwareBin2() {
        val camera = Camera()
        assertEquals(AppBinPlan(2, 1, 2), AppBinning.apply(2, camera) { it == 1 })
        assertEquals(listOf(2, 1), camera.calls)
        assertEquals(1, camera.currentHardwareBin)
    }

    @Test fun verifiedColourOutputRetainsNativeBin2() {
        val camera = Camera()
        assertEquals(AppBinPlan(2, 2, 1), AppBinning.apply(2, camera) { true })
        assertEquals(listOf(2), camera.calls)
    }

    @Test fun ambiguousColourFallbackMustConfirmNativeBin1BeforePublishing() {
        val camera = Camera().apply { rejectReset = true }
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            AppBinning.apply(2, camera) { it == 1 }
        }
    }

    @Test fun restoringBin1MustAlsoRestoreAnAcceptedPixelLayout() {
        val camera = Camera()
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            AppBinning.apply(2, camera) { false }
        }
    }

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
