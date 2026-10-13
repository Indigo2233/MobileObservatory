package com.indigo.mobileobservatory.stacking

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.Roi
import com.indigo.mobileobservatory.recording.RawRecordingNormalizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Repeatable synthetic workload. Reports observations, never device performance thresholds. */
@RunWith(AndroidJUnit4::class)
class StackAllocationInstrumentedTest {
    private val width = 1024
    private val height = 768
    private val traits = PixelTraits(alignment = SampleAlignment.RIGHT)
    private fun frame(): FrameData {
        val bytes = ByteArray(width * height * 2)
        for (p in 0 until width * height) {
            val value = 1000 + (p % width) * 4 + (p / width) * 2 + (p % 19) * 100
            bytes[p * 2] = value.toByte(); bytes[p * 2 + 1] = (value ushr 8).toByte()
        }
        return FrameData(bytes, width, height, PixelFormat.BAYER_RG16, 0, 0)
    }

    @Test fun recordSteadyStateAllocationTimingHeapAndBoundedQueue() = runBlocking {
        val input = frame()
        val workspace = DdfapdDemosaicer.Workspace()
        val image = LinearPixelDecoder.decode(input, traits, algorithm = DemosaicAlgorithm.DDFAPD, workspace = workspace)
        val cache = BackgroundModelCache()
        val parameters = StackProcessingParameters.preset(ProcessingPreset.NATURAL)
        var checksum = 0f
        val measurements = JSONArray()
        measurements.put(measure("recording_owned_normalize", 24) {
            val result = RawRecordingNormalizer.normalizeOwnedInPlace(input, traits)
            check(result.data === input.data)
        })
        measurements.put(measure("ddfapd_reused_workspace", 20) {
            checksum += LinearPixelDecoder.decode(input, traits, algorithm = DemosaicAlgorithm.DDFAPD,
                workspace = workspace).pixels[100]
        })
        measurements.put(measure("natural_cached_background_direct_bitmap", 24) {
            val prepared = StackPostProcessor.prepare(image, parameters, cache)
            val bitmap = StackBitmap.create(prepared, 1280)
            checksum += (bitmap.getPixel(100, 100) and 255).toFloat()
            bitmap.recycle()
        })
        assertEquals(5L, workspace.arrayAllocations)
        assertEquals(1L, cache.fitCount)
        workspace.release()
        assertEquals(0L, workspace.scratchBytes)

        val key = StackSessionKey("synthetic-buffer", width, height, input.pixelFormat,
            Roi(0, 0, width, height), 1, 1, 33_333.0, 0f, "none", "synthetic")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queueWorkspace = DdfapdDemosaicer.Workspace()
        // Isolate bounded ingestion and decoding. No camera, registration or disk throughput
        // is represented by this queue test; every decoded frame is accepted deliberately.
        val session = LiveStackSession(scope, { object : LiveStackEngine {
            override fun add(image: LinearImage, exposureSeconds: Double, cancelled: () -> Boolean) =
                LiveFrameOutcome(true, 0, 0.0, 0.0)
            override fun snapshot(state: LiveStackState): LiveStackSnapshot? = null
        } }, { f, _ -> LinearPixelDecoder.decode(f, traits, algorithm = DemosaicAlgorithm.DDFAPD,
            workspace = queueWorkspace) }, {}, onWorkerReset = queueWorkspace::release)
        val state: LiveStackState
        val queueStart = System.nanoTime()
        try {
            session.start(key)
            repeat(60) { index ->
                session.offer(input.copy(frameId = index.toLong()), key)
                val waitNanos = queueStart + (index + 1) * 1_000_000_000L / 30 - System.nanoTime()
                if (waitNanos > 0) delay(waitNanos / 1_000_000L)
            }
            withTimeout(60_000) {
                while (session.state.value.accepted + session.state.value.dropped < 60) delay(20)
            }
            state = session.state.value
        } finally { session.close(); session.awaitClosed(); scope.cancel() }
        val report = JSONObject().put("environment", "Android synthetic; see device model, not a physical-camera benchmark")
            .put("model", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT)
            .put("width", width).put("height", height).put("warmupFrames", 4)
            .put("measurements", measurements).put("checksum", checksum)
            .put("queue", JSONObject().put("offered", state.received).put("accepted", state.accepted)
                .put("dropped", state.dropped).put("dropRate", state.dropped.toDouble() / state.received)
                .put("targetInputFps", 30).put("elapsedMs", (System.nanoTime() - queueStart) / 1_000_000))
            .put("limitations", "Single run; short synthetic sample. Heap is sampled every 1 ms. ART gc-time/blocking-gc-time are aggregate counters, not stop-the-world pause percentiles. Queue tests decoding only. No before/after physical Android device comparison.")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(app.getExternalFilesDir(null), "stack-allocation-current.json")
        output.writeText(report.toString(2))
        println("STACK_ALLOCATION_REPORT ${output.absolutePath}")
        // UTP may uninstall the app after a run; its per-test logcat remains available.
        println("STACK_ALLOCATION_JSON $report")
    }

    private fun measure(name: String, frames: Int, action: () -> Unit): JSONObject {
        repeat(4) { action() }
        System.gc()
        val runtime = Runtime.getRuntime()
        fun used() = runtime.totalMemory() - runtime.freeMemory()
        fun stat(key: String) = Debug.getRuntimeStat(key)?.toLongOrNull()
        val allocated = stat("art.gc.bytes-allocated")
        val gc = stat("art.gc.gc-time")
        val blocking = stat("art.gc.blocking-gc-time")
        val collections = stat("art.gc.gc-count")
        val peak = AtomicLong(used())
        val nativePeak = AtomicLong(Debug.getNativeHeapAllocatedSize())
        val running = AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                peak.accumulateAndGet(used(), ::maxOf)
                nativePeak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                Thread.sleep(1)
            }
        }.apply { start() }
        val durations = LongArray(frames)
        val start = System.nanoTime()
        try {
            repeat(frames) { index ->
                val before = System.nanoTime(); action(); durations[index] = System.nanoTime() - before
            }
        } finally { running.set(false); sampler.join() }
        val seconds = (System.nanoTime() - start) / 1e9
        fun delta(key: String, before: Long?): Any = before?.let { first -> stat(key)?.minus(first) } ?: JSONObject.NULL
        val bytes = delta("art.gc.bytes-allocated", allocated)
        durations.sort()
        return JSONObject().put("name", name).put("frames", frames).put("seconds", seconds)
            .put("allocatedBytes", bytes).put("allocationBytesPerSecond", (bytes as? Long)?.div(seconds) ?: JSONObject.NULL)
            .put("gcTimeMs", delta("art.gc.gc-time", gc)).put("blockingGcTimeMs", delta("art.gc.blocking-gc-time", blocking))
            .put("gcCount", delta("art.gc.gc-count", collections)).put("sampledPeakJavaBytes", peak.get())
            .put("sampledPeakNativeBytes", nativePeak.get())
            .put("frameP95Ms", durations[kotlin.math.ceil(frames * .95).toInt() - 1] / 1e6)
    }
}
