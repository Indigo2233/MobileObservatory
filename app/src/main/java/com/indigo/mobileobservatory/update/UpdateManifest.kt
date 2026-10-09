package com.indigo.mobileobservatory.update

import org.json.JSONObject

/**
 * Version description published with each GitHub release as `update.json`
 * (or served from GitHub Pages at the same path).
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String?,
    val mandatory: Boolean,
    val minSupportedVersionCode: Int
) {
    fun isNewerThan(currentVersionCode: Int): Boolean = versionCode > currentVersionCode

    /**
     * True when the running build is below [minSupportedVersionCode] or the
     * release is flagged mandatory. Such an update cannot be postponed.
     */
    fun isRequiredFor(currentVersionCode: Int): Boolean =
        mandatory || (minSupportedVersionCode > 0 && currentVersionCode < minSupportedVersionCode)
}

class UpdateManifestFormatException(message: String) : IllegalArgumentException(message)

object UpdateManifestParser {
    fun parse(raw: String): UpdateManifest {
        val normalized = raw.trimStart('\uFEFF').trim()
        val json = try {
            JSONObject(normalized)
        } catch (error: Exception) {
            throw UpdateManifestFormatException("Update manifest is not valid JSON.")
        }

        val versionCode = json.optInt("versionCode", -1)
        if (versionCode <= 0) {
            throw UpdateManifestFormatException("Update manifest has no valid versionCode.")
        }

        val versionName = json.optString("versionName").trim()
        if (versionName.isEmpty()) {
            throw UpdateManifestFormatException("Update manifest has no versionName.")
        }

        val apkUrl = json.optString("apkUrl").trim()
        if (!apkUrl.startsWith("https://")) {
            throw UpdateManifestFormatException("Update manifest apkUrl must use HTTPS.")
        }

        val sha256 = json.optString("sha256").trim().lowercase()
        if (sha256.length != 64 || !sha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            throw UpdateManifestFormatException("Update manifest sha256 is missing or malformed.")
        }

        val minSupportedVersionCode = json.optInt("minSupportedVersionCode", 0)
        if (minSupportedVersionCode < 0) {
            throw UpdateManifestFormatException("Update manifest minSupportedVersionCode is invalid.")
        }

        return UpdateManifest(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            sha256 = sha256,
            notes = json.optString("notes").trim().takeIf { it.isNotEmpty() },
            mandatory = json.optBoolean("mandatory", false),
            minSupportedVersionCode = minSupportedVersionCode
        )
    }
}
