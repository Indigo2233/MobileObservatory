package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.SolarSweepRate
import com.indigo.mobileobservatory.solar.SOLAR_REPEAT_SWEEP_COUNTS
import com.indigo.mobileobservatory.solar.SolarOutputProduct
import com.indigo.mobileobservatory.solar.SolarBandingAxis
import com.indigo.mobileobservatory.solar.SolarReconstructionSettings
import com.indigo.mobileobservatory.solar.SolarScanIssue
import com.indigo.mobileobservatory.solar.SolarScanIssueCode
import com.indigo.mobileobservatory.solar.SolarScanIssueSeverity
import com.indigo.mobileobservatory.solar.SolarScanPhase
import com.indigo.mobileobservatory.solar.SolarScanTaskRecord
import com.indigo.mobileobservatory.solar.SolarScanLegRecord
import com.indigo.mobileobservatory.solar.SolarSourcePolicy
import com.indigo.mobileobservatory.ui.viewmodel.SolarScanUiState
import com.indigo.mobileobservatory.ui.viewmodel.SolarScanViewModel
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.ui.components.LivePreview
import com.indigo.mobileobservatory.util.ImageUtils
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class SolarTab {
    HARDWARE,
    SCAN,
    RECONSTRUCT
}

internal fun isSolarSourceBusy(selected: File?, recording: File?): Boolean =
    selected != null && recording != null && selected.absoluteFile == recording.absoluteFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolarScanScreen(
    viewModel: SolarScanViewModel,
    camera: CameraViewModel,
    onBack: () -> Unit,
    onOpenResult: (File) -> Unit,
    busySource: File? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(SolarTab.HARDWARE) }
    var tabWasChosen by rememberSaveable { mutableStateOf(false) }
    var confirmSimulation by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelHardware() } }
    val host = LocalView.current
    DisposableEffect(host, state.hardwareActive) {
        val previous = host.keepScreenOn
        if (state.hardwareActive) host.keepScreenOn = true
        onDispose { if (state.hardwareActive) host.keepScreenOn = previous }
    }
    LaunchedEffect(state.selectedFile?.absolutePath) {
        if (state.selectedFile != null && !tabWasChosen) tab = SolarTab.RECONSTRUCT
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.solar_scan)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.solar_scan_refresh))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = tab.ordinal) {
                Tab(
                    selected = tab == SolarTab.HARDWARE,
                    onClick = { tabWasChosen = true; tab = SolarTab.HARDWARE },
                    text = { Text(stringResource(R.string.solar_hardware_tab)) }
                )
                Tab(
                    selected = tab == SolarTab.SCAN,
                    enabled = !state.hardwareActive,
                    onClick = { tabWasChosen = true; tab = SolarTab.SCAN },
                    text = { Text(stringResource(R.string.solar_hardware_simulation_tab)) }
                )
                Tab(
                    selected = tab == SolarTab.RECONSTRUCT,
                    enabled = !state.hardwareActive,
                    onClick = { tabWasChosen = true; tab = SolarTab.RECONSTRUCT },
                    text = { Text(stringResource(R.string.solar_scan_reconstruct)) }
                )
            }
            if (state.busy) {
                LinearProgressIndicator(
                    progress = state.progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            when (tab) {
                SolarTab.HARDWARE -> HardwareScanTab(state, viewModel, camera, onOpenResult)
                SolarTab.SCAN -> ScanTab(
                    state = state,
                    viewModel = viewModel,
                    onRun = {
                        if (state.configuration.sourcePolicy == SolarSourcePolicy.KEEP) {
                            viewModel.runSimulation()
                        } else confirmSimulation = true
                    },
                    onOpenResult = onOpenResult
                )
                SolarTab.RECONSTRUCT -> ReconstructionTab(
                    state,
                    viewModel,
                    onOpenResult,
                    busySource
                )
            }
        }
    }
    if (confirmSimulation) {
        AlertDialog(
            onDismissRequest = { confirmSimulation = false },
            title = { Text(stringResource(R.string.solar_scan_source_policy)) },
            text = {
                Text(
                    when (state.configuration.sourcePolicy) {
                        SolarSourcePolicy.TRASH_AFTER_SEVEN_DAYS ->
                            stringResource(R.string.solar_scan_trash_confirm)
                        SolarSourcePolicy.DELETE_AFTER_COMMIT ->
                            stringResource(R.string.solar_scan_delete_confirm)
                        SolarSourcePolicy.KEEP -> stringResource(R.string.solar_scan_keep_source)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSimulation = false
                    viewModel.runSimulation()
                }) { Text(stringResource(R.string.solar_scan_simulate)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSimulation = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HardwareScanTab(
    state: SolarScanUiState,
    viewModel: SolarScanViewModel,
    camera: CameraViewModel,
    onOpenResult: (File) -> Unit
) {
    val preview by camera.previewBitmap.collectAsStateWithLifecycle()
    val roi by camera.roi.collectAsStateWithLifecycle()
    val rate by camera.mountSlewRate.collectAsStateWithLifecycle()
    val motion by camera.mountMotionState.collectAsStateWithLifecycle()
    val cameraConnection by camera.connectionState.collectAsStateWithLifecycle()
    val mountConnection by camera.mountConnectionState.collectAsStateWithLifecycle()
    val mountAccess by camera.mountAccessMode.collectAsStateWithLifecycle()
    val paused by camera.previewPaused.collectAsStateWithLifecycle()
    val trials by viewModel.trials.collectAsStateWithLifecycle()
    val mountAvailable = remember(mountConnection, mountAccess, motion) { camera.canSolarSweep }
    val ready = preview != null && !paused && cameraConnection is com.indigo.mobileobservatory.camera.ConnectionState.Connected &&
        mountAvailable && !state.busy && !motion.isActive
    val canTrial = ready && SolarSweepRate.entries.any { it.mountRate == rate }
    val paired = trials.size == 2 && trials[0].motion.pairsWith(trials[1].motion)
    var countText by rememberSaveable { mutableStateOf("2") }
    val count = countText.toIntOrNull()?.takeIf { it in SOLAR_REPEAT_SWEEP_COUNTS }
    val canRepeat = canTrial && paired && count != null
    var confirm by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.hardwareActive) {
            TaskStatusCard(state, hardware = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::cancel) { Text(stringResource(R.string.solar_scan_stop)) }
                if (state.trialMoving) {
                    Button(onClick = viewModel::finishTrial) { Text(stringResource(R.string.solar_hardware_finish)) }
                }
            }
        }
        if (preview != null) {
            LivePreview(
                bitmap = preview,
                proposedRoi = roi,
                sourceRoi = roi,
                modifier = Modifier.fillMaxWidth().height(190.dp),
                preferFullWidth = true
            )
        }
        state.message?.let { Text(it) }
        if (state.hardwareActive) return@Column
        Text(stringResource(R.string.solar_hardware_instructions))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in SolarSweepRate.entries) {
                FilterChip(selected = rate == option.mountRate, onClick = { camera.setMountSlewRate(option.mountRate) },
                    enabled = ready, label = { Text(stringResource(
                        if (option == SolarSweepRate.FIND) R.string.solar_hardware_find_rate
                        else R.string.solar_hardware_fast_rate
                    )) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = canTrial, onClick = { viewModel.startTrial(camera, MountDirection.WEST) }) {
                Text(stringResource(R.string.solar_hardware_west))
            }
            Button(enabled = canTrial, onClick = { viewModel.startTrial(camera, MountDirection.EAST) }) {
                Text(stringResource(R.string.solar_hardware_east))
            }
        }
        trials.forEach { trial ->
            Text(stringResource(
                R.string.solar_hardware_trial_summary,
                stringResource(if (trial.motion.direction == MountDirection.WEST) R.string.solar_hardware_west
                    else R.string.solar_hardware_east),
                trial.motion.motionMs / 1_000.0,
                trial.coverage.leftMargin,
                trial.coverage.rightMargin
            ))
        }
        if (trials.isNotEmpty() && !paired) Text(stringResource(R.string.solar_hardware_pair_required))
        if (paired) {
            OutlinedTextField(
                value = countText,
                onValueChange = { input ->
                    if (input.length <= SOLAR_REPEAT_SWEEP_COUNTS.last.toString().length &&
                        input.all(Char::isDigit)
                    ) countText = input
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                singleLine = true,
                isError = countText.isNotEmpty() && count == null,
                label = { Text(stringResource(R.string.solar_hardware_count)) },
                supportingText = {
                    Text(stringResource(
                        R.string.solar_hardware_count_range,
                        SOLAR_REPEAT_SWEEP_COUNTS.first,
                        SOLAR_REPEAT_SWEEP_COUNTS.last
                    ))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in listOf(2, 4, 6)) {
                    FilterChip(selected = count == option, onClick = { countText = option.toString() },
                        enabled = !state.busy, label = { Text(option.toString()) })
                }
            }
        }
        Button(onClick = { confirm = true }, enabled = canRepeat) {
            Text(stringResource(R.string.solar_hardware_auto))
        }
        RecentRuns(state.runs, onOpenResult)
    }
    if (confirm && count != null) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.solar_hardware_auto)) },
            text = { Text(stringResource(R.string.solar_hardware_confirm, count)) },
            confirmButton = {
                TextButton(enabled = canRepeat, onClick = {
                    confirm = false
                    viewModel.startReciprocating(camera, count)
                }) { Text(stringResource(R.string.solar_hardware_start)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun ScanTab(
    state: SolarScanUiState,
    viewModel: SolarScanViewModel,
    onRun: () -> Unit,
    onOpenResult: (File) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.solar_scan_hint), style = MaterialTheme.typography.bodyMedium)
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(999.dp)
        ) {
            Text(
                stringResource(R.string.solar_scan_simulation_badge),
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium
            )
        }
        TaskStatusCard(state)
        ScanConfigurationCard(state, viewModel)
        PreflightCard(state)
        state.message?.let { message ->
            Surface(
                color = if (state.phase == SolarScanPhase.FAILED ||
                    state.phase == SolarScanPhase.CANCELLED
                ) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f))
                    TextButton(onClick = viewModel::dismissMessage) { Text(stringResource(R.string.close)) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRun,
                enabled = !state.busy && state.preflight.canStart
            ) {
                Icon(Icons.Default.AutoAwesome, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.solar_scan_simulate))
            }
            if (state.busy) {
                TextButton(onClick = viewModel::cancel) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.solar_scan_stop))
                }
            }
        }
        RecentRuns(state.runs, onOpenResult)
    }
}

