package com.indigo.mobileobservatory.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.indigo.mobileobservatory.polar.PolarAdjustmentSnapshot
import com.indigo.mobileobservatory.polar.PolarSampleSession
import com.indigo.mobileobservatory.util.FileLogger

class PolarSamplesViewModel : ViewModel() {
    var adjustment by mutableStateOf<PolarAdjustmentSnapshot?>(null)
    val session = PolarSampleSession { index, sample ->
        if (index < 3) adjustment = null
        val slot = if (index == PolarSampleSession.PREVIEW_INDEX) "test-exposure" else if (index == 3) "correction" else "point-${index + 1}"
        val text = "sample=$slot phase=${sample.phase} failedPhase=${sample.failedPhase} " +
            "file=${sample.file?.absolutePath} elapsedMs=${sample.result?.elapsedMs} reason=${sample.failure.orEmpty()}"
        if (sample.failure != null) FileLogger.w("PolarAlign", "$text\n${sample.result?.log.orEmpty().takeLast(24_000)}")
        else FileLogger.i("PolarAlign", text)
    }
    var positionedPoint = 0
    var canResume = true
}
