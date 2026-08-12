package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.camera.DeviceEntry
import com.indigo.mobileobservatory.camera.FrameProcessor
import com.indigo.mobileobservatory.ui.components.*
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.mount.MountMotionState
import com.indigo.mobileobservatory.ui.viewmodel.CaptureFormat
import com.indigo.mobileobservatory.ui.viewmodel.RecordFormat
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.AppOrientationMode
import com.indigo.mobileobservatory.ui.RememberAppOrientation

private enum class MainControlTab {
    CAMERA,
    MOUNT,
    STAR_MAP,
    ACCESSORIES
}

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
    redNightMode: Boolean = false,
    onRedNightModeChange: (Boolean) -> Unit = {}
) {
    val showPlayer by viewModel.showPlayer.collectAsState()
    var showPlateSolve by remember { mutableStateOf(false) }
    var showPolarAlign by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val phoneNav = rememberPhonePlateSolveNavState()
    var selectedTab by rememberSaveable { mutableStateOf(MainControlTab.CAMERA) }
    val globalMountMotionState by viewModel.mountMotionState.collectAsState()

    val orientationMode = when {
        showGuide || showPlayer -> AppOrientationMode.LANDSCAPE
        selectedTab == MainControlTab.CAMERA -> AppOrientationMode.LANDSCAPE
        else -> AppOrientationMode.PORTRAIT
    }
    RememberAppOrientation(orientationMode)

    MountMotionStopPopup(
        state = globalMountMotionState,
        onStop = viewModel::stopMountMotion
    )

    if (phoneNav.destination != null) {
        PhonePlateSolveScreens(phoneNav)
        return
    }

    if (showPlateSolve) {
        val mountCoordinates by viewModel.mountCoordinates.collectAsState()
        PlateSolveScreen(
            mountCoordinates = mountCoordinates,
            onBack = { showPlateSolve = false }
        )
        return
    }

    if (showPolarAlign) {
        val mountCoordinates by viewModel.mountCoordinates.collectAsState()
        val mountSite by viewModel.mountSite.collectAsState()
        val mountBusy by viewModel.mountBusy.collectAsState()
        val mountMoveStatus by viewModel.mountMoveStatus.collectAsState()
        PolarAlignmentScreen(
            mountCoordinates = mountCoordinates,
            mountSite = mountSite,
            mountBusy = mountBusy,
            mountMoveStatus = mountMoveStatus,
            onReadMountSite = { viewModel.readMountSite() },
            onSyncPhoneSiteToMount = { lat, lon -> viewModel.syncPhoneSiteToMount(lat, lon) },
            onMoveMountRaBy = { distance, east, rate -> viewModel.moveMountRaBy(distance, east, rate) },
            onStopMountRaMove = { viewModel.stopMountRaMove() },
            onCaptureFits = { viewModel.captureTempFitsForPlateSolve() },
            onBack = { showPolarAlign = false }
        )
        return
    }

    if (showGuide) {
        GuideScreen(
            viewModel = viewModel,
            onBack = { showGuide = false }
        )
        return
    }

    if (showPlayer) {
        val mountCoordinates by viewModel.mountCoordinates.collectAsState()
        PlayerScreen(
            recordingsDir = viewModel.getRecordingsDir(),
            capturesDir = viewModel.getCapturesDir(),
            mountCoordinates = mountCoordinates,
            onBack = { viewModel.closePlayer() }
        )
        return
    }

    if (showSettings) {
        SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
        return
    }

    val connectionState by viewModel.connectionState.collectAsState()

    val exposureUs by viewModel.exposureUs.collectAsState()
    val gain by viewModel.gain.collectAsState()
    val pixelFormat by viewModel.pixelFormat.collectAsState()
    val supportedPixelFormats by viewModel.supportedPixelFormats.collectAsState()
    val roi by viewModel.roi.collectAsState()
    val autoStretch by viewModel.autoStretch.collectAsState()
    val flipH by viewModel.flipH.collectAsState()
    val flipV by viewModel.flipV.collectAsState()
    val rotation by viewModel.rotation.collectAsState()
    val longExposureEnabled by viewModel.longExposureEnabled.collectAsState()
    val longExposureProgress by viewModel.longExposureProgress.collectAsState()
    val exposureCountdown by viewModel.exposureCountdown.collectAsState()
    val exposureProgressFraction by viewModel.exposureProgressFraction.collectAsState()
    val autoExposureMode by viewModel.autoExposureMode.collectAsState()
    val awbMode by viewModel.awbMode.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val histogram by viewModel.histogram.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingFrameCount by viewModel.recordingFrameCount.collectAsState()
    val recordingDurationMs by viewModel.recordingDurationMs.collectAsState()
    val recordingBytes by viewModel.recordingBytes.collectAsState()
    val targetName by viewModel.targetName.collectAsState()
    val captureFormat by viewModel.captureFormat.collectAsState()
    val recordFormat by viewModel.recordFormat.collectAsState()
    val recordLimit by viewModel.recordLimit.collectAsState()
    val previewPaused by viewModel.previewPaused.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val focusAssistEnabled by viewModel.focusAssistEnabled.collectAsState()
    val focusScore by viewModel.focusScore.collectAsState()
    val focusHistory by viewModel.focusHistory.collectAsState()
    val focusZoomCenter by viewModel.focusZoomCenter.collectAsState()
    val focusZoomFactor by viewModel.focusZoomFactor.collectAsState()

    val fwConnected by viewModel.filterWheelConnected.collectAsState()
    val fwPosition by viewModel.filterWheelPosition.collectAsState()
    val fwMoving by viewModel.filterWheelMoving.collectAsState()
    val fwSlotNames by viewModel.filterWheelSlotNames.collectAsState()
    val fwInfo by viewModel.filterWheelInfo.collectAsState()
    val fwBidirectional by viewModel.filterWheelBidirectional.collectAsState()
    var showFwEditDialog by remember { mutableStateOf(false) }

    val eafConnected by viewModel.eafConnected.collectAsState()
    val eafPosition by viewModel.eafPosition.collectAsState()
    val eafMoving by viewModel.eafMoving.collectAsState()
    val eafTemperature by viewModel.eafTemperature.collectAsState()
    val eafInfoState by viewModel.eafInfo.collectAsState()
    val mountConnectionState by viewModel.mountConnectionState.collectAsState()
    val mountCoordinates by viewModel.mountCoordinates.collectAsState()
    val mountHost by viewModel.mountHost.collectAsState()
    val mountPort by viewModel.mountPort.collectAsState()
    val mountTransport by viewModel.mountTransport.collectAsState()
    val mountUsbDevices by viewModel.mountUsbDevices.collectAsState()
    val mountUsbDeviceId by viewModel.mountUsbDeviceId.collectAsState()
    val mountBaudRate by viewModel.mountBaudRate.collectAsState()
    val mountBusy by viewModel.mountBusy.collectAsState()
    val mountMoveStatus by viewModel.mountMoveStatus.collectAsState()
    val mountSlewRate by viewModel.mountSlewRate.collectAsState()
    val mountTrackingEnabled by viewModel.mountTrackingEnabled.collectAsState()
    val precisionGotoProgress by viewModel.precisionGotoProgress.collectAsState()
    val detectedBitDepth by viewModel.detectedBitDepth.collectAsState()
    val readoutMode by viewModel.readoutMode.collectAsState()
    val supportedReadoutModes by viewModel.supportedReadoutModes.collectAsState()

    // Cooling
    val coolingInfo by viewModel.coolingInfo.collectAsState()
    val coolerOn by viewModel.coolerOn.collectAsState()
    val sensorTempTenths by viewModel.sensorTempTenths.collectAsState()
    val targetTempTenths by viewModel.targetTempTenths.collectAsState()
    val coolingPowerPct by viewModel.coolingPowerPct.collectAsState()
    val tempHistory by viewModel.tempHistory.collectAsState()
    val rampStatus by viewModel.rampStatus.collectAsState()

    val showDevicePicker by viewModel.showDevicePicker.collectAsState()
    val allDevices by viewModel.cameraManager.devices.collectAsState()

    var showPanel by remember { mutableStateOf(true) }
    var showRoiOverlay by remember { mutableStateOf(false) }
    var showOverlayPanel by remember { mutableStateOf(true) }
    var viewResetTrigger by remember { mutableIntStateOf(0) }

    if (showDevicePicker) {
        DevicePickerDialog(
            devices = allDevices,
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

    if (showFwEditDialog && fwInfo != null) {
        FilterWheelEditDialog(
            slotNames = fwSlotNames,
            currentSlotCount = fwInfo?.slotCount ?: fwSlotNames.size,
            onSave = { newNames, newSlotCount ->
                if (newSlotCount != fwInfo?.slotCount) {
                    viewModel.setFilterWheelSlotCount(newSlotCount)
                }
                newNames.forEachIndexed { index, name ->
                    viewModel.setFilterWheelSlotName(index, name)
                }
                showFwEditDialog = false
            },
            onDismiss = { showFwEditDialog = false }
        )
    }

    val sensorWidth = when (val cs = connectionState) {
        is ConnectionState.Connected -> cs.info.sensorWidth
        else -> 1920
    }
    val sensorHeight = when (val cs = connectionState) {
        is ConnectionState.Connected -> cs.info.sensorHeight
        else -> 1200
    }
    val roiMinW = viewModel.cameraManager.activeCamera?.roiMinWidth ?: 8
    val roiMinH = viewModel.cameraManager.activeCamera?.roiMinHeight ?: 8

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (selectedTab != MainControlTab.STAR_MAP || !BuildConfig.STELLARIUM_ENABLED) {
            TabRow(
                selectedTabIndex = selectedTab.ordinal,
                modifier = Modifier.height(48.dp)
            ) {
                Tab(
                    selected = selectedTab == MainControlTab.CAMERA,
                    onClick = { selectedTab = MainControlTab.CAMERA },
                    text = {
                        Text(
                            stringResource(R.string.tab_camera),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
                Tab(
                    selected = selectedTab == MainControlTab.MOUNT,
                    onClick = { selectedTab = MainControlTab.MOUNT },
                    text = {
                        Text(
                            stringResource(R.string.tab_mount),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
                Tab(
                    selected = selectedTab == MainControlTab.STAR_MAP,
                    onClick = { selectedTab = MainControlTab.STAR_MAP },
                    text = {
                        Text(
                            stringResource(R.string.star_map),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
                Tab(
                    selected = selectedTab == MainControlTab.ACCESSORIES,
                    onClick = { selectedTab = MainControlTab.ACCESSORIES },
                    text = {
                        Text(
                            stringResource(R.string.tab_accessories),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
                Tab(
                    selected = false,
                    onClick = { showSettings = true },
                    text = {
                        Text(
                            stringResource(R.string.settings),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
                }
        }

        when (selectedTab) {
            MainControlTab.MOUNT -> {
                MountControlScreen(
                    viewModel = viewModel,
                    onOpenStarMap = { selectedTab = MainControlTab.STAR_MAP },
                    onOpenPolarAlignment = { showPolarAlign = true },
                    onOpenGuiding = { showGuide = true },
                    onOpenPushTo = { phoneNav.destination = PhonePlateSolveDestination.PUSH_TO },
                    onOpenTargetLibrary = { phoneNav.destination = PhonePlateSolveDestination.TARGET_LIBRARY },
                    modifier = Modifier.weight(1f)
                )
            }
            MainControlTab.STAR_MAP -> {
                if (BuildConfig.STELLARIUM_ENABLED) {
                    val mountSite by viewModel.mountSite.collectAsState()
                    StarMapScreen(
                        mountCoordinates = mountCoordinates,
                        mountSite = mountSite,
                        mountConnected = mountConnectionState is
                            com.indigo.mobileobservatory.mount.MountConnectionState.Connected,
                        mountBusy = mountBusy,
                        mountSlewRate = mountSlewRate,
                        precisionGotoProgress = precisionGotoProgress,
                        cameraPixelSizeUm = (connectionState as? ConnectionState.Connected)
                            ?.info?.pixelSizeUm,
                        cameraFrameWidthPx = roi.width,
                        cameraFrameHeightPx = roi.height,
                        onGoto = { target ->
                            viewModel.gotoMountTarget(
                                name = target.name,
                                raHours = target.raHours,
                                decDeg = target.decDegrees
                            )
                        },
                        onSync = { target ->
                            viewModel.syncMountToTarget(
                                name = target.name,
                                raHours = target.raHours,
                                decDeg = target.decDegrees
                            )
                        },
                        onPrecisionGoto = { target ->
                            viewModel.startPrecisionGoto(
                                name = target.name,
                                raHours = target.raHours,
                                decDeg = target.decDegrees
                            )
                        },
                        onSlewRateChange = viewModel::setMountSlewRate,
                        onManualMoveStart = viewModel::startMountManualMove,
                        onManualMoveStop = { viewModel.stopMountManualMove(it) },
                        onStopMount = viewModel::stopMountMotion,
                        onBack = { selectedTab = MainControlTab.MOUNT }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(Modifier.padding(24.dp)) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    stringResource(R.string.star_map),
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(stringResource(R.string.stellarium_assets_missing))
                                Text(stringResource(R.string.stellarium_build_hint),
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
            MainControlTab.ACCESSORIES -> {
                AccessoriesScreen(
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f),
                    onOpenCamera = { selectedTab = MainControlTab.CAMERA },
                    onOpenMount = { selectedTab = MainControlTab.MOUNT }
                )
            }
            MainControlTab.CAMERA -> Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
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
                            focusAssistEnabled = focusAssistEnabled
                        )

                        if (showRoiOverlay) {
                            RoiOverlay(
                                roi = roi,
                                sensorWidth = sensorWidth,
                                sensorHeight = sensorHeight,
                                onRoiChange = { viewModel.setRoi(it) }
                            )
                        }

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
                                    .padding(8.dp),
                                color = Color(0xAA000000),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    statusMessage,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
                            onPlateSolve = { showPlateSolve = true },
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
                            Button(onClick = { viewModel.connectCamera() }) {
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

                // Toolbar at top-right
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (connectionState is ConnectionState.Connected) {
                        SmallFloatingActionButton(
                            onClick = { viewResetTrigger++ },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Icon(
                                Icons.Default.FitScreen,
                                stringResource(R.string.fit_to_view),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    SmallFloatingActionButton(
                        onClick = { onRedNightModeChange(!redNightMode) },
                        containerColor = if (redNightMode) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Icon(
                            if (redNightMode) Icons.Default.Nightlight else Icons.Default.NightlightRound,
                            stringResource(R.string.red_night_mode),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (connectionState is ConnectionState.Connected) {
                        SmallFloatingActionButton(
                            onClick = { showPanel = !showPanel },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Icon(
                                if (showPanel) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                                stringResource(R.string.toggle_panel),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        SmallFloatingActionButton(
                            onClick = { showRoiOverlay = !showRoiOverlay },
                            containerColor = if (showRoiOverlay) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Icon(
                                Icons.Default.Crop,
                                stringResource(R.string.toggle_roi),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        SmallFloatingActionButton(
                            onClick = { showOverlayPanel = !showOverlayPanel },
                            containerColor = if (showOverlayPanel) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Icon(
                                if (showOverlayPanel) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                stringResource(R.string.toggle_overlay),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        SmallFloatingActionButton(
                            onClick = { viewModel.toggleFocusAssist() },
                            containerColor = if (focusAssistEnabled) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Icon(
                                Icons.Default.CenterFocusWeak,
                                stringResource(R.string.focus_assist),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    SmallFloatingActionButton(
                        onClick = { viewModel.openPlayer() },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Icon(
                            Icons.Default.VideoLibrary,
                            stringResource(R.string.video_library),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    SmallFloatingActionButton(
                        onClick = { phoneNav.destination = PhonePlateSolveDestination.PHONE_CAMERA_DEBUG },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Icon(
                            Icons.Default.PhoneAndroid,
                            stringResource(R.string.phone_camera_debug),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (connectionState is ConnectionState.Connected) {
                        SmallFloatingActionButton(
                            onClick = { showPlateSolve = true },
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Icon(
                                Icons.Default.Search,
                                stringResource(R.string.plate_solve),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
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
                        exposureUs = exposureUs,
                        gain = gain,
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
                        gainMax = viewModel.getGainMax(),
                        longExposureProgress = longExposureProgress,
                        cameraInfo = camInfo,
                        cameraName = camName,
                        fwConnected = fwConnected,
                        fwPosition = fwPosition,
                        fwMoving = fwMoving,
                        fwSlotNames = fwSlotNames,
                        fwSlotCount = fwInfo?.slotCount ?: 0,
                        fwBidirectional = fwBidirectional,
                        onFwBidirectionalToggle = { viewModel.setFilterWheelBidirectional(it) },
                        onFwEditNames = { showFwEditDialog = true },
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
                        onPixelFormatChange = { viewModel.setPixelFormat(it) },
                        readoutMode = readoutMode,
                        supportedReadoutModes = supportedReadoutModes,
                        onReadoutModeChange = { viewModel.setReadoutMode(it) },
                        onRoiChange = { viewModel.setRoi(it) },
                        onResetRoi = { viewModel.resetRoi() },
                        onAutoStretchToggle = { viewModel.setAutoStretch(it) },
                        onAutoExposureModeChange = { viewModel.setAutoExposureMode(it) },
                        awbMode = awbMode.ordinal,
                        isBayerCamera = pixelFormat.isBayer,
                        onAwbModeChange = { viewModel.setAwbMode(FrameProcessor.AwbMode.entries[it]) },
                        onFlipH = { viewModel.toggleFlipH() },
                        onFlipV = { viewModel.toggleFlipV() },
                        onRotation = { viewModel.setRotation(it) },
                        onToggleLongExposure = { viewModel.toggleLongExposure() },
                        roiMinWidth = roiMinW,
                        roiMinHeight = roiMinH,
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
                        onSwitchCamera = {
                            viewModel.cameraManager.enumerateDevices()
                            viewModel.showDevicePicker()
                        },
                        onScanDevices = {
                            viewModel.cameraManager.enumerateDevices()
                            viewModel.showDevicePicker()
                        },
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
    }
}

@Composable
private fun MountMotionStopPopup(
    state: MountMotionState,
    onStop: () -> Unit
) {
    if (!state.isActive) return
    Popup(
        alignment = Alignment.BottomStart,
        properties = PopupProperties(focusable = false)
    ) {
        Card(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(state.label, style = MaterialTheme.typography.labelLarge)
                Button(
                    onClick = onStop,
                    enabled = !state.isStopping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    if (state.isStopping) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        Icon(Icons.Default.Stop, contentDescription = null)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.isStopping) "STOPPING" else "STOP")
                }
            }
        }
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
        Button(onClick = onConnect) {
            Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.connect))
        }
        OutlinedButton(onClick = onPlateSolve) {
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
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_camera)) },
        text = {
            if (devices.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
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
    focusAssistEnabled: Boolean
) {
    val bitmap by viewModel.previewBitmap.collectAsState()
    LivePreview(
        bitmap = bitmap,
        flipH = flipH,
        flipV = flipV,
        rotationDeg = rotation,
        resetTrigger = resetTrigger,
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
    val bitmap by viewModel.previewBitmap.collectAsState()
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
