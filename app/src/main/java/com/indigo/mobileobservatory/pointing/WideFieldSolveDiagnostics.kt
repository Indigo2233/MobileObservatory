package com.indigo.mobileobservatory.pointing

data class WideFieldCaptureMetrics(
    val logicalCameraId: String? = null,
    val physicalCameraId: String? = null,
    val focalLengthMm: Double? = null,
    val equivalentFocalLengthMm: Double? = null,
    val exposureNs: Long? = null,
    val iso: Int? = null,
    val burstFrameCount: Int? = null,
    val sessionOpenLatencyMs: Long? = null,
    val captureLatencyMs: Long? = null,
    val extractionElapsedNanos: Long? = null,
    val mirrored: Boolean? = null,
    /** Only the explicitly enabled probe reads this already saved private image. */
    val evidenceImagePath: String? = null
)

/** Measurements describe this solve only. Unavailable values stay null. */
data class WideFieldSolveDiagnostics(
    val localElapsedNanos: Long? = null,
    val blindElapsedNanos: Long? = null,
    val indexAccessNanos: Long = 0L,
    val candidateTriangles: Long = 0L,
    val verifiedOrientations: Long = 0L,
    val searchPasses: Int = 0,
    val searchedBuckets: List<String> = emptyList(),
    val residentBuckets: List<String> = emptyList(),
    val residentIndexArrayBytes: Long = 0L,
    val indexLoads: List<WideFieldIndexLoad> = emptyList(),
    val catalogFingerprint: String? = null
)

internal class WideFieldSolveTrace {
    var localNanos: Long? = null
    var blindNanos: Long? = null
    var indexNanos = 0L
    var candidates = 0L
    var verifications = 0L
    var passes = 0
    val buckets = LinkedHashSet<String>()
    val loads = ArrayList<WideFieldIndexLoad>()
    var indexes: LazyWideFieldIndexes? = null
    var fingerprint: String? = null
    fun snapshot() = WideFieldSolveDiagnostics(localNanos, blindNanos, indexNanos, candidates,
        verifications, passes, buckets.toList(), indexes?.residentBuckets().orEmpty(),
        indexes?.residentArrayBytes() ?: 0L, loads.toList(), fingerprint)
}
