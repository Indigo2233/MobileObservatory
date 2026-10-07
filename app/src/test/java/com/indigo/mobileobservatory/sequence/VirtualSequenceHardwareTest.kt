package com.indigo.mobileobservatory.sequence

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualSequenceHardwareTest {
    @Test
    fun exposureWritesDeterministicFitsWithRequestedMetadata() = runBlocking {
        val output = Files.createTempDirectory("virtual-sequence").toFile()
        try {
            val hardware = VirtualSequenceHardware()
            hardware.switchFilter("Ha")
            val frame = hardware.takeExposure(
                seconds = 12.5,
                gain = 180,
                offset = 20,
                destDir = output,
                binning = 2,
                imageType = "DARK"
            )

            val fits = output.resolve(frame.name)
            assertTrue(fits.isFile)
            val header = fits.inputStream().use { input ->
                val bytes = ByteArray(2_880)
                assertEquals(bytes.size, input.read(bytes))
                bytes.toString(Charsets.US_ASCII)
            }
            assertTrue(header.contains("NAXIS1  =                  320"))
            assertTrue(header.contains("NAXIS2  =                  240"))
            assertTrue(header.contains("IMAGETYP= 'DARK'"))
            assertTrue(header.contains("FILTER  = 'Ha'"))
            assertTrue(header.contains("XBINNING=                    2"))
            assertEquals(36, frame.starCount)
            assertEquals("Ha", frame.filter)
        } finally {
            output.deleteRecursively()
        }
    }

    @Test
    fun virtualDevicesPublishSequenceState() = runBlocking {
        val hardware = VirtualSequenceHardware()
        hardware.cool(-10.0, durationMinutes = 2.0)
        hardware.slew(6.25, -22.5)
        hardware.guide(true, forceCalibration = true)
        hardware.moveFocuser(21_250)
        hardware.rotateTo(42.0)

        assertEquals(-100, hardware.status.value.sensorTemperatureTenths)
        assertEquals(2.0, hardware.status.value.lastCoolingDurationMinutes, 0.0001)
        assertEquals(6.25, hardware.raHours(), 0.0001)
        assertEquals(-22.5, hardware.decDeg(), 0.0001)
        assertTrue(hardware.guidingLocked())
        assertTrue(hardware.status.value.guideForceCalibration)
        assertEquals(21_250, hardware.focuserPosition())
        assertEquals(42.0, hardware.status.value.rotatorAngleDeg, 0.0001)
    }


    @Test
    fun virtualDeviceControlsUpdateState() = runBlocking {
        val hardware = VirtualSequenceHardware()

        hardware.dewHeater(true)
        hardware.usbLimit(73)
        hardware.flatLight(true)
        hardware.flatBrightness(190)
        hardware.flatLight(false)

        assertEquals(true, hardware.status.value.dewHeaterOn)
        assertEquals(73, hardware.status.value.usbLimit)
        assertEquals(false, hardware.status.value.flatLightOn)
        assertEquals(190, hardware.status.value.flatBrightness)
    }

    @Test
    fun virtualWarmRecordsRequestedDuration() = runBlocking {
        val hardware = VirtualSequenceHardware()
        hardware.cool(-5.0, durationMinutes = 1.0)
        hardware.warm(durationMinutes = 3.0)

        assertEquals(3.0, hardware.status.value.lastWarmingDurationMinutes, 0.0001)
        assertEquals(120, hardware.status.value.sensorTemperatureTenths)
        assertEquals(false, hardware.status.value.coolerOn)
    }
}
