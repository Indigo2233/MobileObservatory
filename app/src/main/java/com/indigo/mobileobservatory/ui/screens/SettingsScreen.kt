package com.indigo.mobileobservatory.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.AppOrientationMode
import com.indigo.mobileobservatory.ui.RememberAppOrientation
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.camera.GainValueNormalizer
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.ReadoutMode
import com.indigo.mobileobservatory.settings.CameraDefaults
import com.indigo.mobileobservatory.settings.CoverDefaults
import com.indigo.mobileobservatory.settings.FocuserDefaults
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.util.FileLogger
import kotlin.math.roundToInt

private enum class SettingsSection {
    GENERAL,
    CAMERA,
    FILTER_WHEEL,
    FOCUSER,
    COVER,
    ROTATOR,
    DIAGNOSTICS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit
) {
    var selectedSection by rememberSaveable { mutableStateOf(SettingsSection.GENERAL) }
    RememberAppOrientation(AppOrientationMode.PORTRAIT)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = selectedSection.ordinal) {
                SettingsSection.entries.forEach { section ->
                    Tab(
                        selected = section == selectedSection,
                        onClick = { selectedSection = section },
                        text = { Text(settingsSectionTitle(section)) }
                    )
                }
            }
            when (selectedSection) {
                SettingsSection.GENERAL -> GeneralSettingsPage()
                SettingsSection.CAMERA -> CameraSettingsPage(viewModel)
                SettingsSection.FILTER_WHEEL -> FilterWheelSettingsPage(viewModel)
                SettingsSection.FOCUSER -> FocuserSettingsPage(viewModel)
                SettingsSection.COVER -> CoverSettingsPage(viewModel)
                SettingsSection.ROTATOR -> RotatorSettingsPage(viewModel)
                SettingsSection.DIAGNOSTICS -> DiagnosticsSettingsPage()
            }
        }
    }
}

@Composable
private fun settingsSectionTitle(section: SettingsSection): String = when (section) {
    SettingsSection.GENERAL -> stringResource(R.string.settings_general)
    SettingsSection.CAMERA -> stringResource(R.string.settings_camera)
    SettingsSection.FILTER_WHEEL -> stringResource(R.string.settings_filter_wheel)
    SettingsSection.FOCUSER -> stringResource(R.string.settings_focuser)
    SettingsSection.COVER -> stringResource(R.string.settings_cover)
    SettingsSection.ROTATOR -> stringResource(R.string.settings_rotator)
    SettingsSection.DIAGNOSTICS -> stringResource(R.string.settings_diagnostics)
}

@Composable
private fun SettingsPage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        content()
    }
}

