package com.indigo.mobileobservatory.sequence

import com.indigo.mobileobservatory.sequence.catalog.SequenceCatalog
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceRuntimeHardwareMappingTest {
    @Test
    fun skipPreventsQueuedHomeFromSendingACommand() = runTest {
        val finish = CompletableDeferred<Unit>()
        var homeCalls = 0
        val hardware = object : SequenceHardware by RecordingSequenceHardware() {
            override suspend fun takeExposure(seconds: Double, gain: Int, offset: Int, destDir: File,
                binning: Int, imageType: String): SessionFrame {
                finish.await()
                return SessionFrame("test.fits", null, seconds, null)
            }

            override suspend fun goHome() { homeCalls++ }
        }
        val directory = Files.createTempDirectory("sequence-skip-queue").toFile()
        try {
            val runtime = SequenceRuntime(directory, directory, this, hardware)
            val capture = launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.execute(SequenceCatalog.create("TakeExposure"), "TakeExposure")
            }
            val home = launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.execute(SequenceCatalog.create("FindHome"), "FindHome")
            }
            runtime.pauseForMountStop()
            finish.complete(Unit)
            testScheduler.advanceUntilIdle()
            runtime.skip()
            capture.join()
            home.join()
            assertTrue(home.isCancelled)
            assertEquals(0, homeCalls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun exposureRechecksPauseAndGuideLockAfterSettling() = runTest {
        val settled = CompletableDeferred<Unit>()
        val recording = RecordingSequenceHardware()
        var exposureCalls = 0
        val hardware = object : SequenceHardware by recording {
            override suspend fun awaitExposureReady() { settled.await() }

            override suspend fun takeExposure(seconds: Double, gain: Int, offset: Int, destDir: File,
                binning: Int, imageType: String): SessionFrame {
                exposureCalls++
                return SessionFrame("test.fits", null, seconds, null)
            }
        }
        val directory = Files.createTempDirectory("sequence-dither-pause").toFile()
        try {
            val runtime = SequenceRuntime(directory, directory, this, hardware)
            runtime.execute(SequenceCatalog.create("StartGuiding"), "StartGuiding")
            val failure = CompletableDeferred<Throwable?>()
            val capture = launch(start = CoroutineStart.UNDISPATCHED) {
                failure.complete(runCatching {
                    runtime.execute(SequenceCatalog.create("TakeExposure"), "TakeExposure")
                }.exceptionOrNull())
            }
            runtime.pauseForMountStop()
            recording.guideEnabled = false
            settled.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertTrue(!capture.isCompleted)
            assertEquals(0, exposureCalls)
            runtime.resume()
            capture.join()
            assertTrue(failure.await() is GuideLost)
            assertEquals(0, exposureCalls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun queuedOpticalCommandWaitsForResumeAfterMountStop() = runTest {
        val finish = CompletableDeferred<Unit>()
        val recording = RecordingSequenceHardware()
        val hardware = object : SequenceHardware by recording {
            override suspend fun takeExposure(seconds: Double, gain: Int, offset: Int, destDir: File,
                binning: Int, imageType: String): SessionFrame {
                finish.await()
                return SessionFrame("test.fits", null, seconds, null)
            }
        }
        val directory = Files.createTempDirectory("sequence-stop-queue").toFile()
        try {
            val runtime = SequenceRuntime(directory, directory, this, hardware)
            val capture = launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.execute(SequenceCatalog.create("TakeExposure"), "TakeExposure")
            }
            val home = launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.execute(SequenceCatalog.create("FindHome"), "FindHome")
            }
            runtime.pauseForMountStop()
            finish.complete(Unit)
            testScheduler.advanceUntilIdle()
            assertTrue(capture.isCompleted)
            assertTrue(!home.isCompleted)
            runtime.resume()
            home.join()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun parallelTrackingAndGuidingCommandsWaitForTheExposure() = runTest {
        val finish = CompletableDeferred<Unit>()
        val hardware = object : SequenceHardware by RecordingSequenceHardware() {
            override suspend fun takeExposure(seconds: Double, gain: Int, offset: Int, destDir: File,
                binning: Int, imageType: String): SessionFrame {
                finish.await()
                return SessionFrame("test.fits", null, seconds, null)
            }
        }
        val directory = Files.createTempDirectory("sequence-tracking").toFile()
        try {
            val runtime = SequenceRuntime(directory, directory, this, hardware)
            val capture = launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.execute(SequenceCatalog.create("TakeExposure"), "TakeExposure")
            }
            val commands = listOf("SetTracking", "StartGuiding", "StopGuiding").map { name ->
                launch(start = CoroutineStart.UNDISPATCHED) { runtime.execute(SequenceCatalog.create(name), name) }
            }
            assertTrue(commands.none { it.isCompleted })
            finish.complete(Unit)
            capture.join()
            commands.forEach { it.join() }
        } finally {
            finish.complete(Unit)
            directory.deleteRecursively()
        }
    }

    @Test
    fun repeatedStartAndStopBeforeDispatchCreateOneStoppedSession() = runTest {
        val sessions = Files.createTempDirectory("sequence-double-start").toFile()
        val runtime = SequenceRuntime(sessions.resolve("templates"), sessions, this, RecordingSequenceHardware())
        runtime.start()
        runtime.start()
        runtime.stop()
        val stopped = runtime.state.first { it.phase == SequencePhase.Stopped }
        assertEquals(0, stopped.framesDone)
        val json = org.json.JSONObject(sessions.listFiles()!!.single().resolve("session.json").readText())
        assertEquals("Stopped", json.getString("phase"))
        assertEquals(0, json.getJSONArray("frames").length())
    }

    @Test
    fun interruptedSessionRestoresPlanAndFramesWithoutLaunchingHardware() = runTest {
        val sessions = Files.createTempDirectory("sequence-interrupted").toFile()
        val runtime = SequenceRuntime(sessions.resolve("templates"), sessions, this, RecordingSequenceHardware())
        val root = emptyAdvancedSequence("Saved run")
        val dir = sessions.resolve("run").apply { mkdirs() }
        val json = org.json.JSONObject().put("sequence", org.json.JSONObject(root.toJson())).put("phase", "Running")
            .put("frames", org.json.JSONArray().put(org.json.JSONObject().put("name", "first.fits")
                .put("exposureSeconds", 30.0))).put("autofocus", org.json.JSONArray())
        dir.resolve("session.json").writeText(json.toString())
        dir.resolve("first.fits").createNewFile()
        assertTrue(runtime.restoreInterruptedSession())
        assertEquals(SequencePhase.Interrupted, runtime.state.value.phase)
        assertEquals("Saved run", runtime.document.value?.textField("Name"))
        assertEquals(listOf("first.fits"), runtime.frames.value.map { it.name })
        assertEquals(false, runtime.running())
    }

    @Test
    fun storageFailureTerminatesRunAndUnlocksEditor() = runTest {
        val invalidDirectory = Files.createTempFile("sequence", ".file").toFile()
        val runtime = SequenceRuntime(invalidDirectory, invalidDirectory, this, RecordingSequenceHardware())
        runtime.start()
        runtime.state.first { it.phase == SequencePhase.Failed }
        assertTrue(!runtime.locked.value)
        assertTrue(!runtime.state.value.message.isNullOrBlank())
    }

    @Test
    fun fitsWritesWithSameNameAreDistinctAndOnlyCompleteFilesArePublished() {
        val dir = Files.createTempDirectory("sequence-fits").toFile()
        val first = writeSequenceFits(dir, "same") { it.writeText("first") }
        val second = writeSequenceFits(dir, "same") { it.writeText("second") }
        val failure = runCatching { writeSequenceFits(dir, "same") { throw java.io.IOException("full") } }.exceptionOrNull()
        assertTrue(failure is SequenceStorageFailure)
        assertEquals("first", first.readText())
        assertEquals("second", second.readText())
        assertEquals(setOf(first, second), dir.listFiles()!!.toSet())
    }

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
    fun rotationInstructionsAreIgnoredWhenCaaIsDisconnected() = runTest {
        val hardware = RecordingSequenceHardware(rotatorAvailable = false)
        val runtime = SequenceRuntime(
            templatesDir = Files.createTempDirectory("sequence-templates").toFile(),
            sessionsDir = Files.createTempDirectory("sequence-sessions").toFile(),
            scope = this,
            hardware = hardware
        )
        runtime.addTarget("Target", 1.25, 10.0, destination = SequenceEditorMode.Advanced)
        val centerAndRotate = SequenceCatalog.create("CenterAndRotate")
        putExpression(centerAndRotate.fields, "PositionAngle", 42.0, "position-angle")
        val solveAndRotate = SequenceCatalog.create("SolveAndRotate")
        putExpression(solveAndRotate.fields, "PositionAngle", 84.0, "position-angle")
        val moveRotator = SequenceCatalog.create("MoveRotatorMechanical")
        putExpression(moveRotator.fields, "MechanicalAngle", 21.0, "mechanical-angle")

        runtime.execute(centerAndRotate, "CenterAndRotate")
        runtime.execute(solveAndRotate, "SolveAndRotate")
        runtime.execute(moveRotator, "MoveRotatorMechanical")

        assertEquals(1, hardware.centerCalls)
        assertEquals(1, hardware.plateSolveCalls)
        assertTrue(hardware.rotatorAngles.isEmpty())
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

    @Test
    fun mosaicImportCreatesOrderedExecutableTargetsInBothEditors() = runTest {
        val hardware = RecordingSequenceHardware()
        val runtime = SequenceRuntime(
            templatesDir = Files.createTempDirectory("sequence-templates").toFile(),
            sessionsDir = Files.createTempDirectory("sequence-sessions").toFile(),
            scope = this,
            hardware = hardware
        )
        val plan = SequenceMosaicPlan(
            name = "M31 mosaic",
            centerRaHours = 0.7,
            centerDecDeg = 41.2,
            positionAngleDeg = 32.0,
            rows = 1,
            columns = 2,
            overlapPercent = 15,
            panels = listOf(
                SequenceMosaicPanel(1, 1, 1, 0.65, 41.2),
                SequenceMosaicPanel(2, 1, 2, 0.75, 41.2)
            )
        )

        runtime.addMosaic(plan, SequenceEditorMode.Simple)
        assertEquals(SequenceEditorMode.Simple, runtime.mode.value)
        assertEquals(listOf("M31 mosaic · P1", "M31 mosaic · P2"), runtime.draft.value.targets.map { it.name })
        assertEquals(2, runtime.draft.value.plannedFrames())
        assertEquals(plan, runtime.mosaicPlan())
        val simpleRoot = runtime.draft.value.toNinaSequence()
        val simpleTargets = simpleRoot.childItems()
            .first { it.className == "TargetAreaContainer" }
            .childItems()
        assertEquals(2, simpleTargets.size)
        assertEquals(2, simpleTargets[1].intField("MosaicPanelNumber"))
        assertTrue(simpleTargets.all { target ->
            target.childItems().any { it.className == "SlewScopeToRaDec" } &&
                target.childItems().any { it.className == "Center" } &&
                target.childItems().any { it.className == "SequentialContainer" }
        })

        runtime.importJson("mosaic.json", simpleRoot.toJson())
        val importedSecond = runtime.document.value!!.childItems()
            .first { it.className == "TargetAreaContainer" }
            .childItems()[1]
        val center = importedSecond.childItems().first { it.className == "Center" }
        runtime.execute(center, "Center")
        assertEquals(0.75, checkNotNull(hardware.centerRaHours), 1e-9)
        assertEquals(41.2, checkNotNull(hardware.centerDecDeg), 1e-9)

        val advancedRuntime = SequenceRuntime(
            templatesDir = Files.createTempDirectory("sequence-templates").toFile(),
            sessionsDir = Files.createTempDirectory("sequence-sessions").toFile(),
            scope = this,
            hardware = RecordingSequenceHardware()
        )
        advancedRuntime.addMosaic(plan, SequenceEditorMode.Advanced)
        advancedRuntime.addMosaic(
            plan.copy(positionAngleDeg = 40.0),
            SequenceEditorMode.Advanced
        )
        val advancedTargets = advancedRuntime.document.value!!.childItems()
            .first { it.className == "TargetAreaContainer" }
            .childItems()
        assertEquals(2, advancedTargets.size)
        assertEquals(40.0, checkNotNull(dsoPositionAngle(advancedTargets.first())), 1e-9)
        assertTrue(advancedTargets.all { it.childItems().any { child -> child.className == "SequentialContainer" } })
        assertEquals(40.0, checkNotNull(advancedRuntime.mosaicPlan()).positionAngleDeg, 1e-9)
    }
}

private class RecordingSequenceHardware(
    private val rotatorAvailable: Boolean = true
) : SequenceHardware {
    var coolTargetC: Double? = null
    var coolDurationMinutes: Double? = null
    var warmDurationMinutes: Double? = null
    var guideEnabled: Boolean? = null
    var guideForceCalibration: Boolean? = null
    var dewHeaterOn: Boolean? = null
    var usbLimitValue: Int? = null
    var flatLightOn: Boolean? = null
    var flatBrightnessValue: Int? = null
    var centerRaHours: Double? = null
    var centerDecDeg: Double? = null
    var centerCalls: Int = 0
    var plateSolveCalls: Int = 0
    val rotatorAngles = mutableListOf<Double>()

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
    override suspend fun center(raHours: Double, decDeg: Double) {
        centerCalls += 1
        centerRaHours = raHours
        centerDecDeg = decDeg
    }
    override suspend fun plateSolve(): Pair<Double, Double> {
        plateSolveCalls += 1
        return 0.0 to 0.0
    }
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
    override suspend fun rotateTo(angleDeg: Double) {
        rotatorAngles += angleDeg
    }
    override fun isRotatorConnected(): Boolean = rotatorAvailable
    override suspend fun autofocus(destDir: File): AutofocusRun =
        AutofocusRun(0L, null, null, 0, 0.0, emptyList())

    override suspend fun waitUntil(epochMillis: Long) = Unit
    override fun guidingLocked(): Boolean = guideEnabled == true
    override fun raHours(): Double? = null
    override fun decDeg(): Double? = null
}
