package com.indigo.mobileobservatory.sequence

import com.indigo.mobileobservatory.sequence.catalog.SequenceCatalog
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceRuntimeHardwareMappingTest {
    @Test
    fun cameraDurationAndGuideCalibrationFieldsReachHardware() = runTest {
        val hardware = RecordingSequenceHardware()
        val runtime = SequenceRuntime(
            templatesDir = Files.createTempDirectory("sequence-templates").toFile(),
            sessionsDir = Files.createTempDirectory("sequence-sessions").toFile(),
            scope = this,
            hardware = hardware
        )

        val cool = SequenceCatalog.create("CoolCamera")
        putExpression(cool.fields, "Temperature", -12.0, "cool-temperature")
        putExpression(cool.fields, "Duration", 6.0, "cool-duration")
        runtime.execute(cool, "CoolCamera")

        val warm = SequenceCatalog.create("WarmCamera")
        putExpression(warm.fields, "Duration", 4.0, "warm-duration")
        runtime.execute(warm, "WarmCamera")

        val guide = SequenceCatalog.create("StartGuiding")
        guide.fields["ForceCalibration"] = NinaValue.Bool(true)
        runtime.execute(guide, "StartGuiding")

        assertEquals(-12.0, checkNotNull(hardware.coolTargetC), 0.0001)
        assertEquals(6.0, checkNotNull(hardware.coolDurationMinutes), 0.0001)
        assertEquals(4.0, checkNotNull(hardware.warmDurationMinutes), 0.0001)
        assertTrue(hardware.guideEnabled == true)
        assertTrue(hardware.guideForceCalibration == true)
    }


    @Test
    fun deviceControlFieldsReachHardware() = runTest {
        val hardware = RecordingSequenceHardware()
        val runtime = SequenceRuntime(
            templatesDir = Files.createTempDirectory("sequence-templates").toFile(),
            sessionsDir = Files.createTempDirectory("sequence-sessions").toFile(),
            scope = this,
            hardware = hardware
        )

        val dew = SequenceCatalog.create("DewHeater")
        dew.fields["OnOff"] = NinaValue.Bool(true)
        runtime.execute(dew, "DewHeater")

        val usb = SequenceCatalog.create("SetUSBLimit")
        usb.fields["USBLimit"] = NinaValue.Num(64.0, true)
        runtime.execute(usb, "SetUSBLimit")

        val light = SequenceCatalog.create("ToggleLight")
        light.fields["OnOff"] = NinaValue.Bool(true)
        runtime.execute(light, "ToggleLight")

        val brightness = SequenceCatalog.create("SetBrightness")
        putExpression(brightness.fields, "Brightness", 72.0, "flat-brightness")
        runtime.execute(brightness, "SetBrightness")

        assertEquals(true, hardware.dewHeaterOn)
        assertEquals(64, hardware.usbLimitValue)
        assertEquals(true, hardware.flatLightOn)
        assertEquals(72, hardware.flatBrightnessValue)
    }

    @Test
    fun importedSequenceIsSavedAndShareable() = runTest {
        val templatesDir = Files.createTempDirectory("sequence-templates").toFile()
        val runtime = SequenceRuntime(
            templatesDir = templatesDir,
            sessionsDir = Files.createTempDirectory("sequence-sessions").toFile(),
            scope = this,
            hardware = RecordingSequenceHardware()
        )
        val incoming = emptyAdvancedSequence("Imported Target").toJson()

        runtime.importJson("Downloaded Sequence.json", incoming)

        assertEquals(SequenceEditorMode.Advanced, runtime.mode.value)
        assertEquals(listOf("Downloaded Sequence"), runtime.templateNames())
        assertEquals("Imported Target", parseNinaSequence(runtime.exportJson()).textField("Name"))
        val share = runtime.shareFile()
        assertTrue(share.isFile)
        assertTrue(share.name.endsWith(".json"))
    }

    @Test
    fun starMapTargetUsesTheChosenEditorMode() = runTest {
        val runtime = SequenceRuntime(
            templatesDir = Files.createTempDirectory("sequence-templates").toFile(),
            sessionsDir = Files.createTempDirectory("sequence-sessions").toFile(),
            scope = this,
            hardware = RecordingSequenceHardware()
        )

        runtime.setMode(SequenceEditorMode.Advanced)
        runtime.addTarget("Simple target", 1.25, -2.5, 30.0, SequenceEditorMode.Simple)
        assertEquals(SequenceEditorMode.Simple, runtime.mode.value)
        assertEquals("Simple target", runtime.draft.value.title)
        assertEquals(30.0, runtime.draft.value.positionAngleDeg, 0.0)

        runtime.addTarget("Advanced target", 3.5, 4.5, 90.0, SequenceEditorMode.Advanced)
        assertEquals(SequenceEditorMode.Advanced, runtime.mode.value)
        val targets = checkNotNull(runtime.document.value)
            .childItems()
            .first { it.className == "TargetAreaContainer" }
            .childItems()
        assertTrue(targets.any { dsoTargetName(it) == "Advanced target" })
    }
}

private class RecordingSequenceHardware : SequenceHardware {
    var coolTargetC: Double? = null
    var coolDurationMinutes: Double? = null
    var warmDurationMinutes: Double? = null
    var guideEnabled: Boolean? = null
    var guideForceCalibration: Boolean? = null
    var dewHeaterOn: Boolean? = null
    var usbLimitValue: Int? = null
    var flatLightOn: Boolean? = null
    var flatBrightnessValue: Int? = null

    override suspend fun takeExposure(
        seconds: Double,
        gain: Int,
        offset: Int,
        destDir: File,
        binning: Int,
        imageType: String
    ): SessionFrame = SessionFrame("recording.fits", null, seconds, null)

    override suspend fun switchFilter(name: String) = Unit

    override suspend fun cool(targetC: Double, durationMinutes: Double) {
        coolTargetC = targetC
        coolDurationMinutes = durationMinutes
    }

    override suspend fun warm(durationMinutes: Double) {
        warmDurationMinutes = durationMinutes
    }

    override suspend fun slew(raHours: Double, decDeg: Double) = Unit
    override suspend fun center(raHours: Double, decDeg: Double) = Unit
    override suspend fun plateSolve(): Pair<Double, Double> = 0.0 to 0.0
    override suspend fun syncMount(raHours: Double, decDeg: Double) = Unit

    override suspend fun guide(enabled: Boolean, forceCalibration: Boolean) {
        guideEnabled = enabled
        guideForceCalibration = forceCalibration
    }

    override suspend fun dither(radiusPx: Double) = Unit
    override suspend fun tracking(mode: Int) = Unit
    override suspend fun goHome() = Unit
    override suspend fun cover(open: Boolean) = Unit

    override suspend fun dewHeater(on: Boolean) {
        dewHeaterOn = on
    }

    override suspend fun usbLimit(value: Int) {
        usbLimitValue = value
    }

    override suspend fun flatLight(on: Boolean) {
        flatLightOn = on
    }

    override suspend fun flatBrightness(value: Int) {
        flatBrightnessValue = value
    }

    override suspend fun moveFocuser(position: Int) = Unit
    override suspend fun rotateTo(angleDeg: Double) = Unit
    override suspend fun autofocus(destDir: File): AutofocusRun =
        AutofocusRun(0L, null, null, 0, 0.0, emptyList())

    override suspend fun waitUntil(epochMillis: Long) = Unit
    override fun guidingLocked(): Boolean = guideEnabled == true
    override fun raHours(): Double? = null
    override fun decDeg(): Double? = null
}
