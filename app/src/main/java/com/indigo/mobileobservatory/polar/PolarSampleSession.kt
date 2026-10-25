package com.indigo.mobileobservatory.polar

import com.indigo.mobileobservatory.astrometry.PlateSolveResult
import com.indigo.mobileobservatory.astrometry.PlateSolveOptics
import com.indigo.mobileobservatory.astrometry.FitsSolveHints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Instant

enum class PolarSamplePhase { EMPTY, CAPTURING, SOLVING, COMPLETE, FAILED, CANCELLED }

data class PolarSolveConfiguration(val focalLengthMm: Double?, val pixelSizeUm: Double?, val catalogHeightPx: Int,
                                   val useMountHint: Boolean, val searchRadiusDeg: Double) {
    fun fieldOfView(hints: FitsSolveHints): Double? =
        PlateSolveOptics.astapFovDeg(hints, focalLengthMm, pixelSizeUm, catalogHeightPx)
}

data class PolarSample(
    val phase: PolarSamplePhase = PolarSamplePhase.EMPTY,
    val file: File? = null,
    val result: PlateSolveResult? = null,
    val failure: String? = null,
    val failedPhase: PolarSamplePhase? = null,
    val capturedAt: Instant? = null
)

data class PolarResumePlan(val startIndex: Int, val reset: Boolean, val moveFirst: Boolean)

fun polarResumePlan(samples: List<PolarSample>, positionedPoint: Int, canResume: Boolean): PolarResumePlan {
    val completed = samples.take(3).takeWhile { it.phase == PolarSamplePhase.COMPLETE }.size
    val reset = !canResume || completed == 3 || completed < positionedPoint
    val start = if (reset) 0 else completed
    return PolarResumePlan(start, reset, !reset && start > positionedPoint)
}

/** Sample execution is independent of the screen so failure evidence survives navigation. */
class PolarSampleSession(private val onEvent: (Int, PolarSample) -> Unit = { _, _ -> }) {
    private val lock = Any()
    private val revisions = LongArray(4)
    private val _samples = MutableStateFlow(List(4) { PolarSample() })
    val samples = _samples.asStateFlow()

    fun import(index: Int, file: File) = synchronized(lock) { revisions[index]++; update(index, PolarSample(file = file)) }
    fun clear() = synchronized(lock) { revisions.indices.forEach { revisions[it]++ }; _samples.value = List(4) { PolarSample() } }
    private fun update(index: Int, sample: PolarSample, revision: Long? = null) = synchronized(lock) {
        if (revision != null && revisions[index] != revision) return@synchronized
        _samples.update { current -> current.toMutableList().also { it[index] = sample } }
        onEvent(index, sample)
    }

    suspend fun execute(index: Int, capture: suspend () -> File?, fieldOfView: suspend (File?) -> Double?,
                        solve: suspend (File, Double) -> PlateSolveResult,
                        noFrame: String = "No image was received from the camera.",
                        missingOptics: String = "Image dimensions, focal length and pixel size are required.",
                        exposureTime: suspend (File) -> Instant = { Instant.now() }): PlateSolveResult {
        var sample = PolarSample(PolarSamplePhase.CAPTURING)
        val revision = synchronized(lock) { revisions[index]++; update(index, sample); revisions[index] }
        try {
            val file = capture() ?: error(noFrame)
            currentCoroutineContext().ensureActive()
            // Preserve the image before optics validation or solver execution can fail.
            sample = sample.copy(phase = PolarSamplePhase.SOLVING, file = file)
            update(index, sample, revision)
            sample = sample.copy(capturedAt = exposureTime(file))
            val fov = fieldOfView(file)?.takeIf { it.isFinite() && it in .2..90.0 } ?: error(missingOptics)
            val result = solve(file, fov)
            currentCoroutineContext().ensureActive()
            if (result.success) {
                val ra = result.raDeg; val dec = result.decDeg
                check(ra != null && dec != null && ra.isFinite() && dec.isFinite() &&
                    ra in 0.0..360.0 && dec in -90.0..90.0) { "Solver returned an invalid sky position." }
            }
            update(index, sample.copy(
                phase = if (result.success) PolarSamplePhase.COMPLETE else PolarSamplePhase.FAILED,
                result = result,
                failure = result.message.takeUnless { result.success },
                failedPhase = PolarSamplePhase.SOLVING.takeUnless { result.success }
            ), revision)
            return result
        } catch (cancel: CancellationException) {
            update(index, sample.copy(phase = PolarSamplePhase.CANCELLED, failedPhase = sample.phase), revision)
            throw cancel
        } catch (failure: Exception) {
            update(index, sample.copy(phase = PolarSamplePhase.FAILED, failure = failure.message ?: failure.javaClass.simpleName,
                failedPhase = sample.phase,
                result = PlateSolveResult(false, failure.message ?: failure.javaClass.simpleName, log = failure.stackTraceToString())), revision)
            throw failure
        }
    }
}
