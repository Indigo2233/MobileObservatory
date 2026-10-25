package com.indigo.mobileobservatory.polar

enum class PolarSampleAdvice { CAPTURE, OPTICS, STARS, DATABASE, TIMEOUT, SOLVE }

fun PolarSample.advice(): PolarSampleAdvice {
    if (failedPhase == PolarSamplePhase.CAPTURING) return PolarSampleAdvice.CAPTURE
    val detail = "${failure.orEmpty()}\n${result?.log.orEmpty()}".lowercase()
    return when {
        "optics" in detail || "focal length" in detail || "pixel size" in detail || "焦距" in detail -> PolarSampleAdvice.OPTICS
        "not enough stars" in detail || "no stars" in detail || "too few stars" in detail -> PolarSampleAdvice.STARS
        "database is not installed" in detail || "database not found" in detail -> PolarSampleAdvice.DATABASE
        "timed out" in detail || "timeout" in detail || "超时" in detail -> PolarSampleAdvice.TIMEOUT
        else -> PolarSampleAdvice.SOLVE
    }
}