@Composable
private fun TaskStatusCard(state: SolarScanUiState, hardware: Boolean = false) {
    if (state.phase == SolarScanPhase.IDLE) return
    Card {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        when (state.phase) {
                            SolarScanPhase.IDLE -> R.string.solar_scan_phase_idle
                            SolarScanPhase.PREFLIGHT -> R.string.solar_scan_phase_preflight
                            SolarScanPhase.ANALYZING -> R.string.solar_scan_phase_analyzing
                            SolarScanPhase.MOVING_TO_START -> R.string.solar_scan_phase_moving
                            SolarScanPhase.RECORDING -> R.string.solar_scan_phase_recording
                            SolarScanPhase.SETTLING -> R.string.solar_scan_phase_settling
                            SolarScanPhase.RECONSTRUCTING -> R.string.solar_scan_phase_reconstructing
                            SolarScanPhase.COMMITTING -> R.string.solar_scan_phase_committing
                            SolarScanPhase.COMPLETED -> R.string.solar_scan_phase_completed
                            SolarScanPhase.STOPPING -> R.string.solar_scan_phase_stopping
                            SolarScanPhase.CANCELLED -> R.string.solar_scan_phase_cancelled
                            SolarScanPhase.FAILED -> R.string.solar_scan_phase_failed
                        }
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
                if (state.totalLegs > 0) {
                    Text(
                        stringResource(
                            R.string.solar_scan_leg_progress,
                            state.currentLeg.coerceAtLeast(1),
                            state.totalLegs,
                            state.completedLegs
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                state.currentDirection?.let { direction ->
                    Text(
                        stringResource(
                            when {
                                hardware && direction.flipVertical -> R.string.solar_hardware_east
                                hardware -> R.string.solar_hardware_west
                                direction.flipVertical -> R.string.solar_scan_direction_b_to_a
                                else -> R.string.solar_scan_direction_a_to_b
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ScanConfigurationCard(
    state: SolarScanUiState,
    viewModel: SolarScanViewModel
) {
    val configuration = state.configuration
    val enabled = !state.busy
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.solar_scan_configuration), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.solar_scan_endpoint_a), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SolarNumberField(
                    stringResource(R.string.solar_scan_ra_hours),
                    configuration.endpointA.raHours,
                    Modifier.weight(1f),
                    enabled = enabled
                ) { value ->
                    if (value in 0.0..24.0) viewModel.updateConfiguration {
                        it.copy(endpointA = it.endpointA.copy(raHours = value))
                    }
                }
                SolarNumberField(
                    stringResource(R.string.solar_scan_dec_degrees),
                    configuration.endpointA.decDegrees,
                    Modifier.weight(1f),
                    enabled = enabled
                ) { value ->
                    if (value in -90.0..90.0) viewModel.updateConfiguration {
                        it.copy(endpointA = it.endpointA.copy(decDegrees = value))
                    }
                }
            }
            Text(stringResource(R.string.solar_scan_endpoint_b), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SolarNumberField(
                    stringResource(R.string.solar_scan_ra_hours),
                    configuration.endpointB.raHours,
                    Modifier.weight(1f),
                    enabled = enabled
                ) { value ->
                    if (value in 0.0..24.0) viewModel.updateConfiguration {
                        it.copy(endpointB = it.endpointB.copy(raHours = value))
                    }
                }
                SolarNumberField(
                    stringResource(R.string.solar_scan_dec_degrees),
                    configuration.endpointB.decDegrees,
                    Modifier.weight(1f),
                    enabled = enabled
                ) { value ->
                    if (value in -90.0..90.0) viewModel.updateConfiguration {
                        it.copy(endpointB = it.endpointB.copy(decDegrees = value))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SolarNumberField(
                    stringResource(R.string.solar_scan_duration),
                    configuration.scanDurationMs / 1_000.0,
                    Modifier.weight(1f),
                    enabled = enabled
                ) { value ->
                    val milliseconds = (value * 1_000.0).roundToLong()
                    if (milliseconds > 0) viewModel.updateConfiguration {
                        it.copy(scanDurationMs = milliseconds)
                    }
                }
                SolarNumberField(
                    stringResource(R.string.solar_scan_crossing),
                    configuration.diskCrossingMs / 1_000.0,
                    Modifier.weight(1f),
                    enabled = enabled
                ) { value ->
                    val milliseconds = (value * 1_000.0).roundToLong()
                    if (milliseconds > 0) viewModel.updateConfiguration {
                        it.copy(diskCrossingMs = milliseconds)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SolarNumberField(
                    stringResource(R.string.solar_scan_rate),
                    configuration.frameRate,
                    Modifier.weight(1f),
                    enabled = enabled
                ) { value ->
                    if (value > 0.0) viewModel.updateConfiguration { it.copy(frameRate = value) }
                }
                SolarNumberField(
                    stringResource(R.string.solar_scan_exposure_ms),
                    configuration.exposureUs / 1_000.0,
                    Modifier.weight(1f),
                    enabled = enabled
                ) { value ->
                    val microseconds = (value * 1_000.0).roundToLong()
                    if (microseconds > 0) viewModel.updateConfiguration {
                        it.copy(exposureUs = microseconds)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SolarNumberField(
                    stringResource(R.string.solar_scan_disk_diameter),
                    configuration.diskDiameterArcsec,
                    Modifier.weight(1f),
                    enabled = enabled
                ) { value ->
                    if (value > 0.0) viewModel.updateConfiguration { it.copy(diskDiameterArcsec = value) }
                }
                SolarNumberField(
                    stringResource(R.string.solar_scan_position_uncertainty),
                    configuration.positionUncertaintyArcsec,
                    Modifier.weight(1f),
                    enabled = enabled
                ) { value ->
                    if (value >= 0.0) viewModel.updateConfiguration {
                        it.copy(positionUncertaintyArcsec = value)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SolarNumberField(
                    stringResource(R.string.solar_scan_start_blank),
                    configuration.startBlankArcsec,
                    Modifier.weight(1f),
                    enabled = enabled
                ) { value ->
                    if (value >= 0.0) viewModel.updateConfiguration { it.copy(startBlankArcsec = value) }
                }
                SolarNumberField(
                    stringResource(R.string.solar_scan_end_blank),
                    configuration.endBlankArcsec,
                    Modifier.weight(1f),
                    enabled = enabled
                ) { value ->
                    if (value >= 0.0) viewModel.updateConfiguration { it.copy(endBlankArcsec = value) }
                }
            }
            SolarNumberField(
                stringResource(R.string.solar_scan_legs),
                configuration.legCount.toDouble(),
                Modifier.fillMaxWidth(),
                enabled = enabled
            ) { value ->
                if (value >= 1.0 && value <= 9_999.0) viewModel.updateConfiguration {
                    it.copy(legCount = value.toInt())
                }
            }
            Text(stringResource(R.string.solar_scan_source_policy), style = MaterialTheme.typography.labelLarge)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SolarSourcePolicy.entries.forEach { policy ->
                    FilterChip(
                        selected = configuration.sourcePolicy == policy,
                        onClick = {
                            viewModel.updateConfiguration { it.copy(sourcePolicy = policy) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                        label = {
                            Text(
                                stringResource(
                                    when (policy) {
                                        SolarSourcePolicy.KEEP -> R.string.solar_scan_keep_source
                                        SolarSourcePolicy.TRASH_AFTER_SEVEN_DAYS -> R.string.solar_scan_trash_source
                                        SolarSourcePolicy.DELETE_AFTER_COMMIT -> R.string.solar_scan_delete_source
                                    }
                                )
                            )
                        }
                    )
                }
            }
            Text(
                stringResource(
                    R.string.solar_scan_estimates,
                    configuration.framesPerLeg.toString(),
                    configuration.angularStepArcsec,
                    configuration.exposureBlurArcsec
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                stringResource(
                    R.string.solar_scan_storage,
                    ImageUtils.formatFileSize(configuration.requiredBytes)
                ),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun PreflightCard(state: SolarScanUiState) {
    val issues = state.preflight.issues
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (state.preflight.canStart) {
                MaterialTheme.colorScheme.secondaryContainer
            } else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.solar_scan_preflight), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    if (state.preflight.canStart) R.string.solar_scan_ready else R.string.solar_scan_blocked
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            issues.forEach { issue ->
                Text(
                    text = issueText(issue),
                    color = if (issue.severity == SolarScanIssueSeverity.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun RecentRuns(runs: List<SolarScanTaskRecord>, onOpenResult: (File) -> Unit) {
    Text(stringResource(R.string.solar_scan_recent_runs), style = MaterialTheme.typography.titleMedium)
    if (runs.isEmpty()) {
        Text(stringResource(R.string.solar_scan_no_runs), color = MaterialTheme.colorScheme.outline)
        return
    }
    runs.groupBy { it.batchId ?: it.id }.values.take(8).forEach { group ->
        val run = group.first()
        if (run.batchId != null) {
            RecentHardwareBatch(group, onOpenResult)
            return@forEach
        }
        val result = run.legs.firstNotNullOfOrNull(::solarResultFile)
        Card {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(run.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.solar_scan_run_summary,
                            run.legs.size,
                            run.legs.sumOf { it.recordingFrames }
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        formatInstant(run.completedAtUtc ?: run.createdAtUtc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    run.legs.mapNotNull { it.coverage }.firstOrNull()?.let { coverage ->
                        Text(
                            stringResource(
                                R.string.solar_scan_coverage_report,
                                coverage.startBlankArcsec,
                                coverage.endBlankArcsec,
                                coverage.stableCoverageFraction * 100.0
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (coverage.accelerationOverlapsDisk || coverage.decelerationOverlapsDisk) {
                            Text(
                                stringResource(R.string.solar_scan_coverage_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    run.warnings.forEach { warning ->
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                TextButton(
                    onClick = { if (result != null) onOpenResult(result) },
                    enabled = result != null
                ) { Text(stringResource(R.string.solar_scan_open_result)) }
            }
        }
    }
}

private fun solarResultFile(leg: SolarScanLegRecord): File? {
    val result = leg.reconstruction ?: return null
    return sequenceOf(result.displayPng, result.linearPng, result.linearFits)
        .filterNotNull()
        .map(::File)
        .firstOrNull(File::isFile)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentHardwareBatch(group: List<SolarScanTaskRecord>, onOpenResult: (File) -> Unit) {
    val latest = group.first()
    var showResults by rememberSaveable(latest.batchId) { mutableStateOf(false) }
    Card {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.solar_hardware_auto), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.solar_scan_run_summary, group.size,
                        group.sumOf { run -> run.legs.sumOf { it.recordingFrames } }),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(formatInstant(latest.completedAtUtc ?: latest.createdAtUtc),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            TextButton(onClick = { showResults = true }) {
                Text(stringResource(R.string.solar_scan_open_result))
            }
        }
    }
    if (showResults) {
        AlertDialog(
            onDismissRequest = { showResults = false },
            title = { Text(stringResource(R.string.solar_hardware_auto)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(group.sortedBy { it.legs.firstOrNull()?.number }, key = { it.id }) { run ->
                        val result = run.legs.firstNotNullOfOrNull(::solarResultFile)
                        Column {
                            TextButton(enabled = result != null, onClick = {
                                showResults = false
                                if (result != null) onOpenResult(result)
                            }) { Text(run.title) }
                            run.warnings.forEach { warning ->
                                Text(
                                    warning,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showResults = false }) { Text(stringResource(R.string.close)) }
            }
        )
    }
}

@Composable
private fun ReconstructionTab(
    state: SolarScanUiState,
    viewModel: SolarScanViewModel,
    onOpenResult: (File) -> Unit,
    busySource: File?
) {
    val availableFiles = state.files.filterNot {
        it.file.absoluteFile == busySource?.absoluteFile
    }
    val selectedBusy = isSolarSourceBusy(state.selectedFile, busySource)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.solar_scan_source), style = MaterialTheme.typography.titleMedium)
                if (availableFiles.isEmpty()) {
                    Text(stringResource(R.string.solar_scan_no_sources))
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 210.dp)) {
                        items(availableFiles, key = { it.file.absolutePath }) { info ->
                            val selected = state.selectedFile?.absoluteFile == info.file.absoluteFile
                            Surface(
                                tonalElevation = if (selected) 4.dp else 0.dp,
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                                    viewModel.selectSource(info.file)
                                },
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(info.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${info.format} · ${ImageUtils.formatFileSize(info.size)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        state.preview?.let {
            SpectrumPreview(state)
            Button(
                onClick = viewModel::analyzeSelected,
                enabled = !state.busy && state.selectedFile != null && !selectedBusy
            ) {
                Icon(Icons.Default.AutoAwesome, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.solar_scan_find_line))
            }
        }
        if (selectedBusy) {
            Text(
                stringResource(R.string.solar_scan_source_busy),
                color = MaterialTheme.colorScheme.error
            )
        }
        state.analysis?.let { analysis ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(stringResource(R.string.solar_scan_analysis), style = MaterialTheme.typography.titleMedium)
                    Text("${stringResource(R.string.solar_scan_confidence)}: ${"%.1f".format(analysis.confidence * 100)}%")
                    Text("${stringResource(R.string.solar_scan_line_depth)}: ${"%.1f".format(analysis.lineDepth * 100)}%")
                    Text("${stringResource(R.string.solar_scan_valid_columns)}: ${analysis.validColumns}")
                    Text("${stringResource(R.string.solar_scan_suggested_roi)}: ${analysis.suggestedRoi.height}px")
                }
            }
        }
        ReconstructionSettingsCard(state, viewModel)
        state.message?.let { message ->
            Surface(
                color = if (state.phase == SolarScanPhase.FAILED ||
                    state.phase == SolarScanPhase.CANCELLED
                ) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(message, Modifier.fillMaxWidth().padding(12.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::reconstructSelected,
                enabled = !state.busy && state.selectedFile != null &&
                    state.analysis != null && !selectedBusy
            ) {
                Icon(Icons.Default.Image, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.solar_scan_reconstruct_action))
            }
            if (state.busy) {
                TextButton(onClick = viewModel::cancel) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.solar_scan_stop))
                }
            }
        }
        Text(
            stringResource(R.string.solar_scan_manual_keeps_source),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        state.lastResultDirectory?.let { directory ->
            val result = directory.walkTopDown().firstOrNull {
                it.isFile && (it.extension in setOf("png", "fit", "fits"))
            }
            if (result != null) {
                TextButton(onClick = { onOpenResult(result) }) {
                    Text(stringResource(R.string.solar_scan_open_result))
                }
            }
        }
    }
}

@Composable
private fun SpectrumPreview(state: SolarScanUiState) {
    val bitmap = state.preview ?: return
    val sourceHeight = state.sourceHeight.takeIf { it > 0 } ?: bitmap.height
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
            .background(Color.Black)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.solar_scan_source),
            modifier = Modifier.fillMaxSize()
        )
        val primary = MaterialTheme.colorScheme.primary
        val warning = MaterialTheme.colorScheme.error
        state.analysis?.let { analysis ->
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val path = Path()
                for (x in 0 until analysis.trace.width) {
                    val px = (x.toFloat() / (analysis.trace.width - 1).coerceAtLeast(1)) * width
                    val py = (
                        analysis.trace.rowAt(x).toFloat() /
                            (sourceHeight.coerceAtLeast(2) - 1)
                        ) * height
                    if (x == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, primary, style = Stroke(width = 2.5f))
                val top = analysis.suggestedRoi.y.toFloat() /
                    sourceHeight.coerceAtLeast(1) * height
                val bottom = analysis.suggestedRoi.bottom.toFloat() /
                    sourceHeight.coerceAtLeast(1) * height
                drawRect(
                    color = warning,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                    size = androidx.compose.ui.geometry.Size(width, (bottom - top).coerceAtLeast(1f)),
                    style = Stroke(width = 1.5f)
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReconstructionSettingsCard(
    state: SolarScanUiState,
    viewModel: SolarScanViewModel
) {
    val settings = state.reconstructionSettings
    val enabled = !state.busy
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.solar_scan_products), style = MaterialTheme.typography.titleMedium)
            SolarOutputProduct.entries.forEach { product ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = product in settings.products,
                        onCheckedChange = { checked ->
                            val next = if (checked) settings.products + product
                            else settings.products - product
                            if (next.isNotEmpty()) viewModel.updateReconstruction {
                                it.copy(products = next)
                            }
                        },
                        enabled = enabled
                    )
                    Text(
                        stringResource(
                            when (product) {
                                SolarOutputProduct.DISPLAY_PNG -> R.string.solar_scan_display_png
                                SolarOutputProduct.LINEAR_PNG -> R.string.solar_scan_linear_png
                                SolarOutputProduct.LINEAR_FITS -> R.string.solar_scan_linear_fits
                            }
                        )
                    )
                }
            }
            SolarNumberField(
                stringResource(R.string.solar_scan_offset),
                settings.lineShiftPixels,
                Modifier.fillMaxWidth(),
                enabled = enabled
            ) { value ->
                if (value.isFinite()) viewModel.updateReconstruction { it.copy(lineShiftPixels = value) }
            }
            SolarNumberField(
                stringResource(R.string.solar_scan_stretch),
                settings.frameStride.toDouble(),
                Modifier.fillMaxWidth(),
                enabled = enabled
            ) { value ->
                if (value in 1.0..64.0) viewModel.updateReconstruction {
                    it.copy(frameStride = value.toInt())
                }
            }
            Text(
                stringResource(R.string.solar_scan_correction),
                style = MaterialTheme.typography.titleSmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.scanCorrection.bandingAxis == SolarBandingAxis.OFF,
                    onClick = {
                        viewModel.updateReconstruction {
                            it.copy(
                                scanCorrection = it.scanCorrection.copy(
                                    bandingAxis = SolarBandingAxis.OFF,
                                    bandingWidthPixels = null
                                )
                            )
                        }
                    },
                    label = { Text(stringResource(R.string.solar_scan_banding_off)) },
                    enabled = enabled
                )
                FilterChip(
                    selected = settings.scanCorrection.bandingAxis == SolarBandingAxis.AUTO,
                    onClick = {
                        viewModel.updateReconstruction {
                            it.copy(
                                scanCorrection = it.scanCorrection.copy(
                                    bandingAxis = SolarBandingAxis.AUTO
                                )
                            )
                        }
                    },
                    label = { Text(stringResource(R.string.solar_scan_banding_auto)) },
                    enabled = enabled
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.scanCorrection.bandingAxis == SolarBandingAxis.VERTICAL,
                    onClick = {
                        viewModel.updateReconstruction {
                            it.copy(
                                scanCorrection = it.scanCorrection.copy(
                                    bandingAxis = SolarBandingAxis.VERTICAL
                                )
                            )
                        }
                    },
                    label = { Text(stringResource(R.string.solar_scan_banding_vertical)) },
                    enabled = enabled
                )
                FilterChip(
                    selected = settings.scanCorrection.bandingAxis == SolarBandingAxis.HORIZONTAL,
                    onClick = {
                        viewModel.updateReconstruction {
                            it.copy(
                                scanCorrection = it.scanCorrection.copy(
                                    bandingAxis = SolarBandingAxis.HORIZONTAL
                                )
                            )
                        }
                    },
                    label = { Text(stringResource(R.string.solar_scan_banding_horizontal)) },
                    enabled = enabled
                )
            }
            if (settings.scanCorrection.bandingAxis != SolarBandingAxis.OFF) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = settings.scanCorrection.bandingWidthPixels != null,
                        onCheckedChange = { custom ->
                            viewModel.updateReconstruction {
                                it.copy(
                                    scanCorrection = it.scanCorrection.copy(
                                        bandingWidthPixels = if (custom) 15 else null
                                    )
                                )
                            }
                        },
                        enabled = enabled
                    )
                    Text(stringResource(R.string.solar_scan_banding_width_custom))
                }
                settings.scanCorrection.bandingWidthPixels?.let { width ->
                    SolarNumberField(
                        stringResource(R.string.solar_scan_banding_width),
                        width.toDouble(),
                        Modifier.fillMaxWidth(),
                        enabled = enabled
                    ) { value ->
                        val pixels = value.roundToInt()
                        if (pixels in 7..51 && pixels % 2 == 1) {
                            viewModel.updateReconstruction {
                                it.copy(
                                    scanCorrection = it.scanCorrection.copy(
                                        bandingWidthPixels = pixels
                                    )
                                )
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.scanCorrection.alignScanRows,
                    onCheckedChange = { checked ->
                        viewModel.updateReconstruction {
                            it.copy(
                                scanCorrection = it.scanCorrection.copy(alignScanRows = checked)
                            )
                        }
                    },
                    enabled = enabled
                )
                Text(stringResource(R.string.solar_scan_align_rows))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.scanCorrection.correctPeriodicOscillation,
                    onCheckedChange = { checked ->
                        viewModel.updateReconstruction {
                            it.copy(
                                scanCorrection = it.scanCorrection.copy(
                                    correctPeriodicOscillation = checked
                                )
                            )
                        }
                    },
                    enabled = enabled
                )
                Text(stringResource(R.string.solar_scan_periodic_oscillation))
            }
            Text(
                stringResource(R.string.solar_scan_flat_correction),
                style = MaterialTheme.typography.titleSmall
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.flat.synthetic,
                    onCheckedChange = { checked ->
                        viewModel.updateReconstruction {
                            it.copy(
                                flat = it.flat.copy(
                                    synthetic = checked,
                                    physicalSourcePath = if (checked) null else it.flat.physicalSourcePath
                                )
                            )
                        }
                    },
                    enabled = enabled
                )
                Text(stringResource(R.string.solar_scan_synthetic_flat))
            }
            if (settings.flat.synthetic) {
                SolarNumberField(
                    stringResource(R.string.solar_scan_synthetic_strength),
                    settings.flat.syntheticStrength.toDouble(),
                    Modifier.fillMaxWidth(),
                    enabled = enabled
                ) { value ->
                    val strength = value.roundToInt()
                    if (strength in 1..100) {
                        viewModel.updateReconstruction {
                            it.copy(flat = it.flat.copy(syntheticStrength = strength))
                        }
                    }
                }
            }
            PhysicalFlatPicker(
                state = state,
                settings = settings,
                enabled = enabled,
                viewModel = viewModel
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.flipHorizontal,
                    onCheckedChange = { value -> viewModel.updateReconstruction {
                        it.copy(flipHorizontal = value)
                    } },
                    enabled = enabled
                )
                Text(stringResource(R.string.solar_scan_flip_horizontal))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = settings.flipVertical,
                    onCheckedChange = { value -> viewModel.updateReconstruction {
                        it.copy(flipVertical = value)
                    } },
                    enabled = enabled
                )
                Text(stringResource(R.string.solar_scan_flip_vertical))
            }
        }
    }
}

@Composable
private fun PhysicalFlatPicker(
    state: SolarScanUiState,
    settings: SolarReconstructionSettings,
    enabled: Boolean,
    viewModel: SolarScanViewModel
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedPath = settings.flat.physicalSourcePath
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.solar_scan_physical_flat))
            Text(
                selectedPath?.let { File(it).name } ?: stringResource(R.string.solar_scan_flat_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Box {
            TextButton(
                onClick = { expanded = true },
                enabled = enabled
            ) {
                Text(stringResource(R.string.solar_scan_choose_flat))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.solar_scan_flat_none)) },
                    onClick = {
                        expanded = false
                        viewModel.updateReconstruction {
                            it.copy(flat = it.flat.copy(physicalSourcePath = null))
                        }
                    }
                )
                state.files.forEach { info ->
                    DropdownMenuItem(
                        text = { Text(info.name) },
                        onClick = {
                            expanded = false
                            viewModel.updateReconstruction {
                                it.copy(
                                    flat = it.flat.copy(
                                        synthetic = false,
                                        physicalSourcePath = info.file.absolutePath
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SolarNumberField(
    label: String,
    value: Double,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChange: (Double) -> Unit
) {
    var text by rememberSaveable(label) { mutableStateOf(formatNumber(value)) }
    LaunchedEffect(value) {
        val parsed = text.toDoubleOrNull()
        if (parsed == null || kotlin.math.abs(parsed - value) > 1e-9) text = formatNumber(value)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            next.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(onValueChange)
        },
        singleLine = true,
        enabled = enabled,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

@Composable
private fun issueText(issue: SolarScanIssue): String = when (issue.code) {
    SolarScanIssueCode.INVALID_END -> stringResource(R.string.solar_scan_issue_endpoint)
    SolarScanIssueCode.INVALID_DURATION -> stringResource(R.string.solar_scan_issue_duration)
    SolarScanIssueCode.INVALID_CROSSING -> stringResource(R.string.solar_scan_issue_crossing)
    SolarScanIssueCode.INVALID_FRAME_RATE -> stringResource(R.string.solar_scan_issue_rate)
    SolarScanIssueCode.TOO_MANY_FRAMES -> stringResource(
        R.string.solar_scan_issue_frames,
        issue.actual ?: 0.0,
        issue.required ?: 0.0
    )
    SolarScanIssueCode.INVALID_LEG_COUNT -> stringResource(R.string.solar_scan_issue_legs)
    SolarScanIssueCode.INVALID_EXPOSURE -> stringResource(R.string.solar_scan_issue_blur, issue.actual ?: 0.0)
    SolarScanIssueCode.INVALID_ROI -> stringResource(R.string.solar_scan_issue_roi)
    SolarScanIssueCode.SOURCE_TOO_SHORT -> stringResource(R.string.solar_scan_issue_source_short)
    SolarScanIssueCode.OUTSIDE_MARGIN_SHORT -> stringResource(
        R.string.solar_scan_issue_margin,
        issue.actual ?: 0.0,
        issue.required ?: 0.0
    )
    SolarScanIssueCode.EXPOSURE_BLUR -> stringResource(
        R.string.solar_scan_issue_blur,
        issue.actual ?: 0.0
    )
    SolarScanIssueCode.FRAME_RATE_LOW -> stringResource(
        R.string.solar_scan_issue_fps,
        issue.actual ?: 0.0
    )
    SolarScanIssueCode.STORAGE_LOW -> stringResource(
        R.string.solar_scan_issue_storage,
        ImageUtils.formatFileSize((issue.required ?: 0.0).roundToLong())
    )
    SolarScanIssueCode.NO_CALIBRATION -> stringResource(R.string.solar_scan_issue_calibration)
    SolarScanIssueCode.UNSUPPORTED_PIXEL_FORMAT -> stringResource(R.string.solar_scan_issue_format)
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else "%.4f".format(value).trimEnd('0').trimEnd('.')

private fun formatInstant(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault(value)
