package com.indigo.mobileobservatory.camera

/** Stable internal names used by capture state and generated media names. */
object CaptureTargetNames {
    const val DEFAULT = "Jupiter"

    val solarSystemTargets = listOf("Jupiter", "Saturn", "Mars", "Venus", "Mercury", "Moon", "Sun")

    fun canonical(value: String): String {
        val trimmed = value.trim()
        return when (trimmed) {
            "", "Pla" -> DEFAULT
            "Ju" -> "Jupiter"
            "Sa" -> "Saturn"
            "Ma" -> "Mars"
            "Ve" -> "Venus"
            "Me" -> "Mercury"
            "Mo" -> "Moon"
            "Su" -> "Sun"
            else -> trimmed
        }
    }
}
