package com.indigo.mobileobservatory.astro

import android.content.SharedPreferences

/** Shared reader for the map, reference capture and observing task. */
class AndroidOpticsPrefs(private val prefs: SharedPreferences) : OpticsPrefReader {
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun getFloat(key: String, default: Float): Float = prefs.getFloat(key, default)
    override fun contains(key: String): Boolean = prefs.contains(key)
}
