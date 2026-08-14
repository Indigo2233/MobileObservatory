package com.indigo.mobileobservatory.camera

import kotlin.math.abs
import kotlin.math.round

enum class GainControlKind {
    NATIVE_GAIN,
    ISO
}

data class GainPreset(
    val value: Float,
    val label: String
)

data class GainCapability(
    val kind: GainControlKind = GainControlKind.NATIVE_GAIN,
    val label: String = "Gain",
    val unit: String? = null,
    val min: Float,
    val max: Float,
    val step: Float = 1f,
    val defaultValue: Float,
    val allowedValues: List<Float> = emptyList(),
    val presets: List<GainPreset> = emptyList(),
    val decimalPlaces: Int = 0,
    val continuous: Boolean = false
) {
    val isDiscrete: Boolean get() = allowedValues.isNotEmpty()
}

object GainValueNormalizer {

    fun normalize(capability: GainCapability, value: Float): Float {
        val lower = minOf(capability.min, capability.max)
        val upper = maxOf(capability.min, capability.max)
        val fallback = capability.defaultValue.takeIf { it.isFinite() } ?: lower
        val clamped = (value.takeIf { it.isFinite() } ?: fallback).coerceIn(lower, upper)
        val allowed = capability.allowedValues
            .asSequence()
            .filter { it.isFinite() && it in lower..upper }
            .distinct()
            .sorted()
            .toList()
        if (allowed.isNotEmpty()) return allowed.minBy { abs(it - clamped) }
        if (capability.continuous) return clamped
        val step = capability.step.takeIf { it.isFinite() && it > 0f } ?: 1f
        return (lower + round((clamped - lower) / step) * step).coerceIn(lower, upper)
    }

    fun displayValue(capability: GainCapability, value: Float): String =
        "%1$.${capability.decimalPlaces.coerceIn(0, 4)}f".format(normalize(capability, value))

    fun filteredPresets(capability: GainCapability): List<GainPreset> =
        capability.presets
            .filter { preset -> preset.value in minOf(capability.min, capability.max)..maxOf(capability.min, capability.max) }
            .map { preset -> preset.copy(value = normalize(capability, preset.value)) }
            .groupBy { it.value }
            .map { (value, entries) -> GainPreset(value, entries.joinToString(" / ") { it.label }) }
            .sortedBy { it.value }

    fun decimalPlacesForStep(step: Float): Int {
        if (!step.isFinite() || step <= 0f) return 3
        return when {
            step >= 1f -> 0
            step >= 0.1f -> 1
            step >= 0.01f -> 2
            else -> 3
        }
    }
}
