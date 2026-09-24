package com.indigo.mobileobservatory.sequence

import com.indigo.mobileobservatory.sequence.catalog.SequenceCatalog
import com.indigo.mobileobservatory.sequence.catalog.SequenceHardwareSnapshot
import com.indigo.mobileobservatory.sequence.catalog.SupportLevel
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
    }
}
