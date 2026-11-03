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

    @Test fun aReleasedWorkspaceIsCollectedOnceEvenWhenThePlanIsUnchanged() {
        var used = 500L shl 20
        var collections = 0
        val budget = StackHeapAdmission(heap, { used }) { collections++; used = 96L shl 20 }
        // The first session collects the retired images and admits the plan.
        assertEquals(352L shl 20, budget.available(0, 200L shl 20))
        assertEquals(1, collections)
        // Reset releases the workspace. The next identical plan must re-sample the heap,
        // otherwise the budget keeps measuring the retired session's arrays.
        budget.allocationsReleased()
        assertEquals(352L shl 20, budget.available(0, 200L shl 20))
        assertEquals(2, collections)
        // The budget recovered; a following check needs no further collection.
        assertEquals(352L shl 20, budget.available(0, 200L shl 20))
        assertEquals(2, collections)
    }

    @Test fun aReleasedWorkspaceRecoversTheBudgetForAnUnchangedPlan() {
        var used = 200L shl 20
        var collections = 0
        val budget = StackHeapAdmission(heap, { used }) { collections++; used = 64L shl 20 }
        // The plan fits the retained heap, so nothing is collected yet.
        val before = budget.available(0, 200L shl 20)
        assertEquals(0, collections)
        assertTrue("A retained workspace must shrink the budget", before < 384L shl 20)
        budget.allocationsReleased()
        val after = budget.available(0, 200L shl 20)
        assertTrue("A released workspace must restore the budget", after > before)
        assertEquals(1, collections)
    }
}
