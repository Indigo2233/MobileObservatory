package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.ui.components.*
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.R

@Composable
internal fun ObservatoryDeviceBar(viewModel: CameraViewModel, redNightMode: Boolean,
    onRedNightModeChange: (Boolean) -> Unit, onSettings: () -> Unit, onDevice: (String) -> Unit) {
    val connectionState by viewModel.connectionState.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingFrameCount by viewModel.recordingFrameCount.collectAsState()
    val fps by viewModel.fps.collectAsState()
    val globalMountMotionState by viewModel.mountMotionState.collectAsState()
    val mountConnectionState by viewModel.mountConnectionState.collectAsState()
    val mountTrackingEnabled by viewModel.mountTrackingEnabled.collectAsState()
    val coolerOn by viewModel.coolerOn.collectAsState()
    val coolingInfo by viewModel.coolingInfo.collectAsState()
    val sensorTempTenths by viewModel.sensorTempTenths.collectAsState()
    val rampStatus by viewModel.rampStatus.collectAsState()
    val fwConnected by viewModel.filterWheelConnected.collectAsState()
    val fwPosition by viewModel.filterWheelPosition.collectAsState()
    val fwMoving by viewModel.filterWheelMoving.collectAsState()
    val fwSlotNames by viewModel.filterWheelSlotNames.collectAsState()
    val eafConnected by viewModel.eafConnected.collectAsState()
    val eafPosition by viewModel.eafPosition.collectAsState()
    val eafMoving by viewModel.eafMoving.collectAsState()
    // Global status strip: one glance shows what the rig is doing right now.
    val guideConnectionState by viewModel.guideConnectionState.collectAsState()
    val guideRunning by viewModel.guideRunning.collectAsState()
    val cameraConnected = connectionState is ConnectionState.Connected
    val mountConnected =
        mountConnectionState is com.indigo.mobileobservatory.mount.MountConnectionState.Connected
    val cameraPillValue = when {
        isRecording -> "REC ${recordingFrameCount}"
        cameraConnected -> "%.0f fps".format(fps)
        else -> null
    }
    val cameraPillCode = when {
        !cameraConnected -> DeviceStatusCode.OFF
        isRecording -> DeviceStatusCode.ACTIVE
        else -> DeviceStatusCode.READY
    }
    val mountPillValue = when {
        !mountConnected -> null
        globalMountMotionState.showsGlobalStop -> stringResource(R.string.stop)
        mountTrackingEnabled -> stringResource(R.string.tracking)
        else -> stringResource(R.string.tracking_stopped)
    }
    val mountPillCode = when {
        !mountConnected -> DeviceStatusCode.OFF
        globalMountMotionState.showsGlobalStop -> DeviceStatusCode.ACTIVE
        mountTrackingEnabled -> DeviceStatusCode.ACTIVE
        else -> DeviceStatusCode.READY
    }
    val guidePillValue = when {
        guideRunning -> stringResource(R.string.guiding)
        guideConnectionState is ConnectionState.Connected -> null
        else -> null
    }
    val guidePillCode = when {
        guideRunning -> DeviceStatusCode.ACTIVE
        guideConnectionState is ConnectionState.Connected -> DeviceStatusCode.READY
        guideConnectionState is ConnectionState.Connecting -> DeviceStatusCode.CONNECTING
        guideConnectionState is ConnectionState.Error -> DeviceStatusCode.ERROR
        else -> DeviceStatusCode.OFF
    }
    val coolerPillValue = if (coolerOn || coolingInfo != null) {
        "%.1f°C".format(sensorTempTenths / 10f)
    } else {
        null
    }
    val statusDevices = listOf(
        DeviceStatus(
            key = "camera",
            label = stringResource(R.string.camera),
            value = cameraPillValue,
            code = cameraPillCode,
            icon = Icons.Default.CameraAlt
        ),
        DeviceStatus(
            key = "mount",
            label = stringResource(R.string.tab_mount),
            value = mountPillValue,
            code = mountPillCode,
            icon = Icons.Default.Explore
        ),
        DeviceStatus(
            key = "guide",
            label = stringResource(R.string.guiding),
            value = guidePillValue,
            code = guidePillCode,
            icon = Icons.Default.CenterFocusWeak
        ),
        DeviceStatus(
            key = "cooler",
            label = stringResource(R.string.cooler),
            value = coolerPillValue,
            code = when {
                coolingInfo == null && !coolerOn -> DeviceStatusCode.OFF
                rampStatus.isNotEmpty() -> DeviceStatusCode.ACTIVE
                coolerOn -> DeviceStatusCode.ACTIVE
                else -> DeviceStatusCode.READY
            },
            icon = Icons.Default.AcUnit
        ),
        DeviceStatus(
            key = "filter",
            label = stringResource(R.string.filter_wheel),
            value = if (fwConnected) {
                fwSlotNames.getOrNull(fwPosition) ?: "${fwPosition + 1}"
            } else {
                null
            },
            code = when {
                !fwConnected -> DeviceStatusCode.OFF
                fwMoving -> DeviceStatusCode.ACTIVE
                else -> DeviceStatusCode.READY
            },
            icon = Icons.Default.FilterAlt
        ),
        DeviceStatus(
            key = "focuser",
            label = stringResource(R.string.focuser),
            value = if (eafConnected) "$eafPosition" else null,
            code = when {
                !eafConnected -> DeviceStatusCode.OFF
                eafMoving -> DeviceStatusCode.ACTIVE
                else -> DeviceStatusCode.READY
            },
            icon = Icons.Default.Tune
        )
    )

    ObservatoryStatusBar(devices = statusDevices, redNightMode = redNightMode,
        onToggleRedNight = onRedNightModeChange, onOpenSettings = onSettings,
        onDeviceClick = { onDevice(it.key) })
}
