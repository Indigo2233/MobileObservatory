package com.indigo.mobileobservatory.camera

import org.junit.Assert.*
import org.junit.Test

class PhoneSkyResourceBudgetTest {
    private val mib = 1024L * 1024
    private fun rejected(expected: PhoneSkyResourceLimit? = null, operation: () -> Unit) {
        try { operation(); fail("Unsafe request was admitted") }
        catch (error: PhoneSkyResourceException) { if (expected != null) assertEquals(expected, error.limit) }
    }
    @Test fun lowHeapRejectsSixteenFramesButAdmitsAnHonestSingleFrameRequest() {
        val plan = PhoneMonoFrameConverter.plan(4032, 3024, 2048, true)
        val low = PhoneHeapBudget(256 * mib, 128 * mib)
        rejected(PhoneSkyResourceLimit.HEAP) { PhoneSkyResourceBudget.requireCaptureHeap(plan, 2, 16, low) }
        PhoneSkyResourceBudget.requireCaptureHeap(plan, 2, 1, low)
        PhoneSkyResourceBudget.requireCaptureHeap(plan, 2, 16, PhoneHeapBudget(512 * mib, 64 * mib))
    }
    @Test fun nativeRawAndOriginalDngStorageHaveSeparateAdmissionBudgets() {
        rejected(PhoneSkyResourceLimit.CAMERA_BUFFERS) { PhoneSkyResourceBudget.requireNativeCapture(16384, 12288, true, 512 * mib) }
        PhoneSkyResourceBudget.requireNativeCapture(16384, 12288, true, 6 * 1024 * mib)
        rejected(PhoneSkyResourceLimit.STORAGE) { PhoneSkyResourceBudget.requireStorage(64 * mib, 4032, 3024, 16, 8 * mib) }
        PhoneSkyResourceBudget.requireStorage(2 * 1024 * mib, 4032, 3024, 16, 8 * mib)
    }
    @Test fun multiplicationArrayLimitsAndOutputAllocationCannotOverflowIntoAFalsePass() {
        rejected { PhoneSkyResourceBudget.bytes(Long.MAX_VALUE, 2) }
        rejected { PhoneSkyResourceBudget.frameBytes(Int.MAX_VALUE, Int.MAX_VALUE, 2) }
        rejected { PhoneSkyResourceBudget.requireNativeCapture(Int.MAX_VALUE, Int.MAX_VALUE, true, Long.MAX_VALUE) }
        rejected { PhoneSkyResourceBudget.requireStorage(Long.MAX_VALUE, 4000, 3000, 16, Long.MAX_VALUE) }
        rejected(PhoneSkyResourceLimit.HEAP) { PhoneSkyResourceBudget.requireStackOutput(2048, 2048, 2, 16, PhoneHeapBudget(64 * mib, 56 * mib)) }
        PhoneSkyResourceBudget.requireStackOutput(2048, 2048, 2, 16, PhoneHeapBudget(256 * mib, 128 * mib))
    }
}
