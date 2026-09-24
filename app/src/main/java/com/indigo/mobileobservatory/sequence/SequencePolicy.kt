package com.indigo.mobileobservatory.sequence

data class SequenceSignals(
    val nowMillis: Long = 0L,
    val altitudeDeg: Double? = null,
    val altitudeRising: Boolean? = null,
    val minutesToMeridian: Double? = null,
    val temperatureC: Double? = null,
    val lastHfr: Double? = null,
    val filterName: String? = null,
    val sunAltitudeDeg: Double? = null,
    val moonAltitudeDeg: Double? = null,
    val moonIlluminationPct: Double? = null,
    val framesDone: Int = 0,
    val exposuresSinceCenter: Int = 0,
    val exposuresSinceAutofocus: Int = 0,
    val minutesSinceAutofocus: Double = Double.POSITIVE_INFINITY,
    val temperatureAtAutofocus: Double? = null,
    val filterAtAutofocus: String? = null,
    val hfrAtAutofocus: Double? = null
)

enum class TriggerMoment { Before, After }

class TriggerMemory {
    val meridianFired = mutableSetOf<String>()
    val lastFireFrame = mutableMapOf<String, Int>()
}

fun knownCondition(className: String): Boolean = className in setOf(
    "LoopCondition",
    "AltitudeCondition",
    "TimeCondition",
    "TimeSpanCondition",
    "AboveHorizonCondition",
    "SunAltitudeCondition",
    "MoonAltitudeCondition",
    "MoonIlluminationCondition"
)

fun loopAllows(condition: NinaNode, completed: Int): Boolean {
    val limit = expressionNumber(condition, "Iterations") ?: 2.0
    return completed < limit
}

fun limitAllows(condition: NinaNode, signals: SequenceSignals, startedAtMillis: Long): Boolean {
    return when (condition.className) {
        "AltitudeCondition", "AboveHorizonCondition" -> {
            val altitude = signals.altitudeDeg ?: return true
            if (signals.altitudeRising == true) return true
            altitude >= altitudeOffset(condition)
        }
        "SunAltitudeCondition" -> {
            val altitude = signals.sunAltitudeDeg ?: return true
            !compareOrdered(altitude, altitudeOffset(condition), altitudeComparator(condition))
        }
        "MoonAltitudeCondition" -> {
            val altitude = signals.moonAltitudeDeg ?: return true
            !compareOrdered(altitude, altitudeOffset(condition), altitudeComparator(condition))
        }
        "MoonIlluminationCondition" -> {
            val illumination = signals.moonIlluminationPct ?: return true
            val limit = expressionNumber(condition, "UserMoonIllumination") ?: 0.0
            !compareOrdered(illumination, limit, condition.intField("Comparator") ?: 3)
        }
        "TimeCondition" -> !localTimeReached(condition, signals.nowMillis)
        "TimeSpanCondition" -> {
            val minutes = expressionNumber(condition, "TimeSpan")
                ?: condition.doubleField("Minutes")
                ?: 1.0
            signals.nowMillis - startedAtMillis < (minutes * 60_000.0).toLong()
        }
        else -> true
    }
}

fun altitudeComparator(node: NinaNode): Int {
    node.intField("Comparator")?.let { return it }
    val data = (node.fields["Data"] as? NinaValue.Obj)?.node
    return data?.intField("Comparator") ?: 3
}

fun compareOrdered(value: Double, threshold: Double, comparator: Int): Boolean = when (comparator) {
    0 -> kotlin.math.abs(value - threshold) < 0.5
    1 -> value < threshold
    2 -> value <= threshold
    3 -> value > threshold
    4 -> value >= threshold
    5 -> kotlin.math.abs(value - threshold) >= 0.5
    else -> value > threshold
}

fun altitudeOffset(condition: NinaNode): Double {
    condition.doubleField("AltitudeOffset")?.let { return it }
    val data = (condition.fields["Data"] as? NinaValue.Obj)?.node
    return data?.doubleField("Offset") ?: 30.0
}

fun localTimeReached(condition: NinaNode, nowMillis: Long): Boolean {
    val hours = condition.intField("Hours") ?: return false
    val minutes = condition.intField("Minutes") ?: 0
    val local = java.time.Instant.ofEpochMilli(nowMillis)
        .atZone(java.time.ZoneId.systemDefault())
    return local.hour * 60 + local.minute >= hours * 60 + minutes
}

fun matchingTriggers(
    triggers: List<NinaNode>,
    previous: NinaNode?,
    next: NinaNode?,
    moment: TriggerMoment,
    signals: SequenceSignals,
    memory: TriggerMemory,
    settings: SequenceSettings
): List<NinaNode> = triggers.filter { trigger ->
    if (moment == TriggerMoment.After) shouldTriggerAfter(trigger, previous, next, signals)
    else shouldTrigger(trigger, previous, next, signals, memory, settings)
}

