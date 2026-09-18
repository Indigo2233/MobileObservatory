package com.indigo.mobileobservatory.camera.zwo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZwoSdkTest {
    @Test
    fun openIndexIgnoresPropertyHeightMasqueradingAsCameraId() {
        assertEquals(0, ZwoSdk.openIndex(0))
        assertEquals(0, ZwoSdk.openIndex(-1))
        assertEquals(1, ZwoSdk.openIndex(1))
    }

    @Test
    fun captureBufferUsesCurrentFormatNotRgb24FullFrame() {
        assertEquals(9576 * 6388, ZwoSdk.captureDirectBufferBytes(9576, 6388, 1))
        assertEquals(9576 * 6388 * 2, ZwoSdk.captureDirectBufferBytes(9576, 6388, 2))
        assertTrue(ZwoSdk.captureDirectBufferBytes(9576, 6388, 1) < 9576 * 6388 * 3)
    }

    @Test
    fun previewStaysAtBin1SoRoiMatchesRecording() {
        assertEquals(1, ZwoSdk.defaultPreviewBin(3008, 3008, listOf(1, 2)))
        assertEquals(1, ZwoSdk.defaultPreviewBin(9576, 6388, listOf(1, 2, 3)))
        assertEquals(1, ZwoSdk.defaultPreviewBin(9576, 6388, listOf(1)))
    }

    @Test
    fun rejectsJniMisparsedSensorSize() {
        assertTrue(ZwoSdk.sensorSizePlausible(9576, 6388))
        assertFalse(ZwoSdk.sensorSizePlausible(1, 9576))
        assertFalse(ZwoSdk.sensorSizePlausible(6388, 0))
    }

    @Test
    fun nullJniReturnIsNotSuccess() {
        assertFalse(ZwoSdk.returnSucceeded(null))
        assertTrue(ZwoSdk.returnSucceeded(0))
        assertFalse(ZwoSdk.returnSucceeded(1))
    }

    @Test
    fun fallbackSizeFor6200() {
        assertEquals(9576 to 6388, ZwoSdk.fallbackSensorSize("ZWO ASI6200MC Pro"))
        assertEquals(9576 to 6388, ZwoSdk.fallbackSensorSize("ASI6200MM Pro"))
    }

    @Test
    fun largeFramePoolKeepsAtMostOneBuffer() {
        assertEquals(1, ZwoSdk.maxPooledFrameBuffers(61_171_488))
        assertEquals(2, ZwoSdk.maxPooledFrameBuffers(16 * 1024 * 1024))
        assertEquals(8, ZwoSdk.maxPooledFrameBuffers(3_008 * 3_008))
    }

    @Test
    fun grabBufferIsLargerThanImageSize() {
        val image = ZwoSdk.captureDirectBufferBytes(4784, 3194, 1)
        val grab = ZwoSdk.captureGrabBufferBytes(4784, 3194, 1)
        assertTrue(grab > image)
        assertTrue(grab >= image + 4096)
    }

    @Test
    fun grabTimeoutCoversLargeUsb2Frame() {
        val timeout = ZwoSdk.grabTimeoutMs(10_000f, 4784 * 3194)
        assertTrue(timeout >= 3000)
        assertTrue(ZwoSdk.grabTimeoutMs(2_000_000f, 1_000_000) >= 4000)
    }

    @Test
    fun zwoTargetTempUsesWholeCelsiusWhenRangeIsSmall() {
        assertTrue(ZwoSdk.targetTempIsWholeCelsius(-40, 30))
        assertFalse(ZwoSdk.targetTempIsWholeCelsius(-400, 300))
        assertEquals(-100, ZwoSdk.nativeTargetToTenths(-10, wholeCelsius = true))
        assertEquals(-10L, ZwoSdk.tenthsToNativeTarget(-100, wholeCelsius = true))
        assertEquals(-100, ZwoSdk.nativeTargetToTenths(-100, wholeCelsius = false))
    }
}
