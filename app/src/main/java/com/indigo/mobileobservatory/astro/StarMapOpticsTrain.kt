package com.indigo.mobileobservatory.astro

import java.util.Locale

enum class OpticsTrainId {
    PRIMARY,
    SECONDARY;

    fun prefValue(): String = if (this == PRIMARY) "primary" else "secondary"

    companion object {
        fun fromPref(value: String?): OpticsTrainId =
            if (value == "primary") PRIMARY else SECONDARY
    }
}

data class OpticsTrainConfig(
    val id: OpticsTrainId,
    val mode: FovInstrumentMode,
    val telescopeId: String = "scope_80_500",
    val eyepieceId: String = "ep_25_50",
    val sensorId: String = OpticsEquipment.CONNECTED_SENSOR_ID,
    val customTelescopeFl: String = "500",
    val customEyepieceFl: String = "25",
    val customEyepieceAfov: String = "50",
    val customSensorPixelUm: String = "3.75",
    val customSensorWidth: String = "1920",
    val customSensorHeight: String = "1080"
) {
    fun compute(
        telescopes: List<TelescopeSpec> = OpticsEquipment.defaultTelescopes,
        eyepieces: List<EyepieceSpec> = OpticsEquipment.defaultEyepieces,
        sensors: List<SensorSpec>
    ): FovComputation? {
        val fl = resolveTelescopeFl(telescopes, telescopeId, customTelescopeFl) ?: return null
        return when (mode) {
            FovInstrumentMode.EYEPIECE -> {
                val eyepiece = resolveEyepiece(
                    eyepieces,
                    eyepieceId,
                    customEyepieceFl,
                    customEyepieceAfov
                ) ?: return null
                OpticsEquipment.computeEyepiece(fl, eyepiece)
            }
            FovInstrumentMode.SENSOR -> {
                val sensor = resolveSensor(
                    sensors,
                    sensorId,
                    customSensorPixelUm,
                    customSensorWidth,
                    customSensorHeight
                ) ?: return null
                OpticsEquipment.computeSensor(fl, sensor)
            }
        }
    }
}

data class OpticsPrefWrite(
    val strings: Map<String, String>,
    val bools: Map<String, Boolean>
)

interface OpticsPrefReader {
    fun getString(key: String, default: String?): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getFloat(key: String, default: Float): Float
    fun contains(key: String): Boolean
}

class MapOpticsPrefs(
    private val strings: Map<String, String> = emptyMap(),
    private val bools: Map<String, Boolean> = emptyMap(),
    private val floats: Map<String, Float> = emptyMap()
) : OpticsPrefReader {
    override fun getString(key: String, default: String?): String? = strings[key] ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean = bools[key] ?: default
    override fun getFloat(key: String, default: Float): Float = floats[key] ?: default
    override fun contains(key: String): Boolean =
        key in strings || key in bools || key in floats
}

private data class TrainKeys(
    val mode: String,
    val telescopeId: String,
    val eyepieceId: String,
    val sensorId: String,
    val customScopeFl: String,
    val customEpFl: String,
    val customEpAfov: String,
    val customSensorUm: String,
    val customSensorW: String,
    val customSensorH: String
)

object StarMapOpticsPrefs {
    const val ACTIVE_TRAIN = "star_map_active_train"
    const val SHOW_OVERLAY = "star_map_show_fov_overlay"
    const val PLATE_FOCAL_LENGTH_MM = "plate_focal_length_mm"

    private val PRIMARY_KEYS = TrainKeys(
        mode = "star_map_fov_mode",
        telescopeId = "star_map_telescope_id",
        eyepieceId = "star_map_eyepiece_id",
        sensorId = "star_map_sensor_id",
        customScopeFl = "star_map_custom_scope_fl",
        customEpFl = "star_map_custom_ep_fl",
        customEpAfov = "star_map_custom_ep_afov",
        customSensorUm = "star_map_custom_sensor_um",
        customSensorW = "star_map_custom_sensor_w",
        customSensorH = "star_map_custom_sensor_h"
    )

    private val SECONDARY_KEYS = TrainKeys(
        mode = "star_map_secondary_fov_mode",
        telescopeId = "star_map_secondary_telescope_id",
        eyepieceId = "star_map_secondary_eyepiece_id",
        sensorId = "star_map_secondary_sensor_id",
        customScopeFl = "star_map_secondary_custom_scope_fl",
        customEpFl = "star_map_secondary_custom_ep_fl",
        customEpAfov = "star_map_secondary_custom_ep_afov",
        customSensorUm = "star_map_secondary_custom_sensor_um",
        customSensorW = "star_map_secondary_custom_sensor_w",
        customSensorH = "star_map_secondary_custom_sensor_h"
    )

    private fun keysFor(id: OpticsTrainId): TrainKeys =
        if (id == OpticsTrainId.PRIMARY) PRIMARY_KEYS else SECONDARY_KEYS

    fun loadActive(prefs: OpticsPrefReader): OpticsTrainId =
        OpticsTrainId.fromPref(prefs.getString(ACTIVE_TRAIN, null))

    fun loadPrimary(prefs: OpticsPrefReader): OpticsTrainConfig =
        loadTrain(OpticsTrainId.PRIMARY, prefs)

    fun loadSecondary(
        prefs: OpticsPrefReader,
        hasConnectedCamera: Boolean,
        plateFocalLengthMm: Float? = prefs.getFloat(PLATE_FOCAL_LENGTH_MM, 0f).takeIf { it > 0f }
    ): OpticsTrainConfig {
        if (!prefs.contains(SECONDARY_KEYS.mode)) {
            return defaultSecondary(hasConnectedCamera, plateFocalLengthMm)
        }
        return loadTrain(OpticsTrainId.SECONDARY, prefs)
    }

