package com.indigo.mobileobservatory.camera

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

enum class GainControlKind {
    NATIVE_GAIN,
    ISO
}

enum class GainHelperKind {
    NONE,
    VENDOR_NATIVE
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
    val continuous: Boolean = false,
    val helperKind: GainHelperKind = GainHelperKind.NONE
) {
    val isDiscrete: Boolean get() = allowedValues.isNotEmpty()
    val isReadOnly: Boolean get() = min == max
}

object GainValueNormalizer {

    fun normalize(capability: GainCapability, value: Float): Float {
        val lower = minOf(capability.min, capability.max)
        val upper = maxOf(capability.min, capability.max)
        val fallback = capability.defaultValue.takeIf { it.isFinite() } ?: lower
        val clamped = (value.takeIf { it.isFinite() } ?: fallback).coerceIn(lower, upper)
        val allowed = discreteValues(capability)
        if (allowed.isNotEmpty()) return allowed.minBy { abs(it - clamped) }
        if (capability.continuous) return clamped
        val step = capability.step.takeIf { it.isFinite() && it > 0f } ?: 1f
        return (lower + round((clamped - lower) / step) * step).coerceIn(lower, upper)
    }

    fun displayValue(capability: GainCapability, value: Float): String =
        "%1$.${capability.decimalPlaces.coerceIn(0, 4)}f".format(normalize(capability, value))

    fun parseInput(text: String): Float? = text.trim().toFloatOrNull()

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

    fun adjustForExposureStops(capability: GainCapability, current: Float, stops: Float): Float {
        if (!stops.isFinite() || stops == 0f) return normalize(capability, current)
        if (capability.kind == GainControlKind.ISO || capability.isDiscrete) {
            return adjustDiscreteForStops(capability, current, stops)
        }
        val step = capability.step.takeIf { it.isFinite() && it > 0f } ?: 1f
        val multiplier = when {
            stops >= 0.5f -> 10f
            stops >= 0.25f -> 5f
            stops > 0f -> 1f
            stops <= -0.5f -> -10f
            stops <= -0.25f -> -5f
            else -> -1f
        }
        return normalize(capability, current + step * multiplier)
    }

    fun isoCapability(
        allowedValues: List<Float>,
        current: Float,
        defaultValue: Float = current
    ): GainCapability {
        val allowed = allowedValues
            .filter { it.isFinite() && it > 0f }
            .distinct()
            .sorted()
        val min = allowed.firstOrNull() ?: 100f
        val max = allowed.lastOrNull() ?: min
        val fallback = current.takeIf { it.isFinite() } ?: defaultValue
        return GainCapability(
            kind = GainControlKind.ISO,
            label = "ISO",
            min = min,
            max = max,
            step = 1f,
            defaultValue = defaultValue.takeIf { it.isFinite() } ?: min,
            allowedValues = allowed,
            decimalPlaces = 0
        ).let { capability ->
            capability.copy(defaultValue = normalize(capability, fallback))
        }
    }

    private fun adjustDiscreteForStops(
        capability: GainCapability,
        current: Float,
        stops: Float
    ): Float {
        val allowed = discreteValues(capability)
        if (allowed.isEmpty()) return normalize(capability, current)
        val currentSnapped = allowed.minBy { abs(it - current) }
        val target = currentSnapped * 2.0.pow(stops.toDouble()).toFloat()
        val nearest = allowed.minBy { abs(it - target) }
        if (nearest != currentSnapped) return nearest
        val index = allowed.indexOf(currentSnapped)
        val next = if (stops > 0f) {
            (index + 1).coerceAtMost(allowed.lastIndex)
        } else {
            (index - 1).coerceAtLeast(0)
        }
        return allowed[next]
    }

    private fun discreteValues(capability: GainCapability): List<Float> {
        val lower = minOf(capability.min, capability.max)
        val upper = maxOf(capability.min, capability.max)
        return capability.allowedValues
            .asSequence()
            .filter { it.isFinite() && it in lower..upper }
            .distinct()
            .sorted()
            .toList()
    }
}

object GainConversions {
    const val ZWO_UNITS_PER_DB = 10f
    const val PLAYER_ONE_DB_PER_UNIT = 0.1f
    const val DB_PER_STOP = 6.0206f

    fun toupcamPercentToDb(percent: Float): Float =
        (20.0 * kotlin.math.log10(percent.coerceAtLeast(1f) / 100.0)).toFloat()

    fun zwoNativeToDb(native: Float): Float = native / ZWO_UNITS_PER_DB

    fun playerOneNativeToDb(native: Float): Float = native * PLAYER_ONE_DB_PER_UNIT

    fun zwoStopsToNative(stops: Float): Float = stops * ZWO_UNITS_PER_DB * DB_PER_STOP

    fun playerOneStopsToNative(stops: Float): Float = stops * DB_PER_STOP / PLAYER_ONE_DB_PER_UNIT

    fun dbEquivalent(unit: String?, converted: Float): Float? {
        if (!converted.isFinite()) return null
        if (unit.equals("dB", ignoreCase = true)) return null
        return converted
    }
}
