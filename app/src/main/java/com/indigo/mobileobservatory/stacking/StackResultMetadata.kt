package com.indigo.mobileobservatory.stacking

import org.json.JSONArray
import org.json.JSONObject

/** Versioned, explicit schema; every pixel-affecting parameter is stored, including masks. */
object StackResultMetadata {
    fun parameters(p: StackProcessingParameters): JSONObject = JSONObject().apply {
        put("schema", 1); put("preset", p.preset.name)
        put("blackPoint", p.blackPoint); put("whitePoint", p.whitePoint); put("midtone", p.midtone)
        put("curve", p.curve); put("brightness", p.brightness); put("localContrast", p.localContrast)
        put("highlightCompression", p.highlightCompression); put("sharpening", p.sharpening)
        put("autoWhiteBalance", p.autoWhiteBalance); put("redBalance", p.redBalance); put("blueBalance", p.blueBalance)
        put("saturation", p.saturation)
        put("stretchEnabled", p.stretchEnabled); put("whiteBalanceEnabled", p.whiteBalanceEnabled)
        put("saturationEnabled", p.saturationEnabled)
        put("displayVersion", p.displayVersion)
        put("background", JSONObject().apply {
            put("enabled", p.background.enabled); put("strength", p.background.strength); put("gridSize", p.background.gridSize)
            put("mode", p.background.mode.name); put("sampleRadius", p.background.sampleRadius)
            put("samplePoints", JSONArray().apply { p.background.samplePoints.forEach { point ->
                put(JSONArray(listOf(point.x, point.y)))
            } })
            put("targetMasks", JSONArray().apply { p.background.targetMasks.forEach { mask ->
                put(JSONArray(listOf(mask.left, mask.top, mask.right, mask.bottom)))
            } })
        })
    }

    fun parseParameters(json: JSONObject): StackProcessingParameters {
        require(json.getInt("schema") == 1) { "Unsupported processing recipe version" }
        val background = json.getJSONObject("background")
        val masks = background.getJSONArray("targetMasks")
        val points = background.optJSONArray("samplePoints") ?: JSONArray()
        return StackProcessingParameters(
            preset = ProcessingPreset.valueOf(json.getString("preset")),
            blackPoint = json.getDouble("blackPoint"), whitePoint = json.getDouble("whitePoint"),
            midtone = json.getDouble("midtone"), curve = json.getDouble("curve"), brightness = json.getDouble("brightness"),
            localContrast = json.getDouble("localContrast"), highlightCompression = json.getDouble("highlightCompression"),
            sharpening = json.getDouble("sharpening"), autoWhiteBalance = json.getBoolean("autoWhiteBalance"),
            redBalance = json.getDouble("redBalance"), blueBalance = json.getDouble("blueBalance"), saturation = json.getDouble("saturation"),
            stretchEnabled = json.optBoolean("stretchEnabled", true),
            whiteBalanceEnabled = json.optBoolean("whiteBalanceEnabled", true),
            saturationEnabled = json.optBoolean("saturationEnabled", true),
            displayVersion = json.optInt("displayVersion", 1).also {
                require(it in 1..2) { "Unsupported display processing version" }
            },
            background = BackgroundParameters(background.getBoolean("enabled"), background.getDouble("strength"),
                background.getInt("gridSize"), List(masks.length()) { index -> masks.getJSONArray(index).let {
                    TargetMask(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3))
                } }, mode = BackgroundMode.valueOf(background.optString("mode", BackgroundMode.AUTO.name)),
                sampleRadius = background.optDouble("sampleRadius", .025),
                samplePoints = List(points.length()) { index -> points.getJSONArray(index).let {
                    BackgroundSamplePoint(it.getDouble(0), it.getDouble(1))
                } })
        )
    }

    fun processing(result: StackProcessingResult): JSONObject = JSONObject().apply {
        put("parameters", parameters(result.parameters))
        put("whiteBalanceGains", JSONArray(result.whiteBalanceGains))
        put("elapsedMs", result.elapsedMs)
        result.background?.let { model ->
            put("backgroundModel", JSONObject().apply {
                put("applied", model.applied); put("reason", model.reason); put("method", model.method)
                put("gridSize", model.gridSize); put("acceptedSamples", model.acceptedSamples); put("strength", model.strength)
                put("mode", model.mode.name); put("sampleRadius", model.sampleRadius)
                put("samplePoints", JSONArray().apply { model.samplePoints.forEach { point ->
                    put(JSONArray(listOf(point.x, point.y)))
                } })
                put("targetMasks", JSONArray().apply { model.targetMasks.forEach { mask ->
                    put(JSONArray(listOf(mask.left, mask.top, mask.right, mask.bottom)))
                } })
                put("coefficients", JSONArray().apply { model.coefficients.forEach { put(JSONArray(it)) } })
                put("channelAnchors", JSONArray(model.channelAnchors))
                fun stats(s: BackgroundStatistics) = JSONObject().apply {
                    put("median", s.median); put("gridSpread", s.gridSpread); put("noise", s.noise)
                }
                put("before", stats(model.before)); put("after", stats(model.after))
            })
        }
    }
}
