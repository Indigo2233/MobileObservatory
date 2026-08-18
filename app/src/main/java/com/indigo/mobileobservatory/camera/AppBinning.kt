package com.indigo.mobileobservatory.camera

data class AppBinPlan(
    val appBin: Int,
    val hardwareBin: Int,
    val softwareBin: Int
) {
    val usesHardware: Boolean get() = hardwareBin > 1
    val usesSoftware: Boolean get() = softwareBin > 1
}

object AppBinning {
    val OPTIONS: List<Int> = listOf(1, 2, 3, 4)

    fun hardwareCandidates(requested: Int, supportedHardware: List<Int>): List<Int> {
        val app = requested.coerceAtLeast(1)
        return supportedHardware.filter { it > 0 && app % it == 0 }.distinct().sortedDescending()
    }

    fun plan(requested: Int, supportedHardware: List<Int>): AppBinPlan {
        val app = requested.coerceAtLeast(1)
        val hw = hardwareCandidates(app, supportedHardware).firstOrNull() ?: 1
        return AppBinPlan(appBin = app, hardwareBin = hw, softwareBin = (app / hw).coerceAtLeast(1))
    }
}
