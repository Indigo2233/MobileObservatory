package com.indigo.mobileobservatory.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.mount.MountConnectionState
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.MountProtocolType
import com.indigo.mobileobservatory.mount.MountSlewRate
import com.indigo.mobileobservatory.mount.MountTransportType
import com.indigo.mobileobservatory.permissions.AppSettingsNavigator
import com.indigo.mobileobservatory.permissions.BluetoothPermissionPolicy
import com.indigo.mobileobservatory.ui.MountConnectionAction
import com.indigo.mobileobservatory.ui.MountConnectionUiState
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MountControlScreen(
    viewModel: CameraViewModel,
    onOpenStarMap: () -> Unit,
    onOpenPolarAlignment: () -> Unit,
    onOpenGuiding: () -> Unit,
    onOpenPushTo: () -> Unit = {},
    onOpenCalibration: () -> Unit = {},
    onOpenTargetLibrary: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.mountConnectionState.collectAsState()
    val coordinates by viewModel.mountCoordinates.collectAsState()
    val transport by viewModel.mountTransport.collectAsState()
    val host by viewModel.mountHost.collectAsState()
    val port by viewModel.mountPort.collectAsState()
    val synScanHost by viewModel.synScanHost.collectAsState()
    val synScanPort by viewModel.synScanPort.collectAsState()
    val usbDevices by viewModel.mountUsbDevices.collectAsState()
    val usbDeviceId by viewModel.mountUsbDeviceId.collectAsState()
    val baudRate by viewModel.mountBaudRate.collectAsState()
    val bluetoothDevices by viewModel.mountBluetoothDevices.collectAsState()
    val bluetoothAddress by viewModel.mountBluetoothAddress.collectAsState()
    val protocol by viewModel.mountProtocol.collectAsState()
    val detectedInfo by viewModel.mountDetectedInfo.collectAsState()
    val busy by viewModel.mountBusy.collectAsState()
    val connectionMessage by viewModel.mountConnectionMessage.collectAsState()
    val moveStatus by viewModel.mountMoveStatus.collectAsState()
    val slewRate by viewModel.mountSlewRate.collectAsState()
    val tracking by viewModel.mountTrackingEnabled.collectAsState()
    val connected = connectionState is MountConnectionState.Connected
    val connectionUi = MountConnectionUiState.from(
        connection = connectionState,
        transport = transport,
        busy = busy
    )
    val context = LocalContext.current
    val activity = context as? Activity
    val requiredBluetoothPermissions = BluetoothPermissionPolicy.requiredPermissions(
        Build.VERSION.SDK_INT
    )
    var pendingBluetoothAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var bluetoothPermissionDenied by remember { mutableStateOf(false) }
    var bluetoothPermissionDeniedForever by remember { mutableStateOf(false) }
    var bluetoothPermissionAsked by remember { mutableStateOf(false) }
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        bluetoothPermissionAsked = true
        val granted = requiredBluetoothPermissions.all { permission ->
            result[permission] == true || ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
        val action = pendingBluetoothAction
        pendingBluetoothAction = null
        bluetoothPermissionDenied = !granted
        bluetoothPermissionDeniedForever = !granted &&
            activity != null &&
            requiredBluetoothPermissions.any { permission ->
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            }
        if (granted) action?.invoke()
    }

    fun runWithBluetoothPermissions(action: () -> Unit) {
        val missing = requiredBluetoothPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            bluetoothPermissionDenied = false
            bluetoothPermissionDeniedForever = false
            action()
        } else {
            pendingBluetoothAction = action
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun scanBluetooth() {
        runWithBluetoothPermissions(viewModel::scanMountBluetoothDevices)
    }

    LaunchedEffect(Unit) {
        viewModel.scanMountUsbDevices()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text(
                stringResource(R.string.mount_control_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                stringResource(R.string.mount_connection_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = transport == MountTransportType.TCP,
                        onClick = { viewModel.setMountTransport(MountTransportType.TCP) },
                        label = { Text("TCP") },
                        enabled = !connected && !busy
                    )
                    FilterChip(
                        selected = transport == MountTransportType.USB_SERIAL,
                        onClick = {
                            viewModel.setMountTransport(MountTransportType.USB_SERIAL)
                            viewModel.scanMountUsbDevices()
                        },
                        label = { Text("USB") },
                        enabled = !connected && !busy
                    )
                    FilterChip(
                        selected = transport == MountTransportType.BLUETOOTH,
                        onClick = {
                            viewModel.setMountTransport(MountTransportType.BLUETOOTH)
                            scanBluetooth()
                        },
                        label = { Text(stringResource(R.string.bluetooth)) },
                        enabled = !connected && !busy
                    )
                    FilterChip(
                        selected = transport == MountTransportType.SYNSCAN_WIFI,
                        onClick = {
                            viewModel.setMountTransport(MountTransportType.SYNSCAN_WIFI)
                            viewModel.setMountProtocol(MountProtocolType.SKYWATCHER)
                        },
                        label = { Text("SynScan Wi‑Fi") },
                        enabled = !connected && !busy
                    )
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MountProtocolType.entries.forEach { type ->
                        FilterChip(
                            selected = protocol == type,
                            onClick = { viewModel.setMountProtocol(type) },
                            label = {
                                Text(
                                    when (type) {
                                        MountProtocolType.AUTO -> stringResource(R.string.protocol_auto)
                                        MountProtocolType.LX200_ONSTEP -> "LX200 / OnStep"
                                        MountProtocolType.IOPTRON -> "iOptron"
                                        MountProtocolType.SKYWATCHER -> "Sky-Watcher"
                                    }
                                )
                            },
                            enabled = !connected && !busy
                        )
                    }
                }

                when (transport) {
                    MountTransportType.TCP -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = host,
                                onValueChange = viewModel::setMountHost,
                                label = { Text(stringResource(R.string.host)) },
                                singleLine = true,
                                enabled = !connected,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = port,
                                onValueChange = viewModel::setMountPort,
                                label = { Text(stringResource(R.string.port)) },
                                singleLine = true,
                                enabled = !connected,
                                modifier = Modifier.width(100.dp)
                            )
                        }
                    }
                    MountTransportType.USB_SERIAL -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = viewModel::scanMountUsbDevices,
                                enabled = !connected && !busy
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Text(stringResource(R.string.scan))
                            }
                            OutlinedTextField(
                                value = baudRate,
                                onValueChange = viewModel::setMountBaudRate,
                                label = { Text(stringResource(R.string.baud_rate)) },
                                singleLine = true,
                                enabled = !connected,
                                modifier = Modifier.width(140.dp)
                            )
                        }
                        if (usbDevices.isEmpty()) {
                            Text(
                                stringResource(R.string.no_usb_serial_devices),
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            usbDevices.forEach { device ->
                                FilterChip(
                                    selected = usbDeviceId == device.deviceId,
                                    onClick = { viewModel.setMountUsbDevice(device.deviceId) },
                                    label = { Text(device.label) },
                                    enabled = !connected && !busy
                                )
                            }
                        }
                    }
                    MountTransportType.BLUETOOTH -> {
                        FilledTonalButton(
                            onClick = ::scanBluetooth,
                            enabled = !connected && !busy
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Text(stringResource(R.string.paired_devices))
                        }
                        if (bluetoothPermissionDenied) {
                            Text(
                                stringResource(
                                    if (bluetoothPermissionDeniedForever && bluetoothPermissionAsked) {
                                        R.string.nearby_devices_permission_denied_forever
                                    } else {
                                        R.string.nearby_devices_permission_required
                                    }
                                ),
                                color = MaterialTheme.colorScheme.error
                            )
                            if (bluetoothPermissionDeniedForever && bluetoothPermissionAsked) {
                                OutlinedButton(
                                    onClick = { AppSettingsNavigator.openApplicationDetails(context) }
                                ) {
                                    Text(stringResource(R.string.open_app_settings))
                                }
                            }
                        }
                        if (bluetoothDevices.isEmpty()) {
                            Text(
                                stringResource(R.string.pair_mount_in_settings),
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            bluetoothDevices.forEach { device ->
                                FilterChip(
                                    selected = bluetoothAddress == device.address,
                                    onClick = {
                                        viewModel.setMountBluetoothDevice(device.address)
                                    },
                                    label = { Text(device.label) },
                                    enabled = !connected && !busy
                                )
                            }
                        }
                    }
                    MountTransportType.SYNSCAN_WIFI -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = synScanHost,
                                onValueChange = viewModel::setSynScanHost,
                                label = { Text(stringResource(R.string.synscan_app_address)) },
                                singleLine = true,
                                enabled = !connected,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = synScanPort,
                                onValueChange = viewModel::setSynScanPort,
                                label = { Text(stringResource(R.string.udp_port)) },
                                singleLine = true,
                                enabled = !connected,
                                modifier = Modifier.width(110.dp)
                            )
                        }
                        Text(
                            stringResource(R.string.synscan_connection_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            when (connectionUi.action) {
                                MountConnectionAction.CANCEL -> viewModel.cancelMountConnection()
                                MountConnectionAction.DISCONNECT -> viewModel.disconnectMount()
                                MountConnectionAction.CONNECT -> {
                                    if (transport == MountTransportType.BLUETOOTH) {
                                        runWithBluetoothPermissions(viewModel::connectMount)
                                    } else {
                                        viewModel.connectMount()
                                    }
                                }
                            }
                        },
                        enabled = connectionUi.actionEnabled
                    ) {
                        if (connectionUi.showProgress) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            if (connectionState is MountConnectionState.Connecting &&
                                transport == MountTransportType.BLUETOOTH) {
                                stringResource(R.string.cancel_connection)
                            } else {
                                stringResource(
                                    if (connected) R.string.disconnect else R.string.connect
                                )
                            }
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::readMountCoordinates,
                        enabled = connected && !busy
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text(stringResource(R.string.refresh_coordinates))
                    }
                }

                Text(
                    when (val state = connectionState) {
                        MountConnectionState.Disconnected ->
                            stringResource(R.string.disconnected)
                        MountConnectionState.Connecting ->
                            connectionMessage.ifBlank { stringResource(R.string.connecting) }
                        is MountConnectionState.Connected ->
                            stringResource(R.string.connected)
                        is MountConnectionState.Error -> state.message
                    },
                    color = if (connectionState is MountConnectionState.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                coordinates?.let {
                    Text(
                        "${it.formatRa()}  ${it.formatDec()}",
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (detectedInfo.isNotBlank()) {
                    Text(
                        detectedInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (connected) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.manual_mount_control),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MountSlewRate.entries.forEach { rate ->
                            FilterChip(
                                selected = rate == slewRate,
                                onClick = { viewModel.setMountSlewRate(rate) },
                                label = { Text(rate.label) }
                            )
                        }
                    }
                    MountPressButton("N", stringResource(R.string.move_north), MountDirection.NORTH, viewModel)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MountPressButton("W", stringResource(R.string.move_west), MountDirection.WEST, viewModel)
                        FilledTonalButton(onClick = viewModel::stopMountMotion) {
                            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop_mount))
                        }
                        MountPressButton("E", stringResource(R.string.move_east), MountDirection.EAST, viewModel)
                    }
                    MountPressButton("S", stringResource(R.string.move_south), MountDirection.SOUTH, viewModel)
                    if (moveStatus.isNotBlank()) {
                        Text(moveStatus, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource(R.string.tracking))
                        Switch(
                            checked = tracking,
                            onCheckedChange = viewModel::setMountTracking
                        )
                        OutlinedButton(onClick = viewModel::goMountHome) {
                            Icon(Icons.Default.Home, contentDescription = null)
                            Text(stringResource(R.string.home))
                        }
                        OutlinedButton(onClick = viewModel::setMountHomeHere) {
                            Text(stringResource(R.string.set_home))
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (BuildConfig.STELLARIUM_ENABLED) {
                FilledTonalButton(onClick = onOpenStarMap) {
                    Text(stringResource(R.string.star_map))
                }
            }
            FilledTonalButton(onClick = onOpenPolarAlignment) {
                Text(stringResource(R.string.polar_alignment))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(onClick = onOpenPushTo) {
                Text(stringResource(R.string.push_to_title))
            }
            FilledTonalButton(onClick = onOpenTargetLibrary) {
                Text(stringResource(R.string.target_library_title))
            }
            FilledTonalButton(onClick = onOpenCalibration) {
                Text(stringResource(R.string.calibration_title))
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.guiding_module), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.guiding_module_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                FilledTonalButton(onClick = onOpenGuiding) {
                    Text(stringResource(R.string.guiding))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun MountPressButton(
    label: String,
    contentDescription: String,
    direction: MountDirection,
    viewModel: CameraViewModel
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 1.dp,
        modifier = Modifier
            .size(56.dp)
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(direction) {
                detectTapGestures(
                    onPress = {
                        viewModel.startMountManualMove(direction)
                        tryAwaitRelease()
                        viewModel.stopMountManualMove(direction)
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(label)
        }
    }
}
