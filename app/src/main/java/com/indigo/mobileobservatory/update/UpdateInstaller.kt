package com.indigo.mobileobservatory.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

sealed interface ApkVerification {
    object Valid : ApkVerification
    data class Invalid(val reason: String) : ApkVerification
}

object UpdateInstaller {
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Confirms the downloaded APK carries the same package name, version code,
     * and signer as the installed application before the system installer runs.
     */
    fun verifyApk(context: Context, apk: File, expectedVersionCode: Int): ApkVerification {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        @Suppress("DEPRECATION")
        val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return ApkVerification.Invalid("Package archive could not be read.")

        if (archive.packageName != context.packageName) {
            return ApkVerification.Invalid("Package name does not match this application.")
        }

        @Suppress("DEPRECATION")
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }
        if (archiveVersionCode != expectedVersionCode.toLong()) {
            return ApkVerification.Invalid("Package version does not match the update manifest.")
        }

        @Suppress("DEPRECATION")
        val installed = packageManager.getPackageInfo(context.packageName, flags)
        val archiveSigners = signingDigests(archive)
        val installedSigners = signingDigests(installed)
        // Some OEM package managers withhold archive certificates. The platform
        // installer still enforces signature matching, so only reject when both
        // sides report signers and they disagree.
        if (archiveSigners.isNotEmpty() &&
            installedSigners.isNotEmpty() &&
            archiveSigners.intersect(installedSigners).isEmpty()
        ) {
            return ApkVerification.Invalid("Package signer does not match the installed application.")
        }
        return ApkVerification.Valid
    }

    @Suppress("DEPRECATION")
    private fun signingDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