fun insertedInstructions(trigger: NinaNode, settings: SequenceSettings = SequenceSettings()): List<String> =
    when (trigger.className) {
        "MeridianFlipTrigger" -> buildList {
            add("StopGuiding")
            add("SlewScopeToRaDec")
            if (settings.recenterAfterFlip) add("Center")
            if (settings.autofocusAfterFlip) add("RunAutofocus")
            add("StartGuiding")
        }
        "CenterAfterDriftTrigger" -> listOf("Center")
        "DitherAfterExposures" -> listOf("Dither")
        "RestoreGuiding" -> listOf("StartGuiding")
        "AutofocusAfterExposures",
        "AutofocusAfterTemperatureChangeTrigger",
        "AutofocusAfterTimeTrigger",
        "AutofocusAfterFilterChange",
        "AutofocusAfterHFRIncreaseTrigger" -> listOf("RunAutofocus")
        else -> emptyList()
    }

fun triggerRunnerItems(trigger: NinaNode): List<NinaNode> {
    val runner = (trigger.fields["TriggerRunner"] as? NinaValue.Obj)?.node ?: return emptyList()
    return runner.childItems()
}

private fun shouldTriggerAfter(
    trigger: NinaNode,
    previous: NinaNode?,
    next: NinaNode?,
    signals: SequenceSignals
): Boolean {
    if (trigger.className.isEmpty()) return false
    previous ?: next ?: signals
    return false
}

private fun shouldTrigger(
    trigger: NinaNode,
    previous: NinaNode?,
    next: NinaNode?,
    signals: SequenceSignals,
    memory: TriggerMemory,
    settings: SequenceSettings
): Boolean {
    val key = trigger.id ?: trigger.className
    return when (trigger.className) {
        "MeridianFlipTrigger" -> {
            if (key in memory.meridianFired) false
            else {
                val minutes = signals.minutesToMeridian ?: return false
                minutes <= settings.minutesAfterMeridian
            }
        }
        "CenterAfterDriftTrigger" -> {
            if (!nextIsLight(next)) return false
            val every = (expressionNumber(trigger, "AfterExposures") ?: 1.0).toInt()
            every > 0 && signals.exposuresSinceCenter >= every
        }
        "DitherAfterExposures" -> {
            if (!nextIsLight(next)) return false
            val every = (expressionNumber(trigger, "AfterExposures") ?: 3.0).toInt()
            val last = memory.lastFireFrame[key] ?: -1
            every > 0 &&
                signals.framesDone > 0 &&
                signals.framesDone % every == 0 &&
                last < signals.framesDone
        }
        "RestoreGuiding" -> nextIsLight(next)
        "AutofocusAfterExposures" -> {
            if (!nextIsLight(next)) return false
            val every = (expressionNumber(trigger, "AfterExposures") ?: 5.0).toInt()
            every > 0 && signals.exposuresSinceAutofocus > 0 && signals.exposuresSinceAutofocus % every == 0
        }
        "AutofocusAfterTimeTrigger" -> {
            val minutes = expressionNumber(trigger, "Amount") ?: 30.0
            signals.minutesSinceAutofocus >= minutes
        }
        "AutofocusAfterTemperatureChangeTrigger" -> {
            val delta = expressionNumber(trigger, "Amount") ?: 5.0
            val now = signals.temperatureC ?: return false
            val then = signals.temperatureAtAutofocus ?: return false
            kotlin.math.abs(now - then) >= delta
        }
        "AutofocusAfterFilterChange" -> {
            val now = signals.filterName ?: return false
            val then = signals.filterAtAutofocus ?: return false
            now != then
        }
        "AutofocusAfterHFRIncreaseTrigger" -> {
            val percent = expressionNumber(trigger, "Amount") ?: 5.0
            val now = signals.lastHfr ?: return false
            val base = signals.hfrAtAutofocus ?: return false
            base > 0.0 && now >= base * (1.0 + percent / 100.0)
        }
        else -> false
    }
}

private fun nextIsLight(next: NinaNode?): Boolean {
    if (next?.className != "TakeExposure") return false
    val imageType = next.textField("ImageType") ?: "LIGHT"
    return imageType == "LIGHT"
}

fun markTriggerFired(trigger: NinaNode, signals: SequenceSignals, memory: TriggerMemory) {
    val key = trigger.id ?: trigger.className
    memory.lastFireFrame[key] = signals.framesDone
    if (trigger.className == "MeridianFlipTrigger") memory.meridianFired += key
}
