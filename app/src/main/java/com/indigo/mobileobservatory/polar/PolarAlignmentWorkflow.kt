package com.indigo.mobileobservatory.polar

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlin.math.abs

/** The screen and software replay use the same ordering: move, solve each point, determine, adjust. */
suspend fun runPolarAlignmentWorkflow(
    initialPoints: List<PolarSolvePoint> = emptyList(),
    moveToPoint: suspend (Int) -> Unit,
    capturePoint: suspend (Int) -> PolarSolvePoint,
    determine: (List<PolarSolvePoint>) -> PolarErrorDetermination,
    onDetermined: (PolarErrorDetermination) -> Unit,
    adjust: suspend (PolarErrorDetermination) -> Unit
) {
    require(initialPoints.size in 0..3)
    val points = initialPoints.toMutableList()
    for (index in points.size..2) {
        currentCoroutineContext().ensureActive()
        moveToPoint(index)
        currentCoroutineContext().ensureActive()
        points += capturePoint(index)
    }
    currentCoroutineContext().ensureActive()
    val solved = determine(points)
    onDetermined(solved)
    currentCoroutineContext().ensureActive()
    adjust(solved)
}

/** Two consecutive valid measurements within tolerance finish adjustment; zero keeps it running. */
suspend fun runPolarCorrectionPhase(
    determination: PolarErrorDetermination,
    toleranceArcMin: Double,
    capture: suspend () -> PolarSolvePoint?,
    onEstimate: (PolarAlignmentResult, CorrectionFieldInfo) -> Unit,
    onUnstable: () -> Unit,
    onWithinTolerance: (Double) -> Unit,
    seed: PolarAlignmentResult = determination.initial
) {
    var seedAzimuth = seed.azimuthErrorDeg
    var seedAltitude = seed.altitudeErrorDeg
    var consecutiveWithinTolerance = 0
    while (true) {
        yield()
        val frame = capture()
        if (frame == null) { consecutiveWithinTolerance = 0; continue }
        val estimate = ContinuousPolarErrorEstimator.estimate(determination, frame, seedAzimuth, seedAltitude)
        if (!estimate.success) {
            consecutiveWithinTolerance = 0
            onUnstable()
            continue
        }
        seedAzimuth = estimate.azimuthErrorDeg
        seedAltitude = estimate.altitudeErrorDeg
        val result = determination.resultForResidual(seedAzimuth, seedAltitude)
        onEstimate(result, determination.correctionFieldInfo(frame))
        if (toleranceArcMin > 0 && abs(result.totalErrorArcMin) <= toleranceArcMin) {
            if (++consecutiveWithinTolerance >= 2) {
                onWithinTolerance(abs(result.totalErrorArcMin))
                return
            }
        } else consecutiveWithinTolerance = 0
    }
}
