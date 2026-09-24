package com.indigo.mobileobservatory.sequence

import com.indigo.mobileobservatory.sequence.catalog.SequenceCatalog
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceEngineTest {

    @Test
    fun `simple exposure loops call the filter and the camera once per frame`() = runTest {
        val port = RecordingPort()
        val engine = SequenceEngine(port)
        val state = engine.run(sample())

        assertEquals(SequencePhase.Completed, state.phase)
        assertEquals(listOf("Ha", "Ha", "L"), port.filters)
        assertEquals(listOf(120.0, 120.0, 30.0), port.exposures)
    }

    @Test
    fun `pause holds the next instruction until resume`() = runTest {
        val port = RecordingPort()
        val engine = SequenceEngine(port)
        port.pauseOnExposure = 1
        port.control = engine.control
        val job = launch { engine.run(sample()) }
        testScheduler.advanceUntilIdle()
        assertTrue(engine.control.isPaused)
        assertEquals(listOf(120.0), port.exposures)
        engine.control.resume()
        job.join()
        assertEquals(SequencePhase.Completed, engine.state.phase)
        assertEquals(3, port.exposures.size)
    }

    @Test
    fun `stop cancels the sequence without running the end area`() = runTest {
        val port = RecordingPort()
        val engine = SequenceEngine(port)
        port.stopOnExposure = 1
        port.control = engine.control
        val state = engine.run(sample(endExposure = true))

        assertEquals(SequencePhase.Stopped, state.phase)
        assertEquals(listOf(120.0), port.exposures)
        assertEquals(listOf(120.0), port.finishedExposures)
    }

    @Test
    fun `skip to end runs the end area and drops remaining target frames`() = runTest {
        val port = RecordingPort()
        val engine = SequenceEngine(port)
        port.skipToEndOnExposure = 1
        port.control = engine.control
        val state = engine.run(sample(endExposure = true))

        assertEquals(SequencePhase.Completed, state.phase)
        assertEquals(listOf(120.0, 1.0), port.exposures)
    }

    @Test
    fun `skip during an exposure drops that frame and continues`() = runTest {
        val port = RecordingPort()
        val engine = SequenceEngine(port)
        port.skipOnExposure = 1
        port.control = engine.control
        val state = engine.run(sample())

        assertEquals(SequencePhase.Completed, state.phase)
        assertEquals(listOf(120.0, 30.0), port.finishedExposures)
    }

    @Test
    fun `continue on error keeps going and abort stops before the end`() = runTest {
        val continuing = RecordingPort(failExposure = 1)
        val continued = SequenceEngine(continuing).run(sample())
        assertEquals(SequencePhase.Completed, continued.phase)
        assertEquals(listOf(120.0, 30.0), continuing.finishedExposures)

        val aborting = RecordingPort(failExposure = 1)
        val root = sample()
        root.find("TakeExposure").first().fields["ErrorBehavior"] = NinaValue.Num(2.0, true)
        val aborted = SequenceEngine(aborting).run(root)
        assertEquals(SequencePhase.Failed, aborted.phase)
        assertTrue(aborting.finishedExposures.isEmpty())
    }

    @Test
    fun `unknown instruction pauses until it is skipped`() = runTest {
        val json = """
            {
              "${'$'}id": "1",
              "${'$'}type": "NINA.Sequencer.Container.SequenceRootContainer, NINA.Sequencer",
              "Items": {
                "${'$'}id": "2",
                "${'$'}type": "System.Collections.ObjectModel.ObservableCollection`1[[NINA.Sequencer.SequenceItem.ISequenceItem, NINA.Sequencer]], System.ObjectModel",
                "${'$'}values": [
                  {
                    "${'$'}id": "3",
                    "${'$'}type": "NINA.Sequencer.Container.StartAreaContainer, NINA.Sequencer",
                    "Items": {
                      "${'$'}id": "4",
                      "${'$'}type": "System.Collections.ObjectModel.ObservableCollection`1[[NINA.Sequencer.SequenceItem.ISequenceItem, NINA.Sequencer]], System.ObjectModel",
                      "${'$'}values": [
                        {
                          "${'$'}id": "5",
                          "${'$'}type": "NINA.Sequencer.SequenceItem.Dome.OpenDomeShutter, NINA.Sequencer",
                          "Temperature": -10
                        }
                      ]
                    }
                  },
                  {
                    "${'$'}id": "6",
                    "${'$'}type": "NINA.Sequencer.Container.TargetAreaContainer, NINA.Sequencer",
                    "Items": {
                      "${'$'}id": "7",
                      "${'$'}type": "System.Collections.ObjectModel.ObservableCollection`1[[NINA.Sequencer.SequenceItem.ISequenceItem, NINA.Sequencer]], System.ObjectModel",
                      "${'$'}values": []
                    }
                  },
                  {
                    "${'$'}id": "8",
                    "${'$'}type": "NINA.Sequencer.Container.EndAreaContainer, NINA.Sequencer",
                    "Items": {
                      "${'$'}id": "9",
                      "${'$'}type": "System.Collections.ObjectModel.ObservableCollection`1[[NINA.Sequencer.SequenceItem.ISequenceItem, NINA.Sequencer]], System.ObjectModel",
                      "${'$'}values": [
                        {
                          "${'$'}id": "10",
                          "${'$'}type": "NINA.Sequencer.SequenceItem.Imaging.TakeExposure, NINA.Sequencer",
                          "ExposureTime": 1
                        }
                      ]
                    }
                  }
                ]
              }
            }
        """.trimIndent()
        val port = RecordingPort()
        val engine = SequenceEngine(port)
        val job = launch { engine.run(parseNinaSequence(json)) }
        testScheduler.advanceUntilIdle()
        assertEquals(SequencePhase.Paused, engine.state.phase)
        assertEquals("OpenDomeShutter", engine.state.currentClassName)
        assertTrue(port.finishedExposures.isEmpty())
        engine.control.requestSkip()
        job.join()
        assertEquals(SequencePhase.Completed, engine.state.phase)
        assertEquals(listOf(1.0), port.finishedExposures)
    }

    @Test
    fun `annotation runs and a message box pauses until skip`() = runTest {
        val root = emptyAdvancedSequence("Tonight")
        val startId = checkNotNull(root.childItems()[0].id)
        assertTrue(addSequenceNode(root, startId, "Annotation"))
        assertTrue(addSequenceNode(root, startId, "MessageBox"))
        val port = RecordingPort()
        val engine = SequenceEngine(port)
        val job = launch { engine.run(root) }
        testScheduler.advanceUntilIdle()
        assertEquals(SequencePhase.Paused, engine.state.phase)
        assertEquals("MessageBox", engine.state.currentClassName)
        assertEquals(listOf("Annotation"), port.calls)
        engine.control.requestSkip()
        job.join()
        assertEquals(SequencePhase.Completed, engine.state.phase)
    }

    @Test
    fun `disabled exposure is skipped and later frames still run`() = runTest {
        val root = sample()
        root.find("TakeExposure").first().fields["Status"] =
            NinaValue.Num(SEQUENCE_STATUS_DISABLED.toDouble(), true)
        val port = RecordingPort()
        val state = SequenceEngine(port).run(root)

        assertEquals(SequencePhase.Completed, state.phase)
        assertEquals(listOf("Ha", "Ha", "L"), port.filters)
        assertEquals(listOf(30.0), port.exposures)
    }

    @Test
    fun `altitude condition ends the current target and the next target still runs`() = runTest {
        val world = ClockWorld()
        world.altitude = 10.0
        val port = RecordingPort()
        val engine = SequenceEngine(port, world = world)
        val state = engine.run(twoTargets())

        assertEquals(SequencePhase.Completed, state.phase)
        assertEquals(listOf(7.0), port.exposures)
    }

    @Test
    fun `meridian flip runs once and the autofocus trigger waits for the next frame`() = runTest {
        val world = ClockWorld()
        world.meridianMinutes = 30.0
        val port = RecordingPort()
        port.beforeExposure = { count ->
            if (count == 2) {
                world.meridianMinutes = -1.0
                world.now = 60_000L
            }
        }
        val engine = SequenceEngine(port, world = world)
        val state = engine.run(triggeredExposures())

        assertEquals(SequencePhase.Completed, state.phase)
        assertEquals(
            listOf(
                "TakeExposure",
                "TakeExposure",
                "StopGuiding",
                "SlewScopeToRaDec",
                "Center",
                "StartGuiding",
                "RunAutofocus",
                "TakeExposure"
            ),
            port.calls
        )
    }

    @Test
    fun `dither fires between lights when every exposure is due`() = runTest {
        val port = RecordingPort()
        val engine = SequenceEngine(port, world = ClockWorld())
        engine.run(ditheredExposures())
        assertEquals(1, port.calls.count { it == "Dither" })
        assertEquals(
            listOf("TakeExposure", "Dither", "TakeExposure"),
            port.calls.filter { it == "TakeExposure" || it == "Dither" }
        )
    }

    @Test
    fun `altitude condition still takes a frame while the target is rising`() = runTest {
        val world = ClockWorld()
        world.altitude = 10.0
        world.rising = true
        val port = RecordingPort()
        val engine = SequenceEngine(port, world = world)
        val state = engine.run(twoTargets())

        assertEquals(SequencePhase.Completed, state.phase)
        assertEquals(listOf(5.0, 7.0), port.exposures)
    }

    @Test
    fun `sun altitude condition skips a target once the sun is up`() = runTest {
        val world = ClockWorld()
        world.sun = 12.0
        val port = RecordingPort()
        val state = SequenceEngine(port, world = world).run(sunLimited())
        assertEquals(SequencePhase.Completed, state.phase)
        assertEquals(listOf(7.0), port.exposures)
    }

    @Test
    fun `condition watchdog stops an in-flight exposure and the next target still runs`() = runTest {
        val world = ClockWorld()
        world.altitude = 50.0
        val port = RecordingPort()
        port.holdFirstExposure = true
        port.beforeExposure = { world.altitude = 10.0 }
        val engine = SequenceEngine(port, world = world)
        val job = launch { engine.run(twoTargets()) }
        job.join()
        assertEquals(SequencePhase.Completed, engine.state.phase)
        assertEquals(listOf(5.0, 7.0), port.exposures)
        assertEquals(listOf(7.0), port.finishedExposures)
    }

    @Test
    fun `parallel instruction set runs both children`() = runTest {
        val root = emptyAdvancedSequence("P")
        val startId = checkNotNull(root.childItems()[0].id)
        assertTrue(addSequenceNode(root, startId, "ParallelContainer"))
        val parallel = root.childItems()[0].childItems().single { it.className == "ParallelContainer" }
        val parallelId = checkNotNull(parallel.id)
        assertTrue(addSequenceNode(root, parallelId, "TakeExposure"))
        assertTrue(addSequenceNode(root, parallelId, "TakeExposure"))
        val port = RecordingPort()
        val state = SequenceEngine(port).run(root)
        assertEquals(SequencePhase.Completed, state.phase)
        assertEquals(2, port.exposures.size)
    }

    @Test
    fun `center after drift recenters only when the solve is off target`() = runTest {
        val onTarget = RecordingPort()
        onTarget.pointing = 1.0 to 2.0
        SequenceEngine(onTarget).run(driftedExposures())
        assertEquals(0, onTarget.calls.count { it == "Center" })

        val drifted = RecordingPort()
        drifted.pointing = 2.0 to 2.0
        SequenceEngine(drifted).run(driftedExposures())
        assertTrue(drifted.calls.contains("Center"))
    }

    @Test
    fun `solve and sync is a known instruction`() = runTest {
        val root = emptyAdvancedSequence("Solve")
        val startId = checkNotNull(root.childItems()[0].id)
        assertTrue(addSequenceNode(root, startId, "SolveAndSync"))
        val port = RecordingPort()
        val state = SequenceEngine(port).run(root)
        assertEquals(SequencePhase.Completed, state.phase)
        assertEquals(listOf("SolveAndSync"), port.calls)
    }

    private fun sample(endExposure: Boolean = false): NinaNode {
        val root = SimpleSequenceDraft(
            title = "M42",
            raHours = 5.5,
            decDegrees = -5.0,
            rows = listOf(
                SimpleExposureRow("Ha", 120.0, 100, 10, count = 2),
                SimpleExposureRow("L", 30.0, 100, 10, count = 1)
            )
        ).toNinaSequence()
        if (endExposure) {
            val end = root.childItems().first { it.className == "EndAreaContainer" }
            val exposure = root.find("TakeExposure").last()
            val copy = parseNinaSequence(
                """
                {
                  "${'$'}id": "end-exposure",
                  "${'$'}type": "${exposure.type}",
                  "ExposureTime": 1,
                  "ErrorBehavior": 0,
                  "Attempts": 1
                }
                """.trimIndent()
            )
            end.fields["Items"] = NinaValue.Collection(
                type = (end.fields.getValue("Items") as NinaValue.Collection).type,
                id = (end.fields.getValue("Items") as NinaValue.Collection).id,
                values = listOf(NinaValue.Obj(copy))
            )
        }
        return root
    }
}

