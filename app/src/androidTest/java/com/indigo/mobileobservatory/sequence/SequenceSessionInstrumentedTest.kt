package com.indigo.mobileobservatory.sequence

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceSessionInstrumentedTest {
    @Test
    fun sustainedCaptureAndInterruptedSessionRecovery() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "sequence-endurance-").toFile()
        val owner = SupervisorJob()
        val captureScope = CoroutineScope(owner + Dispatchers.Main.immediate)
        val hardware = VirtualSequenceHardware()
        fun runtime(scope: CoroutineScope) = SequenceRuntime(File(dir, "templates"), File(dir, "sessions"), scope, hardware, hardware)
        val sequence = runtime(captureScope)
        try {
            withContext(Dispatchers.Main) {
                sequence.updateDraft(sequence.draft.value.copy(title = "Endurance",
                    rows = listOf(SimpleExposureRow(null, 0.1, 120, 10, 120, 4, 4))))
                sequence.start()
            }
            val completed = withTimeout(90_000) { sequence.state.first { it.phase == SequencePhase.Completed } }
            assertEquals(120, completed.framesDone)
            val session = File(dir, "sessions").listFiles()!!.single()
            val manifest = JSONObject(File(session, "session.json").readText())
            assertEquals("Completed", manifest.getString("phase"))
            val files = session.listFiles()!!.filter { it.extension == "fits" }
            assertEquals(120, files.size)
            val names = (0 until 120).map { manifest.getJSONArray("frames").getJSONObject(it).getString("name") }
            assertEquals(files.map { it.name }.toSet(), names.toSet())

            withContext(Dispatchers.Main) { sequence.start() }
            withTimeout(10_000) { sequence.state.first { it.framesDone >= 3 } }
            owner.cancelAndJoin()
            val interrupted = File(dir, "sessions").listFiles()!!.single { it != session }
            val manifestFile = File(interrupted, "session.json")
            val checkpoint = JSONObject(manifestFile.readText())
            val saved = checkpoint.getJSONArray("frames")
            val last = saved.remove(saved.length() - 1) as JSONObject
            saved.put(JSONObject().put("name", "missing.fits").put("exposureSeconds", 0.1))
            manifestFile.writeText(checkpoint.toString())
            File(interrupted, "unfinished.partial").writeBytes(byteArrayOf(0))
            val restored = runtime(this)
            assertTrue(restored.restoreInterruptedSession())
            assertFalse(restored.running())
            assertEquals(interrupted.listFiles()!!.count { it.extension == "fits" }, restored.frames.value.size)
            val recovered = restored.frames.value.single { it.name == last.getString("name") }
            assertEquals(last.getDouble("exposureSeconds"), recovered.exposureSeconds, 0.000001)
            assertEquals(last.getString("filter"), recovered.filter)
            assertTrue(restored.frames.value.size in 3 until 120)
            assertEquals(SequencePhase.Interrupted, restored.state.value.phase)
        } finally {
            owner.cancelAndJoin()
            dir.deleteRecursively()
        }
        Unit
    }
}
