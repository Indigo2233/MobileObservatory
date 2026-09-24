package com.indigo.mobileobservatory.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NinaSequenceTest {

    @Test
    fun `round trip keeps unknown fields, refs, and the legacy assembly suffix`() {
        val original = """
            {
              "${'$'}id": "1",
              "${'$'}type": "NINA.Sequencer.Container.SequenceRootContainer, NINA.Sequencer",
              "Name": "Root",
              "Items": {
                "${'$'}id": "2",
                "${'$'}type": "System.Collections.ObjectModel.ObservableCollection`1[[NINA.Sequencer.SequenceItem.ISequenceItem, NINA.Sequencer]], System.ObjectModel",
                "${'$'}values": [
                  {
                    "${'$'}id": "3",
                    "${'$'}type": "NINA.Sequencer.SequenceItem.Imaging.TakeExposure, NINA",
                    "Parent": { "${'$'}ref": "1" },
                    "PluginData": "keep-me",
                    "ExposureTime": 30.5
                  }
                ]
              }
            }
        """.trimIndent()

        val parsed = parseNinaSequence(original)
        assertEquals("TakeExposure", parsed.childItems().single().className)

        val again = parseNinaSequence(parsed.toJson())
        assertSameNode(parsed, again)
        assertEquals("keep-me", again.childItems().single().textField("PluginData"))
        assertEquals("1", (again.childItems().single().fields.getValue("Parent") as NinaValue.Ref).id)
        assertEquals(30.5, again.childItems().single().doubleField("ExposureTime")!!, 0.0)
    }

    @Test
    fun `simple sequence expands into a NINA root with exposure loops`() {
        val root = SimpleSequenceDraft(
            title = "M42",
            raHours = 5.5,
            decDegrees = -5.5,
            rows = listOf(
                SimpleExposureRow(filterName = "Ha", exposureSeconds = 120.0, gain = 100, offset = 10, count = 2),
                SimpleExposureRow(filterName = null, exposureSeconds = 30.0, gain = 0, offset = 0, count = 1)
            ),
            positionAngleDeg = 12.5,
            ditherEvery = 3
        ).toNinaSequence()

        assertEquals("SequenceRootContainer", root.className)
        assertEquals(
            listOf("StartAreaContainer", "TargetAreaContainer", "EndAreaContainer"),
            root.childItems().map { it.className }
        )
        val target = root.childItems()[1].childItems().single()
        assertEquals("DeepSkyObjectContainer", target.className)
        val inputTarget = (target.fields.getValue("Target") as NinaValue.Obj).node
        assertEquals(12.5, inputTarget.doubleField("PositionAngle")!!, 0.0)
        val coordinates = (inputTarget.fields.getValue("InputCoordinates") as NinaValue.Obj).node
        assertEquals(5, coordinates.intField("RAHours"))
        assertEquals(30, coordinates.intField("RAMinutes"))
        assertEquals(true, (coordinates.fields.getValue("NegativeDec") as NinaValue.Bool).value)
        assertEquals(-5, coordinates.intField("DecDegrees"))
        assertEquals(30, coordinates.intField("DecMinutes"))

        val loops = target.childItems()
        assertEquals(listOf("SequentialContainer", "SequentialContainer"), loops.map { it.className })
        assertEquals(2, loops[0].collectionNodes("Conditions").single().intField("Iterations"))
        assertEquals(
            listOf("SwitchFilter", "TakeExposure"),
            loops[0].childItems().map { it.className }
        )
        assertEquals("Ha", loops[0].childItems().first().textField("ComboBoxText"))
        assertEquals(listOf("TakeExposure"), loops[1].childItems().map { it.className })
        val exposure = loops[0].childItems().last()
        assertEquals(120.0, expressionNumber(exposure, "ExposureTime")!!, 0.0)
        assertEquals("120", expressionDefinition(exposure, "ExposureTime"))
        val dither = target.collectionNodes("Triggers").single()
        assertEquals("DitherAfterExposures", dither.className)
        assertTrue(dither.type.contains("Trigger.Guider.DitherAfterExposures"))
        assertEquals(3.0, expressionNumber(dither, "AfterExposures")!!, 0.0)
        assertEquals("Dither", triggerRunnerItems(dither).single().className)

        assertSameNode(root, parseNinaSequence(root.toJson()))
    }

    @Test
    fun `expression definition is preferred over the numeric snapshot`() {
        val json = """
            {
              "${'$'}id": "1",
              "${'$'}type": "NINA.Sequencer.SequenceItem.Imaging.TakeExposure, NINA.Sequencer",
              "ExposureTime": 60,
              "ExposureTimeDefinition": "120",
              "ExposureTimeExpression": {
                "${'$'}id": "2",
                "${'$'}type": "NINA.Sequencer.Logic.Expression, NINA.Sequencer",
                "Definition": "90"
              }
            }
        """.trimIndent()
        val node = parseNinaSequence(json)
        assertEquals(90.0, expressionNumber(node, "ExposureTime")!!, 0.0)
        assertTrue(expressionIsUnsupported(node, "ExposureTime").not())
        node.fields["ExposureTimeExpression"] = NinaValue.Obj(
            NinaNode(
                NINA_EXPRESSION_TYPE,
                "3",
                linkedMapOf("Definition" to NinaValue.Text("Gain * 2"))
            )
        )
        assertTrue(expressionIsUnsupported(node, "ExposureTime"))
    }
}

private fun assertSameNode(expected: NinaNode, actual: NinaNode) {
    assertEquals(expected.type, actual.type)
    assertEquals(expected.id, actual.id)
    assertEquals(expected.fields.keys, actual.fields.keys)
    for (key in expected.fields.keys) {
        assertSameValue(expected.fields.getValue(key), actual.fields.getValue(key))
    }
}

private fun assertSameValue(expected: NinaValue, actual: NinaValue) {
    when (expected) {
        is NinaValue.Num -> {
            val other = actual as NinaValue.Num
            assertEquals(expected.value, other.value, 0.0)
        }
        is NinaValue.Obj -> assertSameNode(expected.node, (actual as NinaValue.Obj).node)
        is NinaValue.Collection -> {
            val other = actual as NinaValue.Collection
            assertEquals(expected.type, other.type)
            assertEquals(expected.id, other.id)
            assertEquals(expected.values.size, other.values.size)
            expected.values.zip(other.values).forEach { (left, right) -> assertSameValue(left, right) }
        }
        is NinaValue.Array -> {
            val other = actual as NinaValue.Array
            expected.values.zip(other.values).forEach { (left, right) -> assertSameValue(left, right) }
        }
        else -> assertEquals(expected, actual)
    }
}
