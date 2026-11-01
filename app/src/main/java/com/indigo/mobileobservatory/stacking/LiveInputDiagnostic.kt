package com.indigo.mobileobservatory.stacking

/** Input, working set and admission result captured together for budget diagnostics. */
data class LiveInputDiagnostic(
    val issue: String,
    val input: StackSessionKey,
    val budgetBytes: Long,
    val workingBytes: Long,
    val calibrationBytes: Long,
    val retainedSession: Boolean
) {
    val startable get() = issue.isEmpty()
}

internal fun liveStackDiagnosticText(diagnostic: LiveInputDiagnostic?): String {
    if (diagnostic == null) return "no camera input diagnostic"
    fun mib(bytes: Long) = kotlin.math.ceil(bytes / 1048576.0).toLong()
    return "issue=${diagnostic.issue.ifEmpty { "none" }} " +
        "input=${diagnostic.input.width}x${diagnostic.input.height}/${diagnostic.input.format} " +
        "bin=${diagnostic.input.hardwareBin}+${diagnostic.input.softwareBin} " +
        "neededMiB=${mib(diagnostic.workingBytes)} budgetMiB=${mib(diagnostic.budgetBytes)} " +
        "calibrationMiB=${mib(diagnostic.calibrationBytes)} retained=${diagnostic.retainedSession}"
}
