package com.indigo.mobileobservatory.ui

import com.indigo.mobileobservatory.ui.components.MAX_CUSTOM_RECORD_FRAMES
import com.indigo.mobileobservatory.ui.components.MAX_CUSTOM_RECORD_SECONDS
import com.indigo.mobileobservatory.ui.components.RecordLimit
import com.indigo.mobileobservatory.ui.components.RecordLimitType
import com.indigo.mobileobservatory.ui.components.formatRecordLimitDuration
import com.indigo.mobileobservatory.ui.components.parseCustomRecordDuration
import com.indigo.mobileobservatory.ui.components.parseCustomRecordFrames
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordLimitTest {
    @Test fun customLimitControlsAreAlwaysPublished() {
        val source = listOf(
            File("src/main/java/com/indigo/mobileobservatory/ui/components/RecordBar.kt"),
            File("app/src/main/java/com/indigo/mobileobservatory/ui/components/RecordBar.kt")
        ).first { it.isFile }.readText()
        val selector = source.substring(
            source.indexOf("private fun RecordLimitSelector"),
            source.indexOf("private fun TargetSelector")
        )
        assertTrue(!selector.contains("customEnabled"))
        assertTrue(selector.contains("custom_frame_limit"))
        assertTrue(selector.contains("custom_duration_limit"))
    }

    @Test fun customFrameCountMustBePositiveAndFitTheRecordingCounter() {
        assertNull(parseCustomRecordFrames(""))
        assertNull(parseCustomRecordFrames("0"))
        assertEquals(1, parseCustomRecordFrames("1"))
        assertEquals(MAX_CUSTOM_RECORD_FRAMES, parseCustomRecordFrames(MAX_CUSTOM_RECORD_FRAMES.toString()))
        assertNull(parseCustomRecordFrames((MAX_CUSTOM_RECORD_FRAMES + 1).toString()))
    }

    @Test fun customDurationCombinesHoursMinutesAndSeconds() {
        assertEquals(3_661, parseCustomRecordDuration("1", "1", "1"))
        assertEquals(59, parseCustomRecordDuration("", "", "59"))
        assertNull(parseCustomRecordDuration("", "60", "0"))
        assertNull(parseCustomRecordDuration("", "0", "60"))
        assertNull(parseCustomRecordDuration("", "", ""))
        assertEquals(MAX_CUSTOM_RECORD_SECONDS, parseCustomRecordDuration("99", "59", "59"))
        assertNull(parseCustomRecordDuration("100", "0", "0"))
    }

    @Test fun durationDisplayKeepsMinutesAndSecondsVisible() {
        assertEquals("5m", RecordLimit(RecordLimitType.TIME, 300).displayText())
        assertEquals("5m30s", RecordLimit(RecordLimitType.TIME, 330).displayText())
        assertEquals("1h02m03s", RecordLimit(RecordLimitType.TIME, 3_723).displayText())
        assertEquals("2h", formatRecordLimitDuration(7_200))
    }

}
