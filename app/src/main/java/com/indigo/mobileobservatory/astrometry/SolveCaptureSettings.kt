package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.polar.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File

/** A task snapshots its exposure and solve policy before starting motion. */
data class SolveCaptureSettings(
    val exposureUs: Float = 5_000_000f,
    val searchRadiusDeg: Double = 30.0,
    val blindFallback: Boolean = true,
    val gain: Float? = null,
    val focalLengthMm: Double? = null,
    val pixelSizeUm: Double? = null
) {
    init {
        require(exposureUs.isFinite() && exposureUs > 0)
        require(searchRadiusDeg.isFinite() && searchRadiusDeg in .1..180.0)
        require(gain == null || gain.isFinite())
        require(focalLengthMm == null || focalLengthMm.isFinite() && focalLengthMm > 0)
        require(pixelSizeUm == null || pixelSizeUm.isFinite() && pixelSizeUm > 0)
    }
}

fun PlateSolveResult.canRetryCapture(): Boolean = !success && PolarSample(
    phase = PolarSamplePhase.FAILED, failedPhase = PolarSamplePhase.SOLVING, result = this
).failureDetails().reason in setOf(PolarFailureReason.STARS, PolarFailureReason.NO_SOLUTION, PolarFailureReason.TIMEOUT)

/** Blind fallback uses the same photograph; a later capture retry must acquire a new one. */
suspend fun ImagePlateSolver.solveWithFallback(file: File, fov: Double, hint: MountCoordinates?,
    radiusDeg: Double, blindFallback: Boolean): PlateSolveResult {
    val first = solve(file, fov, hint, if (hint == null) 180.0 else radiusDeg)
    if (first.success || hint == null || !blindFallback || !first.canRetryCapture()) return first
    currentCoroutineContext().ensureActive()
    val blind = solve(file, fov, null, 180.0)
    return blind.copy(elapsedMs = first.elapsedMs + blind.elapsedMs,
        log = first.log + "\nattempt=blind_fallback\n" + blind.log)
}

/** Only image-dependent solve failures repeat; camera, optics and database failures stop visibly. */
suspend fun retrySolveCapture(maxAttempts: Int? = null, onRetry: (Int, PlateSolveResult) -> Unit = { _, _ -> },
    captureAndSolve: suspend () -> PlateSolveResult): PlateSolveResult {
    require(maxAttempts == null || maxAttempts > 0)
    var attempt = 1
    while (true) {
        currentCoroutineContext().ensureActive()
        val result = captureAndSolve()
        if (!result.canRetryCapture() || maxAttempts != null && attempt >= maxAttempts) return result
        onRetry(++attempt, result)
        delay(1500)
    }
}
