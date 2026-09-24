package com.indigo.mobileobservatory.sequence

/** Application settings that NINA stores in its profile, not in the sequence JSON. */
data class SequenceSettings(
    val minutesAfterMeridian: Double = 0.0,
    val maxMinutesAfterMeridian: Double = 0.0,
    val pauseTimeBeforeMeridian: Double = 0.0,
    val recenterAfterFlip: Boolean = true,
    val autofocusAfterFlip: Boolean = false,
    val settleTimeSeconds: Int = 0,
    val ditherPixels: Double = 3.0
)
