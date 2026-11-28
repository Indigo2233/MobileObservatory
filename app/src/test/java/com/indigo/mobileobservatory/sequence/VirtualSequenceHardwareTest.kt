package com.indigo.mobileobservatory.sequence

import java.nio.file.Files
import com.indigo.mobileobservatory.util.FitsHeaderParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualSequenceHardwareTest {
    @Test
    fun singleTargetRunsFromCoolingThroughTwoFiltersToShutdown() = runBlocking {
        val output = Files.createTempDirectory("sequence-m31").toFile()
        try {
            withTimeout(30_000) {
                val hardware = VirtualSequenceHardware()
                val sessions = output.resolve("sessions")
                val sequence = SequenceRuntime(output.resolve("plans"), sessions, this, hardware, hardware)
                val draft = SimpleSequenceDraft(
                    title = "M31", raHours = 0.7123, decDegrees = 41.269,
                    coolToC = -10.0, slewBefore = true, centerBefore = true,
                    autofocusBefore = true, guideBefore = true, ditherEvery = 2,
                    rows = listOf(SimpleExposureRow("L", 30.0, 120, 10, 3),
                        SimpleExposureRow("Ha", 60.0, 180, 10, 3)),
                    endStopGuide = true, endWarm = true, endStopTracking = true, endCloseCover = true
                )
                sequence.updateDraft(draft)
                sequence.start()
                val result = sequence.state.first { it.phase != SequencePhase.Running }
                assertEquals(result.message, SequencePhase.Completed, result.phase)
                assertEquals(null, result.message)
                assertEquals(6, result.framesDone)
                val session = sessions.listFiles()!!.single()
                val manifest = JSONObject(session.resolve("session.json").readText())
                assertEquals("Completed", manifest.getString("phase"))
                val plan = parseNinaSequence(manifest.getJSONObject("sequence").toString())
                val operations = listOf("CoolCamera", "SlewScopeToRaDec", "Center", "RunAutofocus",
                    "StartGuiding", "Dither", "StopGuiding", "WarmCamera", "SetTracking", "CloseCover")
                for (operation in operations) {
                    val instruction = plan.find(operation).single()
                    assertEquals(operation, "FINISHED", result.nodeStatus[instruction.id])
                }
                assertEquals(1, manifest.getJSONArray("autofocus").length())
                assertEquals(6, manifest.getJSONArray("frames").length())
                for (index in 0 until 6) {
                    val frame = manifest.getJSONArray("frames").getJSONObject(index)
                    val header = FitsHeaderParser.read(session.resolve(frame.getString("name")))
                    assertEquals(if (index < 3) "L" else "Ha", header["FILTER"])
                    assertEquals(if (index < 3) 30.0 else 60.0, header.doubleValue("EXPOSURE")!!, 0.00001)
                    assertEquals("LIGHT", header["IMAGETYP"])
                }
                val status = hardware.status.value
                assertEquals(draft.raHours, status.raHours, 0.0001)
                assertEquals(draft.decDegrees, status.decDeg, 0.0001)
                assertTrue(!status.guiding && !status.tracking && !status.coolerOn && !status.coverOpen)
            }
        } finally {
            output.deleteRecursively()
        }
    }

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