@Composable
private fun GeneralSettingsPage() = SettingsPage {
    Text(stringResource(R.string.save_location), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.save_location_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraSettingsPage(viewModel: CameraViewModel) = SettingsPage {
    val connection by viewModel.connectionState.collectAsState()
    val supportedReadoutModes by viewModel.supportedReadoutModes.collectAsState()
    val supportedPixelFormats by viewModel.supportedPixelFormats.collectAsState()
    val gain by viewModel.gain.collectAsState()
    val gainCapability by viewModel.gainCapability.collectAsState()
    val currentReadoutMode by viewModel.readoutMode.collectAsState()
    val currentPixelFormat by viewModel.pixelFormat.collectAsState()
    val offset by viewModel.offset.collectAsState()
    val offsetRange by viewModel.offsetRange.collectAsState()
    val offsetLabel by viewModel.offsetLabel.collectAsState()
    val offsetStep by viewModel.offsetStep.collectAsState()
    val nativeReadoutModes by viewModel.nativeReadoutModes.collectAsState()
    val currentNativeReadoutModeId by viewModel.nativeReadoutModeId.collectAsState()
    val heaterSupported by viewModel.heaterSupported.collectAsState()
    val heaterLevel by viewModel.heaterLevel.collectAsState()
    val heaterMaxLevel by viewModel.heaterMaxLevel.collectAsState()
    val fanSupported by viewModel.fanSupported.collectAsState()
    val fanLevel by viewModel.fanLevel.collectAsState()
    val fanMaxLevel by viewModel.fanMaxLevel.collectAsState()
    val cameraInfo = (connection as? ConnectionState.Connected)?.info
    var defaults by remember(cameraInfo?.serialNumber) { mutableStateOf<CameraDefaults?>(null) }
    var gainText by remember(cameraInfo?.serialNumber) { mutableStateOf("") }
    var gainInputInvalid by remember(cameraInfo?.serialNumber) { mutableStateOf(false) }
    var offsetText by remember(cameraInfo?.serialNumber) { mutableStateOf("") }
    var nativeReadoutModeId by remember(cameraInfo?.serialNumber) { mutableStateOf<String?>(null) }

    LaunchedEffect(cameraInfo?.serialNumber) {
        defaults = viewModel.cameraDefaults()
        gainText = defaults?.gain?.toInt()?.toString() ?: gain.toInt().toString()
        offsetText = defaults?.offset?.toInt()?.toString() ?: offset?.toInt()?.toString().orEmpty()
        nativeReadoutModeId = defaults?.nativeReadoutModeId ?: currentNativeReadoutModeId
    }

    if (cameraInfo == null) {
        Text(stringResource(R.string.no_active_camera))
        return@SettingsPage
    }

    Text(cameraInfo.name, style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.default_readout_mode), style = MaterialTheme.typography.titleSmall)
    if (nativeReadoutModes.isNotEmpty()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            nativeReadoutModes.forEach { mode ->
                FilterChip(
                    selected = nativeReadoutModeId == mode.id,
                    onClick = { nativeReadoutModeId = mode.id },
                    label = { Text(mode.displayName) }
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            supportedReadoutModes.forEach { mode ->
                FilterChip(
                    selected = (defaults?.readoutMode ?: currentReadoutMode) == mode,
                    onClick = { defaults = (defaults ?: CameraDefaults()).copy(readoutMode = mode) },
                    label = { Text(mode.displayName) }
                )
            }
        }
    }
    if (heaterSupported && heaterMaxLevel > 0) {
        Text(stringResource(R.string.anti_dew_heater), style = MaterialTheme.typography.titleSmall)
        if (heaterMaxLevel <= 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (heaterLevel > 0) stringResource(R.string.state_on) else stringResource(R.string.state_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = heaterLevel > 0,
                    onCheckedChange = { viewModel.setHeaterLevel(if (it) 1 else 0) }
                )
            }
        } else {
            var heaterDraft by remember(cameraInfo?.serialNumber, heaterLevel) {
                mutableIntStateOf(heaterLevel)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = heaterDraft.toFloat(),
                    onValueChange = { heaterDraft = it.roundToInt().coerceIn(0, heaterMaxLevel) },
                    onValueChangeFinished = { viewModel.setHeaterLevel(heaterDraft) },
                    valueRange = 0f..heaterMaxLevel.toFloat(),
                    steps = (heaterMaxLevel - 1).coerceIn(0, 100),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$heaterDraft / $heaterMaxLevel",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
    if (fanSupported && fanMaxLevel > 0) {
        Text(stringResource(R.string.fan_speed), style = MaterialTheme.typography.titleSmall)
        if (fanMaxLevel <= 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (fanLevel > 0) stringResource(R.string.state_on) else stringResource(R.string.state_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = fanLevel > 0,
                    onCheckedChange = { viewModel.setFanLevel(if (it) 1 else 0) }
                )
            }
        } else {
            var fanDraft by remember(cameraInfo?.serialNumber, fanLevel) {
                mutableIntStateOf(fanLevel)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = fanDraft.toFloat(),
                    onValueChange = { fanDraft = it.roundToInt().coerceIn(0, fanMaxLevel) },
                    onValueChangeFinished = { viewModel.setFanLevel(fanDraft) },
                    valueRange = 0f..fanMaxLevel.toFloat(),
                    steps = (fanMaxLevel - 1).coerceIn(0, 100),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$fanDraft / $fanMaxLevel",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
    OutlinedTextField(
        value = gainText,
        onValueChange = { raw ->
            gainText = raw.filter { it.isDigit() }
            gainInputInvalid = false
        },
        label = {
            val capability = gainCapability
            val label = capability?.label ?: stringResource(R.string.default_gain)
            val unit = capability?.unit?.let { " $it" }.orEmpty()
            Text("$label$unit")
        },
        supportingText = {
            gainCapability?.let { capability ->
                Text("${capability.min.toInt()}-${capability.max.toInt()}")
            }
        },
        singleLine = true,
        isError = gainInputInvalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    if (supportedPixelFormats.size > 1) {
    Text(stringResource(R.string.default_pixel_format), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        supportedPixelFormats.take(4).forEach { format ->
            FilterChip(
                selected = (defaults?.pixelFormat ?: currentPixelFormat) == format,
                onClick = { defaults = (defaults ?: CameraDefaults()).copy(pixelFormat = format) },
                label = { Text(format.displayName) }
            )
        }
    }
    }
    if (offsetRange != null) {
        OutlinedTextField(
            value = offsetText,
            onValueChange = { raw -> offsetText = raw.filter { it.isDigit() } },
            label = { Text("$offsetLabel (${offsetRange?.min?.toInt()}-${offsetRange?.max?.toInt()})") },
            supportingText = { Text("Step: ${offsetStep.toInt()}") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        Text(stringResource(R.string.offset_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Button(onClick = {
        if (gainText.isNotBlank() && gainText.toIntOrNull() == null) {
            gainInputInvalid = true
            return@Button
        }
        val normalizedGain = gainText.toIntOrNull()?.toFloat()?.let { value ->
            gainCapability?.let { capability -> GainValueNormalizer.normalize(capability, value) } ?: value
        }
        val updatedDefaults = (defaults ?: CameraDefaults()).copy(
            gain = normalizedGain,
            offset = offsetText.toIntOrNull()?.toFloat(),
            nativeReadoutModeId = nativeReadoutModeId
        )
        viewModel.saveCameraDefaults(updatedDefaults) { savedDefaults ->
            defaults = savedDefaults
            gainText = savedDefaults.gain?.toInt()?.toString().orEmpty()
        }
    }) { Text(stringResource(R.string.device_settings_apply)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterWheelSettingsPage(viewModel: CameraViewModel) = SettingsPage {
    val connected by viewModel.filterWheelConnected.collectAsState()
    val names by viewModel.filterWheelSlotNames.collectAsState()
    val wheelInfo by viewModel.filterWheelInfo.collectAsState()
    val bidirectional by viewModel.filterWheelBidirectional.collectAsState()
    var showEditor by remember { mutableStateOf(false) }

    val activeWheelInfo = wheelInfo
    if (!connected || activeWheelInfo == null) {
        Text(stringResource(R.string.no_active_filter_wheel))
        return@SettingsPage
    }

    Text(activeWheelInfo.name, style = MaterialTheme.typography.titleMedium)
    SettingSwitch(
        label = stringResource(R.string.bidirectional),
        checked = bidirectional,
        onCheckedChange = viewModel::setFilterWheelBidirectional
    )
    Text(names.mapIndexed { index, name -> "${index + 1}. $name" }.joinToString("\n"))
    OutlinedButton(onClick = { showEditor = true }) { Text(stringResource(R.string.edit_names)) }
    if (showEditor) {
        FilterWheelEditDialog(
            slotNames = names,
            currentSlotCount = activeWheelInfo.slotCount,
            onSave = { newNames, count ->
                if (count != activeWheelInfo.slotCount) viewModel.setFilterWheelSlotCount(count)
                newNames.forEachIndexed(viewModel::setFilterWheelSlotName)
                showEditor = false
            },
            onDismiss = { showEditor = false }
        )
    }
}

@Composable
private fun FocuserSettingsPage(viewModel: CameraViewModel) = SettingsPage {
    val info by viewModel.eafInfo.collectAsState()
    var settings by remember(info?.name) { mutableStateOf(FocuserDefaults()) }

    LaunchedEffect(info?.name) {
        settings = viewModel.focuserDefaults().let { defaults ->
            info?.let {
                defaults.copy(
                    fineStep = defaults.fineStep.takeIf { value -> value > 0 } ?: it.fineStep,
                    coarseStep = defaults.coarseStep.takeIf { value -> value > 0 } ?: it.coarseStep
                )
            } ?: defaults
        }
    }

    val activeInfo = info
    if (activeInfo == null) {
        Text(stringResource(R.string.no_active_focuser))
        return@SettingsPage
    }

    Text(activeInfo.name, style = MaterialTheme.typography.titleMedium)
    IntegerSettingField(stringResource(R.string.fine_step), settings.fineStep) {
        settings = settings.copy(fineStep = it)
    }
    IntegerSettingField(stringResource(R.string.coarse_step), settings.coarseStep) {
        settings = settings.copy(coarseStep = it)
    }
    IntegerSettingField(stringResource(R.string.max_step), settings.maxStep ?: activeInfo.maxStep) {
        settings = settings.copy(maxStep = it)
    }
    IntegerSettingField(stringResource(R.string.overshoot_steps), settings.backlashSteps) {
        settings = settings.copy(backlashSteps = it)
    }
    SettingSwitch(
        label = stringResource(R.string.focuser_reverse_direction),
        checked = settings.direction == 1,
        onCheckedChange = { settings = settings.copy(direction = if (it) 1 else 0) }
    )
    SettingSwitch(
        label = stringResource(R.string.backlash_direction),
        checked = settings.backlashDirection == 1,
        onCheckedChange = { settings = settings.copy(backlashDirection = if (it) 1 else 0) }
    )
    Button(onClick = { viewModel.saveFocuserDefaults(settings) }) {
        Text(stringResource(R.string.device_settings_apply))
    }
}

@Composable
private fun CoverSettingsPage(viewModel: CameraViewModel) = SettingsPage {
    val info by viewModel.coverDeviceInfo.collectAsState()
    val currentBrightness by viewModel.calibratorBrightness.collectAsState()
    var settings by remember(info) { mutableStateOf(CoverDefaults()) }

    LaunchedEffect(info) {
        settings = viewModel.coverDefaults().let { defaults ->
            defaults.copy(brightness = defaults.brightness ?: currentBrightness)
        }
    }

    val activeInfo = info
    if (activeInfo == null) {
        Text(stringResource(R.string.no_active_cover))
        return@SettingsPage
    }

    Text(activeInfo, style = MaterialTheme.typography.titleMedium)
    IntegerSettingField(stringResource(R.string.cover_open_angle), settings.openAngle) {
        settings = settings.copy(openAngle = it.coerceIn(0, 360))
    }
    IntegerSettingField(stringResource(R.string.cover_closed_angle), settings.closedAngle) {
        settings = settings.copy(closedAngle = it.coerceIn(0, 360))
    }
    IntegerSettingField(stringResource(R.string.cover_working_angle), settings.workingAngle) {
        settings = settings.copy(workingAngle = it.coerceIn(0, 360))
    }
    IntegerSettingField(stringResource(R.string.default_brightness), settings.brightness ?: currentBrightness) {
        settings = settings.copy(brightness = it)
    }
    Text(stringResource(R.string.cover_angle_capability_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { viewModel.saveCoverDefaults(settings) }) {
        Text(stringResource(R.string.device_settings_apply))
    }
}

@Composable
private fun RotatorSettingsPage(viewModel: CameraViewModel) = SettingsPage {
    val info by viewModel.rotatorDeviceInfo.collectAsState()
    val reversed by viewModel.rotatorReversed.collectAsState()
    val hold by viewModel.rotatorHold.collectAsState()
    val stepsPerDegree by viewModel.rotatorStepsPerDegree.collectAsState()
    var stepsText by remember(info) { mutableStateOf(stepsPerDegree.toString()) }

    val activeInfo = info
    if (activeInfo == null) {
        Text(stringResource(R.string.no_active_rotator))
        return@SettingsPage
    }

    Text(activeInfo, style = MaterialTheme.typography.titleMedium)
    SettingSwitch(stringResource(R.string.reverse), reversed, viewModel::setRotatorReversed)
    SettingSwitch(stringResource(R.string.motor_hold), hold, viewModel::setRotatorHold)
    OutlinedTextField(
        value = stepsText,
        onValueChange = { stepsText = it },
        label = { Text(stringResource(R.string.steps_per_degree)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Button(onClick = { stepsText.toIntOrNull()?.let(viewModel::setRotatorStepsPerDegree) }) {
        Text(stringResource(R.string.device_settings_apply))
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
}

@Composable
private fun IntegerSettingField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(label, value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
