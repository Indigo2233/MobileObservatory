package com.indigo.mobileobservatory.pointing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.astro.ObserverSite
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the evidence mechanism with a labelled synthetic file, not sky accuracy or hardware. */
@RunWith(AndroidJUnit4::class)
class WideFieldBaselineInstrumentedTest {
    @Test fun probeIsOptInAndRecordsAnInputHashWithoutPretendingAnIndependentReference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "wf-baseline")
        val files = listOf("enabled", "solver-events.jsonl", "solver-events.previous.jsonl")
            .associate { name -> File(directory, name).let { it to if (it.isFile) it.readBytes() else null } }
        val image = File(context.cacheDir, "synthetic-wf-probe-${UUID.randomUUID()}.fit")
        val bytes = "synthetic probe mechanism fixture, not a real capture".toByteArray()
        image.writeBytes(bytes)
        try {
            val marker = File(directory, "enabled")
            marker.delete()
            val catalog = PhoneBrightStarCatalog.load(context)
            val request = WideFieldSolveRequest(StarExtractionResult(emptyList(), 32, 5f, null, 10f, 1f),
                64, 48, 40.0, 30.0, Instant.parse("2026-10-14T00:00:00Z"), ObserverSite(30.0, 120.0), catalog,
                captureMetrics = WideFieldCaptureMetrics(logicalCameraId = "synthetic-test", evidenceImagePath = image.absolutePath))
            val log = File(directory, "solver-events.jsonl")
            val before = if (log.isFile) log.readBytes() else null
            assertFalse(WideFieldSolver.solve(request).success)
            if (before == null) assertFalse(log.exists()) else assertArrayEquals(before, log.readBytes())

            directory.mkdirs(); marker.writeText("enabled by synthetic instrumentation")
            val result = WideFieldSolver.solve(request)
            assertEquals(WideFieldSolveFailure.INSUFFICIENT_STARS, result.failure)
            val event = JSONObject(log.readLines().last())
            assertFalse(event.getBoolean("success")); assertTrue(event.isNull("raDeg"))
            assertEquals(1, event.getInt("schema"))
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
                event.getJSONObject("capture").getString("imageSha256"))
            assertFalse(event.has("referenceWcs")); assertTrue(event.getJSONObject("metrics").has("javaHeapUsedBytes"))
        } finally {
            image.delete()
            files.forEach { (file, original) -> if (original == null) file.delete() else { file.parentFile?.mkdirs(); file.writeBytes(original) } }
        }
    }
}
