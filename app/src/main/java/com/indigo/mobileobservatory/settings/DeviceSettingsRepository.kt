package com.indigo.mobileobservatory.settings

import android.content.Context
import com.indigo.mobileobservatory.accessories.power.PowerInterfaceNames
import com.indigo.mobileobservatory.sequence.SequenceSettings
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.ReadoutMode

data class CameraDefaults(
    val readoutMode: ReadoutMode? = null,
    val nativeReadoutModeId: String? = null,
    val gain: Float? = null,
    val offset: Float? = null,
    val pixelFormat: PixelFormat? = null
)

data class FocuserDefaults(
    val fineStep: Int = 10,
    val coarseStep: Int = 50,
    val maxStep: Int? = null,
    val direction: Int = 0,
    val backlashSteps: Int = 0,
    val backlashDirection: Int = 0
)

data class CoverDefaults(
    val openAngle: Int = 180,
    val closedAngle: Int = 0,
    val workingAngle: Int = 180,
    val brightness: Int? = null
)

class DeviceSettingsRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun cameraDefaults(deviceId: String): CameraDefaults {
        val prefix = prefix(CAMERA, deviceId)
        if (preferences.contains("${prefix}${CameraGainSettings.LEGACY_SUFFIX}")) {
            preferences.edit().remove("${prefix}${CameraGainSettings.LEGACY_SUFFIX}").apply()
        }
        return CameraDefaults(
            readoutMode = preferences.getString("${prefix}readout", null)
                ?.let { value -> ReadoutMode.entries.firstOrNull { it.name == value } },
            nativeReadoutModeId = preferences.getString("${prefix}native_readout", null),
            gain = CameraGainSettings.read(
                contains = preferences::contains,
                getFloat = { key -> preferences.getFloat(key, 0f) },
                prefix = prefix
            ),
            offset = preferences.takeIf { it.contains("${prefix}offset") }
                ?.getFloat("${prefix}offset", 0f),
            pixelFormat = preferences.getString("${prefix}pixel_format", null)
                ?.let { value -> PixelFormat.entries.firstOrNull { it.name == value } }
        )
    }

    fun saveCameraDefaults(deviceId: String, settings: CameraDefaults) {
        val prefix = prefix(CAMERA, deviceId)
        preferences.edit()
            .putString("${prefix}readout", settings.readoutMode?.name)
            .putString("${prefix}native_readout", settings.nativeReadoutModeId)
            .putString("${prefix}pixel_format", settings.pixelFormat?.name)
            .applyNullableFloat("${prefix}${CameraGainSettings.VALUE_SUFFIX}", settings.gain)
            .remove("${prefix}${CameraGainSettings.LEGACY_SUFFIX}")
            .applyNullableFloat("${prefix}offset", settings.offset)
            .apply()
    }

    fun cameraUsbBandwidth(deviceId: String, pixelFormat: PixelFormat): Int? {
        val key = "${prefix(CAMERA, deviceId)}usb_bandwidth_${pixelFormat.name}"
        return preferences.takeIf { it.contains(key) }?.getInt(key, 0)
    }

    fun saveCameraUsbBandwidth(deviceId: String, pixelFormat: PixelFormat, value: Int) {
        preferences.edit()
            .putInt("${prefix(CAMERA, deviceId)}usb_bandwidth_${pixelFormat.name}", value)
            .apply()
    }

    fun focuserDefaults(deviceId: String): FocuserDefaults {
        val prefix = prefix(FOCUSER, deviceId)
        return FocuserDefaults(
            fineStep = preferences.getInt("${prefix}fine_step", 10).coerceAtLeast(1),
            coarseStep = preferences.getInt("${prefix}coarse_step", 50).coerceAtLeast(1),
            maxStep = preferences.takeIf { it.contains("${prefix}max_step") }
                ?.getInt("${prefix}max_step", 0)?.takeIf { it > 0 },
            direction = preferences.getInt("${prefix}direction", 0).coerceIn(0, 1),
            backlashSteps = preferences.getInt("${prefix}backlash_steps", 0).coerceAtLeast(0),
            backlashDirection = preferences.getInt("${prefix}backlash_direction", 0).coerceIn(0, 1)
        )
    }

    fun hasFocuserDefaults(deviceId: String): Boolean =
        preferences.contains("${prefix(FOCUSER, deviceId)}fine_step")

    fun saveFocuserDefaults(deviceId: String, settings: FocuserDefaults) {
        val prefix = prefix(FOCUSER, deviceId)
        preferences.edit()
            .putInt("${prefix}fine_step", settings.fineStep.coerceAtLeast(1))
            .putInt("${prefix}coarse_step", settings.coarseStep.coerceAtLeast(1))
            .applyNullableInt("${prefix}max_step", settings.maxStep)
            .putInt("${prefix}direction", settings.direction.coerceIn(0, 1))
            .putInt("${prefix}backlash_steps", settings.backlashSteps.coerceAtLeast(0))
            .putInt("${prefix}backlash_direction", settings.backlashDirection.coerceIn(0, 1))
            .apply()
    }

    fun coverDefaults(deviceId: String): CoverDefaults {
        val prefix = prefix(COVER, deviceId)
        return CoverDefaults(
            openAngle = preferences.getInt("${prefix}open_angle", 180).coerceIn(0, 360),
            closedAngle = preferences.getInt("${prefix}closed_angle", 0).coerceIn(0, 360),
            workingAngle = preferences.getInt("${prefix}working_angle", 180).coerceIn(0, 360),
            brightness = preferences.takeIf { it.contains("${prefix}brightness") }
                ?.getInt("${prefix}brightness", 0)
        )
    }

    fun saveCoverDefaults(deviceId: String, settings: CoverDefaults) {
        val prefix = prefix(COVER, deviceId)
        preferences.edit()
            .putInt("${prefix}open_angle", settings.openAngle.coerceIn(0, 360))
            .putInt("${prefix}closed_angle", settings.closedAngle.coerceIn(0, 360))
            .putInt("${prefix}working_angle", settings.workingAngle.coerceIn(0, 360))
            .applyNullableInt("${prefix}brightness", settings.brightness)
            .apply()
    }

    fun powerInterfaceNames(deviceId: String): Map<String, String> {
        val keyPrefix = prefix(POWER_BOX, deviceId) + "interface_name."
        return PowerInterfaceNames.normalize(
            preferences.all.mapNotNull { (key, value) ->
                if (key.startsWith(keyPrefix) && value is String) {
                    key.removePrefix(keyPrefix) to value
                } else {
                    null
                }
            }.toMap()
        )
    }

    fun savePowerInterfaceNames(deviceId: String, names: Map<String, String>) {
        val keyPrefix = prefix(POWER_BOX, deviceId) + "interface_name."
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(keyPrefix) }
            .forEach(editor::remove)
        PowerInterfaceNames.normalize(names).forEach { (key, value) ->
            editor.putString(keyPrefix + key, value)
        }
        editor.apply()
    }

    fun sequenceSettings(): SequenceSettings {
        val prefix = "sequence."
        return SequenceSettings(
            minutesAfterMeridian = preferences.getFloat("${prefix}minutes_after_meridian", 0f).toDouble(),
            maxMinutesAfterMeridian = preferences.getFloat("${prefix}max_minutes_after_meridian", 0f).toDouble(),
            pauseTimeBeforeMeridian = preferences.getFloat("${prefix}pause_before_meridian", 0f).toDouble(),
            recenterAfterFlip = preferences.getBoolean("${prefix}recenter_after_flip", true),
            autofocusAfterFlip = preferences.getBoolean("${prefix}autofocus_after_flip", false),
            settleTimeSeconds = preferences.getInt("${prefix}settle_seconds", 0).coerceAtLeast(0),
            ditherPixels = preferences.getFloat("${prefix}dither_pixels", 3f).toDouble().coerceAtLeast(0.0)
        )
    }

    fun saveSequenceSettings(settings: SequenceSettings) {
        val prefix = "sequence."
        preferences.edit()
            .putFloat("${prefix}minutes_after_meridian", settings.minutesAfterMeridian.toFloat())
            .putFloat("${prefix}max_minutes_after_meridian", settings.maxMinutesAfterMeridian.toFloat())
            .putFloat("${prefix}pause_before_meridian", settings.pauseTimeBeforeMeridian.toFloat())
            .putBoolean("${prefix}recenter_after_flip", settings.recenterAfterFlip)
            .putBoolean("${prefix}autofocus_after_flip", settings.autofocusAfterFlip)
            .putInt("${prefix}settle_seconds", settings.settleTimeSeconds.coerceAtLeast(0))
            .putFloat("${prefix}dither_pixels", settings.ditherPixels.toFloat())
            .apply()
    }

    private fun prefix(type: String, deviceId: String): String =
        "$type.${deviceId.replace(Regex("[^A-Za-z0-9_.-]"), "_")}."

    private fun android.content.SharedPreferences.Editor.applyNullableFloat(key: String, value: Float?) =
        if (value == null) remove(key) else putFloat(key, value)

    private fun android.content.SharedPreferences.Editor.applyNullableInt(key: String, value: Int?) =
        if (value == null) remove(key) else putInt(key, value)

    private companion object {
        const val PREFS_NAME = "device_settings"
        const val CAMERA = "camera"
        const val FOCUSER = "focuser"
        const val COVER = "cover"
        const val POWER_BOX = "power_box"
    }
}

object CameraGainSettings {
    const val VALUE_SUFFIX = "gain_value"
    const val LEGACY_SUFFIX = "gain"

    fun read(
        contains: (String) -> Boolean,
        getFloat: (String) -> Float,
        prefix: String
    ): Float? {
        val nativeKey = prefix + VALUE_SUFFIX
        return if (contains(nativeKey)) getFloat(nativeKey) else null
    }
}
