package com.indigo.mobileobservatory.pointing

/** Array-capacity estimate, not a measurement or an Android OOM guarantee. */
object AuxiliaryCaptureMemoryBudget {
    fun requiredHeapBytes(auxiliaryFrameBytes: Long, mainFrameBytes: Long = 0): Long {
        require(auxiliaryFrameBytes in 1..Int.MAX_VALUE.toLong() && mainFrameBytes in 0..Int.MAX_VALUE.toLong())
        // Three chosen raw frames, one queued private sample, and preview/SDK Java ownership.
        // Small PSF timelines are bounded separately; leave headroom for FITS/ASTAP and bitmaps.
        return auxiliaryFrameBytes * 6 + mainFrameBytes * 2 + 32L * 1024 * 1024
    }
}
