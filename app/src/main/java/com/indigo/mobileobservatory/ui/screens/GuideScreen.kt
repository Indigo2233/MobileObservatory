package com.indigo.mobileobservatory.ui.screens

import com.indigo.mobileobservatory.R

import androidx.compose.ui.res.stringResource

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.camera.DeviceEntry
import com.indigo.mobileobservatory.mount.MountConnectionState
import com.indigo.mobileobservatory.ui.components.LivePreview
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.ui.viewmodel.GuideAlgorithm
import com.indigo.mobileobservatory.ui.viewmodel.GuideCalibrationState
import com.indigo.mobileobservatory.ui.viewmodel.GuideHistoryPoint
import com.indigo.mobileobservatory.ui.viewmodel.GuideStar
import kotlin.math.abs
import kotlin.math.max
import java.util.Locale

@Composable
fun GuideScreen(
    viewModel: CameraViewModel,
    onBack: () -> Unit
) {
    val connectionState by viewModel.guideConnectionState.collectAsState()
    val previewBitmap by viewModel.guidePreviewBitmap.collectAsState()
    val guideStar by viewModel.guideStar.collectAsState()
    val guideStars by viewModel.guideStars.collectAsState()
    val referenceStar by viewModel.guideReferenceStar.collectAsState()
    val referenceStars by viewModel.guideReferenceStars.collectAsState()
    val correction by viewModel.guideCorrection.collectAsState()
    val guideRunning by viewModel.guideRunning.collectAsState()
    val guideCalibrating by viewModel.guideCalibrating.collectAsState()
    val calibrationState by viewModel.guideCalibrationState.collectAsState()
    val calibration by viewModel.guideCalibration.collectAsState()
    val guideStatus by viewModel.guideStatus.collectAsState()
    val exposureUs by viewModel.guideExposureUs.collectAsState()
    val gain by viewModel.guideGain.collectAsState()
    val raAggressiveness by viewModel.guideRaAggressiveness.collectAsState()
    val decAggressiveness by viewModel.guideDecAggressiveness.collectAsState()
    val guideHistory by viewModel.guideHistory.collectAsState()
    val raRmsPx by viewModel.guideRaRmsPx.collectAsState()
    val decRmsPx by viewModel.guideDecRmsPx.collectAsState()
    val totalRmsPx by viewModel.guideTotalRmsPx.collectAsState()
    val minMovePx by viewModel.guideMinMovePx.collectAsState()
    val algorithm by viewModel.guideAlgorithm.collectAsState()
    val multiStarEnabled by viewModel.guideMultiStarEnabled.collectAsState()
    val calibrationPulseMs by viewModel.guideCalibrationPulseMs.collectAsState()
    val reverseRa by viewModel.guideReverseRa.collectAsState()
    val reverseDec by viewModel.guideReverseDec.collectAsState()
    val showDevicePicker by viewModel.showGuideDevicePicker.collectAsState()
    val devices by viewModel.guideDevices.collectAsState()
    val mountConnectionState by viewModel.mountConnectionState.collectAsState()
    val mountCoordinates by viewModel.mountCoordinates.collectAsState()
    val mountBusy by viewModel.mountBusy.collectAsState()

    if (showDevicePicker) {
        val mainSn = viewModel.cameraManager.activeCamera?.cameraInfo?.serialNumber
        GuideDevicePickerDialog(
            devices = devices.filter { it.serialNumber != mainSn },
            onSelect = { entry ->
                viewModel.hideGuideDevicePicker()
                viewModel.connectGuideCameraBySn(entry.serialNumber)
            },
            onDismiss = { viewModel.hideGuideDevicePicker() }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color.Black)
        ) {
            when (connectionState) {
                is ConnectionState.Connected -> {
                    LivePreview(
                        bitmap = previewBitmap,
                        modifier = Modifier.fillMaxSize()
                    )
                    GuideOverlay(
                        bitmap = previewBitmap,
                        stars = guideStars,
                        referenceStars = referenceStars.ifEmpty { referenceStar?.let { listOf(it) } ?: emptyList() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is ConnectionState.Connecting,
                is ConnectionState.Enumerating -> {
                    Text(
                        guideStatus,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.GpsFixed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(stringResource(R.string.no_guide_camera_connected), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { viewModel.requestGuideConnect() }) {
                            Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.connect_guide_camera))
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                color = Color(0xAA000000)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                    }
                    Text(stringResource(R.string.guiding), color = Color.White, fontSize = 14.sp)
                }
            }

            if (guideStatus.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                    color = Color(0xAA000000)
                ) {
                    Text(
                        guideStatus,
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
        ) {
            LazyColumn(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    GuideSection(title = stringResource(R.string.guide_camera)) {
                        val connected = connectionState is ConnectionState.Connected
                        Text(
                            when (val state = connectionState) {
                                is ConnectionState.Connected -> stringResource(R.string.guide_camera_info, state.info.name, state.info.serialNumber)
                                is ConnectionState.Error -> state.message
                                is ConnectionState.Connecting -> stringResource(R.string.connecting)
                                is ConnectionState.Enumerating -> stringResource(R.string.searching)
                                is ConnectionState.Disconnected -> stringResource(R.string.disconnected)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                viewModel.guideCameraManager.enumerateDevices()
                                viewModel.showGuideDevicePicker()
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.scan))
                            }
                            Button(
                                onClick = {
                                    if (connected) viewModel.disconnectGuideCamera() else viewModel.requestGuideConnect()
                                }
                            ) {
                                Icon(
                                    if (connected) Icons.Default.LinkOff else Icons.Default.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(if (connected) R.string.disconnect else R.string.connect))
                            }
                        }
                    }
                }

                item {
                    GuideSection(title = stringResource(R.string.guide_exposure)) {
                        Slider(
                            value = exposureUs.coerceIn(50_000f, 5_000_000f),
                            onValueChange = { viewModel.setGuideExposure(it) },
                            valueRange = 50_000f..5_000_000f,
                            enabled = connectionState is ConnectionState.Connected
                        )
                        Text(
                            stringResource(R.string.guide_exposure_seconds, exposureUs / 1_000_000f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = gain.coerceIn(0f, 30f),
                            onValueChange = { viewModel.setGuideGain(it) },
                            valueRange = 0f..30f,
                            enabled = connectionState is ConnectionState.Connected
                        )
                        Text(
                            stringResource(R.string.guide_gain_db, gain),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    GuideSection(title = stringResource(R.string.guide_star)) {
                        Text(
                            guideStar?.let {
                                stringResource(R.string.guide_star_value, it.x, it.y, it.snr)
                            } ?: stringResource(R.string.no_star_detected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.detected_stars, guideStars.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (referenceStars.isNotEmpty()) {
                                stringResource(R.string.reference_stars, referenceStars.size)
                            } else {
                                stringResource(R.string.reference_unlocked)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.lockGuideStar() }) {
                                Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.lock))
                            }
                            OutlinedButton(onClick = { viewModel.clearGuideLock() }) {
                                Text(stringResource(R.string.clear))
                            }
                        }
                    }
                }

                item {
                    GuideSection(title = stringResource(R.string.calibration)) {
                        Text(
                            when (calibrationState) {
                                GuideCalibrationState.IDLE -> stringResource(R.string.not_calibrated)
                                GuideCalibrationState.RUNNING -> stringResource(R.string.running_calibration)
                                GuideCalibrationState.COMPLETE -> stringResource(R.string.calibrated)
                                GuideCalibrationState.FAILED -> stringResource(R.string.calibration_failed)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        calibration?.let {
                            Text(
                                stringResource(R.string.guide_rates, it.eastRatePxPerSec, it.northRatePxPerSec),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        GuideSlider(
                            label = stringResource(R.string.calibration_pulse),
                            value = calibrationPulseMs.toFloat(),
                            range = 300f..5000f,
                            display = stringResource(R.string.milliseconds_value, calibrationPulseMs),
                            onChange = { viewModel.setGuideCalibrationPulseMs(it.toInt()) }
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.startGuideCalibration() },
                                enabled = !guideCalibrating && connectionState is ConnectionState.Connected
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.calibrate))
                            }
                            OutlinedButton(
                                onClick = { viewModel.clearGuideCalibration() },
                                enabled = !guideCalibrating
                            ) {
                                Text(stringResource(R.string.clear))
                            }
                        }
                    }
                }

                item {
                    GuideSection(title = stringResource(R.string.autoguide)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(if (guideRunning) R.string.running else R.string.stopped))
                            Switch(
                                checked = guideRunning,
                                onCheckedChange = { viewModel.setGuideRunning(it) }
                            )
                        }
                        GuideToggle(stringResource(R.string.multi_star), multiStarEnabled) { viewModel.setGuideMultiStarEnabled(it) }
                        Text(
                            stringResource(R.string.algorithm),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            GuideAlgorithm.entries.chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    row.forEach { item ->
                                        val selected = item == algorithm
                                        OutlinedButton(
                                            onClick = { viewModel.setGuideAlgorithm(item) }
                                        ) {
                                            Text(
                                                algorithmLabel(item),
                                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        correction?.let {
                            Text(
                                stringResource(R.string.guide_correction, it.dxPx, it.dyPx, it.raPulseMs, it.decPulseMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        GuideSlider(
                            label = stringResource(R.string.ra_aggressiveness),
                            value = raAggressiveness,
                            range = 0.1f..1.0f,
                            display = "%.0f%%".format(Locale.US, raAggressiveness * 100f),
                            onChange = { viewModel.setGuideRaAggressiveness(it) }
                        )
                        GuideSlider(
                            label = stringResource(R.string.dec_aggressiveness),
                            value = decAggressiveness,
                            range = 0.1f..1.0f,
                            display = "%.0f%%".format(Locale.US, decAggressiveness * 100f),
                            onChange = { viewModel.setGuideDecAggressiveness(it) }
                        )
                        GuideSlider(
                            label = stringResource(R.string.min_move),
                            value = minMovePx,
                            range = 0.05f..1.0f,
                            display = "%.2f px".format(Locale.US, minMovePx),
                            onChange = { viewModel.setGuideMinMovePx(it) }
                        )
                        Text(
                            stringResource(R.string.min_move_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        GuideToggle(stringResource(R.string.reverse_ra), reverseRa) { viewModel.setGuideReverseRa(it) }
                        GuideToggle(stringResource(R.string.reverse_dec), reverseDec) { viewModel.setGuideReverseDec(it) }
                    }
                }

                item {
                    GuideSection(title = stringResource(R.string.guide_graph)) {
                        GuideGraph(
                            history = guideHistory,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                stringResource(R.string.ra_rms, raRmsPx),
                                color = Color(0xFF42A5F5),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                stringResource(R.string.dec_rms, decRmsPx),
                                color = Color(0xFFEF5350),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            stringResource(R.string.total_rms_samples, totalRmsPx, guideHistory.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { viewModel.clearGuideHistory() },
                            enabled = guideHistory.isNotEmpty()
                        ) {
                            Text(stringResource(R.string.clear_graph))
                        }
                    }
                }

                item {
                    GuideSection(title = stringResource(R.string.tab_mount)) {
                        Text(
                            when (val state = mountConnectionState) {
                                is MountConnectionState.Connected -> stringResource(R.string.connected)
                                is MountConnectionState.Connecting -> stringResource(R.string.connecting)
                                is MountConnectionState.Error -> state.message
                                is MountConnectionState.Disconnected -> stringResource(R.string.disconnected)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        mountCoordinates?.let {
                            Text(
                                "${it.formatRa()}  ${it.formatDec()}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (mountConnectionState is MountConnectionState.Connected) {
                                        viewModel.disconnectMount()
                                    } else {
                                        viewModel.connectMount()
                                    }
                                },
                                enabled = !mountBusy
                            ) {
                                Text(stringResource(if (mountConnectionState is MountConnectionState.Connected) R.string.disconnect else R.string.connect))
                            }
                            OutlinedButton(
                                onClick = { viewModel.readMountCoordinates() },
                                enabled = mountConnectionState is MountConnectionState.Connected && !mountBusy
                            ) {
                                Text(stringResource(R.string.read))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideGraph(
    history: List<GuideHistoryPoint>,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.background(Color(0xFF101318))
    ) {
        val padding = 8.dp.toPx()
        val graphWidth = size.width - padding * 2f
        val graphHeight = size.height - padding * 2f
        if (graphWidth <= 0f || graphHeight <= 0f) return@Canvas

        val centerY = padding + graphHeight / 2f
        drawLine(
            color = Color(0xFF68717D),
            start = Offset(padding, centerY),
            end = Offset(size.width - padding, centerY),
            strokeWidth = 1.dp.toPx()
        )
        listOf(0.25f, 0.75f).forEach { fraction ->
            val y = padding + graphHeight * fraction
            drawLine(
                color = Color(0x334F5965),
                start = Offset(padding, y),
                end = Offset(size.width - padding, y),
                strokeWidth = 1.dp.toPx()
            )
        }
        if (history.size < 2) return@Canvas

        val maxError = max(
            1f,
            history.maxOf { max(abs(it.raErrorPx), abs(it.decErrorPx)) }
        )
        val xStep = graphWidth / (history.size - 1).toFloat()
        val yScale = graphHeight * 0.45f / maxError

        fun drawSeries(value: (GuideHistoryPoint) -> Float, color: Color) {
            for (index in 1 until history.size) {
                val previous = history[index - 1]
                val current = history[index]
                drawLine(
                    color = color,
                    start = Offset(
                        padding + (index - 1) * xStep,
                        centerY - value(previous) * yScale
                    ),
                    end = Offset(
                        padding + index * xStep,
                        centerY - value(current) * yScale
                    ),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        drawSeries({ it.raErrorPx }, Color(0xFF42A5F5))
        drawSeries({ it.decErrorPx }, Color(0xFFEF5350))
    }
}

@Composable
private fun GuideOverlay(
    bitmap: Bitmap?,
    stars: List<GuideStar>,
    referenceStars: List<GuideStar>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val bmp = bitmap ?: return@Canvas
        if (bmp.width <= 0 || bmp.height <= 0) return@Canvas

        val scale = minOf(size.width / bmp.width.toFloat(), size.height / bmp.height.toFloat())
        val x0 = (size.width - bmp.width * scale) / 2f
        val y0 = (size.height - bmp.height * scale) / 2f

        fun map(star: GuideStar): Offset {
            return Offset(x0 + star.x * scale, y0 + star.y * scale)
        }

        referenceStars.forEach {
            val p = map(it)
            drawCircle(Color(0xFF55CCFF), radius = 18f, center = p, style = Stroke(width = 2f))
            drawLine(Color(0xFF55CCFF), Offset(p.x - 26f, p.y), Offset(p.x + 26f, p.y), strokeWidth = 2f)
            drawLine(Color(0xFF55CCFF), Offset(p.x, p.y - 26f), Offset(p.x, p.y + 26f), strokeWidth = 2f)
        }
        stars.forEachIndexed { index, star ->
            val p = map(star)
            drawCircle(
                if (index == 0) Color(0xFFFFD54F) else Color(0xAAFFE082),
                radius = if (index == 0) 12f else 8f,
                center = p,
                style = Stroke(width = 2f)
            )
        }
    }
}

@Composable
private fun GuideSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun algorithmLabel(algorithm: GuideAlgorithm): String = stringResource(
    when (algorithm) {
        GuideAlgorithm.HYSTERESIS -> R.string.guide_algorithm_hysteresis
        GuideAlgorithm.LOW_PASS -> R.string.guide_algorithm_low_pass
        GuideAlgorithm.RESIST_SWITCH -> R.string.guide_algorithm_resist_switch
        GuideAlgorithm.PREDICTIVE_RA -> R.string.guide_algorithm_predictive_ra
    }
)

@Composable
private fun GuideSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit
) {
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(display, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
}

@Composable
private fun GuideToggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun GuideDevicePickerDialog(
    devices: List<DeviceEntry>,
    onSelect: (DeviceEntry) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_guide_camera)) },
        text = {
            if (devices.isEmpty()) {
                Text(stringResource(R.string.no_guide_camera_found))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(devices) { entry ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(entry) }
                                .padding(12.dp)
                        ) {
                            Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "SN: ${entry.serialNumber}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Divider(modifier = Modifier.padding(top = 8.dp))
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
