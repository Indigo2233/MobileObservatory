package com.indigo.mobileobservatory.polar

enum class PolarSampleAdvice { CAPTURE, OPTICS, STARS, DATABASE, TIMEOUT, SOLVE }

enum class PolarFailureReason { CAPTURE, OPTICS, STARS, DATABASE, TIMEOUT, EXECUTION, IMAGE, MEMORY, NO_SOLUTION }
data class PolarFailureDetails(val reason: PolarFailureReason, val detectedStars: Int?, val nativeMessage: String)

fun PolarSample.failureDetails(): PolarFailureDetails {
    val message = failure ?: result?.message.orEmpty()
    if (failedPhase == PolarSamplePhase.CAPTURING) return PolarFailureDetails(PolarFailureReason.CAPTURE, null, message)
    val attempts = result?.log.orEmpty().lineSequence().map(String::trim).toList()
    val lines = attempts.drop(attempts.indexOfLast { it.startsWith("attempt=") } + 1).filter { line ->
        line.isNotBlank() && !Regex("^(ABIs|ASTAP|workDir|input|mountHint|command|exitCode|imageDims|candidateWcsFiles|outputBase|PLTSOLVD|solutionSource|restoredOriginalDims)=").containsMatchIn(line) &&
            !Regex("^D(?:20|50) files=").containsMatchIn(line) && !line.startsWith("at ")
    }.toList()
    val native = lines.joinToString("\n")
    val text = "$message\n$native".lowercase()
    val countPatterns = listOf(
        Regex("(?:detected|found)\\s+(?:only\\s+)?(\\d+)\\s+stars?", RegexOption.IGNORE_CASE),
        Regex("(\\d+)\\s+stars?\\s+(?:found|detected)", RegexOption.IGNORE_CASE),
        Regex("stars?\\s+(?:detected|found)\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE),
        Regex("(\\d+)\\s+stars?,\\s*\\d+\\s+quads", RegexOption.IGNORE_CASE))
    val count = countPatterns.asSequence().flatMap { it.findAll(native) }
        .maxByOrNull { it.range.first }?.groupValues?.get(1)?.toIntOrNull()
    val reason = when {
        "could not start" in text || "cli is missing" in text || "permission denied" in text -> PolarFailureReason.EXECUTION
        "not enough memory" in text || "out of memory" in text -> PolarFailureReason.MEMORY
        "no star database" in text || "database is not installed" in text || "database not found" in text -> PolarFailureReason.DATABASE
        "timed out" in message.lowercase() || "timeout" in message.lowercase() || "超时" in message -> PolarFailureReason.TIMEOUT
        "not enough stars" in text || "no stars" in text || "too few stars" in text ||
            Regex("only\\s+\\d+\\s+stars?\\s+found[^\\n]*abort").containsMatchIn(text) -> PolarFailureReason.STARS
        "unsupported image" in text || "cannot read image" in text || "could not read image" in text -> PolarFailureReason.IMAGE
        "optics" in message.lowercase() || "focal length" in message.lowercase() || "pixel size" in message.lowercase() ||
            "焦距" in message || Regex("(?:field of view|image scale)[^\\n]*(?:incorrect|invalid|too small|too large)").containsMatchIn(text) -> PolarFailureReason.OPTICS
        else -> PolarFailureReason.NO_SOLUTION
    }
    val relevant = lines.filter { Regex("error|failed|not enough|no stars|no solution|not solved|abort|timeout|timed out", RegexOption.IGNORE_CASE).containsMatchIn(it) }
    return PolarFailureDetails(reason, count, (relevant.takeLast(2).ifEmpty { lines.takeLast(2) }.joinToString("\n").ifBlank { message }).take(1200))
}

fun PolarSample.advice(): PolarSampleAdvice = when (failureDetails().reason) {
    PolarFailureReason.CAPTURE -> PolarSampleAdvice.CAPTURE
    PolarFailureReason.OPTICS -> PolarSampleAdvice.OPTICS
    PolarFailureReason.STARS -> PolarSampleAdvice.STARS
    PolarFailureReason.DATABASE -> PolarSampleAdvice.DATABASE
    PolarFailureReason.TIMEOUT -> PolarSampleAdvice.TIMEOUT
    else -> PolarSampleAdvice.SOLVE
}
