package com.indigo.mobileobservatory.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.camera.toupcam.CoolingInfo
import com.indigo.mobileobservatory.camera.toupcam.EAFInfo
import com.indigo.mobileobservatory.camera.toupcam.TempHistoryPoint
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.mount.MountConnectionState
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.MountSlewRate
import com.indigo.mobileobservatory.mount.MountTransportType
import com.indigo.mobileobservatory.mount.MountUsbDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlPanel(
    exposureUs: Float,
    gain: Float,
    pixelFormat: PixelFormat,
    supportedPixelFormats: List<PixelFormat>,
    roi: Roi,
    autoStretch: Boolean,
    autoExposureMode: AutoExposureMode,
    fps: Float,
    histogram: HistogramData?,
    sensorWidth: Int,
    sensorHeight: Int,
    flipH: Boolean,
    flipV: Boolean,
    rotation: Int,
    longExposureEnabled: Boolean,
    exposureMax: Float,
    gainMax: Float = 24f,
    longExposureProgress: String,
    // Device section params
    cameraInfo: CameraInfo?,
    cameraName: String,
    showAccessoryControls: Boolean = false,
    showMountControls: Boolean = false,
    fwConnected: Boolean,
    fwPosition: Int,
    fwMoving: Boolean,
    fwSlotNames: List<String>,
    fwSlotCount: Int,
    fwBidirectional: Boolean,
    onFwBidirectionalToggle: (Boolean) -> Unit,
    onFwEditNames: () -> Unit,
    eafConnected: Boolean,
    eafPosition: Int,
    eafMoving: Boolean,
    eafTemperature: Float?,
    eafInfo: EAFInfo?,
    mountConnectionState: MountConnectionState,
    mountCoordinates: MountCoordinates?,
    mountHost: String,
    mountPort: String,
    mountTransport: MountTransportType,
    mountUsbDevices: List<MountUsbDevice>,
    mountUsbDeviceId: Int,
    mountBaudRate: String,
    mountBusy: Boolean,
    mountMoveStatus: String = "",
    mountSlewRate: MountSlewRate = MountSlewRate.DEFAULT,
    mountTrackingEnabled: Boolean = false,
    detectedBitDepth: Int,
    onExposureChange: (Float) -> Unit,
    onGainChange: (Float) -> Unit,
    onPixelFormatChange: (PixelFormat) -> Unit,
    readoutMode: ReadoutMode = ReadoutMode.NORMAL,
    supportedReadoutModes: List<ReadoutMode> = listOf(ReadoutMode.NORMAL),
    onReadoutModeChange: (ReadoutMode) -> Unit = {},
    onRoiChange: (Roi) -> Unit,
    onResetRoi: () -> Unit,
    onAutoStretchToggle: (Boolean) -> Unit,
    onAutoExposureModeChange: (AutoExposureMode) -> Unit,
    awbMode: Int = 0,
    isBayerCamera: Boolean = false,
    onAwbModeChange: (Int) -> Unit = {},
    onFlipH: () -> Unit,
    onFlipV: () -> Unit,
    onRotation: (Int) -> Unit,
    onToggleLongExposure: () -> Unit,
    roiMinWidth: Int = 8,
    roiMinHeight: Int = 8,
    // Cooling params
    coolingInfo: CoolingInfo?,
    coolerOn: Boolean,
    sensorTempTenths: Int,
    targetTempTenths: Int,
    coolingPowerPct: Float,
    tempHistory: List<TempHistoryPoint>,
    rampStatus: String,
    onCoolerToggle: (Boolean) -> Unit,
    onTargetTempChange: (Int) -> Unit,
    onStartCoolDown: (targetTenths: Int, durationMin: Int) -> Unit,
    onStartWarmUp: (durationMin: Int) -> Unit,
    onStopRamp: () -> Unit,
    onSwitchCamera: () -> Unit,
    onScanDevices: () -> Unit,
    onFwSetPosition: (Int) -> Unit,
    onFwReset: () -> Unit,
    onEafMoveTo: (Int) -> Unit,
    onEafMoveRelative: (Int) -> Unit,
    onEafHalt: () -> Unit,
    onEafSetZero: () -> Unit,
    onEafSetDirection: (Int) -> Unit = {},
    onEafSetFineStep: (Int) -> Unit = {},
    onEafSetMaxStep: (Int) -> Unit = {},
    onEafSetBacklash: (Int, Int) -> Unit = { _, _ -> },
    onMountHostChange: (String) -> Unit,
    onMountPortChange: (String) -> Unit,
    onMountTransportChange: (MountTransportType) -> Unit,
    onMountUsbDeviceChange: (Int) -> Unit,
    onMountBaudRateChange: (String) -> Unit,
    onMountScanUsb: () -> Unit,
    onMountConnect: () -> Unit,
    onMountDisconnect: () -> Unit,
    onMountReadCoordinates: () -> Unit,
    onMountManualMoveStart: (MountDirection) -> Unit = {},
    onMountManualMoveStop: (MountDirection) -> Unit = {},
    onMountStopAll: () -> Unit = {},
    onMountSlewRateChange: (MountSlewRate) -> Unit = {},
    onMountTracking: (Boolean) -> Unit = {},
    onMountHome: () -> Unit = {},
    onMountSetHome: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var deviceExpanded by remember { mutableStateOf(true) }
    var captureExpanded by remember { mutableStateOf(true) }
    var imageExpanded by remember { mutableStateOf(true) }
    var roiExpanded by remember { mutableStateOf(false) }
    val decreaseCoarseFocusDescription = stringResource(R.string.decrease_coarse_focus)
    val decreaseFineFocusDescription = stringResource(R.string.decrease_fine_focus)
    val haltOrZeroFocusDescription = stringResource(R.string.halt_or_zero_focus)
    val increaseFineFocusDescription = stringResource(R.string.increase_fine_focus)
    val increaseCoarseFocusDescription = stringResource(R.string.increase_coarse_focus)
    val decreaseCoolingDurationDescription = stringResource(R.string.decrease_cooling_duration)
    val increaseCoolingDurationDescription = stringResource(R.string.increase_cooling_duration)
    val decreaseWarmingDurationDescription = stringResource(R.string.decrease_warming_duration)
    val increaseWarmingDurationDescription = stringResource(R.string.increase_warming_duration)
    val decreaseRoiXDescription = stringResource(R.string.decrease_roi_x)
    val increaseRoiXDescription = stringResource(R.string.increase_roi_x)
    val decreaseRoiYDescription = stringResource(R.string.decrease_roi_y)
    val increaseRoiYDescription = stringResource(R.string.increase_roi_y)

    Column(
        modifier = modifier
            .width(280.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // FPS display
        Text(
            stringResource(R.string.fps_format).format(fps),
            style = MaterialTheme.typography.titleSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // ── Device ───────
        SectionHeader(stringResource(R.string.section_device), deviceExpanded) { deviceExpanded = !deviceExpanded }
        AnimatedVisibility(visible = deviceExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Camera
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.camera), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(cameraName, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = onSwitchCamera,
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.switch_camera), fontSize = 10.sp)
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                if (showAccessoryControls) {
                // Filter Wheel
                Text(stringResource(R.string.filter_wheel), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                var fwDropdownExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(
                        onClick = { fwDropdownExpanded = true },
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            if (fwConnected) stringResource(R.string.connected) else stringResource(R.string.not_connected),
                            fontSize = 10.sp, modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = fwDropdownExpanded,
                        onDismissRequest = { fwDropdownExpanded = false }
                    ) {
                        if (fwConnected) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.connected), fontSize = 11.sp) },
                                onClick = { fwDropdownExpanded = false },
                                leadingIcon = { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.no_device_found), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline) },
                                onClick = { fwDropdownExpanded = false },
                                enabled = false
                            )
                        }
                    }
                }
                if (fwConnected && fwSlotCount > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.bidirectional), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Switch(
                                checked = fwBidirectional,
                                onCheckedChange = onFwBidirectionalToggle,
                                modifier = Modifier.height(20.dp).padding(start = 4.dp)
                            )
                        }
                        Row {
                            if (fwMoving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                            }
                            IconButton(onClick = onFwEditNames, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Edit, stringResource(R.string.edit_names), modifier = Modifier.size(14.dp))
                            }
                            IconButton(onClick = onFwReset, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.reset), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    val cols = if (fwSlotCount <= 5) fwSlotCount else (fwSlotCount + 1) / 2
                    val rows = if (fwSlotCount <= 5) 1 else 2
                    for (row in 0 until rows) {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0 until cols) {
                                val idx = row * cols + col
                                if (idx < fwSlotCount) {
                                    val name = fwSlotNames.getOrElse(idx) { "${idx + 1}" }
                                    FilterChip(
                                        selected = idx == fwPosition,
                                        onClick = { onFwSetPosition(idx) },
                                        label = { Text(name, fontSize = 9.sp) },
                                        modifier = Modifier.height(26.dp).weight(1f),
                                        enabled = !fwMoving
                                    )
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // EAF
                Text(stringResource(R.string.focuser), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                var eafDropdownExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(
                        onClick = { eafDropdownExpanded = true },
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Row(modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (eafConnected) stringResource(R.string.connected) else stringResource(R.string.not_connected),
                                fontSize = 10.sp
                            )
                            if (eafConnected && eafTemperature != null) {
                                Text("%.1f°C".format(eafTemperature), fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                            if (eafMoving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            }
                        }
                        Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(
                        expanded = eafDropdownExpanded,
                        onDismissRequest = { eafDropdownExpanded = false }
                    ) {
                        if (eafConnected) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.connected), fontSize = 11.sp) },
                                onClick = { eafDropdownExpanded = false },
                                leadingIcon = { Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp)) }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.no_device_found), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline) },
                                onClick = { eafDropdownExpanded = false },
                                enabled = false
                            )
                        }
                    }
                }
                if (eafConnected && eafInfo != null) {
                    var showEafEditDialog by remember { mutableStateOf(false) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.pos_format, eafPosition, eafInfo.maxPosition),
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(
                            onClick = { showEafEditDialog = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Edit, stringResource(R.string.eaf_settings),
                                modifier = Modifier.size(14.dp))
                        }
                    }

                    var targetText by remember(eafPosition) { mutableStateOf(eafPosition.toString()) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = targetText,
                            onValueChange = { targetText = it.filter { c -> c.isDigit() || c == '-' } },
                            modifier = Modifier.weight(1f).height(40.dp),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                            label = { Text(stringResource(R.string.target), fontSize = 9.sp) }
                        )
                        FilledTonalButton(
                            onClick = {
                                targetText.toIntOrNull()?.let { onEafMoveTo(it) }
                            },
                            modifier = Modifier.height(40.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text(stringResource(R.string.go), fontSize = 10.sp) }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        FilledTonalButton(
                            onClick = { onEafMoveRelative(-eafInfo.coarseStep) },
                            modifier = Modifier.size(32.dp).semantics { contentDescription = decreaseCoarseFocusDescription },
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("<<", fontSize = 10.sp) }
                        FilledTonalButton(
                            onClick = { onEafMoveRelative(-eafInfo.fineStep) },
                            modifier = Modifier.size(32.dp).semantics { contentDescription = decreaseFineFocusDescription },
                            contentPadding = PaddingValues(0.dp)
                        ) { Text("<", fontSize = 10.sp) }
                        FilledTonalButton(
                            onClick = { if (eafMoving) onEafHalt() else onEafSetZero() },
                            modifier = Modifier.size(32.dp).semantics { contentDescription = haltOrZeroFocusDescription },
                            contentPadding = PaddingValues(0.dp),
                            colors = if (eafMoving) ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ) else ButtonDefaults.filledTonalButtonColors()
                        ) { Text(if (eafMoving) "■" else "0", fontSize = 10.sp) }
                        FilledTonalButton(
                            onClick = { onEafMoveRelative(eafInfo.fineStep) },
                            modifier = Modifier.size(32.dp).semantics { contentDescription = increaseFineFocusDescription },
                            contentPadding = PaddingValues(0.dp)
                        ) { Text(">", fontSize = 10.sp) }
                        FilledTonalButton(
                            onClick = { onEafMoveRelative(eafInfo.coarseStep) },
                            modifier = Modifier.size(32.dp).semantics { contentDescription = increaseCoarseFocusDescription },
                            contentPadding = PaddingValues(0.dp)
                        ) { Text(">>", fontSize = 10.sp) }
                    }

                    if (showEafEditDialog) {
                        EAFEditDialog(
                            eafInfo = eafInfo,
                            onSave = { dir, fine, maxSt, blSteps, blDir ->
                                onEafSetDirection(dir)
                                onEafSetFineStep(fine)
                                onEafSetMaxStep(maxSt)
                                onEafSetBacklash(blSteps, blDir)
                                showEafEditDialog = false
                            },
                            onDismiss = { showEafEditDialog = false }
                        )
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                }

                if (showMountControls) {
                // Telescope / OnStep
                Text(stringResource(R.string.telescope), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val mountConnected = mountConnectionState is MountConnectionState.Connected
                val mountError = (mountConnectionState as? MountConnectionState.Error)?.message
                var mountUsbExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = mountTransport == MountTransportType.TCP,
                        onClick = { onMountTransportChange(MountTransportType.TCP) },
                        enabled = !mountBusy && !mountConnected,
                        label = { Text("TCP", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = mountTransport == MountTransportType.USB_SERIAL,
                        onClick = {
                            onMountTransportChange(MountTransportType.USB_SERIAL)
                            onMountScanUsb()
                        },
                        enabled = !mountBusy && !mountConnected,
                        label = { Text("USB", fontSize = 10.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (mountTransport == MountTransportType.TCP) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = mountHost,
                            onValueChange = onMountHostChange,
                            modifier = Modifier.weight(1f).height(56.dp),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                            label = { Text(stringResource(R.string.host), fontSize = 10.sp) }
                        )
                        OutlinedTextField(
                            value = mountPort,
                            onValueChange = onMountPortChange,
                            modifier = Modifier.width(78.dp).height(56.dp),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                            label = { Text(stringResource(R.string.port), fontSize = 10.sp) }
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ExposedDropdownMenuBox(
                            expanded = mountUsbExpanded,
                            onExpandedChange = { mountUsbExpanded = !mountUsbExpanded && !mountBusy && !mountConnected },
                            modifier = Modifier.weight(1f)
                        ) {
                            val selectedUsb = mountUsbDevices.firstOrNull { it.deviceId == mountUsbDeviceId }
                            OutlinedTextField(
                                value = selectedUsb?.label ?: stringResource(R.string.no_usb_serial),
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.menuAnchor().fillMaxWidth().height(56.dp),
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                                label = { Text(stringResource(R.string.section_device), fontSize = 10.sp) }
                            )
                            ExposedDropdownMenu(
                                expanded = mountUsbExpanded,
                                onDismissRequest = { mountUsbExpanded = false }
                            ) {
                                mountUsbDevices.forEach { device ->
                                    DropdownMenuItem(
                                        text = { Text(device.label, fontSize = 11.sp) },
                                        onClick = {
                                            onMountUsbDeviceChange(device.deviceId)
                                            mountUsbExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        FilledTonalIconButton(
                            onClick = onMountScanUsb,
                            enabled = !mountBusy && !mountConnected,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        }
                    }
                    OutlinedTextField(
                        value = mountBaudRate,
                        onValueChange = onMountBaudRateChange,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                        label = { Text("Baud", fontSize = 10.sp) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = if (mountConnected) onMountDisconnect else onMountConnect,
                        enabled = !mountBusy,
                        modifier = Modifier.height(32.dp).weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Icon(Icons.Default.SettingsInputAntenna, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (mountConnected) "Disconnect" else "Connect", fontSize = 10.sp)
                    }
                    FilledTonalButton(
                        onClick = onMountReadCoordinates,
                        enabled = mountConnected && !mountBusy,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        if (mountBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        } else {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                        }
                    }
                }
                if (mountCoordinates != null) {
                    Text(
                        "${mountCoordinates.formatRa()}  ${mountCoordinates.formatDec()}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Text(
                        when (mountConnectionState) {
                            MountConnectionState.Connecting -> "Connecting..."
                            MountConnectionState.Disconnected -> if (mountTransport == MountTransportType.USB_SERIAL) {
                                "OnStep/LX200 over USB serial"
                            } else {
                                "OnStep/LX200 over TCP"
                            }
                            is MountConnectionState.Connected -> "Connected"
                            is MountConnectionState.Error -> mountError ?: stringResource(R.string.mount_error)
                        },
                        fontSize = 10.sp,
                        color = if (mountConnectionState is MountConnectionState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                    )
                }
                if (mountConnected) {
                    MountControlPad(
                        enabled = !mountBusy,
                        onMoveStart = onMountManualMoveStart,
                        onMoveStop = onMountManualMoveStop,
                        onStopAll = onMountStopAll,
                        moveStatus = mountMoveStatus,
                        slewRate = mountSlewRate,
                        trackingEnabled = mountTrackingEnabled,
                        onSlewRateChange = onMountSlewRateChange,
                        onTracking = onMountTracking,
                        onHome = onMountHome,
                        onSetHome = onMountSetHome
                    )
                }

                }

                // Cooler
                if (coolingInfo != null) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Text(stringResource(R.string.cooler), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    // Temperature & Power display
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${"%.1f".format(sensorTempTenths / 10.0)}°C",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace
                        )
                        if (coolingInfo.hasTec && coolerOn) {
                            Text(
                                "${"%.0f".format(coolingPowerPct)}%",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Temperature/Power chart
                    if (tempHistory.size >= 2) {
                        val tempColor = MaterialTheme.colorScheme.primary
                        val powerColor = MaterialTheme.colorScheme.tertiary
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                        ) {
                            val w = size.width
                            val h = size.height
                            val pts = tempHistory
                            if (pts.size < 2) return@Canvas
                            val timeStart = pts.first().timestampMs
                            val timeEnd = pts.last().timestampMs
                            val timeRange = (timeEnd - timeStart).coerceAtLeast(1)

                            val tempMin = pts.minOf { it.sensorTenths } - 20
                            val tempMax = pts.maxOf { it.sensorTenths } + 20
                            val tempRange = (tempMax - tempMin).coerceAtLeast(1)

                            val tempPath = Path()
                            val powerPath = Path()
                            pts.forEachIndexed { i, p ->
                                val x = ((p.timestampMs - timeStart).toFloat() / timeRange) * w
                                val yTemp = h - ((p.sensorTenths - tempMin).toFloat() / tempRange) * h
                                val yPower = h - (p.powerPct / 100f) * h
                                if (i == 0) {
                                    tempPath.moveTo(x, yTemp)
                                    powerPath.moveTo(x, yPower)
                                } else {
                                    tempPath.lineTo(x, yTemp)
                                    powerPath.lineTo(x, yPower)
                                }
                            }
                            drawPath(powerPath, powerColor.copy(alpha = 0.4f), style = Stroke(width = 1.5f))
                            drawPath(tempPath, tempColor, style = Stroke(width = 2f))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.temp_legend), fontSize = 8.sp, color = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.power_legend), fontSize = 8.sp, color = MaterialTheme.colorScheme.tertiary)
                            }
                            val duration = (tempHistory.last().timestampMs - tempHistory.first().timestampMs) / 1000
                            Text("${duration / 60}m${duration % 60}s", fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }

                    if (coolingInfo.canSetTarget) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.tec), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Switch(
                                checked = coolerOn,
                                onCheckedChange = { onCoolerToggle(it) },
                                modifier = Modifier.height(24.dp)
                            )
                        }

                        if (coolerOn) {
                            var userTargetText by remember { mutableStateOf((targetTempTenths / 10).toString()) }

                            Text(
                                stringResource(R.string.sensor_temp, "%.1f".format(sensorTempTenths / 10.0)),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = userTargetText,
                                    onValueChange = { v ->
                                        userTargetText = v.filter { c -> c.isDigit() || c == '-' }
                                    },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    label = { Text(stringResource(R.string.target_temp_label), fontSize = 9.sp) },
                                    suffix = { Text(stringResource(R.string.celsius_suffix), fontSize = 9.sp) }
                                )
                                FilledTonalButton(
                                    onClick = {
                                        userTargetText.toIntOrNull()?.let { t ->
                                            val clamped = t.coerceIn(
                                                coolingInfo.targetMinTenths / 10,
                                                coolingInfo.targetMaxTenths / 10
                                            )
                                            userTargetText = clamped.toString()
                                            onTargetTempChange(clamped * 10)
                                        }
                                    },
                                    modifier = Modifier.height(44.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) { Text(stringResource(R.string.set), fontSize = 10.sp) }
                            }

                            val presets = listOf(-20, -15, -10, -5, 0, 5, 10).filter { t ->
                                t * 10 in coolingInfo.targetMinTenths..coolingInfo.targetMaxTenths
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                presets.forEach { preset ->
                                    val isSelected = userTargetText.toIntOrNull() == preset
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            userTargetText = preset.toString()
                                            onTargetTempChange(preset * 10)
                                        },
                                        label = { Text("${preset}°", fontSize = 9.sp) },
                                        modifier = Modifier.height(26.dp).weight(1f)
                                    )
                                }
                            }

                            // Cool-down ramp
                            var coolDuration by remember { mutableIntStateOf(5) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        val target = userTargetText.toIntOrNull() ?: 0
                                        val clamped = target.coerceIn(
                                            coolingInfo.targetMinTenths / 10,
                                            coolingInfo.targetMaxTenths / 10
                                        )
                                        onStartCoolDown(clamped * 10, coolDuration)
                                    },
                                    modifier = Modifier.weight(1f).height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Default.AcUnit, null, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text(stringResource(R.string.cool), fontSize = 9.sp)
                                }
                                IconButton(onClick = { coolDuration = (coolDuration - 1).coerceAtLeast(0) },
                                    modifier = Modifier.size(24.dp).semantics { contentDescription = decreaseCoolingDurationDescription }) {
                                    Icon(Icons.Default.Remove, null, modifier = Modifier.size(14.dp))
                                }
                                Text("${coolDuration}m", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                IconButton(onClick = { coolDuration = (coolDuration + 1).coerceAtMost(30) },
                                    modifier = Modifier.size(24.dp).semantics { contentDescription = increaseCoolingDurationDescription }) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                                }
                            }

                            // Warm-up ramp
                            var warmDuration by remember { mutableIntStateOf(5) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilledTonalButton(
                                    onClick = { onStartWarmUp(warmDuration) },
                                    modifier = Modifier.weight(1f).height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                    )
                                ) {
                                    Icon(Icons.Default.Whatshot, null, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text(stringResource(R.string.warm), fontSize = 9.sp)
                                }
                                IconButton(onClick = { warmDuration = (warmDuration - 1).coerceAtLeast(0) },
                                    modifier = Modifier.size(24.dp).semantics { contentDescription = decreaseWarmingDurationDescription }) {
                                    Icon(Icons.Default.Remove, null, modifier = Modifier.size(14.dp))
                                }
                                Text("${warmDuration}m", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                IconButton(onClick = { warmDuration = (warmDuration + 1).coerceAtMost(30) },
                                    modifier = Modifier.size(24.dp).semantics { contentDescription = increaseWarmingDurationDescription }) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                                }
                            }

                            if (rampStatus.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(rampStatus, fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.tertiary)
                                    TextButton(onClick = onStopRamp,
                                        modifier = Modifier.height(24.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                                        Text(stringResource(R.string.stop), fontSize = 9.sp, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            } else {
                                val diff = kotlin.math.abs(sensorTempTenths - targetTempTenths) / 10.0
                                val statusColor = if (diff <= 1.0)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                                Text(
                                    if (diff <= 1.0) stringResource(R.string.stabilized) else "Δ${"%.1f".format(diff)}°C",
                                    fontSize = 10.sp,
                                    color = statusColor
                                )
                            }
                        }
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Scan button
                OutlinedButton(
                    onClick = onScanDevices,
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.scan_devices), fontSize = 10.sp)
                }
            }
        }

        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        // ── Capture Settings ───────
        SectionHeader(stringResource(R.string.section_capture), captureExpanded) { captureExpanded = !captureExpanded }
        AnimatedVisibility(visible = captureExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposureSlider(
                    exposureUs = exposureUs,
                    maxUs = exposureMax,
                    onExposureChange = onExposureChange
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.long_exp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (longExposureProgress.isNotEmpty()) {
                            Text(
                                longExposureProgress,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Switch(
                            checked = longExposureEnabled,
                            onCheckedChange = { onToggleLongExposure() },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }

                GainSlider(
                    gain = gain,
                    maxGain = gainMax,
                    onGainChange = onGainChange
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.format),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (fmt in supportedPixelFormats) {
                            val chipLabel = if (!fmt.is8bit && !fmt.isRgb && pixelFormat == fmt && detectedBitDepth in 10..14 && detectedBitDepth != fmt.nativeBits) {
                                val prefix = if (fmt.isMono) "MONO" else "BAYER RG"
                                "$prefix$detectedBitDepth"
                            } else {
                                fmt.displayName
                            }
                            FilterChip(
                                selected = pixelFormat == fmt,
                                onClick = { onPixelFormatChange(fmt) },
                                label = { Text(chipLabel, fontSize = 11.sp) },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                }

                if (supportedReadoutModes.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.readout),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (mode in supportedReadoutModes) {
                                FilterChip(
                                    selected = readoutMode == mode,
                                    onClick = { onReadoutModeChange(mode) },
                                    label = { Text(mode.displayName, fontSize = 11.sp) },
                                    modifier = Modifier.height(28.dp)
                                )
                            }
                        }
                    }
                }

                // Auto exposure mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.auto_exp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (mode in AutoExposureMode.entries) {
                            FilterChip(
                                selected = autoExposureMode == mode,
                                onClick = { onAutoExposureModeChange(mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            AutoExposureMode.OFF -> stringResource(R.string.ae_off)
                                            AutoExposureMode.CONTINUOUS -> stringResource(R.string.ae_cont)
                                            AutoExposureMode.SINGLE_SHOT -> stringResource(R.string.ae_once)
                                        },
                                        fontSize = 10.sp
                                    )
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                }

                if (isBayerCamera || pixelFormat.isRgb) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.awb),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val awbOptions = listOf(0 to stringResource(R.string.awb_off), 2 to stringResource(R.string.awb_cont), 1 to stringResource(R.string.awb_once))
                            awbOptions.forEach { (modeOrdinal, label) ->
                                FilterChip(
                                    selected = awbMode == modeOrdinal,
                                    onClick = { onAwbModeChange(modeOrdinal) },
                                    label = { Text(label, fontSize = 10.sp) },
                                    modifier = Modifier.height(28.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        // ── Image Control ───────
        SectionHeader(stringResource(R.string.section_image), imageExpanded) { imageExpanded = !imageExpanded }
        AnimatedVisibility(visible = imageExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HistogramView(histogramData = histogram)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.auto_stretch),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = autoStretch,
                        onCheckedChange = onAutoStretchToggle,
                        modifier = Modifier.height(24.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.flip_rot), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(50.dp))
                    FilterChip(
                        selected = flipH,
                        onClick = onFlipH,
                        label = { Text("H", fontSize = 10.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                    FilterChip(
                        selected = flipV,
                        onClick = onFlipV,
                        label = { Text("V", fontSize = 10.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                    FilterChip(
                        selected = rotation != 0,
                        onClick = { onRotation((rotation + 90) % 360) },
                        label = { Text("${rotation}°", fontSize = 10.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }

        Divider(color = MaterialTheme.colorScheme.outlineVariant)

        // ── ROI ───────
        SectionHeader(stringResource(R.string.section_roi), roiExpanded) { roiExpanded = !roiExpanded }
        AnimatedVisibility(visible = roiExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${roi.width} x ${roi.height}  @  (${roi.x}, ${roi.y})",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val presets = buildList {
                    add(stringResource(R.string.roi_full) to Roi(0, 0, sensorWidth, sensorHeight))
                    fun centered(w: Int, h: Int): Roi {
                        val cw = w.coerceAtMost(sensorWidth)
                        val ch = h.coerceAtMost(sensorHeight)
                        return Roi((sensorWidth - cw) / 2, (sensorHeight - ch) / 2, cw, ch)
                    }
                    fun canFit(w: Int, h: Int) = sensorWidth >= w && sensorHeight >= h
                    if (canFit(3840, 2160) && (sensorWidth > 3840 || sensorHeight > 2160)) add("3840x2160" to centered(3840, 2160))
                    if (canFit(2560, 1440)) add("2560x1440" to centered(2560, 1440))
                    if (canFit(2160, 2160)) add("2160x2160" to centered(2160, 2160))
                    if (canFit(1920, 1080)) add("1920x1080" to centered(1920, 1080))
                    if (canFit(1200, 1200)) add("1200x1200" to centered(1200, 1200))
                    if (canFit(1280, 960))  add("1280x960" to centered(1280, 960))
                    if (canFit(1280, 720))  add("1280x720" to centered(1280, 720))
                    if (canFit(1024, 768))  add("1024x768" to centered(1024, 768))
                    if (canFit(960, 960))   add("960x960" to centered(960, 960))
                    if (canFit(640, 480))   add("640x480" to centered(640, 480))
                    if (canFit(480, 480))   add("480x480" to centered(480, 480))
                    if (canFit(320, 240))   add("320x240" to centered(320, 240))
                    if (canFit(320, 320))   add("320x320" to centered(320, 320))
                    if (canFit(1920, 128))  add("1920x128" to centered(1920, 128))
                    if (canFit(1920, 64))   add("1920x64" to centered(1920, 64))
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { (label, preset) ->
                                FilterChip(
                                    selected = roi.width == preset.width && roi.height == preset.height,
                                    onClick = { onRoiChange(preset) },
                                    label = { Text(label, fontSize = 10.sp) },
                                    modifier = Modifier.height(28.dp)
                                )
                            }
                        }
                    }
                }

                val step = 8

                Text(stringResource(R.string.width_label, roi.width), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = roi.width.toFloat(),
                    onValueChange = {
                        val w = (it.toInt() / step) * step
                        val cx = roi.x + roi.width / 2
                        val nx = ((cx - w / 2) / step * step).coerceIn(0, sensorWidth - w)
                        onRoiChange(Roi(nx, roi.y, w, roi.height))
                    },
                    valueRange = roiMinWidth.toFloat()..sensorWidth.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(stringResource(R.string.height_label, roi.height), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = roi.height.toFloat(),
                    onValueChange = {
                        val h = (it.toInt() / step) * step
                        val cy = roi.y + roi.height / 2
                        val ny = ((cy - h / 2) / step * step).coerceIn(0, sensorHeight - h)
                        onRoiChange(Roi(roi.x, ny, roi.width, h))
                    },
                    valueRange = roiMinHeight.toFloat()..sensorHeight.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(stringResource(R.string.offset_x_label, roi.x), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        onRoiChange(roi.copy(x = (roi.x - step).coerceAtLeast(0)))
                    }, modifier = Modifier.size(28.dp).semantics { contentDescription = decreaseRoiXDescription }) {
                        Icon(Icons.Default.Remove, null, modifier = Modifier.size(16.dp))
                    }
                    Slider(
                        value = roi.x.toFloat(),
                        onValueChange = { onRoiChange(roi.copy(x = (it.toInt() / step) * step)) },
                        valueRange = 0f..(sensorWidth - roi.width).toFloat().coerceAtLeast(0f),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        onRoiChange(roi.copy(x = (roi.x + step).coerceAtMost(sensorWidth - roi.width)))
                    }, modifier = Modifier.size(28.dp).semantics { contentDescription = increaseRoiXDescription }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    }
                }

                Text(stringResource(R.string.offset_y_label, roi.y), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        onRoiChange(roi.copy(y = (roi.y - step).coerceAtLeast(0)))
                    }, modifier = Modifier.size(28.dp).semantics { contentDescription = decreaseRoiYDescription }) {
                        Icon(Icons.Default.Remove, null, modifier = Modifier.size(16.dp))
                    }
                    Slider(
                        value = roi.y.toFloat(),
                        onValueChange = { onRoiChange(roi.copy(y = (it.toInt() / step) * step)) },
                        valueRange = 0f..(sensorHeight - roi.height).toFloat().coerceAtLeast(0f),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        onRoiChange(roi.copy(y = (roi.y + step).coerceAtMost(sensorHeight - roi.height)))
                    }, modifier = Modifier.size(28.dp).semantics { contentDescription = increaseRoiYDescription }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    }
                }

                TextButton(onClick = onResetRoi) {
                    Text(stringResource(R.string.reset_roi), fontSize = 11.sp)
                }
            }
        }

        // ── Info ───────
        if (cameraInfo != null) {
            Divider(color = MaterialTheme.colorScheme.outlineVariant)

            var infoExpanded by remember { mutableStateOf(false) }
            SectionHeader(stringResource(R.string.section_info), infoExpanded) { infoExpanded = !infoExpanded }
            AnimatedVisibility(visible = infoExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow(stringResource(R.string.info_camera), cameraInfo.name)
                    InfoRow(stringResource(R.string.info_sn), cameraInfo.serialNumber)
                    InfoRow(stringResource(R.string.info_sensor), cameraInfo.sensorName ?: "—")
                    InfoRow(stringResource(R.string.info_resolution), "${cameraInfo.sensorWidth} x ${cameraInfo.sensorHeight}")
                    val displayBitDepth = if (pixelFormat.is8bit) 8 else detectedBitDepth
                    InfoRow(stringResource(R.string.info_bit_depth), "${displayBitDepth}-bit")
                    val formatDisplayName = if (pixelFormat.is8bit) {
                        pixelFormat.displayName
                    } else {
                        val prefix = if (pixelFormat.isMono) "MONO" else "BAYER RG"
                        "$prefix$displayBitDepth"
                    }
                    InfoRow(stringResource(R.string.info_format), formatDisplayName)
                    InfoRow(stringResource(R.string.info_roi), "${roi.width} x ${roi.height}")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SectionHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) stringResource(R.string.collapse) else stringResource(R.string.expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EAFEditDialog(
    eafInfo: EAFInfo,
    onSave: (direction: Int, fineStep: Int, maxStep: Int, backlashSteps: Int, backlashDir: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var direction by remember { mutableIntStateOf(eafInfo.direction) }
    var fineStepText by remember { mutableStateOf(eafInfo.fineStep.toString()) }
    var maxStepText by remember { mutableStateOf(eafInfo.maxStep.toString()) }
    var backlashText by remember { mutableStateOf(eafInfo.backlashSteps.toString()) }
    var backlashDir by remember { mutableIntStateOf(eafInfo.backlashDirection) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.focuser_settings)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.direction), fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = direction == 0,
                            onClick = { direction = 0 },
                            label = { Text(stringResource(R.string.dir_normal), fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = direction == 1,
                            onClick = { direction = 1 },
                            label = { Text(stringResource(R.string.dir_reverse), fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = fineStepText,
                        onValueChange = { fineStepText = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.fine_step), fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    OutlinedTextField(
                        value = maxStepText,
                        onValueChange = { maxStepText = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.max_step), fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                }
                Text(stringResource(R.string.coarse_formula), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.outline)

                Divider()

                Text(stringResource(R.string.backlash_compensation), fontSize = 13.sp)

                OutlinedTextField(
                    value = backlashText,
                    onValueChange = { backlashText = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.overshoot_steps), fontSize = 11.sp) },
                    placeholder = { Text(stringResource(R.string.zero_off), fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.direction), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = backlashDir == 0,
                            onClick = { backlashDir = 0 },
                            label = { Text(stringResource(R.string.dir_inward), fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = backlashDir == 1,
                            onClick = { backlashDir = 1 },
                            label = { Text(stringResource(R.string.dir_outward), fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    direction,
                    fineStepText.toIntOrNull()?.coerceAtLeast(1) ?: eafInfo.fineStep,
                    maxStepText.toIntOrNull()?.coerceAtLeast(100) ?: eafInfo.maxStep,
                    backlashText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    backlashDir
                )
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MountControlPad(
    enabled: Boolean,
    onMoveStart: (MountDirection) -> Unit,
    onMoveStop: (MountDirection) -> Unit,
    onStopAll: () -> Unit,
    moveStatus: String,
    slewRate: MountSlewRate,
    trackingEnabled: Boolean,
    onSlewRateChange: (MountSlewRate) -> Unit,
    onTracking: (Boolean) -> Unit,
    onHome: () -> Unit,
    onSetHome: () -> Unit
) {
    var confirmHome by remember { mutableStateOf(false) }
    var confirmSetHome by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Text(stringResource(R.string.mount_control_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            MountSlewRate.entries.forEach { rate ->
                FilterChip(
                    selected = slewRate == rate,
                    onClick = { onSlewRateChange(rate) },
                    enabled = enabled,
                    label = { Text(rate.label, fontSize = 9.sp) },
                    modifier = Modifier.height(30.dp)
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            MountDirectionButton("N", MountDirection.NORTH, enabled, onMoveStart, onMoveStop)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MountDirectionButton("W", MountDirection.WEST, enabled, onMoveStart, onMoveStop)
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(
                onClick = onStopAll,
                enabled = enabled,
                modifier = Modifier.size(width = 70.dp, height = 42.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) { Text(stringResource(R.string.stop), fontSize = 11.sp) }
            Spacer(Modifier.width(6.dp))
            MountDirectionButton("E", MountDirection.EAST, enabled, onMoveStart, onMoveStop)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            MountDirectionButton("S", MountDirection.SOUTH, enabled, onMoveStart, onMoveStop)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalButton(
                onClick = { onTracking(!trackingEnabled) },
                enabled = enabled,
                modifier = Modifier.height(34.dp).weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) { Text(stringResource(if (trackingEnabled) R.string.tracking_off else R.string.tracking_on), fontSize = 10.sp) }
            FilledTonalButton(
                onClick = { confirmHome = true },
                enabled = enabled,
                modifier = Modifier.height(34.dp).weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) { Text(stringResource(R.string.home), fontSize = 10.sp) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = { confirmSetHome = true },
                enabled = enabled,
                modifier = Modifier.height(34.dp).weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) { Text(stringResource(R.string.set_home), fontSize = 10.sp) }
        }
        if (moveStatus.isNotBlank()) {
            Text(moveStatus, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
        }
    }
    if (confirmHome) {
        AlertDialog(
            onDismissRequest = { confirmHome = false },
            title = { Text(stringResource(R.string.go_home)) },
            text = { Text(stringResource(R.string.mount_home_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmHome = false
                    onHome()
                }) { Text(stringResource(R.string.go)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmHome = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    if (confirmSetHome) {
        AlertDialog(
            onDismissRequest = { confirmSetHome = false },
            title = { Text(stringResource(R.string.set_home)) },
            text = { Text(stringResource(R.string.set_home_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSetHome = false
                    onSetHome()
                }) { Text(stringResource(R.string.set)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSetHome = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun MountDirectionButton(
    label: String,
    direction: MountDirection,
    enabled: Boolean,
    onMoveStart: (MountDirection) -> Unit,
    onMoveStop: (MountDirection) -> Unit
) {
    Surface(
        modifier = Modifier
            .size(48.dp)
            .pointerInput(enabled, direction) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
                        onMoveStart(direction)
                        try {
                            awaitRelease()
                        } finally {
                            onMoveStop(direction)
                        }
                    }
                )
            },
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