private class RecordingPort(
    private val failExposure: Int? = null
) : SequenceDevicePort {
    var control: SequenceControl? = null
    var pauseOnExposure: Int? = null
    var stopOnExposure: Int? = null
    var skipOnExposure: Int? = null
    var skipToEndOnExposure: Int? = null
    val filters = mutableListOf<String>()
    val exposures = mutableListOf<Double>()
    val finishedExposures = mutableListOf<Double>()
    val calls = mutableListOf<String>()
    var beforeExposure: ((Int) -> Unit)? = null
    var afterExposure: ((Int) -> Unit)? = null
    var holdFirstExposure = false
    var pointing: Pair<Double, Double>? = null
    private var exposureStarts = 0

    override suspend fun measurePointing(): Pair<Double, Double>? = pointing

    override suspend fun execute(instruction: NinaNode, className: String) {
        calls += className
        when (className) {
            "SwitchFilter" -> filters += instruction.textField("ComboBoxText").orEmpty()
            "TakeExposure" -> {
                exposureStarts += 1
                beforeExposure?.invoke(exposureStarts)
                val seconds = expressionNumber(instruction, "ExposureTime") ?: 0.0
                exposures += seconds
                if (exposureStarts == failExposure) throw IllegalStateException("camera")
                if (exposureStarts == pauseOnExposure) control?.pause()
                if (exposureStarts == stopOnExposure) control?.stop()
                if (exposureStarts == skipOnExposure) {
                    control?.requestSkip()
                    awaitCancellation()
                }
                if (exposureStarts == skipToEndOnExposure) {
                    control?.requestSkipToEnd()
                    awaitCancellation()
                }
                if (holdFirstExposure && exposureStarts == 1) delay(10_000)
                finishedExposures += seconds
                afterExposure?.invoke(finishedExposures.size)
            }
        }
    }
}