    fun defaultSecondary(
        hasConnectedCamera: Boolean,
        plateFocalLengthMm: Float? = null
    ): OpticsTrainConfig {
        if (hasConnectedCamera) {
            val customFl = plateFocalLengthMm?.takeIf { it > 0f }?.let {
                "%.1f".format(Locale.US, it)
            }
            return OpticsTrainConfig(
                id = OpticsTrainId.SECONDARY,
                mode = FovInstrumentMode.SENSOR,
                telescopeId = if (customFl != null) "scope_custom" else "scope_80_500",
                sensorId = OpticsEquipment.CONNECTED_SENSOR_ID,
                customTelescopeFl = customFl ?: "500"
            )
        }
        return OpticsTrainConfig(
            id = OpticsTrainId.SECONDARY,
            mode = FovInstrumentMode.EYEPIECE,
            telescopeId = "scope_80_500",
            eyepieceId = "ep_40_68"
        )
    }

    fun shouldWritePlateFocalLength(train: OpticsTrainId, mode: FovInstrumentMode): Boolean =
        train == OpticsTrainId.SECONDARY && mode == FovInstrumentMode.SENSOR

    fun snapshot(
        primary: OpticsTrainConfig,
        secondary: OpticsTrainConfig,
        active: OpticsTrainId,
        showOverlay: Boolean
    ): OpticsPrefWrite {
        val strings = mutableMapOf<String, String>()
        strings[ACTIVE_TRAIN] = active.prefValue()
        putTrain(strings, primary.copy(id = OpticsTrainId.PRIMARY))
        putTrain(strings, secondary.copy(id = OpticsTrainId.SECONDARY))
        return OpticsPrefWrite(
            strings = strings,
            bools = mapOf(SHOW_OVERLAY to showOverlay)
        )
    }

    private fun loadTrain(id: OpticsTrainId, prefs: OpticsPrefReader): OpticsTrainConfig {
        val keys = keysFor(id)
        val mode = if (prefs.getString(keys.mode, "SENSOR") == "EYEPIECE") {
            FovInstrumentMode.EYEPIECE
        } else {
            FovInstrumentMode.SENSOR
        }
        val customFl = prefs.getString(keys.customScopeFl, null)
            ?: prefs.getFloat(PLATE_FOCAL_LENGTH_MM, 0f).takeIf { it > 0f }?.let {
                "%.1f".format(Locale.US, it)
            }
            ?: "500"
        return OpticsTrainConfig(
            id = id,
            mode = mode,
            telescopeId = prefs.getString(keys.telescopeId, "scope_80_500") ?: "scope_80_500",
            eyepieceId = prefs.getString(keys.eyepieceId, "ep_25_50") ?: "ep_25_50",
            sensorId = prefs.getString(keys.sensorId, OpticsEquipment.CONNECTED_SENSOR_ID)
                ?: OpticsEquipment.CONNECTED_SENSOR_ID,
            customTelescopeFl = customFl,
            customEyepieceFl = prefs.getString(keys.customEpFl, "25") ?: "25",
            customEyepieceAfov = prefs.getString(keys.customEpAfov, "50") ?: "50",
            customSensorPixelUm = prefs.getString(keys.customSensorUm, "3.75") ?: "3.75",
            customSensorWidth = prefs.getString(keys.customSensorW, "1920") ?: "1920",
            customSensorHeight = prefs.getString(keys.customSensorH, "1080") ?: "1080"
        )
    }

    private fun putTrain(target: MutableMap<String, String>, train: OpticsTrainConfig) {
        val keys = keysFor(train.id)
        target[keys.mode] = if (train.mode == FovInstrumentMode.EYEPIECE) "EYEPIECE" else "SENSOR"
        target[keys.telescopeId] = train.telescopeId
        target[keys.eyepieceId] = train.eyepieceId
        target[keys.sensorId] = train.sensorId
        target[keys.customScopeFl] = train.customTelescopeFl
        target[keys.customEpFl] = train.customEyepieceFl
        target[keys.customEpAfov] = train.customEyepieceAfov
        target[keys.customSensorUm] = train.customSensorPixelUm
        target[keys.customSensorW] = train.customSensorWidth
        target[keys.customSensorH] = train.customSensorHeight
    }
}

fun resolveTelescopeFl(
    telescopes: List<TelescopeSpec>,
    selectedId: String,
    customFl: String
): Double? {
    val selected = telescopes.firstOrNull { it.id == selectedId } ?: return null
    return if (selected.id == "scope_custom") {
        customFl.toDoubleOrNull()
    } else {
        selected.focalLengthMm
    }
}

fun resolveEyepiece(
    eyepieces: List<EyepieceSpec>,
    selectedId: String,
    customFl: String,
    customAfov: String
): EyepieceSpec? {
    val selected = eyepieces.firstOrNull { it.id == selectedId } ?: return null
    if (selected.id != "ep_custom") return selected
    val fl = customFl.toDoubleOrNull() ?: return null
    val afov = customAfov.toDoubleOrNull() ?: return null
    return selected.copy(focalLengthMm = fl, apparentFovDeg = afov)
}

fun resolveSensor(
    sensors: List<SensorSpec>,
    selectedId: String,
    customPixelUm: String,
    customWidth: String,
    customHeight: String
): SensorSpec? {
    val selected = sensors.firstOrNull { it.id == selectedId } ?: return null
    if (selected.id != OpticsEquipment.CUSTOM_SENSOR_ID) return selected
    val px = customPixelUm.toDoubleOrNull() ?: return null
    val w = customWidth.toIntOrNull() ?: return null
    val h = customHeight.toIntOrNull() ?: return null
    return selected.copy(pixelSizeUm = px, widthPx = w, heightPx = h)
}
