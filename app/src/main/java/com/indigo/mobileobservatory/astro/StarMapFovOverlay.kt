package com.indigo.mobileobservatory.astro

import java.util.Locale

/**
 * JS bridge calls that keep telescope (eyepiece, solid) and preview (sensor,
 * dashed) FOV overlays on the star map at the same time.
 */
object StarMapFovOverlay {
    fun scripts(
        showOverlay: Boolean,
        eyepieceFovDeg: Double?,
        sensorWidthDeg: Double?,
        sensorHeightDeg: Double?,
        alsoZoom: Boolean,
        zoomMode: FovInstrumentMode
    ): List<String> {
        if (!showOverlay) {
            return listOf(
                "window.MercStarMap && window.MercStarMap.clearEyepieceFovOverlay();",
                "window.MercStarMap && window.MercStarMap.clearSensorFovOverlay();"
            )
        }
        val scripts = mutableListOf<String>()
        val eyepiece = eyepieceFovDeg?.takeIf { it > 0.0 }
        if (eyepiece != null) {
            val zoom = alsoZoom && zoomMode == FovInstrumentMode.EYEPIECE
            scripts +=
                "window.MercStarMap && window.MercStarMap.setEyepieceFovOverlay(${js(eyepiece)},$zoom);"
        } else {
            scripts += "window.MercStarMap && window.MercStarMap.clearEyepieceFovOverlay();"
        }
        val width = sensorWidthDeg?.takeIf { it > 0.0 }
        val height = sensorHeightDeg?.takeIf { it > 0.0 }
        if (width != null && height != null) {
            val zoom = alsoZoom && zoomMode == FovInstrumentMode.SENSOR
            scripts +=
                "window.MercStarMap && window.MercStarMap.setSensorFovOverlay(${js(width)},${js(height)},$zoom);"
        } else {
            scripts += "window.MercStarMap && window.MercStarMap.clearSensorFovOverlay();"
        }
        return scripts
    }

    private fun js(value: Double): String = "%.8f".format(Locale.US, value)
}
