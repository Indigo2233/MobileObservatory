package com.indigo.mobileobservatory.ui.screens

import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.pointing.WideFieldSolveFailure

internal object PushToSolveCopy {
    fun messageRes(failure: WideFieldSolveFailure?, starCount: Int): Int = when (failure) {
        WideFieldSolveFailure.INSUFFICIENT_STARS -> R.string.push_to_fail_few_stars
        WideFieldSolveFailure.DEVICE_MOTION -> R.string.push_to_fail_motion
        WideFieldSolveFailure.AMBIGUOUS_CANDIDATE -> R.string.push_to_fail_ambiguous
        WideFieldSolveFailure.NO_CANDIDATE ->
            if (starCount >= 10) R.string.push_to_fail_no_match else R.string.push_to_fail_few_stars
        WideFieldSolveFailure.HIGH_RESIDUAL -> R.string.push_to_fail_residual
        WideFieldSolveFailure.INVALID_LENS_METADATA -> R.string.push_to_fail_lens
        WideFieldSolveFailure.CAPTURE_FAILED -> R.string.push_to_fail_capture
        null -> if (starCount < 8) R.string.push_to_fail_few_stars else R.string.push_to_fail_generic
    }
}
