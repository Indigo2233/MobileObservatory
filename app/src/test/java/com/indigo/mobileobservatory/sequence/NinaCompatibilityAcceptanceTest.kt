package com.indigo.mobileobservatory.sequence

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NinaCompatibilityAcceptanceTest {
    @Test
    fun `NINA retained node fixture survives runtime import and export`() = runTest {
        val source = fixtureText()
        val expected = parseNinaSequence(source)
        val runtime = runtime(this)

        runtime.importJson("nina-retained-nodes.json", source)

        val actual = parseNinaSequence(runtime.exportJson())
        assertEquals(expected.toJson(indent = 0), actual.toJson(indent = 0))
        assertEquals(SequenceEditorMode.Advanced, runtime.mode.value)
        assertEquals(listOf("nina-retained-nodes"), runtime.templateNames())
        assertEquals(6, actual.plannedFrames())

        val retainedClasses = setOf(
            "ExternalScript",
            "ConnectEquipment",
            "Constant",
            "OpenDomeShutter",
            "WaitUntilSafe",
            "LinkedTemplateContainer",
            "SafetyMonitorCondition",
            "ReconnectTrigger",
            "TakeSubframeExposure",
            "AutoExposureFlat",
            "SetSwitchValue"
        )
        val nodes = actual.descendants().associateBy { it.className }
        retainedClasses.forEach { className -> assertNotNull(className, nodes[className]) }

        val exposure = checkNotNull(nodes["TakeExposure"])
        assertEquals("ExposureSeconds * 2", expressionDefinition(exposure, "ExposureTime"))
        assertEquals("level-5", (exposure.fields.getValue("Parent") as NinaValue.Ref).id)

        val subframe = checkNotNull(nodes["TakeSubframeExposure"])
        val roi = (subframe.fields.getValue("Subframe") as NinaValue.Obj).node
        assertEquals(320, roi.intField("X"))
        assertEquals(180, roi.intField("Y"))
        assertEquals(640, roi.intField("Width"))
        assertEquals(480, roi.intField("Height"))
    }

    @Test
    fun `deeply nested sequence remains editable countable and exportable`() = runTest {
        val runtime = runtime(this)
        runtime.importJson("deep.json", fixtureText())

        runtime.editSequence { root ->
            val exposure = checkNotNull(findSequenceNode(root, "expression-exposure"))
            val deepest = checkNotNull(findSequenceNode(root, "level-5"))
            setSequenceField(root, checkNotNull(exposure.id), "ExposureTime", "45") &&
                addSequenceNode(root, checkNotNull(deepest.id), "TakeExposure")
        }

        val exportedText = runtime.exportJson()
        val exported = parseNinaSequence(exportedText)
        assertEquals(12, exported.plannedFrames())
        assertEquals(
            "45",
            expressionDefinition(
                checkNotNull(findSequenceNode(exported, "expression-exposure")),
                "ExposureTime"
            )
        )
        assertEquals(
            listOf("TakeExposure", "TakeSubframeExposure", "TakeExposure"),
            checkNotNull(findSequenceNode(exported, "level-5")).childItems().map { it.className }
        )
        assertNotNull(findSequenceNode(exported, "linked-template"))
        assertNotNull(findSequenceNode(parseNinaSequence(exportedText), "subframe-exposure"))
    }

    private fun fixtureText(): String = checkNotNull(
        javaClass.getResource("/sequence/nina-retained-nodes.json")
    ).readText()

    private fun runtime(scope: CoroutineScope) = SequenceRuntime(
        templatesDir = Files.createTempDirectory("nina-compatibility-templates").toFile(),
        sessionsDir = Files.createTempDirectory("nina-compatibility-sessions").toFile(),
        scope = scope,
        hardware = CompatibilityHardware()
    )
}

private fun NinaNode.descendants(): List<NinaNode> {
    val result = ArrayList<NinaNode>()
    fun visit(node: NinaNode) {
        result += node
        node.fields.values.forEach { value ->
            when (value) {
                is NinaValue.Obj -> visit(value.node)
                is NinaValue.Collection -> value.values.filterIsInstance<NinaValue.Obj>().forEach { visit(it.node) }
                else -> Unit
            }
        }
    }
    visit(this)
    return result
}

private class CompatibilityHardware : SequenceHardware {
    override suspend fun takeExposure(
        seconds: Double,
        gain: Int,
        offset: Int,
        destDir: File,
        binning: Int,
        imageType: String
    ) = SessionFrame("compatibility.fits", null, seconds, null)

    override suspend fun switchFilter(name: String) = Unit
    override suspend fun cool(targetC: Double, durationMinutes: Double) = Unit
    override suspend fun warm(durationMinutes: Double) = Unit
    override suspend fun slew(raHours: Double, decDeg: Double) = Unit
    override suspend fun center(raHours: Double, decDeg: Double) = Unit
    override suspend fun plateSolve(): Pair<Double, Double> = 0.0 to 0.0
    override suspend fun syncMount(raHours: Double, decDeg: Double) = Unit
    override suspend fun guide(enabled: Boolean, forceCalibration: Boolean) = Unit
    override suspend fun dither(radiusPx: Double) = Unit
    override suspend fun tracking(mode: Int) = Unit
    override suspend fun goHome() = Unit
    override suspend fun cover(open: Boolean) = Unit
    override suspend fun dewHeater(on: Boolean) = Unit
    override suspend fun usbLimit(value: Int) = Unit
    override suspend fun flatLight(on: Boolean) = Unit
    override suspend fun flatBrightness(value: Int) = Unit
    override suspend fun moveFocuser(position: Int) = Unit
    override suspend fun rotateTo(angleDeg: Double) = Unit
    override suspend fun autofocus(destDir: File) =
        AutofocusRun(0L, null, null, 0, 0.0, emptyList())
    override suspend fun waitUntil(epochMillis: Long) = Unit
    override fun guidingLocked(): Boolean = false
    override fun raHours(): Double? = null
    override fun decDeg(): Double? = null
}
