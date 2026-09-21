package com.indigo.mobileobservatory.astro

import java.util.Locale

data class FovSkyAnchor(
    val raHours: Double,
    val decDegrees: Double,
    val frame: String = "JNOW"
) {
    fun jsFrame(): String = when (frame.uppercase(Locale.US)) {
        "ICRF", "J2000" -> "ICRF"
        else -> "JNOW"
    }
}

/**
 * JS bridge for the active optical train. Target (dashed) is the framing
 * box and always sits at screen centre. Current (solid) follows the mount
 * when connected, otherwise also the screen centre.
 */
object StarMapFovOverlay {
    fun scripts(
        showOverlay: Boolean,
        computation: FovComputation?,
        currentLabel: String,
        targetLabel: String,
        alsoZoom: Boolean,
        currentAnchor: FovSkyAnchor? = null,
        targetAnchor: FovSkyAnchor? = null
    ): List<String> {
        val clearTarget = "window.MercStarMap && window.MercStarMap.clearTargetFovOverlay();"
        val clearCurrent = "window.MercStarMap && window.MercStarMap.clearCurrentFovOverlay();"
        if (!showOverlay || computation == null || !computation.hasOverlay) {
            return listOf(clearCurrent, clearTarget)
        }
        val setCurrent = setter("Current", computation, currentLabel, alsoZoom, currentAnchor)
        val setTarget = setter("Target", computation, targetLabel, alsoZoom = false, targetAnchor)
        return listOf(setCurrent, setTarget)
    }

    fun overlayCaption(
        trainLabel: String,
        roleWord: String,
        computation: FovComputation?
    ): String {
        if (computation == null || !computation.hasOverlay) return ""
        return when (computation.mode) {
            FovInstrumentMode.EYEPIECE ->
                "$trainLabel $roleWord ${"%.2f".format(Locale.US, computation.circleDeg!!)}°"
            FovInstrumentMode.SENSOR ->
                "$trainLabel $roleWord ${"%.2f".format(Locale.US, computation.rectWidthDeg!!)}°×" +
                    "${"%.2f".format(Locale.US, computation.rectHeightDeg!!)}°"
        }
    }

    private fun setter(
        role: String,
        computation: FovComputation,
        label: String,
        alsoZoom: Boolean,
        anchor: FovSkyAnchor?
    ): String {
        val labelJs = jsString(label)
        val extra = if (role == "Current") {
            val zoom = if (alsoZoom) "true" else "false"
            "$zoom,$labelJs${anchorArgs(anchor)}"
        } else {
            "$labelJs${anchorArgs(anchor)}"
        }
        return when (computation.mode) {
            FovInstrumentMode.EYEPIECE ->
                "window.MercStarMap && window.MercStarMap.set${role}CircleFovOverlay(" +
                    "${js(computation.circleDeg!!)},$extra);"
            FovInstrumentMode.SENSOR ->
                "window.MercStarMap && window.MercStarMap.set${role}RectFovOverlay(" +
                    "${js(computation.rectWidthDeg!!)},${js(computation.rectHeightDeg!!)},$extra);"
        }
    }

    private fun anchorArgs(anchor: FovSkyAnchor?): String {
        if (anchor == null) return ""
        return ",${js(anchor.raHours)},${js(anchor.decDegrees)},\"${anchor.jsFrame()}\""
    }

    private fun js(value: Double): String = "%.8f".format(Locale.US, value)

    private fun jsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", "")
        return "\"$escaped\""
    }
}
