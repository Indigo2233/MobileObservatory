package com.indigo.mobileobservatory.sequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceEditTest {
    @Test
    fun `copy delete move and global trigger edit the nina tree`() {
        val root = SimpleSequenceDraft(
            title = "M42",
            raHours = 1.0,
            decDegrees = 2.0,
            rows = listOf(SimpleExposureRow(null, 10.0, 0, 0, count = 1))
        ).toNinaSequence()
        val exposure = find(root, "TakeExposure").single()
        val exposureId = checkNotNull(exposure.id)

        assertTrue(duplicateSequenceNode(root, exposureId))
        assertEquals(2, find(root, "TakeExposure").size)
        assertFalse(duplicateSequenceNode(root, checkNotNull(root.id)))

        assertTrue(moveSequenceNode(root, exposureId, up = false))
        assertEquals(exposureId, find(root, "TakeExposure")[1].id)

        assertTrue(deleteSequenceNode(root, exposureId))
        assertEquals(1, find(root, "TakeExposure").size)
        assertFalse(deleteSequenceNode(root, checkNotNull(root.childItems()[0].id)))

        assertTrue(addSequenceNode(root, checkNotNull(root.id), "DitherAfterExposures"))
        val trigger = root.collectionNodes("Triggers").single()
        assertEquals("DitherAfterExposures", trigger.className)
        assertEquals(root.id, (trigger.fields.getValue("Parent") as NinaValue.Ref).id)

        val loop = find(root, "SequentialContainer").single()
        assertTrue(addSequenceNode(root, checkNotNull(loop.id), "LoopCondition"))
        assertTrue(loop.collectionNodes("Conditions").any { it.className == "LoopCondition" })

        assertTrue(setSequenceDisabled(root, checkNotNull(find(root, "TakeExposure").single().id), true))
        assertTrue(sequenceNodeDisabled(find(root, "TakeExposure").single()))
    }

    @Test
    fun `deep sky target stores sky coordinates and position angle`() {
        val root = emptyAdvancedSequence("Tonight")
        val areaId = checkNotNull(root.childItems()[1].id)
        assertTrue(addSequenceNode(root, areaId, "DeepSkyObjectContainer"))
        val target = find(root, "DeepSkyObjectContainer").single()
        val id = checkNotNull(target.id)
        assertEquals("0", sequenceFieldText(target, "PositionAngle"))
        assertTrue(setSequenceField(root, id, "PositionAngle", "23.5"))
        assertEquals(23.5, dsoPositionAngle(target)!!, 1e-9)
        assertTrue(applyDsoSkyTarget(root, id, "M42", 5.588, -5.391, 15.0))
        assertEquals("M42", dsoTargetName(target))
        assertEquals("M42", target.textField("Name"))
        assertEquals(5.588, dsoRaHours(target)!!, 1e-4)
        assertEquals(-5.391, dsoDecDegrees(target)!!, 1e-4)
        assertEquals(15.0, dsoPositionAngle(target)!!, 1e-9)
        assertEquals("15", sequenceFieldText(target, "PositionAngle"))
        assertTrue(setDsoCoordinatePart(root, id, "RAHours", "5"))
        assertTrue(setDsoCoordinatePart(root, id, "RAMinutes", "35"))
        assertTrue(setDsoCoordinatePart(root, id, "RASeconds", "17"))
        assertEquals(5.0 + 35.0 / 60.0 + 17.0 / 3600.0, dsoRaHours(target)!!, 1e-6)
    }

    @Test
    fun `binning time provider and exposure summary follow the nina fields`() {
        val root = emptyAdvancedSequence("Tonight")
        val startId = checkNotNull(root.childItems()[0].id)
        val areaId = checkNotNull(root.childItems()[1].id)
        assertTrue(addSequenceNode(root, areaId, "DeepSkyObjectContainer"))
        val target = find(root, "DeepSkyObjectContainer").single()
        assertTrue(addSequenceNode(root, checkNotNull(target.id), "SwitchFilter"))
        assertTrue(addSequenceNode(root, checkNotNull(target.id), "TakeExposure"))
        val exposure = find(root, "TakeExposure").single()
        assertEquals("1x1", sequenceBinningText(exposure))
        assertTrue(setSequenceBinning(root, checkNotNull(exposure.id), "2x2"))
        assertEquals("2x2", sequenceBinningText(exposure))
        val summary = dsoExposureSummary(target)
        assertEquals(1, summary.size)
        assertEquals("L", summary.single().filter)
        assertTrue(addSequenceNode(root, startId, "WaitForTime"))
        val wait = find(root, "WaitForTime").single()
        assertEquals("TimeProvider", sequenceTimeProviderId(wait))
        val now = 1_700_000_000_000L
        assertTrue(setSequenceField(root, checkNotNull(wait.id), "Hours", "6"))
        assertTrue(nextClockTimeMillis(wait, now) != null)
        assertTrue(setSequenceTimeProvider(root, checkNotNull(wait.id), "SunsetProvider"))
        assertEquals("SunsetProvider", sequenceTimeProviderId(wait))
        assertEquals(null, nextClockTimeMillis(wait, now))
        assertTrue(nextClockTimeMillis(wait, now, 39.9, 116.4) != null)
    }

    @Test
    fun `snippets clone into a parent and catalog drops land at the hover index`() {
        val root = emptyAdvancedSequence("Tonight")
        val startId = checkNotNull(root.childItems()[0].id)
        val start = root.childItems()[0]
        assertTrue(addSequenceNode(root, startId, "Annotation"))
        assertTrue(addSequenceNode(root, startId, "TakeExposure"))
        val exposure = find(root, "TakeExposure").single()
        val json = exposure.toJson()
        assertTrue(insertSequenceSnippet(root, startId, json, "Items"))
        val copies = find(root, "TakeExposure")
        assertEquals(2, copies.size)
        assertTrue(copies[0].id != copies[1].id)
        assertTrue(addSequenceNodeAt(root, startId, "WaitForTimeSpan", "Items", 0))
        assertEquals("WaitForTimeSpan", start.childItems().first().className)
    }

    @Test
    fun `smart exposure catalog node uses the NINA imaging type`() {
        val root = emptyAdvancedSequence("Tonight")
        val startId = checkNotNull(root.childItems()[0].id)
        assertTrue(addSequenceNode(root, startId, "SmartExposure"))
        val smart = find(root, "SmartExposure").single()
        assertTrue(smart.type.contains("SequenceItem.Imaging.SmartExposure"))
        assertEquals(listOf("SwitchFilter", "TakeExposure"), smart.childItems().map { it.className })
        assertEquals("DitherAfterExposures", smart.collectionNodes("Triggers").single().className)
        assertEquals("1", expressionDefinition(smart, "Iterations"))
        assertEquals("1", expressionDefinition(smart.collectionNodes("Conditions").single(), "Iterations"))
        val runner = (smart.collectionNodes("Triggers").single().fields["TriggerRunner"] as NinaValue.Obj).node
        assertEquals("Dither", runner.childItems().single().className)
    }

    @Test
    fun `a new advanced sequence has three empty areas`() {
        val root = emptyAdvancedSequence("Tonight")
        assertEquals(
            listOf("StartAreaContainer", "TargetAreaContainer", "EndAreaContainer"),
            root.childItems().map { it.className }
        )
        assertTrue(root.childItems().all { it.childItems().isEmpty() })
        assertEquals("开始", root.childItems()[0].textField("Name"))
    }

    @Test
    fun `drag moves an instruction across containers of the same kind`() {
        val root = emptyAdvancedSequence("Tonight")
        val start = root.childItems()[0]
        val end = root.childItems()[2]
        val startId = checkNotNull(start.id)
        val endId = checkNotNull(end.id)
        assertTrue(addSequenceNode(root, startId, "TakeExposure"))
        assertTrue(addSequenceNode(root, startId, "CoolCamera"))
        val exposure = find(root, "TakeExposure").single()
        val exposureId = checkNotNull(exposure.id)

        assertTrue(relocateSequenceNode(root, exposureId, endId, "Items", 0))
        assertEquals(listOf("CoolCamera"), start.childItems().map { it.className })
        assertEquals(listOf("TakeExposure"), end.childItems().map { it.className })
        assertEquals(endId, (exposure.fields.getValue("Parent") as NinaValue.Ref).id)
        assertFalse(relocateSequenceNode(root, exposureId, endId, "Triggers", 0))
        assertFalse(relocateSequenceNode(root, startId, endId, "Items", 0))

        assertTrue(addSequenceNode(root, startId, "SequentialContainer"))
        val container = find(root, "SequentialContainer").single()
        val containerId = checkNotNull(container.id)
        assertFalse(relocateSequenceNode(root, containerId, containerId, "Items", 0))
        assertTrue(relocateSequenceNode(root, exposureId, containerId, "Items", 0))
        assertEquals("TakeExposure", container.childItems().single().className)
        assertTrue(end.childItems().isEmpty())
    }

    @Test
    fun `param summary uses camera for default gain`() {
        val root = emptyAdvancedSequence("Tonight")
        addSequenceNode(root, checkNotNull(root.childItems()[0].id), "TakeExposure")
        val exposure = root.childItems()[0].childItems().single()
        val summary = sequenceParamSummary(exposure, true)
        assertTrue(summary.contains("60"))
        assertTrue(summary.contains("LIGHT"))
        assertTrue(summary.contains("相机"))
    }

    @Test
    fun `reset progress clears exposure count and keeps disable`() {
        val root = emptyAdvancedSequence("Tonight")
        addSequenceNode(root, checkNotNull(root.childItems()[0].id), "TakeExposure")
        val exposure = root.childItems()[0].childItems().single()
        val id = checkNotNull(exposure.id)
        exposure.fields["ExposureCount"] = NinaValue.Num(4.0, true)
        setSequenceDisabled(root, id, true)
        assertTrue(resetSequenceProgress(root, id))
        assertEquals(0, exposure.intField("ExposureCount"))
        assertTrue(sequenceNodeDisabled(exposure))
    }

    private fun find(root: NinaNode, className: String): List<NinaNode> {
        val found = mutableListOf<NinaNode>()
        fun walk(node: NinaNode) {
            if (node.className == className) found += node
            listOf("Items", "Conditions", "Triggers").forEach { field ->
                node.collectionNodes(field).forEach { walk(it) }
            }
        }
        walk(root)
        return found
    }
}
