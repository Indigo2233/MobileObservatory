package com.indigo.mobileobservatory.pointing

import com.indigo.mobileobservatory.camera.PhoneManualExposure

internal data class PhoneCaptureAttempt(
    val exposureSeconds: Double,
    val iso: Int,
    val burstFrameCount: Int
)

/**
 * Next capture settings after a failed phone-sky solve. Burst and ISO go up when too few
 * stars were seen; a crowded but unmatched field is treated as a lens/geometry problem.
 */
internal object PhoneSolveCaptureLadder {
    fun next(
        current: PhoneCaptureAttempt,
        failure: WideFieldSolveFailure?,
        starCount: Int,
        attemptIndex: Int,
        maxExposureSeconds: Double,
        minIso: Int,
        maxIso: Int
    ): PhoneCaptureAttempt? {
        if (attemptIndex >= 3) return null
        return when (failure) {
            WideFieldSolveFailure.INSUFFICIENT_STARS -> bumpLight(
                current, maxExposureSeconds, minIso, maxIso
            )
            WideFieldSolveFailure.NO_CANDIDATE ->
                if (starCount < 10) bumpLight(current, maxExposureSeconds, minIso, maxIso) else null
            WideFieldSolveFailure.DEVICE_MOTION,
            WideFieldSolveFailure.AMBIGUOUS_CANDIDATE,
            WideFieldSolveFailure.HIGH_RESIDUAL ->
                if (attemptIndex == 0) current else null
            WideFieldSolveFailure.CAPTURE_FAILED -> current
            else -> if (starCount < 10) bumpLight(current, maxExposureSeconds, minIso, maxIso) else null
        }
    }

    private fun bumpLight(
        current: PhoneCaptureAttempt,
        maxExposureSeconds: Double,
        minIso: Int,
        maxIso: Int
    ): PhoneCaptureAttempt? {
        val nextBurst = PhoneManualExposure.BURST_PRESETS.firstOrNull { it > current.burstFrameCount }
        if (nextBurst != null) return current.copy(burstFrameCount = nextBurst)
        if (current.iso == PhoneManualExposure.ISO_AUTO) {
            val manual = 3200.coerceIn(minIso, maxIso)
            if (manual != current.iso) return current.copy(iso = manual)
        } else if (current.iso < maxIso) {
            return current.copy(iso = (current.iso * 2).coerceAtMost(maxIso))
        }
        if (current.exposureSeconds < maxExposureSeconds - 0.04) {
            return current.copy(
                exposureSeconds = minOf(maxExposureSeconds, current.exposureSeconds * 1.6)
            )
        }
        return null
    }
}
