package com.indigo.mobileobservatory.accessories.rotator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WandererRotatorProtocolTest {
    @Test
    fun parsesSupportedMiniHandshake() {
        val handshake = requireNotNull(
            WandererRotatorProtocol.parseHandshake(
                "WandererRotatorMiniA20240226A-10000A0.5A1A"
            )
        )

        assertEquals(WandererRotatorProtocol.Model.MINI, handshake.model)
        assertEquals(20240226, handshake.firmware)
        assertEquals(-10_000, handshake.mechanicalAngleMilliDegrees)
        assertEquals(350.0, handshake.angleDegrees, 0.0001)
        assertEquals(0.5, handshake.backlashDegrees, 0.0001)
        assertTrue(handshake.reversed)
        assertTrue(WandererRotatorProtocol.isFirmwareSupported(handshake))
    }

    @Test
    fun parsesLiteModelsWithTheirOwnStepScale() {
        val liteV1 = requireNotNull(
            WandererRotatorProtocol.parseHandshake("WandererRotatorLiteA20240403A0A0A0A")
        )
        val liteV2 = requireNotNull(
            WandererRotatorProtocol.parseHandshake("WandererRotatorLiteV2A20240226A0A0A0A")
        )

        assertEquals(WandererRotatorProtocol.Model.LITE_V1, liteV1.model)
        assertEquals(1155, liteV1.model.stepsPerDegree)
        assertEquals(WandererRotatorProtocol.Model.LITE_V2, liteV2.model)
        assertEquals(1199, liteV2.model.stepsPerDegree)
    }

    @Test
    fun rejectsUnknownAndOutdatedHandshakes() {
        assertNull(WandererRotatorProtocol.parseHandshake("WandererCoverV4A20250506A0A0A0A"))
        val outdated = requireNotNull(
            WandererRotatorProtocol.parseHandshake("WandererRotatorLiteA20240101A0A0A0A")
        )
        assertFalse(WandererRotatorProtocol.isFirmwareSupported(outdated))
    }

    @Test
    fun encodesRelativeMoveUsingModelSpecificSteps() {
        assertEquals("1011420", WandererRotatorProtocol.encodeMove(10.0, WandererRotatorProtocol.Model.MINI))
        assertEquals("988010", WandererRotatorProtocol.encodeMove(-10.0, WandererRotatorProtocol.Model.LITE_V2))
        assertEquals("1700001", WandererRotatorProtocol.encodeReverse(true))
        assertEquals("1700000", WandererRotatorProtocol.encodeReverse(false))
    }

    @Test
    fun parsesCompletionAndSelectsShortestAbsoluteMove() {
        val completion = requireNotNull(
            WandererRotatorProtocol.parseMoveCompletion("10.00A10000A")
        )

        assertEquals(10.0, completion.movedDegrees, 0.0001)
        assertEquals(10_000, completion.mechanicalAngleMilliDegrees)
        assertEquals(-20.0, WandererRotatorProtocol.shortestDelta(10.0, 350.0), 0.0001)
    }

    @Test
    fun parsesIntegerMoveCompletion() {
        val completion = requireNotNull(
            WandererRotatorProtocol.parseMoveCompletion("-5A-5000A")
        )

        assertEquals(-5.0, completion.movedDegrees, 0.0001)
        assertEquals(-5_000, completion.mechanicalAngleMilliDegrees)
    }
}