private class ClockWorld : SequenceWorld {
    var altitude: Double? = null
    var rising: Boolean? = null
    var meridianMinutes: Double? = null
    var sun: Double? = null
    var now: Long = 0L
    override fun altitudeDeg(): Double? = altitude
    override fun altitudeRising(): Boolean? = rising
    override fun minutesToMeridian(): Double? = meridianMinutes
    override fun sunAltitudeDeg(): Double? = sun
    override fun nowMillis(): Long = now
}

private fun sunLimited(): NinaNode {
    val low = targetContainer("low", 5.0, altitudeOffset = null)
    val data = NinaNode(
        type = "NINA.Sequencer.Utility.WaitLoopData, NINA.Sequencer",
        id = "sun-data",
        fields = linkedMapOf(
            "Offset" to NinaValue.Num(0.0, true),
            "Comparator" to NinaValue.Num(3.0, true)
        )
    )
    low.fields["Conditions"] = NinaValue.Collection(
        "",
        null,
        listOf(
            NinaValue.Obj(
                NinaNode(
                    type = "NINA.Sequencer.Conditions.SunAltitudeCondition, NINA.Sequencer",
                    id = "sun-alt",
                    fields = linkedMapOf("Data" to NinaValue.Obj(data))
                )
            )
        )
    )
    val next = targetContainer("next", 7.0, altitudeOffset = null)
    return sequenceRoot(listOf(low, next))
}

