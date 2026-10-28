package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.saveable.rememberSaveable
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.ui.components.*
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.sequence.SequenceFeature
import com.indigo.mobileobservatory.astro.FovPointingCandidate
import com.indigo.mobileobservatory.astro.OpticsTrainId
import com.indigo.mobileobservatory.astro.PointingOpticsChange
import com.indigo.mobileobservatory.astro.ConnectedOpticsGeometry
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.pointing.PhoneSiteProvider
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SkyWorkspaceScreen(viewModel: CameraViewModel, selectedTarget: StarMapTarget?,
    redNightMode: Boolean,
    onTargets: () -> Unit, onPreparation: () -> Unit, onPushTo: (StarMapTarget) -> Unit,
    onTargetSelected: (StarMapTarget) -> Unit, onBack: () -> Unit,
    pointingSources: List<FovPointingCandidate> = emptyList(),
    opticalPathIds: Map<OpticsTrainId, String> = emptyMap(),
    onOpticalPathInvalidated: (OpticsTrainId, PointingOpticsChange) -> Unit = { _, _ -> }) {
    val mountCoordinates by viewModel.mountCoordinates.collectAsStateWithPageLifecycle()
    val mountTime by viewModel.mountTime.collectAsStateWithPageLifecycle()
    val mountSite by viewModel.mountSite.collectAsStateWithPageLifecycle()
    val mountConnectionState by viewModel.mountConnectionState.collectAsStateWithPageLifecycle()
    val mountBusy by viewModel.mountBusy.collectAsStateWithPageLifecycle()
    val mountCapabilities by viewModel.mountPointingCapabilities.collectAsStateWithPageLifecycle()
    val globalMountMotionState by viewModel.mountMotionState.collectAsStateWithPageLifecycle()
    val mountSlewRate by viewModel.mountSlewRate.collectAsStateWithPageLifecycle()
    val precisionGotoProgress by viewModel.precisionGotoProgress.collectAsStateWithPageLifecycle()
    val precisionSamples by viewModel.precisionSamples.samples.collectAsStateWithPageLifecycle()
    val cameraPreviewSettings = viewModel.cameraPreviewSettings()
    var showPrecision by rememberSaveable { mutableStateOf(false) }
    var inspectPrecision by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(precisionGotoProgress.isActive) { if (precisionGotoProgress.isActive) showPrecision = true }
    val connectionState by viewModel.connectionState.collectAsStateWithPageLifecycle()
    val roi by viewModel.roi.collectAsStateWithPageLifecycle()
    val appBinning by viewModel.binning.collectAsStateWithPageLifecycle()
    val guideCameraGeometry by viewModel.guideCameraGeometry.collectAsStateWithPageLifecycle()
    val opticsRevision by viewModel.pointingOpticsRevision.collectAsStateWithPageLifecycle()
    val primaryCameraGeometry = remember(connectionState, roi, appBinning, opticsRevision) {
        viewModel.pointingOpticsConfiguration().primaryGeometry
    }
    val context = LocalContext.current
    val savedSite = rememberSavedObserverSite()
    val observerSite = savedSite?.site ?: mountSite?.let { ObserverSite(it.latitudeDeg, it.longitudeDeg) }
    val observerSiteLabel = stringResource(when {
        savedSite?.origin == "manual" -> R.string.wf_observer_manual
        savedSite != null -> R.string.wf_observer_saved
        mountSite != null -> R.string.wf_observer_mount
        else -> R.string.wf_observer_missing
    })
    var siteDialog by rememberSaveable { mutableStateOf(false) }
    val pageForeground = rememberPageForeground()
    LaunchedEffect(pageForeground) {
        // Existing permission may refresh the shared location; opening the map never prompts for it.
        if (pageForeground && PhoneSiteProvider.hasPermission(context) && savedSite?.origin != "manual") {
            try { PhoneSiteProvider.currentSite(context) } catch (cancel: CancellationException) { throw cancel }
            catch (_: Throwable) { /* The explicit site action offers permission/manual recovery. */ }
        }
    }
    // Adapters declare whether ROI uses sensor pixels or the hardware-bin grid.
    // Software preview binning does not enlarge the physical field.
    val hardwareBinning = remember(connectionState, roi, appBinning) {
        viewModel.cameraManager.activeCamera?.roiSensorScale ?: 1
    }
    val sequenceEnabled = SequenceFeature.ENABLED
    if (BuildConfig.STELLARIUM_ENABLED) {
                    val solvedPositionAngle by viewModel.lastPlateSolvePositionAngleDeg.collectAsStateWithPageLifecycle()
                    val sequenceMosaicPlan by viewModel.sequenceMosaicPlan.collectAsStateWithPageLifecycle()
                    val sequenceGeneration by viewModel.sequenceRuntime.generation.collectAsStateWithPageLifecycle()
                    val restoredSequencePlan = remember(sequenceMosaicPlan, sequenceGeneration) {
                        viewModel.sequenceRuntime.mosaicPlan() ?: sequenceMosaicPlan
                    }
                    val rotatorConnected by viewModel.rotatorConnected.collectAsStateWithPageLifecycle()
                    val rotatorAngle by viewModel.rotatorAngle.collectAsStateWithPageLifecycle()
                    StarMapScreen(
                        mountCoordinates = mountCoordinates,
                        mountSite = mountSite,
                        observerSite = observerSite,
                        observerSiteLabel = observerSiteLabel,
                        onObserverSiteSettings = { siteDialog = true },
                        mountTime = mountTime,
                        mountConnected = mountConnectionState is
                            com.indigo.mobileobservatory.mount.MountConnectionState.Connected,
                        mountPointingCapabilities = mountCapabilities,
                        mountBusy = mountBusy,
                        mountMotionState = globalMountMotionState,
                        mountSlewRate = mountSlewRate,
                        precisionGotoProgress = precisionGotoProgress,
                        onPrecisionDetails = { showPrecision = true },
                        cameraPixelSizeUm = (connectionState as? ConnectionState.Connected)
                            ?.info?.pixelSizeUm,
                        cameraFrameWidthPx = roi.width,
                        cameraFrameHeightPx = roi.height,
                        cameraHardwareBinning = hardwareBinning,
                        cameraGeometry = primaryCameraGeometry,
                        cameraDeviceId = (connectionState as? ConnectionState.Connected)?.info
                            ?.let { it.serialNumber.ifBlank { it.name } }.orEmpty(),
                        guideCameraGeometry = guideCameraGeometry,
                        opticsPreferencesRevision = opticsRevision,
                        lastSolvedPositionAngleDeg = solvedPositionAngle,
                        rotatorConnected = rotatorConnected,
                        rotatorMechanicalAngleDeg = rotatorAngle,
                        initialSequencePlan = restoredSequencePlan,
                        onGoto = { target ->
                            viewModel.gotoMountTarget(
                                name = target.name,
                                raHours = target.raHours,
                                decDeg = target.decDegrees,
                                frame = target.frame
                            )
                        },
                        onSync = { target ->
                            viewModel.syncMountToTarget(
                                name = target.name,
                                raHours = target.raHours,
                                decDeg = target.decDegrees,
                                frame = target.frame
                            )
                        },
                        onPrecisionGoto = { target, toleranceArcmin ->
                            showPrecision = true
                            viewModel.startPrecisionGoto(
                                name = target.name,
                                raHours = target.raHours,
                                decDeg = target.decDegrees,
                                frame = target.frame,
                                toleranceArcmin = toleranceArcmin
                            )
                        },
                        onAddToSequence = viewModel::addSequenceMosaic,
                        onMoveRotator = viewModel::moveRotatorTo,
                        showSequenceActions = sequenceEnabled,
                        onTargetSelected = onTargetSelected,
                        onSlewRateChange = viewModel::setMountSlewRate,
                        onManualMoveStart = viewModel::startMountManualMove,
                        onManualMoveStop = { viewModel.stopMountManualMove(it) },
                        onStopMount = viewModel::stopMountMotion,
                        onGoHome = viewModel::goMountHome,
                        redNightMode = redNightMode,
                        onOpenTargets = onTargets,
                        onOpenPreparation = onPreparation,
                        onPushTo = onPushTo,
                        selectedObservationTarget = selectedTarget,
                        pointingSources = pointingSources,
                        opticalPathIds = opticalPathIds,
                        onOpticalPathInvalidated = onOpticalPathInvalidated,
                        onBack = onBack
                    )
    } else {
        var showOptics by rememberSaveable { mutableStateOf(false) }
        var pendingGoto by remember { mutableStateOf<StarMapTarget?>(null) }
        if (showOptics && pageForeground) {
            PointingOpticsConfigurationSheet(primaryGeometry = primaryCameraGeometry, secondaryGeometry = guideCameraGeometry,
                onOpticalPathInvalidated = onOpticalPathInvalidated, onDismiss = { showOptics = false },
                opticsPreferencesRevision = opticsRevision)
        }
        pendingGoto?.takeIf { pageForeground }?.let { target ->
            StarMapGotoConfirmation(target.name, target.coordinatesText(), onConfirm = {
                pendingGoto = null
                if (mountCapabilities.goto && !mountBusy && mountConnectionState is com.indigo.mobileobservatory.mount.MountConnectionState.Connected)
                    viewModel.gotoMountTarget(target.name, target.raHours, target.decDegrees, target.frame)
            }, onDismiss = { pendingGoto = null })
        }
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.star_map), style = MaterialTheme.typography.titleLarge)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showOptics = true }) { Text(stringResource(R.string.wf_optics_title)) }
                TextButton(onClick = { siteDialog = true }) { Text(observerSiteLabel) }
                TextButton(onClick = onPreparation) { Text(stringResource(R.string.wf_preparation)) }
            }
            selectedTarget?.let { target ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(target.name)
                        Button(onClick = { onPushTo(target) }) { Text(stringResource(R.string.wf_start_push_to)) }
                        if (mountCapabilities.goto && mountConnectionState is com.indigo.mobileobservatory.mount.MountConnectionState.Connected) {
                            OutlinedButton(onClick = { pendingGoto = target }, enabled = !mountBusy) { Text(stringResource(R.string.goto_label)) }
                        }
                    }
                }
            }
            TargetLibraryContent(onGuideTo = { obj -> onPushTo(StarMapTarget("${obj.id} · ${obj.name}", obj.raHours, obj.decDeg, "J2000")) },
                onSelectTarget = { obj -> onTargetSelected(StarMapTarget("${obj.id} · ${obj.name}", obj.raHours, obj.decDeg, "J2000")) },
                showSequenceActions = false, modifier = Modifier.weight(1f))
        }
        androidx.activity.compose.BackHandler(enabled = pageForeground && pendingGoto != null) { pendingGoto = null }
    }
    // Register after the workspace's own Back handler: this visible dialog is the top layer.
    if (showPrecision && pageForeground) {
        val sample = precisionSamples.first()
        val stage = if (precisionGotoProgress.isActive) when (sample.phase) {
            com.indigo.mobileobservatory.polar.PolarSamplePhase.CAPTURING -> stringResource(R.string.solve_capturing_task)
            com.indigo.mobileobservatory.polar.PolarSamplePhase.SOLVING -> stringResource(R.string.solve_solving_task)
            else -> precisionGotoProgress.message
        } else precisionGotoProgress.message
        var previousFile by remember { mutableStateOf<File?>(null) }
        LaunchedEffect(sample) { if (sample.file != null) previousFile = sample.file }
        Dialog(onDismissRequest = { showPrecision = false; inspectPrecision = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.precision_goto_label), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { showPrecision = false; inspectPrecision = null }) { Text(stringResource(R.string.close)) }
                    }
                    Text(stage)
                    PolarWorkflowPreview(file = sample.file ?: previousFile, label = stringResource(R.string.solve_view_capture),
                        livePreview = null, sample = sample, status = stage,
                        error = precisionGotoProgress.message.takeIf { precisionGotoProgress.phase == com.indigo.mobileobservatory.mount.PrecisionGotoPhase.FAILED },
                        busy = precisionGotoProgress.isActive, onStop = viewModel::stopMountMotion,
                        inspectFile = inspectPrecision, onInspect = { inspectPrecision = it }, modifier = Modifier.weight(1f),
                        previewSettings = cameraPreviewSettings.copy(monochrome = true))
                }
            }
        }
    }
    if (siteDialog && pageForeground) ObserverSiteDialog(observerSite, onDismiss = { siteDialog = false })
    androidx.activity.compose.BackHandler(enabled = pageForeground && siteDialog) { siteDialog = false }
}
