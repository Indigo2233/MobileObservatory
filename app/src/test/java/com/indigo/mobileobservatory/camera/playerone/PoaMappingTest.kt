package com.indigo.mobileobservatory.camera.playerone

import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.ReadoutMode
import com.indigo.mobileobservatory.camera.Roi
import com.playeroneastronomy.camera.PoaBayerPattern
import com.playeroneastronomy.camera.PoaImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PoaMappingTest {

    @Test
    fun gainRoundTrip() {
        assertEquals(10.0f, PoaMapping.gainToDb(100), 0.001f)
        assertEquals(100, PoaMapping.dbToGain(10.0f))
        assertEquals(0, PoaMapping.dbToGain(0f))
        assertEquals(5.5f, PoaMapping.gainToDb(55), 0.001f)
        assertEquals(55, PoaMapping.dbToGain(5.5f))
    }

    @Test
    fun gainRounding() {
        assertEquals(56, PoaMapping.dbToGain(5.55f))
        assertEquals(55, PoaMapping.dbToGain(5.54f))
    }

    @Test
    fun toPixelFormatRaw8Color() {
        assertEquals(PixelFormat.BAYER_RG8, PoaMapping.toPixelFormat(PoaImageFormat.RAW8, PoaBayerPattern.RG))
        assertEquals(PixelFormat.BAYER_BG8, PoaMapping.toPixelFormat(PoaImageFormat.RAW8, PoaBayerPattern.BG))
        assertEquals(PixelFormat.BAYER_GR8, PoaMapping.toPixelFormat(PoaImageFormat.RAW8, PoaBayerPattern.GR))
        assertEquals(PixelFormat.BAYER_GB8, PoaMapping.toPixelFormat(PoaImageFormat.RAW8, PoaBayerPattern.GB))
    }

    @Test
    fun toPixelFormatRaw8Mono() {
        assertEquals(PixelFormat.MONO8, PoaMapping.toPixelFormat(PoaImageFormat.RAW8, PoaBayerPattern.MONO))
    }

    @Test
    fun toPixelFormatRaw16() {
        assertEquals(PixelFormat.MONO16, PoaMapping.toPixelFormat(PoaImageFormat.RAW16, PoaBayerPattern.MONO))
        assertEquals(PixelFormat.BAYER_RG16, PoaMapping.toPixelFormat(PoaImageFormat.RAW16, PoaBayerPattern.RG))
        assertEquals(PixelFormat.BAYER_BG16, PoaMapping.toPixelFormat(PoaImageFormat.RAW16, PoaBayerPattern.BG))
        assertEquals(PixelFormat.BAYER_GR16, PoaMapping.toPixelFormat(PoaImageFormat.RAW16, PoaBayerPattern.GR))
        assertEquals(PixelFormat.BAYER_GB16, PoaMapping.toPixelFormat(PoaImageFormat.RAW16, PoaBayerPattern.GB))
    }

    @Test
    fun toPixelFormatMono8AndRgb24() {
        assertEquals(PixelFormat.MONO8, PoaMapping.toPixelFormat(PoaImageFormat.MONO8, PoaBayerPattern.RG))
        assertNull(PoaMapping.toPixelFormat(PoaImageFormat.RGB24, PoaBayerPattern.RG))
    }

    @Test
    fun alignRoiBasic() {
        val aligned = PoaMapping.alignRoi(Roi(1, 1, 100, 100), 1920, 1080)
        assertEquals(0, aligned.width % 4)
        assertEquals(0, aligned.height % 2)
        assertTrue(aligned.width >= 16)
        assertTrue(aligned.height >= 16)
        assertTrue(aligned.x >= 0)
        assertTrue(aligned.y >= 0)
        assertTrue(aligned.x + aligned.width <= 1920)
        assertTrue(aligned.y + aligned.height <= 1080)
    }

    @Test
    fun alignRoiEnforcesMinimum() {
        val aligned = PoaMapping.alignRoi(Roi(0, 0, 3, 1), 100, 100)
        assertEquals(16, aligned.width)
        assertEquals(16, aligned.height)
    }

    @Test
    fun alignRoiClampsToSensor() {
        val aligned = PoaMapping.alignRoi(Roi(0, 0, 2000, 2000), 100, 50)
        assertTrue(aligned.width <= 100)
        assertTrue(aligned.height <= 50)
        assertEquals(0, aligned.width % 4)
        assertEquals(0, aligned.height % 2)
    }

    @Test
    fun mapSensorModeNames() {
        assertEquals(ReadoutMode.HCG, PoaMapping.mapSensorModeName("HCG"))
        assertEquals(ReadoutMode.HCG, PoaMapping.mapSensorModeName("High Conversion Gain"))
        assertEquals(ReadoutMode.LCG, PoaMapping.mapSensorModeName("LCG Mode"))
        assertEquals(ReadoutMode.HDR, PoaMapping.mapSensorModeName("HDR"))
        assertEquals(ReadoutMode.LOW_NOISE, PoaMapping.mapSensorModeName("Low Noise"))
        assertEquals(ReadoutMode.NORMAL, PoaMapping.mapSensorModeName("Normal"))
        assertEquals(ReadoutMode.NORMAL, PoaMapping.mapSensorModeName(null))
    }

    @Test
    fun pickInitialPrefersRaw8() {
        val formats = listOf(PoaImageFormat.RGB24, PoaImageFormat.RAW16, PoaImageFormat.RAW8)
        val picked = PoaMapping.pickInitialFormat(formats, PoaBayerPattern.RG)
        assertEquals(PoaImageFormat.RAW8, picked!!.first)
        assertEquals(PixelFormat.BAYER_RG8, picked.second)
    }
}

