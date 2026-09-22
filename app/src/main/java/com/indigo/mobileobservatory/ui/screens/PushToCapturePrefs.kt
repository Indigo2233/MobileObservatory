package com.indigo.mobileobservatory.ui.screens

import android.content.Context

internal class PushToCapturePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): PushToCaptureSettings? {
        val cameraId = prefs.getString(KEY_CAMERA, null) ?: return null
        return PushToCaptureSettings(
            cameraId = cameraId,
            exposureSeconds = prefs.getFloat(KEY_EXPOSURE, 1f).toDouble(),
            iso = prefs.getInt(KEY_ISO, 1600),
            preferRaw = prefs.getBoolean(KEY_RAW, true),
            autoIso = prefs.getBoolean(KEY_AUTO_ISO, true),
            burstFrameCount = prefs.getInt(KEY_BURST, 1)
        )
    }

    fun save(settings: PushToCaptureSettings) {
        prefs.edit()
            .putString(KEY_CAMERA, settings.cameraId)
            .putFloat(KEY_EXPOSURE, settings.exposureSeconds.toFloat())
            .putInt(KEY_ISO, settings.iso)
            .putBoolean(KEY_RAW, settings.preferRaw)
            .putBoolean(KEY_AUTO_ISO, settings.autoIso)
            .putInt(KEY_BURST, settings.burstFrameCount)
            .apply()
    }

    companion object {
        private const val PREFS = "mobile_observatory"
        private const val KEY_CAMERA = "push_to_camera_id"
        private const val KEY_EXPOSURE = "push_to_exposure_s"
        private const val KEY_ISO = "push_to_iso"
        private const val KEY_RAW = "push_to_prefer_raw"
        private const val KEY_AUTO_ISO = "push_to_auto_iso"
        private const val KEY_BURST = "push_to_burst"
    }
}
