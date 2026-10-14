package com.indigo.mobileobservatory.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.pointing.PhoneSiteProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun rememberSavedObserverSite(): PhoneSiteProvider.SavedSite? {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mobile_observatory", android.content.Context.MODE_PRIVATE) }
    var saved by remember { mutableStateOf(PhoneSiteProvider.savedSite(context)) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("observer_site_") == true || key in listOf("polar_latitude_deg", "polar_longitude_deg"))
                saved = PhoneSiteProvider.savedSite(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return saved
}

@Composable
internal fun ObserverSiteDialog(initial: ObserverSite?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var manual by rememberSaveable { mutableStateOf(false) }
    var latitude by rememberSaveable { mutableStateOf(initial?.latitudeDeg?.toString().orEmpty()) }
    var longitude by rememberSaveable { mutableStateOf(initial?.longitudeDeg?.toString().orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val lat = latitude.toDoubleOrNull(); val lon = longitude.toDoubleOrNull()
    val valid = lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    fun locate() {
        busy = true; error = null
        scope.launch {
            try { PhoneSiteProvider.currentSite(context, forcePhoneLocation = true); onDismiss() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Throwable) { error = failure.message }
            finally { busy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (PhoneSiteProvider.hasPermission(context)) locate() else error = context.getString(R.string.location_permission_required)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.wf_observer_site)) }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                if (PhoneSiteProvider.hasPermission(context)) locate() else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text(stringResource(if (busy) R.string.wf_observer_locating else R.string.wf_observer_phone)) }
            TextButton(onClick = { manual = !manual }, enabled = !busy) { Text(stringResource(R.string.wf_observer_manual)) }
            if (manual) {
                OutlinedTextField(latitude, { latitude = it }, Modifier.fillMaxWidth().testTag("observer-latitude"),
                    label = { Text(stringResource(R.string.wf_observer_latitude)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                OutlinedTextField(longitude, { longitude = it }, Modifier.fillMaxWidth().testTag("observer-longitude"),
                    label = { Text(stringResource(R.string.wf_observer_longitude)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { if (manual) TextButton(enabled = valid && !busy, onClick = {
        PhoneSiteProvider.saveSite(context, ObserverSite(requireNotNull(lat), requireNotNull(lon)))
        onDismiss()
    }) { Text(stringResource(R.string.wf_observer_use)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
