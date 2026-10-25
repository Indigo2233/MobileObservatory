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

/** Display reference only; this does not authorize optical follow or confirm target arrival. */
data class FovCurrentReference(
    val anchor: FovSkyAnchor,
    val positionAngleDeg: Double?,
    val mountEstimated: Boolean,
    val fieldConfirmed: Boolean
)

/**
 * JS bridge for the active optical train. Target (dashed) is anchored to a
 * selected framing coordinate or the map centre. Current (solid) has an explicit
 * sky anchor. A mount reference must be labelled as an estimate; only the shared
 * optical selector can authorize optical follow and pointing verification.
 */
object StarMapFovOverlay {
    fun currentReference(
        optical: FovPointingDecision,
        frameCentered: Boolean,
        mountAnchor: FovSkyAnchor?
    ): FovCurrentReference? {
        val source = optical.source?.takeIf { optical.showCurrent }
        if (source != null) return FovCurrentReference(source.anchor,
            source.positionAngleDeg?.takeIf(Double::isFinite), false, frameCentered)
        val mount = mountAnchor?.takeIf { it.raHours.isFinite() && it.decDegrees.isFinite() &&
            it.decDegrees in -90.0..90.0 && it.frame.uppercase(Locale.US) in setOf("JNOW", "J2000", "ICRF") }
        return mount?.let { FovCurrentReference(it, null, true, false) }
    }

    fun targetSensorFramingScripts(
        positionAngleDeg: Double,
        mosaic: StarMapMosaicConfig,
        alsoZoom: Boolean
    ): List<String> {
        val normalized = mosaic.normalized()
        val angle = ((positionAngleDeg % 360.0) + 360.0) % 360.0
        return listOf(
            "window.MercStarMap && window.MercStarMap.setTargetFovRotation(${js(angle)});",
            "window.MercStarMap && window.MercStarMap.setTargetFovMosaic(" +
                "${normalized.rows},${normalized.columns},${normalized.overlapPercent}," +
                "${normalized.showPanelNumbers},\"${normalized.traversal.name}\"," +
                "\"${normalized.startCorner.name}\",$alsoZoom);"
        )
    }

    fun scripts(
        showOverlay: Boolean,
        computation: FovComputation?,
        currentLabel: String,
        targetLabel: String,
        alsoZoom: Boolean,
        currentAnchor: FovSkyAnchor? = null,
        targetAnchor: FovSkyAnchor? = null,
        showCurrent: Boolean = currentAnchor != null,
        currentPositionAngleDeg: Double? = null
    ): List<String> {
        val clearTarget = "window.MercStarMap && window.MercStarMap.clearTargetFovOverlay();"
        val clearCurrent = "window.MercStarMap && window.MercStarMap.clearCurrentFovOverlay();"
        if (!showOverlay || computation == null || !computation.hasOverlay) {
            return listOf(clearCurrent, clearTarget)
        }
        val setCurrent = if (showCurrent && currentAnchor != null)
            setter("Current", computation, currentLabel, alsoZoom, currentAnchor, currentPositionAngleDeg)
        else clearCurrent
        val setTarget = setter("Target", computation, targetLabel, alsoZoom = false, targetAnchor)
        return listOf(setCurrent, setTarget) + if (alsoZoom && !showCurrent)
            listOf("window.MercStarMap && window.MercStarMap.zoomToTargetFov();") else emptyList()
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
        anchor: FovSkyAnchor?,
        positionAngleDeg: Double? = null
    ): String {
        val labelJs = jsString(label)
        val extra = if (role == "Current") {
            val zoom = if (alsoZoom) "true" else "false"
            "$zoom,$labelJs${anchorArgs(anchor)}" + if (computation.mode == FovInstrumentMode.SENSOR && positionAngleDeg != null)
                ",${js(positionAngleDeg)}" else ""
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
