package com.indigo.mobileobservatory.pointing

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.security.MessageDigest

/** Explicitly enabled private evidence stream. No image pixels and no normal-mode logging. */
internal class WideFieldSolveProbe(context: Context) {
    private val application = context.applicationContext
    private val directory = File(application.filesDir, "wf-baseline")
    private val enabled = File(directory, "enabled")
    private val session by lazy { UUID.randomUUID().toString() }
    private var peakObservedJavaBytes = 0L

    @Synchronized fun record(request: WideFieldSolveRequest, result: WideFieldSolveResult) {
        if (!enabled.isFile) return
        val runtime = Runtime.getRuntime()
        val javaUsed = runtime.totalMemory() - runtime.freeMemory()
        peakObservedJavaBytes = maxOf(peakObservedJavaBytes, javaUsed)
        val battery = application.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryIntent = application.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        fun property(id: Int) = battery?.getIntProperty(id)?.takeUnless { it == Int.MIN_VALUE }
        fun gc(name: String) = runCatching { Debug.getRuntimeStat(name).toLongOrNull() }.getOrNull()
        val metrics = JSONObject().apply {
            put("javaHeapUsedBytes", javaUsed); put("maxObservedJavaHeapBytes", peakObservedJavaBytes)
            put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            put("bytesAllocatedCounter", gc("art.gc.bytes-allocated") ?: JSONObject.NULL)
            put("gcCount", gc("art.gc.gc-count") ?: JSONObject.NULL)
            put("gcTimeMillis", gc("art.gc.gc-time") ?: JSONObject.NULL)
            put("blockingGcCount", gc("art.gc.blocking-gc-count") ?: JSONObject.NULL)
            put("blockingGcTimeMillis", gc("art.gc.blocking-gc-time") ?: JSONObject.NULL)
            put("batteryChargeMicroampHours", property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: JSONObject.NULL)
            put("batteryCurrentMicroamps", property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: JSONObject.NULL)
            put("batteryCapacityPercent", property(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: JSONObject.NULL)
            val temperature = batteryIntent?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0)
            put("batteryTemperatureCelsius", temperature ?: JSONObject.NULL)
            put("thermalStatus", if (Build.VERSION.SDK_INT >= 29)
                (application.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus ?: JSONObject.NULL else JSONObject.NULL)
        }
        val trace = result.quality.diagnostics
        val captured = request.captureMetrics
        val image = captured?.evidenceImagePath?.let(::File)
        val imageHash = image?.takeIf { it.isFile }?.let { file -> runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val bytes = ByteArray(8192)
                while (true) { val count = stream.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull() }
        val diagnostics = JSONObject().apply {
            put("localElapsedNanos", trace?.localElapsedNanos ?: JSONObject.NULL)
            put("blindElapsedNanos", trace?.blindElapsedNanos ?: JSONObject.NULL)
            put("indexAccessNanos", trace?.indexAccessNanos ?: JSONObject.NULL)
            put("candidateTriangles", trace?.candidateTriangles ?: JSONObject.NULL)
            put("verifiedOrientations", trace?.verifiedOrientations ?: JSONObject.NULL)
            put("searchPasses", trace?.searchPasses ?: JSONObject.NULL)
            put("searchedBuckets", JSONArray(trace?.searchedBuckets.orEmpty()))
            put("residentBuckets", JSONArray(trace?.residentBuckets.orEmpty()))
            put("residentIndexArrayBytes", trace?.residentIndexArrayBytes ?: JSONObject.NULL)
            put("indexLoads", JSONArray().apply { trace?.indexLoads?.forEach { load -> put(JSONObject().apply {
                put("bucket", load.bucket); put("origin", load.origin); put("records", load.records)
                put("arrayBytes", load.arrayBytes); put("elapsedNanos", load.elapsedNanos)
                put("rejectedAsset", load.rejectedAsset ?: JSONObject.NULL)
            }) } })
        }
        val event = JSONObject().apply {
            put("schema", 1); put("sessionId", session); put("elapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos())
            put("utcExposureMidpoint", request.observationTime.toString())
            put("frameWidth", request.frameWidth); put("frameHeight", request.frameHeight)
            put("initialFovWidthDeg", request.initialFovWidthDeg); put("initialFovHeightDeg", request.initialFovHeightDeg)
            put("extractedStars", request.extraction.stars.size); put("catalogFingerprint", request.catalog.geometryFingerprint)
            put("success", result.success); put("failure", result.failure?.name ?: JSONObject.NULL)
            put("raDeg", result.raDeg ?: JSONObject.NULL); put("decDeg", result.decDeg ?: JSONObject.NULL)
            put("rotationDeg", result.rotationDeg ?: JSONObject.NULL); put("fovWidthDeg", result.fovWidthDeg ?: JSONObject.NULL)
            put("fovHeightDeg", result.fovHeightDeg ?: JSONObject.NULL); put("elapsedMs", result.quality.elapsedMs)
            put("matchedStars", result.quality.matchedStars); put("rmsResidualDeg", result.quality.rmsResidualDeg ?: JSONObject.NULL)
            put("usedImuPrior", result.quality.usedImuPrior); put("blindFallbackUsed", result.quality.blindFallbackUsed)
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL); put("sdk", Build.VERSION.SDK_INT)
                put("fingerprint", Build.FINGERPRINT)
                put("isEmulator", Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk", true))
            })
            put("capture", JSONObject().apply {
                put("logicalCameraId", captured?.logicalCameraId ?: JSONObject.NULL)
                put("physicalCameraId", captured?.physicalCameraId ?: JSONObject.NULL)
                put("focalLengthMm", captured?.focalLengthMm ?: JSONObject.NULL)
                put("equivalentFocalLengthMm", captured?.equivalentFocalLengthMm ?: JSONObject.NULL)
                put("exposureNs", captured?.exposureNs ?: JSONObject.NULL); put("iso", captured?.iso ?: JSONObject.NULL)
                put("burstFrameCount", captured?.burstFrameCount ?: JSONObject.NULL)
                put("sessionOpenLatencyMs", captured?.sessionOpenLatencyMs ?: JSONObject.NULL)
                put("captureLatencyMs", captured?.captureLatencyMs ?: JSONObject.NULL)
                put("extractionElapsedNanos", captured?.extractionElapsedNanos ?: JSONObject.NULL)
                put("mirrored", captured?.mirrored ?: JSONObject.NULL)
                put("imageName", image?.name ?: JSONObject.NULL); put("imageSha256", imageHash ?: JSONObject.NULL)
            })
            put("metrics", metrics); put("diagnostics", diagnostics)
        }
        directory.mkdirs()
        val file = File(directory, "solver-events.jsonl")
        if (file.length() >= 4L * 1024 * 1024) {
            val previous = File(directory, "solver-events.previous.jsonl")
            if (previous.exists() && !previous.delete()) return
            if (!file.renameTo(previous)) return
        }
        file.appendText(event.toString() + "\n", Charsets.UTF_8)
    }
}
