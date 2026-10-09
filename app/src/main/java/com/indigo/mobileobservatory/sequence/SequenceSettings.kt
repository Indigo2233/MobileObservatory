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

fun meridianFlipDue(minutesToMeridian: Double, settings: SequenceSettings): Boolean {
    val past = -minutesToMeridian
    val until = minutesToMeridian
    val inPause = settings.pauseTimeBeforeMeridian > 0.0 &&
        until > 0.0 &&
        until <= settings.pauseTimeBeforeMeridian
    val max = settings.maxMinutesAfterMeridian
    val inWindow = past >= settings.minutesAfterMeridian &&
        (max <= 0.0 || past <= max)
    return inPause || inWindow
}

fun equatorialSeparationArcmin(
    raHours1: Double,
    decDeg1: Double,
    raHours2: Double,
    decDeg2: Double
): Double {
    var deltaRa = (raHours1 - raHours2) * 15.0
    while (deltaRa > 180.0) deltaRa -= 360.0
    while (deltaRa < -180.0) deltaRa += 360.0
    val raArc = deltaRa * kotlin.math.cos(Math.toRadians(decDeg2))
    return kotlin.math.hypot(raArc, decDeg1 - decDeg2) * 60.0
}