private fun twoTargets(): NinaNode {
    val low = targetContainer("low", 5.0, altitudeOffset = 40.0)
    val next = targetContainer("next", 7.0, altitudeOffset = null)
    return sequenceRoot(listOf(low, next))
}

private fun triggeredExposures(): NinaNode {
    val root = SimpleSequenceDraft(
        title = "Flip",
        raHours = 1.0,
        decDegrees = 2.0,
        rows = listOf(SimpleExposureRow(null, 10.0, 0, 0, count = 3))
    ).toNinaSequence()
    val target = root.childItems()[1].childItems().single()
    val triggers = listOf(
        NinaNode(
            type = "NINA.Sequencer.Trigger.MeridianFlip.MeridianFlipTrigger, NINA.Sequencer",
            id = "meridian",
            fields = linkedMapOf(
                "Parent" to NinaValue.Ref(checkNotNull(target.id))
            )
        ),
        NinaNode(
            type = "NINA.Sequencer.Trigger.Autofocus.AutofocusAfterTimeTrigger, NINA.Sequencer",
            id = "af",
            fields = linkedMapOf(
                "Amount" to NinaValue.Num(1.0, true),
                "AmountDefinition" to NinaValue.Text("1"),
                "Parent" to NinaValue.Ref(checkNotNull(target.id))
            )
        )
    )
    val existing = target.fields.getValue("Triggers") as NinaValue.Collection
    target.fields["Triggers"] = existing.copy(values = triggers.map { NinaValue.Obj(it) })
    return root
}