class PoaDeviceMatcherTest {

    private fun camera(index: Int, id: Int, path: String? = null, pid: Int = 0x5850) =
        PoaDeviceMatcher.CameraKey(index, id, path, pid)

    private fun device(index: Int, reg: Int, name: String? = null, pid: Int = 0x5850) =
        PoaDeviceMatcher.DeviceKey(index, reg, name, pid)

    @Test
    fun matchesByLocalPathFirst() {
        val cameras = listOf(
            camera(0, id = 7, path = "/dev/bus/usb/001/003"),
            camera(1, id = 8, path = "/dev/bus/usb/001/002")
        )
        val devices = listOf(
            device(0, reg = 7, name = "/dev/bus/usb/001/002"),
            device(1, reg = 8, name = "/dev/bus/usb/001/003")
        )

        val result = PoaDeviceMatcher.match(cameras, devices)

        assertEquals(1, result[0])
        assertEquals(0, result[1])
    }

    @Test
    fun fallsBackToRegistrationId() {
        val cameras = listOf(camera(0, id = 3), camera(1, id = 4))
        val devices = listOf(device(0, reg = 4), device(1, reg = 3))

        val result = PoaDeviceMatcher.match(cameras, devices)

        assertEquals(1, result[0])
        assertEquals(0, result[1])
    }

    @Test
    fun fallsBackToUniqueProductId() {
        val cameras = listOf(camera(0, id = 100, pid = 0x5850), camera(1, id = 101, pid = 0x5851))
        val devices = listOf(device(0, reg = 900, pid = 0x5851), device(1, reg = 901, pid = 0x5850))

        val result = PoaDeviceMatcher.match(cameras, devices)

        assertEquals(1, result[0])
        assertEquals(0, result[1])
    }

    @Test
    fun singleCameraPairsWithSingleDevice() {
        val result = PoaDeviceMatcher.match(
            listOf(camera(0, id = 42, path = null, pid = 1)),
            listOf(device(0, reg = 999, name = null, pid = 2))
        )

        assertEquals(0, result[0])
    }

    @Test
    fun ambiguousIdenticalCamerasAreNotGuessed() {
        val cameras = listOf(camera(0, id = 1), camera(1, id = 2))
        val devices = listOf(device(0, reg = 50), device(1, reg = 51))

        val result = PoaDeviceMatcher.match(cameras, devices)

        assertTrue(result.isEmpty())
    }

    @Test
    fun neverReusesTheSameDevice() {
        val cameras = listOf(camera(0, id = 5, path = "/dev/a"), camera(1, id = 5, path = "/dev/a"))
        val devices = listOf(device(0, reg = 5, name = "/dev/a"))

        val result = PoaDeviceMatcher.match(cameras, devices)

        assertEquals(1, result.size)
        assertEquals(0, result[0])
    }

    @Test
    fun emptyInputsAreSafe() {
        assertTrue(PoaDeviceMatcher.match(emptyList(), listOf(device(0, 1))).isEmpty())
        assertTrue(PoaDeviceMatcher.match(listOf(camera(0, 1)), emptyList()).isEmpty())
    }
}

class PlayerOneClaimRegistryTest {

    @Before
    fun clearClaims() {
        PlayerOneClaimRegistry.resetForTest()
    }

    @Test
    fun claimIsExclusive() {
        assertTrue(PlayerOneClaimRegistry.claim(1))
        assertTrue(!PlayerOneClaimRegistry.claim(1))
        assertTrue(PlayerOneClaimRegistry.claim(2))
        PlayerOneClaimRegistry.release(1)
        assertTrue(PlayerOneClaimRegistry.claim(1))
        PlayerOneClaimRegistry.release(1)
        PlayerOneClaimRegistry.release(2)
    }

    @Test
    fun releaseIdempotent() {
        PlayerOneClaimRegistry.release(99)
        assertTrue(PlayerOneClaimRegistry.claim(99))
        PlayerOneClaimRegistry.release(99)
        PlayerOneClaimRegistry.release(99)
        assertTrue(PlayerOneClaimRegistry.claim(99))
        PlayerOneClaimRegistry.release(99)
    }
}
