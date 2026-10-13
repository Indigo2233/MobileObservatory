package com.indigo.mobileobservatory.camera.toupcam

import org.junit.Assert.*
import org.junit.Test

class ToupcamFrameLayoutTest {
    @Test fun digitalBinUsesActualFinalDimensionsWithTheFullSafePullAllocation() {
        val capacity = 3536L * 3536 * 2
        val layout = requireNotNull(ToupcamFrameLayout.validate(3536, 3536, 1768, 1768, 1768, 1768, 2, capacity))
        assertEquals(1768, layout.width); assertEquals(1768, layout.height)
        assertEquals(1768 * 1768 * 2, layout.payloadBytes)
    }
    @Test fun originalRoiCoordinatesDoNotReplaceBinnedPayloadOrKeepTheUnusedTail() {
        val layout = requireNotNull(ToupcamFrameLayout.validate(4000, 3000, 500, 400, 500, 400, 2, 4000L * 3000 * 2))
        assertEquals(400_000, layout.payloadBytes)
        assertEquals(500, layout.width); assertEquals(400, layout.height)
    }
    @Test fun fullResolutionKeepsIdenticalLayoutAndAllowsDenseNonDibRowWidths() {
        val full = requireNotNull(ToupcamFrameLayout.validate(1920, 1080, 1920, 1080, 1920, 1080, 2, 1920L * 1080 * 2))
        assertEquals(1920 * 1080 * 2, full.payloadBytes)
        val raw8 = requireNotNull(ToupcamFrameLayout.validate(4000, 3000, 1178, 1000, 1178, 1000, 1, 4000L * 3000))
        assertEquals(1_178_000, raw8.payloadBytes)
    }
    @Test fun oldQueuedDimensionsMismatchingSdkFinalSizeAreRejected() {
        assertNull(ToupcamFrameLayout.validate(4000, 3000, 4000, 3000, 1000, 800, 2, 4000L * 3000 * 2))
        assertNull(ToupcamFrameLayout.validate(4000, 3000, 1000, 800, 1000, 700, 2, 4000L * 3000 * 2))
    }
    @Test fun croppedOnlyOrTruncatedPullAllocationIsUnsafeEvenIfNewPayloadFits() {
        assertNull(ToupcamFrameLayout.validate(4000, 3000, 500, 400, 500, 400, 2, 400_000))
        assertNull(ToupcamFrameLayout.validate(4000, 3000, 500, 400, 500, 400, 2, 4000L * 3000 * 2 - 1))
        assertNull(ToupcamFrameLayout.validate(4000, 3000, 5000, 4000, 5000, 4000, 2, 4000L * 3000 * 2))
    }
    @Test fun invalidAndOverflowingDimensionsNeverEnterAnArrayCalculation() {
        assertNull(ToupcamFrameLayout.validate(0, 3000, 100, 100, 100, 100, 2, 10_000))
        assertNull(ToupcamFrameLayout.validate(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE,
            Int.MAX_VALUE, Int.MAX_VALUE, 6, Int.MAX_VALUE.toLong()))
        assertNull(ToupcamFrameLayout.validate(100, 100, 0, 100, 0, 100, 2, 20_000))
    }
    @Test fun compactDeliveryDoesNotBorrowMutableSdkScratchAndRejectsTruncatedDestinations() {
        val layout = requireNotNull(ToupcamFrameLayout.validate(8, 8, 4, 4, 4, 4, 2, 128))
        val scratch = ByteArray(128) { it.toByte() }; val delivered = ByteArray(layout.payloadBytes)
        layout.copyPayloadInto(scratch, delivered)
        scratch.fill(0)
        assertArrayEquals(ByteArray(32) { it.toByte() }, delivered)
        assertThrows(IllegalArgumentException::class.java) { layout.copyPayloadInto(ByteArray(31), delivered) }
        assertThrows(IllegalArgumentException::class.java) { layout.copyPayloadInto(scratch, ByteArray(31)) }
    }
}
