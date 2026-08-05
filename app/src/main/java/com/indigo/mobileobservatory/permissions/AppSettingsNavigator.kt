package com.indigo.mobileobservatory.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object AppSettingsNavigator {
    fun openApplicationDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
