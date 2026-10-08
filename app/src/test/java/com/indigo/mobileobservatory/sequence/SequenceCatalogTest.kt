package com.indigo.mobileobservatory.sequence

import com.indigo.mobileobservatory.sequence.catalog.SequenceCatalog
import com.indigo.mobileobservatory.sequence.catalog.SequenceHardwareSnapshot
import com.indigo.mobileobservatory.sequence.catalog.SupportLevel
import com.indigo.mobileobservatory.sequence.catalog.validateSequence
import com.indigo.mobileobservatory.sequence.catalog.validateSequenceNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceCatalogTest {
    @Test
    fun `visible catalog hides retain types until asked`() {
        val visible = sequenceCatalog().map { it.id }.toSet()
        val all = sequenceCatalog(includeHidden = true).map { it.id }.toSet()
        assertTrue(visible.contains("TakeExposure"))
        assertTrue(visible.contains("SmartExposure"))
        assertFalse(visible.contains("OpenDomeShutter"))
        assertTrue(all.contains("OpenDomeShutter"))
        assertTrue(all.contains("LoopWhile"))
        assertFalse(visible.contains("SequenceRootContainer"))
    }

    @Test
    fun `new sequence uses NINA root and area collection shape`() {
        val root = emptyAdvancedSequence("NINA shape")

        assertTrue(root.fields.containsKey("Items"))
        assertTrue(root.fields.containsKey("Triggers"))
        assertFalse(root.fields.containsKey("Conditions"))
        root.childItems().forEach { area ->
            assertTrue(area.fields.containsKey("Items"))
            assertFalse(area.fields.containsKey("Triggers"))
            assertFalse(area.fields.containsKey("Conditions"))
        }

        val sequential = SequenceCatalog.create("SequentialContainer")
        assertTrue(sequential.fields.containsKey("Items"))
        assertTrue(sequential.fields.containsKey("Triggers"))
        assertTrue(sequential.fields.containsKey("Conditions"))
    }

    @Test
    fun `every listed type has a unique id and default factory`() {
        val listed = SequenceCatalog.types.filter { it.listed }
        assertEquals(listed.size, listed.map { it.id }.toSet().size)
        listed.forEach { spec ->
            val node = SequenceCatalog.create(spec.id)
            assertEquals(spec.id, node.className)
            assertTrue(node.type.contains(spec.id))
        }
    }

    @Test
    fun `every retained type is hidden validated and lossless on round trip`() {
        val visible = sequenceCatalog().map { it.id }.toSet()
        val full = sequenceCatalog(includeHidden = true).map { it.id }.toSet()
        val retained = SequenceCatalog.types.filter { it.listed && it.level == SupportLevel.Retain }

        assertTrue(retained.isNotEmpty())
        retained.forEach { spec ->
            val original = SequenceCatalog.create(spec.id)
            val restored = parseNinaSequence(original.toJson())

            assertTrue(spec.hiddenByDefault)
            assertFalse(spec.id in visible)
            assertTrue(spec.id in full)
            assertEquals(original.toJson(indent = 0), restored.toJson(indent = 0))
            assertTrue(
                spec.id,
                validateSequenceNode(restored).any { it.messageEn.contains("Not supported") }
            )
        }
    }

    @Test
    fun `cool camera defaults match NINA`() {
        val node = SequenceCatalog.create("CoolCamera")
        assertEquals(0.0, expressionNumber(node, "Temperature")!!, 0.0)
        assertEquals(0.0, expressionNumber(node, "Duration")!!, 0.0)
    }

    @Test
    fun `dither trigger writes a runner with dither`() {
        val trigger = SequenceCatalog.create("DitherAfterExposures")
        assertEquals(3.0, expressionNumber(trigger, "AfterExposures")!!, 0.0)
        val runner = (trigger.fields.getValue("TriggerRunner") as NinaValue.Obj).node
        assertEquals(listOf("Dither"), runner.childItems().map { it.className })
    }

    @Test
    fun `validation flags missing devices pause types and out of range values`() {
        val exposure = SequenceCatalog.create("TakeExposure")
        assertTrue(validateSequenceNode(exposure).any { it.messageEn.contains("not connected") })
        assertTrue(
            validateSequenceNode(exposure, SequenceHardwareSnapshot(cameraConnected = true)).isEmpty()
        )

        val root = emptyAdvancedSequence("t")
        addSequenceNode(root, checkNotNull(root.childItems()[0].id), "TakeExposure")
        val node = root.childItems()[0].childItems().single { it.className == "TakeExposure" }
        setSequenceField(root, checkNotNull(node.id), "ExposureTime", "4000")
        assertTrue(
            validateSequenceNode(node, SequenceHardwareSnapshot(cameraConnected = true))
                .any { it.messageEn.contains("above") }
        )

        val park = SequenceCatalog.create("ParkScope")
        assertEquals(SupportLevel.Pause, SequenceCatalog.spec("ParkScope")!!.level)
        assertTrue(validateSequenceNode(park).any { it.messageZh.contains("暂不支持") })
        assertEquals(SupportLevel.Execute, SequenceCatalog.spec("DewHeater")!!.level)
        assertTrue(validateSequenceNode(SequenceCatalog.create("DewHeater"), SequenceHardwareSnapshot(cameraConnected = true)).any { it.messageEn.contains("not connected") })
        assertTrue(validateSequenceNode(SequenceCatalog.create("DewHeater"), SequenceHardwareSnapshot(cameraConnected = true, dewHeater = true)).isEmpty())
        assertEquals(SupportLevel.Execute, SequenceCatalog.spec("SetUSBLimit")!!.level)
        assertTrue(validateSequenceNode(SequenceCatalog.create("SetUSBLimit"), SequenceHardwareSnapshot(cameraConnected = true)).any { it.messageEn.contains("not connected") })
        assertTrue(validateSequenceNode(SequenceCatalog.create("SetUSBLimit"), SequenceHardwareSnapshot(cameraConnected = true, usbBandwidthCapable = true)).isEmpty())
        assertEquals(SupportLevel.Execute, SequenceCatalog.spec("ToggleLight")!!.level)
        assertTrue(validateSequenceNode(SequenceCatalog.create("ToggleLight"), SequenceHardwareSnapshot(coverConnected = true)).any { it.messageEn.contains("not connected") })
        assertTrue(validateSequenceNode(SequenceCatalog.create("ToggleLight"), SequenceHardwareSnapshot(flatPanelConnected = true)).isEmpty())
        assertEquals(SupportLevel.Execute, SequenceCatalog.spec("SetBrightness")!!.level)
        assertTrue(validateSequenceNode(SequenceCatalog.create("SetBrightness"), SequenceHardwareSnapshot(flatPanelConnected = true)).isEmpty())
        assertEquals(SupportLevel.Retain, SequenceCatalog.spec("TakeSubframeExposure")!!.level)
    }

    @Test
    fun `validation reports unknown executable nodes`() {
        val unknown = NinaNode(
            type = "NINA.Sequencer.Trigger.Plugin.PluginOnlyTrigger, Plugin",
            id = "plugin-trigger"
        )

        val issues = validateSequenceNode(unknown)

        assertTrue(issues.any { it.messageEn.contains("Unknown") })
    }

    @Test
    fun `validation ignores disabled instruction subtrees`() {
        val root = emptyAdvancedSequence("Disabled")
        val start = root.childItems()[0]
        assertTrue(addSequenceNode(root, checkNotNull(start.id), "SequentialContainer"))
        val set = start.childItems().single()
        assertTrue(addSequenceNode(root, checkNotNull(set.id), "TakeExposure"))
        assertTrue(setSequenceDisabled(root, checkNotNull(set.id), true))

        assertTrue(validateSequence(root).isEmpty())
    }
}
