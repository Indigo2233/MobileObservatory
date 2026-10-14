package com.indigo.mobileobservatory.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.sequence.SequenceFeature
import com.indigo.mobileobservatory.ui.components.ObservatoryPill
import com.indigo.mobileobservatory.ui.components.ObservatoryPillRow
import com.indigo.mobileobservatory.ui.components.rememberSettingsExitState
import com.indigo.mobileobservatory.ui.settings.SettingsDraftViewModel
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.ui.viewmodel.UpdatePhase
import com.indigo.mobileobservatory.ui.viewmodel.UpdateViewModel
import com.indigo.mobileobservatory.util.FileLogger

// Keep old serialized enum values readable after an app upgrade; they are never entries.
private enum class SettingsSection { GENERAL, SEQUENCE, DIAGNOSTICS, CAMERA, FILTER_WHEEL, FOCUSER, COVER, ROTATOR }

/** Application preferences only. Device defaults have one home in equipment details. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: CameraViewModel, onBack: () -> Unit) {
    var selectedSection by rememberSaveable { mutableStateOf(SettingsSection.GENERAL) }
    val updates: UpdateViewModel = viewModel()
    val exit = rememberSettingsExitState()
    com.indigo.mobileobservatory.ui.components.RegisterSettingsExitGuard(exit)
    val motion by viewModel.mountMotionState.collectAsState()
    val stop = stringResource(R.string.stop_mount)
    val sections = listOf(SettingsSection.GENERAL, SettingsSection.DIAGNOSTICS) +
        if (SequenceFeature.ENABLED) listOf(SettingsSection.SEQUENCE) else emptyList()
    LaunchedEffect(selectedSection, SequenceFeature.ENABLED) {
        if (selectedSection !in sections) selectedSection = SettingsSection.GENERAL
    }
    SideEffect {
        exit.register("back", onBack)
        exit.safetyAction = if (motion.showsGlobalStop) stop to viewModel::stopMountMotion else null
        sections.forEach { section -> exit.register("section-${section.name}") { selectedSection = section } }
        if (selectedSection != SettingsSection.SEQUENCE) {
            exit.dirty = false; exit.saving = false; exit.clearDraft = {}; exit.save = {}
        }
    }
    BackHandler { exit.request("back") }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = {
            IconButton(onClick = { exit.request("back") }) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ObservatoryPillRow(
                items = sections.map { ObservatoryPill(it.name, settingsSectionTitle(it)) },
                selectedKey = selectedSection.name,
                onSelect = { key -> if (key != selectedSection.name) exit.request("section-$key") }
            )
            when (selectedSection) {
                SettingsSection.GENERAL -> GeneralSettingsPage(updates)
                SettingsSection.DIAGNOSTICS -> DiagnosticsSettingsPage()
                SettingsSection.SEQUENCE -> if (SequenceFeature.ENABLED) SequenceSettingsEditor(viewModel, exit, onBack)
                else -> GeneralSettingsPage(updates)
            }
        }
    }
}

@Composable
private fun settingsSectionTitle(section: SettingsSection): String = stringResource(when (section) {
    SettingsSection.GENERAL -> R.string.settings_general
    SettingsSection.SEQUENCE -> R.string.tab_sequence
    SettingsSection.DIAGNOSTICS -> R.string.settings_diagnostics
    else -> R.string.settings_general
})

@Composable
private fun SettingsPage(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
}

@Composable
private fun SequenceSettingsEditor(vm: CameraViewModel, exit: com.indigo.mobileobservatory.ui.components.SettingsExitState, onBack: () -> Unit) {
    val settings by vm.sequenceSettings.collectAsState()
    val editor: SettingsDraftViewModel = viewModel(key = "sequence-settings-draft")
    val values = mapOf(
        "after" to settings.minutesAfterMeridian.toString(), "maximum" to settings.maxMinutesAfterMeridian.toString(),
        "pause" to settings.pauseTimeBeforeMeridian.toString(), "settle" to settings.settleTimeSeconds.toString(),
        "dither" to settings.ditherPixels.toString(), "center" to if (settings.recenterAfterFlip) "1" else "0",
        "autofocus" to if (settings.autofocusAfterFlip) "1" else "0")
    fun decimal(key: String, label: String, minimum: Double = 0.0) = SettingsField(key, label,
        valid = { it.toDoubleOrNull()?.let { n -> n.isFinite() && n >= minimum } == true })
    val presentation = DeviceSettingsPresentation("sequence", stringResource(R.string.sequence_meridian_flip), true, values,
        listOf(decimal("after", stringResource(R.string.sequence_minutes_after_meridian), -180.0),
            decimal("maximum", stringResource(R.string.sequence_max_minutes_after_meridian)),
            decimal("pause", stringResource(R.string.sequence_pause_before_meridian)),
            SettingsField("settle", stringResource(R.string.sequence_settle_seconds), valid = { it.toIntOrNull()?.let { n -> n >= 0 } == true }),
            decimal("dither", stringResource(R.string.sequence_dither_pixels)),
            SettingsField("center", stringResource(R.string.sequence_recenter_after_flip), toggle = true),
            SettingsField("autofocus", stringResource(R.string.sequence_autofocus_after_flip), toggle = true)))
    SettingsDraftEditor(presentation, editor, exit, onBack, showToolbar = false) { draft, success, failure ->
        try {
            val f = draft.fields
            vm.updateSequenceSettings(settings.copy(minutesAfterMeridian = f.getValue("after").toDouble(),
                maxMinutesAfterMeridian = f.getValue("maximum").toDouble(), pauseTimeBeforeMeridian = f.getValue("pause").toDouble(),
                settleTimeSeconds = f.getValue("settle").toInt(), ditherPixels = f.getValue("dither").toDouble(),
                recenterAfterFlip = f["center"] == "1", autofocusAfterFlip = f["autofocus"] == "1"))
            success(f)
        } catch (error: Throwable) { failure(error.message.orEmpty()) }
    }
}

@Composable
private fun GeneralSettingsPage(updateViewModel: UpdateViewModel) = SettingsPage {
    Text(stringResource(R.string.save_location), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.save_location_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Divider()
    UpdateSettingsSection(updateViewModel)
}

@Composable
private fun UpdateSettingsSection(updateViewModel: UpdateViewModel) {
    val updateState by updateViewModel.state.collectAsState()
    Text(stringResource(R.string.settings_update), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(
            R.string.update_current_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Button(
            onClick = { updateViewModel.checkForUpdates(manual = true) },
            enabled = !updateState.isBusy
        ) {
            Text(stringResource(R.string.update_check_button))
        }
    }
    SettingSwitch(
        label = stringResource(R.string.update_auto_check),
        checked = updateState.autoCheckEnabled,
        onCheckedChange = updateViewModel::setAutoCheckEnabled
    )
    updateState.statusText?.let { status ->
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (updateState.phase == UpdatePhase.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun DiagnosticsSettingsPage() = SettingsPage {
    val context = LocalContext.current
    Text(stringResource(R.string.debug), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val logFile = FileLogger.getLogFile()
            if (logFile != null && logFile.exists()) {
                runCatching {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        logFile
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_log)))
                }.onFailure { error -> FileLogger.e("Settings", "Failed to share log: ${error.message}") }
            }
        }) { Text(stringResource(R.string.export_log)) }
        OutlinedButton(onClick = FileLogger::clearLog) { Text(stringResource(R.string.clear_log)) }
    }
    FileLogger.getLogFile()?.takeIf { it.exists() }?.let { logFile ->
        Text(
            stringResource(R.string.log_file_summary, logFile.name, logFile.length() / 1024),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Divider()
    Text(stringResource(R.string.about), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(
            R.string.about_desc,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.lp750_credit),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Switch(checked, onCheckedChange)
    }
}
