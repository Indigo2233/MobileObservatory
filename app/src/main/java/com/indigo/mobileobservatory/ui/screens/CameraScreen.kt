package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.camera.DeviceEntry
import com.indigo.mobileobservatory.camera.FrameProcessor
import com.indigo.mobileobservatory.camera.GainCapability
import com.indigo.mobileobservatory.ui.components.*
import com.indigo.mobileobservatory.ui.theme.ObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.mount.MountMotionState
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.sequence.SequenceFeature
import com.indigo.mobileobservatory.stacking.StackMode

@Composable
internal fun CaptureWorkspaceScreen(
    viewModel: CameraViewModel,
    redNightMode: Boolean,
    onRedNightModeChange: (Boolean) -> Unit,
    onPlateSolve: () -> Unit,
    onTargets: () -> Unit,
    onGuide: () -> Unit,
    onSequence: () -> Unit,
    selectedTarget: String?,
    onDeviceSettings: (String) -> Unit = {},
    onOpenLiveStack: () -> Unit = { viewModel.stacking.open(StackMode.LIVE) }
) {
    val connectionState by viewModel.connectionState.collectAsStateWithPageLifecycle()

    val exposureUs by viewModel.exposureUs.collectAsStateWithPageLifecycle()
    val gain by viewModel.gain.collectAsStateWithPageLifecycle()
    val gainCapability by viewModel.gainCapability.collectAsStateWithPageLifecycle()
    val gainWriteInProgress by viewModel.gainWriteInProgress.collectAsStateWithPageLifecycle()
    val usbBandwidth by viewModel.usbBandwidth.collectAsStateWithPageLifecycle()
    val usbBandwidthRange by viewModel.usbBandwidthRange.collectAsStateWithPageLifecycle()
    val pixelFormat by viewModel.pixelFormat.collectAsStateWithPageLifecycle()
    val interpretedFrameFormat by viewModel.liveStackPixelFormat.collectAsStateWithPageLifecycle()
    val supportedPixelFormats by viewModel.supportedPixelFormats.collectAsStateWithPageLifecycle()
    val supportsHostRoi by viewModel.supportsHostRoi.collectAsStateWithPageLifecycle()
    val roi by viewModel.roi.collectAsStateWithPageLifecycle()
    val binning by viewModel.binning.collectAsStateWithPageLifecycle()
    val binningUsesHardware by viewModel.binningUsesHardware.collectAsStateWithPageLifecycle()
    val binningUsesSoftware by viewModel.binningUsesSoftware.collectAsStateWithPageLifecycle()
    val imagingWidth by viewModel.imagingWidth.collectAsStateWithPageLifecycle()
    val imagingHeight by viewModel.imagingHeight.collectAsStateWithPageLifecycle()
    val autoStretch by viewModel.autoStretch.collectAsStateWithPageLifecycle()
    val flipH by viewModel.flipH.collectAsStateWithPageLifecycle()
    val flipV by viewModel.flipV.collectAsStateWithPageLifecycle()
    val rotation by viewModel.rotation.collectAsStateWithPageLifecycle()
    val longExposureEnabled by viewModel.longExposureEnabled.collectAsStateWithPageLifecycle()
    val longExposureProgress by viewModel.longExposureProgress.collectAsStateWithPageLifecycle()
    val exposureCountdown by viewModel.exposureCountdown.collectAsStateWithPageLifecycle()
    val exposureProgressFraction by viewModel.exposureProgressFraction.collectAsStateWithPageLifecycle()
    val autoExposureMode by viewModel.autoExposureMode.collectAsStateWithPageLifecycle()
    val awbMode by viewModel.awbMode.collectAsStateWithPageLifecycle()
    val fps by viewModel.fps.collectAsStateWithPageLifecycle()
    val histogram by viewModel.histogram.collectAsStateWithPageLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithPageLifecycle()
    val recordingFrameCount by viewModel.recordingFrameCount.collectAsStateWithPageLifecycle()
    val recordingDurationMs by viewModel.recordingDurationMs.collectAsStateWithPageLifecycle()
    val recordingBytes by viewModel.recordingBytes.collectAsStateWithPageLifecycle()
    val targetName by viewModel.targetName.collectAsStateWithPageLifecycle()
    val captureFormat by viewModel.captureFormat.collectAsStateWithPageLifecycle()
    val recordFormat by viewModel.recordFormat.collectAsStateWithPageLifecycle()
    val recordLimit by viewModel.recordLimit.collectAsStateWithPageLifecycle()
    val previewPaused by viewModel.previewPaused.collectAsStateWithPageLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithPageLifecycle()
    val focusAssistEnabled by viewModel.focusAssistEnabled.collectAsStateWithPageLifecycle()
    val focusScore by viewModel.focusScore.collectAsStateWithPageLifecycle()
    val focusHistory by viewModel.focusHistory.collectAsStateWithPageLifecycle()
    val focusZoomCenter by viewModel.focusZoomCenter.collectAsStateWithPageLifecycle()
    val focusZoomFactor by viewModel.focusZoomFactor.collectAsStateWithPageLifecycle()
    val cameraCfa by viewModel.cameraCfa.collectAsStateWithPageLifecycle()
    val showCameraCfa by viewModel.showCameraCfaDialog.collectAsStateWithPageLifecycle()
    val cameraCfaApplying by viewModel.cameraCfaApplying.collectAsStateWithPageLifecycle()
    val cfaLiveState by viewModel.stacking.live.state.collectAsStateWithPageLifecycle()
    val cfaCalibrationState by viewModel.stacking.calibration.state.collectAsStateWithPageLifecycle()
    val cfaCalibrationMessage = stringResource(R.string.camera_cfa_busy_calibration)

    val fwConnected by viewModel.filterWheelConnected.collectAsStateWithPageLifecycle()
    val fwPosition by viewModel.filterWheelPosition.collectAsStateWithPageLifecycle()
    val fwMoving by viewModel.filterWheelMoving.collectAsStateWithPageLifecycle()
    val fwSlotNames by viewModel.filterWheelSlotNames.collectAsStateWithPageLifecycle()
    val fwInfo by viewModel.filterWheelInfo.collectAsStateWithPageLifecycle()
    val fwBidirectional by viewModel.filterWheelBidirectional.collectAsStateWithPageLifecycle()

    val eafConnected by viewModel.eafConnected.collectAsStateWithPageLifecycle()
    val eafPosition by viewModel.eafPosition.collectAsStateWithPageLifecycle()
    val eafMoving by viewModel.eafMoving.collectAsStateWithPageLifecycle()
    val eafTemperature by viewModel.eafTemperature.collectAsStateWithPageLifecycle()
    val eafInfoState by viewModel.eafInfo.collectAsStateWithPageLifecycle()
    val mountConnectionState by viewModel.mountConnectionState.collectAsStateWithPageLifecycle()
    val mountCoordinates by viewModel.mountCoordinates.collectAsStateWithPageLifecycle()
    val mountHost by viewModel.mountHost.collectAsStateWithPageLifecycle()
    val mountPort by viewModel.mountPort.collectAsStateWithPageLifecycle()
    val mountTransport by viewModel.mountTransport.collectAsStateWithPageLifecycle()
    val mountUsbDevices by viewModel.mountUsbDevices.collectAsStateWithPageLifecycle()
    val mountUsbDeviceId by viewModel.mountUsbDeviceId.collectAsStateWithPageLifecycle()
    val mountBaudRate by viewModel.mountBaudRate.collectAsStateWithPageLifecycle()
    val mountBusy by viewModel.mountBusy.collectAsStateWithPageLifecycle()
    val mountMoveStatus by viewModel.mountMoveStatus.collectAsStateWithPageLifecycle()
    val mountSlewRate by viewModel.mountSlewRate.collectAsStateWithPageLifecycle()
    val mountTrackingEnabled by viewModel.mountTrackingEnabled.collectAsStateWithPageLifecycle()
    val detectedBitDepth by viewModel.detectedBitDepth.collectAsStateWithPageLifecycle()
    val readoutMode by viewModel.readoutMode.collectAsStateWithPageLifecycle()
    val supportedReadoutModes by viewModel.supportedReadoutModes.collectAsStateWithPageLifecycle()

    // Cooling
    val coolingInfo by viewModel.coolingInfo.collectAsStateWithPageLifecycle()
    val coolerOn by viewModel.coolerOn.collectAsStateWithPageLifecycle()
    val sensorTempTenths by viewModel.sensorTempTenths.collectAsStateWithPageLifecycle()
    val targetTempTenths by viewModel.targetTempTenths.collectAsStateWithPageLifecycle()
    val coolingPowerPct by viewModel.coolingPowerPct.collectAsStateWithPageLifecycle()
    val tempHistory by viewModel.tempHistory.collectAsStateWithPageLifecycle()
    val rampStatus by viewModel.rampStatus.collectAsStateWithPageLifecycle()

    val showDevicePicker by viewModel.showDevicePicker.collectAsStateWithPageLifecycle()
    val allDevices by viewModel.cameraManager.devices.collectAsStateWithPageLifecycle()

    var showPanel by rememberSaveable { mutableStateOf(true) }
    var showOverlayPanel by rememberSaveable { mutableStateOf(true) }
    com.indigo.mobileobservatory.ui.components.ReportObservatoryImmersion(!showPanel)
    androidx.activity.compose.BackHandler(enabled = !showPanel) { showPanel = true }
    var showCenterMarker by rememberSaveable { mutableStateOf(false) }
    var viewResetTrigger by rememberSaveable { mutableIntStateOf(0) }

    if (showDevicePicker && rememberPageForeground()) {
        val connectable = viewModel.camerasAvailableToMain(allDevices)
        DevicePickerDialog(
            devices = connectable,
            emptyMessage = if (allDevices.isNotEmpty() && connectable.isEmpty()) {
                stringResource(R.string.camera_in_use_as_guide)
            } else {
                stringResource(R.string.no_camera_found)
            },
            onSelect = { entry ->
                viewModel.hideDevicePicker()
                if (connectionState is ConnectionState.Connected) {
                    viewModel.disconnectCamera()
                }
                viewModel.connectCameraBySn(entry.serialNumber)
            },
            onDismiss = { viewModel.hideDevicePicker() }
        )
    }


    val sensorWidth = imagingWidth
    if (showCameraCfa && rememberPageForeground()) CameraCfaDialog(selection = cameraCfa, rawAvailable = !pixelFormat.isRgb,
        requiresStackReset = cfaLiveState.phase !in setOf(com.indigo.mobileobservatory.stacking.LiveStackPhase.EMPTY,
            com.indigo.mobileobservatory.stacking.LiveStackPhase.CLOSED), applying = cameraCfaApplying,
        issue = { value -> if (cfaCalibrationState.busy) cfaCalibrationMessage
            else viewModel.cameraCfaChangeMessage(value) },
        onApply = { value, reset -> viewModel.applyCameraCfa(value, reset) }, onDismiss = viewModel::dismissCameraCfaSettings)
    val sensorHeight = imagingHeight
    val roiMinW = viewModel.cameraManager.activeCamera?.roiMinWidth ?: 8
    val roiMinH = viewModel.cameraManager.activeCamera?.roiMinHeight ?: 8

    Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onTargets, modifier = Modifier.weight(1f)) {
                Text(selectedTarget ?: stringResource(R.string.wf_choose_target), maxLines = 1)
            }
            TextButton(onClick = onOpenLiveStack) { Text(stringResource(R.string.stack_live)) }
            TextButton(onClick = onGuide) { Text(stringResource(R.string.guiding)) }
            if (SequenceFeature.ENABLED) TextButton(onClick = onSequence) { Text(stringResource(R.string.tab_sequence)) }
        }
        EaaSessionBar(viewModel, onOpenLiveStack)
        // Main content area
        Row(modifier = Modifier.weight(1f)) {
            // Preview area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black)
            ) {
                when (connectionState) {
                    is ConnectionState.Connected -> {
                        MainPreviewImage(
                            viewModel = viewModel,
                            flipH = flipH,
                            flipV = flipV,
                            rotation = rotation,
                            resetTrigger = viewResetTrigger,
                            focusAssistEnabled = focusAssistEnabled,
                            showCenterMarker = showCenterMarker
                        )

                        if (showOverlayPanel) {
                            OverlayPanel(
                                histogram = histogram,
                                roi = roi,
                                sensorWidth = sensorWidth,
                                sensorHeight = sensorHeight,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                            )
                        }

                        if (focusAssistEnabled) {
                            FocusAssistPreview(
                                viewModel = viewModel,
                                focusScore = focusScore,
                                focusHistory = focusHistory,
                                zoomCenter = focusZoomCenter,
                                zoomFactor = focusZoomFactor,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            SmallFloatingActionButton(
                                onClick = { viewModel.togglePreviewPause() },
                                containerColor = if (previewPaused) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Icon(
                                    if (previewPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                    contentDescription = if (previewPaused) stringResource(R.string.resume) else stringResource(R.string.pause),
                                    tint = if (previewPaused) MaterialTheme.colorScheme.onErrorContainer
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (isRecording) {
                                Surface(
                                    color = Color(0xAAFF0000),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        " ${stringResource(R.string.rec)} ",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        if (statusMessage.isNotEmpty()) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(12.dp)
                                    .widthIn(max = 440.dp),
                                color = ObservatoryTheme.colors.scrim,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    statusMessage,
                                    color = ObservatoryTheme.colors.onImage,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    maxLines = 3
                                )
                            }
                        }

                        if (exposureCountdown > 0.5f) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(80.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = exposureProgressFraction,
                                    modifier = Modifier.size(72.dp),
                                    strokeWidth = 4.dp,
                                    color = Color(0xBBFFFFFF)
                                )
                                Text(
                                    stringResource(R.string.countdown_format).format(exposureCountdown),
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    is ConnectionState.Disconnected -> {
                        DisconnectedOverlay(
                            onConnect = { viewModel.requestConnect() },
                            onPlateSolve = onPlateSolve,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    is ConnectionState.Error -> {
                        val err = (connectionState as ConnectionState.Error).message
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.ErrorOutline, stringResource(R.string.error), tint = Color(0xFFFF6666))
                            Text(err, color = Color(0xFFFF6666), fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { viewModel.requestConnect() }) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }

                    is ConnectionState.Enumerating -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                stringResource(R.string.searching_cameras),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }

                    is ConnectionState.Connecting -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                stringResource(R.string.connecting),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                CameraPreviewTools(
                    connected = connectionState is ConnectionState.Connected,
                    redNightMode = redNightMode,
                    showPanel = showPanel,
                    showOverlayPanel = showOverlayPanel,
                    focusAssistEnabled = focusAssistEnabled,
                    showCenterMarker = showCenterMarker,
                    onFitToView = { viewResetTrigger++ },
                    onRedNightModeChange = onRedNightModeChange,
                    onTogglePanel = { showPanel = !showPanel },
                    onToggleOverlay = { showOverlayPanel = !showOverlayPanel },
                    onToggleFocusAssist = { viewModel.toggleFocusAssist() },
                    onToggleCenterMarker = { showCenterMarker = !showCenterMarker },
                    onPlateSolve = onPlateSolve,
                    onOpenLibrary = { viewModel.openPlayer() },
                    onOpenLiveStack = onOpenLiveStack,
                    onCfaSettings = viewModel::showCameraCfaSettings,
                    modifier = Modifier.align(Alignment.TopEnd)
                )

            }

            // Right control panel
            if (showPanel && connectionState is ConnectionState.Connected) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 1.dp
                ) {
                    val camInfo = when (val cs = connectionState) {
                        is ConnectionState.Connected -> cs.info
                        else -> null
                    }
                    val camName = camInfo?.name ?: "—"
                    ControlPanel(
                        onOpenLiveStack = onOpenLiveStack,
                        exposureUs = exposureUs,
                        gain = gain,
                        gainWriteInProgress = gainWriteInProgress,
                        usbBandwidth = usbBandwidth,
                        usbBandwidthRange = usbBandwidthRange,
                        pixelFormat = pixelFormat,
                        supportedPixelFormats = supportedPixelFormats,
                        roi = roi,
                        autoStretch = autoStretch,
                        autoExposureMode = autoExposureMode,
                        fps = fps,
                        histogram = histogram,
                        sensorWidth = sensorWidth,
                        sensorHeight = sensorHeight,
                        flipH = flipH,
                        flipV = flipV,
                        rotation = rotation,
                        longExposureEnabled = longExposureEnabled,
                        exposureMinFlow = viewModel.exposureUiMinUs,
                        exposureMaxFlow = viewModel.exposureUiMaxUs,
                        gainCapability = gainCapability ?: GainCapability(
                            min = 0f,
                            max = 0f,
                            defaultValue = 0f
                        ),
                        longExposureProgress = longExposureProgress,
                        cameraInfo = camInfo,
                        cameraName = camName,
                        showMountControls = true,
                        fwConnected = fwConnected,
                        fwPosition = fwPosition,
                        fwMoving = fwMoving,
                        fwSlotNames = fwSlotNames,
                        fwSlotCount = fwInfo?.slotCount ?: 0,
                        fwBidirectional = fwBidirectional,
                        onFwBidirectionalToggle = { viewModel.setFilterWheelBidirectional(it) },
                        onFwEditNames = { onDeviceSettings("filter") },
                        onEafSettings = { onDeviceSettings("focuser") },
                        showLegacyFilterSettings = false,
                        eafConnected = eafConnected,
                        eafPosition = eafPosition,
                        eafMoving = eafMoving,
                        eafTemperature = eafTemperature,
                        eafInfo = eafInfoState,
                        mountConnectionState = mountConnectionState,
                        mountCoordinates = mountCoordinates,
                        mountHost = mountHost,
                        mountPort = mountPort,
                        mountTransport = mountTransport,
                        mountUsbDevices = mountUsbDevices,
                        mountUsbDeviceId = mountUsbDeviceId,
                        mountBaudRate = mountBaudRate,
                        mountBusy = mountBusy,
                        mountMoveStatus = mountMoveStatus,
                        mountSlewRate = mountSlewRate,
                        mountTrackingEnabled = mountTrackingEnabled,
                        detectedBitDepth = detectedBitDepth,
                        onExposureChange = { viewModel.setExposure(it) },
                        onGainChange = { viewModel.setGain(it) },
                        onUsbBandwidthChange = { viewModel.setUsbBandwidth(it) },
                        onPixelFormatChange = { viewModel.setPixelFormat(it) },
                        readoutMode = readoutMode,
                        supportedReadoutModes = supportedReadoutModes,
                        onReadoutModeChange = { viewModel.setReadoutMode(it) },
                        onRoiChange = { viewModel.setRoi(it) },
                        onResetRoi = { viewModel.resetRoi() },
                        onAutoStretchToggle = { viewModel.setAutoStretch(it) },
                        onAutoExposureModeChange = { viewModel.setAutoExposureMode(it) },
                        awbMode = awbMode.ordinal,
                        isBayerCamera = (interpretedFrameFormat ?: pixelFormat).isBayer,
                        onAwbModeChange = { viewModel.setAwbMode(FrameProcessor.AwbMode.entries[it]) },
                        onFlipH = { viewModel.toggleFlipH() },
                        onFlipV = { viewModel.toggleFlipV() },
                        onRotation = { viewModel.setRotation(it) },
                        onToggleLongExposure = { viewModel.toggleLongExposure() },
                        roiMinWidth = roiMinW,
                        roiMinHeight = roiMinH,
                        showHostRoi = supportsHostRoi,
                        binning = binning,
                        binningUsesHardware = binningUsesHardware,
                        binningUsesSoftware = binningUsesSoftware,
                        onBinningChange = { viewModel.setBinning(it) },
                        coolingInfo = coolingInfo,
                        coolerOn = coolerOn,
                        sensorTempTenths = sensorTempTenths,
                        targetTempTenths = targetTempTenths,
                        coolingPowerPct = coolingPowerPct,
                        tempHistory = tempHistory,
                        rampStatus = rampStatus,
                        onCoolerToggle = { viewModel.setCoolerOn(it) },
                        onTargetTempChange = { viewModel.setTargetTemperature(it) },
                        onStartCoolDown = { target, dur -> viewModel.startCoolDown(target, dur) },
                        onStartWarmUp = { dur -> viewModel.startWarmUp(dur) },
                        onStopRamp = { viewModel.stopRamp() },
                        onSwitchCamera = { viewModel.requestConnect() },
                        onScanDevices = { viewModel.requestConnect() },
                        onFwSetPosition = { viewModel.setFilterWheelPosition(it) },
                        onFwReset = { viewModel.resetFilterWheel() },
                        onEafMoveTo = { viewModel.eafMoveTo(it) },
                        onEafMoveRelative = { viewModel.eafMoveRelative(it) },
                        onEafHalt = { viewModel.eafHalt() },
                        onEafSetZero = { viewModel.eafSetZero() },
                        onEafSetDirection = { viewModel.eafSetDirection(it) },
                        onEafSetFineStep = { viewModel.eafSetFineStep(it) },
                        onEafSetMaxStep = { viewModel.eafSetMaxStep(it) },
                        onEafSetBacklash = { steps, dir -> viewModel.eafSetBacklash(steps, dir) },
                        onMountHostChange = { viewModel.setMountHost(it) },
                        onMountPortChange = { viewModel.setMountPort(it) },
                        onMountTransportChange = { viewModel.setMountTransport(it) },
                        onMountUsbDeviceChange = { viewModel.setMountUsbDevice(it) },
                        onMountBaudRateChange = { viewModel.setMountBaudRate(it) },
                        onMountScanUsb = { viewModel.scanMountUsbDevices() },
                        onMountConnect = { viewModel.connectMount() },
                        onMountDisconnect = { viewModel.disconnectMount() },
                        onMountReadCoordinates = { viewModel.readMountCoordinates() },
                        onMountManualMoveStart = { viewModel.startMountManualMove(it) },
                        onMountManualMoveStop = { viewModel.stopMountManualMove(it) },
                        onMountStopAll = { viewModel.stopMountMotion() },
                        onMountSlewRateChange = { viewModel.setMountSlewRate(it) },
                        onMountTracking = { viewModel.setMountTracking(it) },
                        onMountHome = { viewModel.goMountHome() },
                        onMountSetHome = { viewModel.setMountHomeHere() }
                    )
                }
            }
        }

        // Bottom record bar
        if (connectionState is ConnectionState.Connected) {
            RecordBar(
                isRecording = isRecording,
                frameCount = recordingFrameCount,
                durationMs = recordingDurationMs,
                bytesWritten = recordingBytes,
                targetName = targetName,
                captureFormatLabel = captureFormat.name,
                recordFormatLabel = recordFormat.name,
                recordLimit = recordLimit,
                onTargetNameChange = { viewModel.setTargetName(it) },
                onStartRecord = { viewModel.startRecording() },
                onStopRecord = { viewModel.stopRecording() },
                onCapture = { viewModel.capture() },
                onSelectCaptureFormat = { viewModel.setCaptureFormat(it) },
                onSelectRecordFormat = { viewModel.setRecordFormat(it) },
                onSelectRecordLimit = { viewModel.setRecordLimit(it) }
            )
        }
            }

}

@Composable
internal fun MountMotionStopPopup(
    state: MountMotionState,
    onStop: () -> Unit
) {
    if (!state.showsGlobalStop) return
    Popup(
        alignment = Alignment.BottomStart,
        properties = PopupProperties(focusable = false)
    ) {
        MountMotionStopBanner(
            state = state,
            onStop = onStop,
            modifier = Modifier.padding(12.dp)
        )
    }
}
@Composable
private fun DisconnectedOverlay(
    onConnect: () -> Unit,
    onPlateSolve: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Default.CameraAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Text(
            stringResource(R.string.no_camera_connected),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.connect_camera_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Button(
            onClick = onConnect,
            modifier = Modifier.height(48.dp)
        ) {
            Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.scan_devices))
        }
        OutlinedButton(
            onClick = onPlateSolve,
            modifier = Modifier.height(48.dp)
        ) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.plate_solve_image))
        }
    }
}

