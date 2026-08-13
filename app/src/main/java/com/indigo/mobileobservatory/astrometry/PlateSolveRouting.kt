package com.indigo.mobileobservatory.astrometry

enum class PlateSolveInputKind { PHONE_BUILT_IN, EXTERNAL_CAMERA }
enum class PlateSolveEngine { PHONE_WIDE_FIELD, ASTAP, PARAMETERS_REQUIRED }

object PlateSolveRouting {
    const val ASTAP_MINIMUM_FOCAL_LENGTH_MM = 200.0

    fun select(input: PlateSolveInputKind, focalLengthMm: Double?): PlateSolveEngine = when (input) {
        PlateSolveInputKind.PHONE_BUILT_IN -> PlateSolveEngine.PHONE_WIDE_FIELD
        PlateSolveInputKind.EXTERNAL_CAMERA -> when {
            focalLengthMm == null || focalLengthMm <= 0.0 -> PlateSolveEngine.PARAMETERS_REQUIRED
            focalLengthMm >= ASTAP_MINIMUM_FOCAL_LENGTH_MM -> PlateSolveEngine.ASTAP
            else -> PlateSolveEngine.PARAMETERS_REQUIRED
        }
    }
}