private fun ditheredExposures(): NinaNode {
    val root = SimpleSequenceDraft(
        title = "Dither",
        raHours = 1.0,
        decDegrees = 2.0,
        rows = listOf(SimpleExposureRow(null, 4.0, 0, 0, count = 2)),
        ditherEvery = 1
    ).toNinaSequence()
    return root
}

private fun driftedExposures(): NinaNode {
    val root = SimpleSequenceDraft(
        title = "Drift",
        raHours = 1.0,
        decDegrees = 2.0,
        rows = listOf(SimpleExposureRow(null, 4.0, 0, 0, count = 2))
    ).toNinaSequence()
    val target = root.childItems()[1].childItems().single()
    val trigger = SequenceCatalog.create("CenterAfterDriftTrigger")
    trigger.fields["Parent"] = NinaValue.Ref(checkNotNull(target.id))
    val existing = target.fields.getValue("Triggers") as NinaValue.Collection
    target.fields["Triggers"] = existing.copy(values = listOf(NinaValue.Obj(trigger)))
    return root
}

private fun targetContainer(id: String, seconds: Double, altitudeOffset: Double?): NinaNode {
    val exposure = NinaNode(
        type = "NINA.Sequencer.SequenceItem.Imaging.TakeExposure, NINA.Sequencer",
        id = "$id-exp",
        fields = linkedMapOf(
            "ExposureTime" to NinaValue.Num(seconds, integral = false),
            "ErrorBehavior" to NinaValue.Num(0.0, true),
            "Attempts" to NinaValue.Num(1.0, true),
            "Parent" to NinaValue.Ref(id)
        )
    )
    val conditions = if (altitudeOffset == null) {
        emptyList()
    } else {
        val data = NinaNode(
            type = "NINA.Sequencer.Utility.WaitLoopData, NINA.Sequencer",
            id = "$id-data",
            fields = linkedMapOf("Offset" to NinaValue.Num(altitudeOffset, true))
        )
        listOf(NinaNode(
            type = "NINA.Sequencer.Conditions.AltitudeCondition, NINA.Sequencer",
            id = "$id-alt",
            fields = linkedMapOf("Data" to NinaValue.Obj(data))
        ))
    }
    return NinaNode(
        type = "NINA.Sequencer.Container.DeepSkyObjectContainer, NINA.Sequencer",
        id = id,
        fields = linkedMapOf(
            "Name" to NinaValue.Text(id),
            "Conditions" to NinaValue.Collection("", null, conditions.map { NinaValue.Obj(it) }),
            "Items" to NinaValue.Collection("", null, listOf(NinaValue.Obj(exposure))),
            "Parent" to NinaValue.Ref("targets")
        )
    )
}

private fun sequenceRoot(targets: List<NinaNode>): NinaNode {
    fun area(id: String, type: String, items: List<NinaNode>) = NinaNode(
        type = "$type, NINA.Sequencer",
        id = id,
        fields = linkedMapOf(
            "Items" to NinaValue.Collection("", null, items.map { NinaValue.Obj(it) })
        )
    )
    val start = area("start", "NINA.Sequencer.Container.StartAreaContainer", emptyList())
    val targetArea = area("targets", "NINA.Sequencer.Container.TargetAreaContainer", targets)
    val end = area("end", "NINA.Sequencer.Container.EndAreaContainer", emptyList())
    return NinaNode(
        type = "NINA.Sequencer.Container.SequenceRootContainer, NINA.Sequencer",
        id = "root",
        fields = linkedMapOf(
            "Items" to NinaValue.Collection("", null, listOf(start, targetArea, end).map { NinaValue.Obj(it) })
        )
    )
}

private fun NinaNode.find(className: String): List<NinaNode> {
    val found = mutableListOf<NinaNode>()
    fun walk(node: NinaNode) {
        if (node.className == className) found += node
        node.fields.values.forEach { value ->
            when (value) {
                is NinaValue.Obj -> walk(value.node)
                is NinaValue.Collection -> value.values.filterIsInstance<NinaValue.Obj>().forEach { walk(it.node) }
                else -> Unit
            }
        }
    }
    walk(this)
    return found
}