@Composable
private fun DevicePickerDialog(
    devices: List<DeviceEntry>,
    onSelect: (DeviceEntry) -> Unit,
    onDismiss: () -> Unit,
    emptyMessage: String
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_camera)) },
        text = {
            if (devices.isEmpty()) {
                Text(
                    emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(devices) { entry ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(entry) },
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 2.dp
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(R.string.sn_label, entry.serialNumber),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun FilterWheelEditDialog(
    slotNames: List<String>,
    currentSlotCount: Int,
    onSave: (List<String>, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var editedSlotCount by remember { mutableIntStateOf(currentSlotCount) }
    var editedNames by remember { mutableStateOf(slotNames.toMutableList()) }

    LaunchedEffect(editedSlotCount) {
        if (editedSlotCount != editedNames.size) {
            editedNames = List(editedSlotCount) { i ->
                editedNames.getOrElse(i) {
                    listOf("L", "R", "G", "B", "R+", "UV", "CH4", "R+610").getOrElse(i) { "${i + 1}" }
                }
            }.toMutableList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_filter_wheel)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.slot_count), style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { if (editedSlotCount > 1) editedSlotCount-- },
                                enabled = editedSlotCount > 1
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.decrease))
                            }
                            Text("$editedSlotCount", style = MaterialTheme.typography.bodyLarge)
                            IconButton(
                                onClick = { if (editedSlotCount < 16) editedSlotCount++ },
                                enabled = editedSlotCount < 16
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.increase))
                            }
                        }
                    }
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                }
                items(editedNames.size) { index ->
                    OutlinedTextField(
                        value = editedNames[index],
                        onValueChange = { newName ->
                            editedNames = editedNames.toMutableList().also {
                                it[index] = newName
                            }
                        },
                        label = { Text(stringResource(R.string.slot_n, index + 1)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(editedNames, editedSlotCount) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}


@Composable
private fun MainPreviewImage(
    viewModel: CameraViewModel,
    flipH: Boolean,
    flipV: Boolean,
    rotation: Int,
    resetTrigger: Int,
    focusAssistEnabled: Boolean,
    showCenterMarker: Boolean
) {
    val bitmap by viewModel.previewBitmap.collectAsStateWithPageLifecycle()
    val stackBitmap by viewModel.stacking.livePreview.collectAsStateWithPageLifecycle()
    val showStack by viewModel.stacking.showLiveStack.collectAsStateWithPageLifecycle()
    LivePreview(
        bitmap = if (showStack && stackBitmap != null) stackBitmap else bitmap,
        flipH = flipH,
        flipV = flipV,
        rotationDeg = rotation,
        resetTrigger = resetTrigger,
        showCenterMarker = showCenterMarker,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (focusAssistEnabled) Modifier.pointerInput(focusAssistEnabled) {
                    detectTapGestures { offset ->
                        viewModel.setFocusZoomCenter(
                            (offset.x / size.width).coerceIn(0f, 1f),
                            (offset.y / size.height).coerceIn(0f, 1f)
                        )
                    }
                } else Modifier
            )
    )
}

@Composable
private fun FocusAssistPreview(
    viewModel: CameraViewModel,
    focusScore: Float?,
    focusHistory: List<Float>,
    zoomCenter: Pair<Float, Float>,
    zoomFactor: Float,
    modifier: Modifier = Modifier
) {
    val bitmap by viewModel.previewBitmap.collectAsStateWithPageLifecycle()
    FocusAssistOverlay(
        bitmap = bitmap,
        focusScore = focusScore,
        focusHistory = focusHistory,
        zoomCenter = zoomCenter,
        zoomFactor = zoomFactor,
        onZoomFactorChange = viewModel::setFocusZoomFactor,
        onDismiss = viewModel::toggleFocusAssist,
        modifier = modifier
    )
}
