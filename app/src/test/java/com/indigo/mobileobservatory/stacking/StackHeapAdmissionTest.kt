package com.indigo.mobileobservatory.stacking

import org.junit.Assert.*
import org.junit.Test

class StackHeapAdmissionTest {
    private val heap = 512L shl 20

    @Test fun collectiblePreviousImagesAreRecheckedBeforeRejectingANewPlan() {
        var used = 500L shl 20
        var collections = 0
        val budget = StackHeapAdmission(heap, { used }) { collections++; used = 96L shl 20 }
        assertEquals(352L shl 20, budget.available(0, 200L shl 20))
        assertEquals(1, collections)
    }

    @Test fun liveObjectsStillRejectThePlanAndRepeatedReadinessChecksDoNotRepeatCollection() {
        var collections = 0
        val budget = StackHeapAdmission(heap, { 500L shl 20 }) { collections++ }
        repeat(20) { assertEquals(0L, budget.available(0, 128L shl 20)) }
        assertEquals(1, collections)
        budget.allocationsReleased()
        assertEquals(0L, budget.available(0, 128L shl 20))
        assertEquals(2, collections)
    }

    @Test fun anImpossiblePlanNeverUsesCollectionToBypassTheCeiling() {
        var collections = 0
        val budget = StackHeapAdmission(heap, { 500L shl 20 }) { collections++ }
        assertEquals(0L, budget.available(0, heap))
        assertEquals(0, collections)
    }

    @Test fun aLargePlanCanRequestReclamationBelowTheGeneralPressureThreshold() {
        var used = 120L shl 20
        var collections = 0
        val budget = StackHeapAdmission(heap, { used }) { collections++; used = 64L shl 20 }
        assertEquals(384L shl 20, budget.available(0, 350L shl 20))
        assertEquals(1, collections)
    }

    @Test fun aPlanThatAlreadyFitsDoesNotRequestCollection() {
        var collections = 0
        val budget = StackHeapAdmission(heap, { 200L shl 20 }) { collections++ }
        assertEquals(248L shl 20, budget.available(0, 200L shl 20))
        assertEquals(0, collections)
    }

    @Test fun retainedCalibrationIsDeductedOnlyOnce() {
        var collections = 0
        val budget = StackHeapAdmission(heap, { 160L shl 20 }) { collections++ }
        assertEquals(384L shl 20, budget.available(96L shl 20, 200L shl 20))
        assertEquals(0, collections)
    }

    @Test fun taskLockSamplingDoesNotCollectOrConsumeTheNextUnlockedAttempt() {
        var used = 500L shl 20
        var collections = 0
        val budget = StackHeapAdmission(heap, { used }) { collections++; used = 96L shl 20 }
        assertEquals(0L, budget.available(0, 200L shl 20, allowCollection = false))
        assertEquals(0, collections)
        assertEquals(352L shl 20, budget.available(0, 200L shl 20))
        assertEquals(1, collections)
    }

    @Test fun aChangedBinOrRoiPlanCanRecheckNewlyRetiredCaptureBuffers() {
        var used = 500L shl 20
        var releaseOnCollection = false
        var collections = 0
        val budget = StackHeapAdmission(heap, { used }) {
            collections++
            if (releaseOnCollection) used = 96L shl 20
        }
        assertEquals(0L, budget.available(0, 200L shl 20))
        releaseOnCollection = true
        assertEquals(352L shl 20, budget.available(0, 100L shl 20))
        assertEquals(2, collections)
    }
}
