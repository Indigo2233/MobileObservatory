package com.indigo.mobileobservatory.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.guide.*
import com.indigo.mobileobservatory.mount.MountConnectionState
import com.indigo.mobileobservatory.ui.components.GainControl
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import java.util.Locale
import kotlin.math.*

private val raColor = Color(0xFF42A5F5)
private val decColor = Color(0xFFEF5350)
private fun number(value: Float, places: Int = 2) = String.format(Locale.getDefault(), "%.${places}f", value)

@Composable
fun GuideScreen(viewModel: CameraViewModel, onBack: () -> Unit) {
    val connection by viewModel.guideConnectionState.collectAsStateWithPageLifecycle()
    val exposing by viewModel.guideExposing.collectAsStateWithPageLifecycle()
    val exposureRequested by viewModel.guideExposureRequested.collectAsStateWithPageLifecycle()
    val running by viewModel.guideRunning.collectAsStateWithPageLifecycle()
    val calibrating by viewModel.guideCalibrating.collectAsStateWithPageLifecycle()
    val autoStarting by viewModel.guideAutoStarting.collectAsStateWithPageLifecycle()
    val status by viewModel.guideStatus.collectAsStateWithPageLifecycle()
    val display by viewModel.guideDisplayFrame.collectAsStateWithPageLifecycle()
    val references by viewModel.guideReferenceStars.collectAsStateWithPageLifecycle()
    val history by viewModel.guideHistory.collectAsStateWithPageLifecycle()
    val focal by viewModel.guideFocalLength.collectAsStateWithPageLifecycle()
    val geometry by viewModel.guideCameraGeometry.collectAsStateWithPageLifecycle()
    val picker by viewModel.showGuideDevicePicker.collectAsStateWithPageLifecycle()
    val devices by viewModel.guideDevices.collectAsStateWithPageLifecycle()
    val writing by viewModel.guideGainWriteInProgress.collectAsStateWithPageLifecycle()
    var panel by rememberSaveable { mutableStateOf("") }
    var samples by rememberSaveable { mutableIntStateOf(100) }
    var showPulses by rememberSaveable { mutableStateOf(true) }
    var plotRange by rememberSaveable { mutableFloatStateOf(4f) }
    val scale = GuideStarAnalysis.arcsecPerPixel(geometry?.let { it.pixelSizeUm * it.binning }, focal)
    val unit = if (scale != null) "″" else "px"
    val factor = scale ?: 1f
    val window = remember(history, samples) { GuidePlotWindow(history, samples) }
    val foreground = rememberPageForeground()
    val connected = connection is ConnectionState.Connected

    if (picker && foreground) {
        val available = viewModel.camerasAvailableToGuide(devices)
        AlertDialog(onDismissRequest = viewModel::hideGuideDevicePicker,
            title = { Text(stringResource(R.string.select_guide_camera)) },
            text = {
                if (available.isEmpty()) Text(stringResource(R.string.no_guide_camera_found))
                else LazyColumn { items(available) { entry ->
                    TextButton(onClick = { viewModel.hideGuideDevicePicker(); viewModel.connectGuideCameraBySn(entry.serialNumber) }) {
                        Text("${entry.name} · ${entry.serialNumber}")
                    }
                } }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = viewModel::hideGuideDevicePicker) { Text(stringResource(R.string.cancel)) } })
    }
    if (panel.isNotEmpty() && foreground) {
        AlertDialog(onDismissRequest = { panel = "" },
            title = { Text(stringResource(if (panel == "devices") R.string.gw_devices else R.string.settings)) },
            text = { Box(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                if (panel == "devices") GuideDevices(viewModel) { panel = "" } else GuideSettings(viewModel)
            } }, confirmButton = {
                if (running || calibrating || autoStarting) TextButton(onClick = { viewModel.setGuideRunning(false) }) { Text(stringResource(R.string.gw_stop_guiding)) }
                if (exposing || exposureRequested) TextButton(onClick = { viewModel.setGuideExposureRunning(false) }) { Text(stringResource(R.string.gw_stop_exposure)) }
                TextButton(onClick = { panel = "" }) { Text(stringResource(R.string.close)) }
            })
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
            GuideAction(R.string.gw_devices, Icons.Default.Link, true, Modifier.weight(1f)) { panel = "devices" }
            GuideAction(if (exposing || exposureRequested) R.string.gw_stop_exposure else R.string.gw_start_exposure,
                if (exposing || exposureRequested) Icons.Default.Stop else Icons.Default.PlayArrow, connected, Modifier.weight(1f)) {
                viewModel.setGuideExposureRunning(!(exposing || exposureRequested))
            }
            GuideAction(if (running || calibrating || autoStarting) R.string.gw_stop_guiding else R.string.gw_auto_guide,
                if (running || calibrating || autoStarting) Icons.Default.Stop else Icons.Default.GpsFixed, connected && !writing, Modifier.weight(1f)) { viewModel.autoSelectAndGuide() }
            GuideAction(R.string.settings, Icons.Default.Settings, true, Modifier.weight(1f)) { panel = "settings" }
        }
        Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 640.dp
            if (wide) Row(Modifier.fillMaxSize()) {
                GuideImage(display, references, viewModel::selectGuideStar, Modifier.weight(1f).fillMaxHeight(), foreground && panel.isEmpty())
                GuideStarPanel(display, factor, unit, Modifier.width(210.dp).fillMaxHeight())
            } else Column(Modifier.fillMaxSize()) {
                GuideImage(display, references, viewModel::selectGuideStar, Modifier.weight(1f).fillMaxWidth(), foreground && panel.isEmpty())
                Text(display?.profile?.let { "HFR ${number(it.hfrPx * factor)} $unit · SNR ${number(display?.primary?.snr ?: 0f)}" }
                    ?: stringResource(R.string.no_star_detected), Modifier.padding(4.dp), fontSize = 11.sp)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text("RA", color = raColor, modifier = Modifier.padding(start = 8.dp), fontSize = 11.sp)
            Text(" / DEC", color = decColor, fontSize = 11.sp)
            listOf(50, 100, 200).forEach { count ->
                TextButton(onClick = { samples = count }) { Text(if (samples == count) "[$count]" else "$count", fontSize = 11.sp) }
            }
            Checkbox(checked = showPulses, onCheckedChange = { showPulses = it }, modifier = Modifier.size(36.dp))
            Text(stringResource(R.string.gw_corrections), fontSize = 11.sp)
            GuideChoice(listOf(1f, 2f, 4f, 8f, 16f), plotRange, { "±${number(it, 0)} $unit" }, { plotRange = it })
            TextButton(onClick = viewModel::clearGuideHistory) { Text(stringResource(R.string.clear_graph), fontSize = 11.sp) }
        }
        Row(Modifier.fillMaxWidth().height(128.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                GuideGraph(window, factor, plotRange, showPulses, Modifier.fillMaxSize())
                Text("+${number(plotRange, 0)} $unit", color = Color.LightGray, fontSize = 9.sp, modifier = Modifier.align(Alignment.TopEnd))
                Text("−${number(plotRange, 0)} $unit", color = Color.LightGray, fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomEnd))
            }
            Box(Modifier.size(128.dp)) {
                GuideScatter(window, factor, plotRange, Modifier.fillMaxSize())
                Text("RA / DEC · $unit", color = Color.LightGray, fontSize = 9.sp, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
        val rms = remember(window.points) { GuideModule().rms(window.points) }
        Text(stringResource(R.string.gw_rms, rms.raPx * factor, rms.decPx * factor, rms.totalPx * factor, unit, window.points.size, samples),
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GuideAction(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, modifier: Modifier, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = modifier) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
        Text(stringResource(label), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun GuideImage(frame: GuideDisplayFrame?, references: List<GuideStar>, onSelect: (Float, Float) -> Unit,
    modifier: Modifier = Modifier, active: Boolean = true) {
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var panX by rememberSaveable { mutableFloatStateOf(0f) }; var panY by rememberSaveable { mutableFloatStateOf(0f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(frame?.width, frame?.height) { zoom = 1f; panX = 0f; panY = 0f }
    BackHandler(active && (zoom != 1f || panX != 0f || panY != 0f)) { zoom = 1f; panX = 0f; panY = 0f }
    val viewport = GuideViewport(canvasSize.width.toFloat(), canvasSize.height.toFloat(), frame?.width ?: 0, frame?.height ?: 0, zoom, panX, panY)
    val currentViewport by rememberUpdatedState(viewport)
    val currentSelect by rememberUpdatedState(onSelect)
    val description = stringResource(R.string.gw_image)
    Canvas(modifier.background(Color.Black).clipToBounds().testTag("guide-image")
        .semantics { contentDescription = description }
        .onSizeChanged { canvasSize = it }
        .pointerInput(frame?.width, frame?.height) {
            detectTransformGestures { centroid, pan, change, _ ->
                val next = (zoom * change).coerceIn(1f, 10f)
                val ratio = next / zoom
                panX = (panX + size.width / 2f - centroid.x) * ratio + centroid.x - size.width / 2f + pan.x
                panY = (panY + size.height / 2f - centroid.y) * ratio + centroid.y - size.height / 2f + pan.y
                zoom = next
            }
        }.pointerInput(Unit) { detectTapGestures(onTap = { point ->
            currentViewport.toImage(point.x, point.y)?.let { currentSelect(it.x, it.y) }
        }, onDoubleTap = { zoom = 1f; panX = 0f; panY = 0f }) }) {
        val source = frame ?: return@Canvas
        if (viewport.scale <= 0f) return@Canvas
        withTransform({ translate(viewport.left, viewport.top); scale(viewport.scale, viewport.scale, Offset.Zero) }) {
            clipRect(0f, 0f, source.width.toFloat(), source.height.toFloat()) {
                drawImage(source.bitmap.asImageBitmap(), dstSize = IntSize(source.width, source.height))
                val stroke = 1.5.dp.toPx() / viewport.scale
                val radius = 8.dp.toPx() / viewport.scale
                val selected = if (references.isEmpty()) source.stars else references.mapNotNull { reference ->
                    source.stars.filter { it.flux / reference.flux in .2f..5f }
                        .minByOrNull { hypot(it.x - reference.x, it.y - reference.y) }
                        ?.takeIf { hypot(it.x - reference.x, it.y - reference.y) <= 60f }
                }.distinct()
                selected.filter { viewport.contains(it.x, it.y) }.forEach { star ->
                    drawCircle(Color(0xFF70DDA0), radius, Offset(star.x, star.y), style = Stroke(stroke))
                }
                source.primary?.takeIf { viewport.contains(it.x, it.y) }?.let { star ->
                    val center = Offset(star.x, star.y); val color = Color(0xFFFFD54F)
                    drawLine(color.copy(alpha = .65f), Offset(0f, star.y), Offset(source.width.toFloat(), star.y), stroke)
                    drawLine(color.copy(alpha = .65f), Offset(star.x, 0f), Offset(star.x, source.height.toFloat()), stroke)
                    drawRect(color, center - Offset(radius * 1.5f, radius * 1.5f), Size(radius * 3, radius * 3), style = Stroke(stroke))
                }
            }
        }
    }
}

@Composable
private fun GuideStarPanel(frame: GuideDisplayFrame?, factor: Float, unit: String, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState()).padding(8.dp)) {
        Text(stringResource(R.string.guide_star), style = MaterialTheme.typography.labelMedium)
        val profile = frame?.profile
        if (profile == null) {
            Text(stringResource(R.string.no_star_detected), fontSize = 11.sp)
        } else {
            GuideStarDetails(profile, frame.primary, factor, unit)
        }
    }
}

@Composable
private fun GuideStarDetails(profile: GuideStarProfile, primary: GuideStar?, factor: Float, unit: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(64.dp).background(Color.Black)) {
                val peak = (profile.peak - profile.background).coerceAtLeast(1f)
                profile.pixels.forEachIndexed { index, value ->
                    val level = ((value - profile.background) / peak).coerceIn(0f, 1f)
                    drawRect(Color(level, level, level), Offset(index % profile.width * size.width / profile.width,
                        index / profile.width * size.height / profile.height), Size(size.width / profile.width + .1f, size.height / profile.height + .1f))
                }
            }
            Column(Modifier.padding(start = 6.dp)) {
                Text("HFR ${number(profile.hfrPx * factor)} $unit", fontSize = 11.sp)
                Text("SNR ${number(primary?.snr ?: 0f)}", fontSize = 11.sp)
                Text(stringResource(R.string.gw_peak, profile.peak), fontSize = 11.sp)
            }
        }
        Text(stringResource(R.string.gw_flux, profile.flux), fontSize = 11.sp)
        Text(stringResource(R.string.gw_profile), fontSize = 11.sp)
        Canvas(Modifier.fillMaxWidth().height(65.dp).background(Color(0xFF101318))) {
            val maxIntensity = profile.radialIntensity.maxOrNull()?.coerceAtLeast(1f) ?: 1f
            fun series(values: List<Float>, divisor: Float, color: Color) {
                for (i in 1 until values.size) drawLine(color,
                    Offset((i - 1) * size.width / (values.size - 1), size.height * (1 - values[i - 1] / divisor)),
                    Offset(i * size.width / (values.size - 1), size.height * (1 - values[i] / divisor)), 2f)
            }
            series(profile.radialIntensity, maxIntensity, raColor)
            series(profile.encircledEnergy, 1f, Color(0xFFFFD54F))
            val hfrX = profile.hfrPx / (profile.radialIntensity.size - 1) * size.width
            drawLine(Color.White.copy(alpha = .5f), Offset(hfrX, 0f), Offset(hfrX, size.height), 1f)
            drawLine(Color.White.copy(alpha = .25f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        }
        Text(stringResource(R.string.gw_profile_legend), fontSize = 10.sp)
        Text(stringResource(R.string.gw_profile_radius, (profile.radialIntensity.size - 1) * factor, unit), fontSize = 10.sp)
}

@Composable
internal fun GuideGraph(window: GuidePlotWindow, factor: Float, range: Float, pulses: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF101318)).clipToBounds().testTag("guide-graph")) {
        val center = size.height / 2
        for (i in 1..3) drawLine(Color(0x3368717D), Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1f)
        val yScale = size.height * .48f / range
        fun y(value: Float) = center - (value * factor).coerceIn(-range, range) * yScale
        window.points.forEachIndexed { i, point ->
            val x = window.xFraction(i) * size.width
            if (pulses) {
                if (point.raPulseMs != 0) drawLine(raColor.copy(alpha = .45f), Offset(x, center), Offset(x, y(point.raCorrectionPx)), 3.dp.toPx())
                if (point.decPulseMs != 0) drawLine(decColor.copy(alpha = .45f), Offset(x + 2, center), Offset(x + 2, y(point.decCorrectionPx)), 3.dp.toPx())
            }
            drawCircle(raColor, 2f, Offset(x, y(point.raErrorPx)))
            drawCircle(decColor, 2f, Offset(x, y(point.decErrorPx)))
            if (i > 0) {
                val previous = window.points[i - 1]; val prevX = window.xFraction(i - 1) * size.width
                drawLine(raColor, Offset(prevX, y(previous.raErrorPx)), Offset(x, y(point.raErrorPx)), 1.5.dp.toPx())
                drawLine(decColor, Offset(prevX, y(previous.decErrorPx)), Offset(x, y(point.decErrorPx)), 1.5.dp.toPx())
            }
        }
    }
}

@Composable
private fun GuideScatter(window: GuidePlotWindow, factor: Float, range: Float, modifier: Modifier) {
    Canvas(modifier.background(Color(0xFF101318)).clipToBounds().testTag("guide-scatter")) {
        val center = Offset(size.width / 2, size.height / 2); val radius = min(size.width, size.height) * .47f
        listOf(.5f, 1f).forEach { drawCircle(Color(0x4468717D), radius * it, center, style = Stroke(1f)) }
        drawLine(Color(0x4468717D), Offset(0f, center.y), Offset(size.width, center.y), 1f)
        drawLine(Color(0x4468717D), Offset(center.x, 0f), Offset(center.x, size.height), 1f)
        window.points.forEachIndexed { index, point ->
            val dx = point.raErrorPx * factor / range
            val dy = -point.decErrorPx * factor / range
            val p = center + Offset(dx.coerceIn(-1f, 1f) * radius, dy.coerceIn(-1f, 1f) * radius)
            drawCircle(if (abs(dx) > 1 || abs(dy) > 1) decColor else raColor.copy(alpha = .25f + .75f * (index + 1) / window.points.size), 2.dp.toPx(), p)
        }
    }
}

@Composable
private fun GuideDevices(vm: CameraViewModel, close: () -> Unit) {
    val camera by vm.guideConnectionState.collectAsStateWithPageLifecycle()
    val mount by vm.mountConnectionState.collectAsStateWithPageLifecycle()
    val busy by vm.mountBusy.collectAsStateWithPageLifecycle()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.guide_camera))
        Text((camera as? ConnectionState.Connected)?.info?.name ?: stringResource(R.string.disconnected))
        Row {
            TextButton(onClick = { close(); vm.guideCameraManager.enumerateDevices(); vm.showGuideDevicePicker() }) { Text(stringResource(R.string.scan)) }
            TextButton(onClick = { if (camera is ConnectionState.Connected) vm.disconnectGuideCamera() else { close(); vm.requestGuideConnect() } }) {
                Text(stringResource(if (camera is ConnectionState.Connected) R.string.disconnect else R.string.connect))
            }
        }
        Divider()
        Text(stringResource(R.string.tab_mount))
        Text(stringResource(if (mount is MountConnectionState.Connected) R.string.connected else R.string.disconnected))
        TextButton(onClick = { if (mount is MountConnectionState.Connected) vm.disconnectMount() else vm.connectMount() }, enabled = !busy) {
            Text(stringResource(if (mount is MountConnectionState.Connected) R.string.disconnect else R.string.connect))
        }
    }
}

@Composable
private fun GuideSettings(vm: CameraViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf(R.string.guide_camera, R.string.calibration, R.string.gw_algorithms, R.string.gw_optics)
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            labels.forEachIndexed { index, label -> TextButton(onClick = { tab = index }) { Text(stringResource(label), color =
                if (tab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (tab) {
                0 -> GuideCameraSettings(vm)
                1 -> GuideCalibrationSettings(vm)
                2 -> GuideAlgorithmSettings(vm)
                3 -> {
                    val focal by vm.guideFocalLength.collectAsStateWithPageLifecycle()
                    val geometry by vm.guideCameraGeometry.collectAsStateWithPageLifecycle()
                    var text by remember(focal) { mutableStateOf(if (focal > 0) focal.toInt().toString() else "") }
                    OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.gw_focal_mm)) }, singleLine = true)
                    TextButton(onClick = { if (text.isBlank()) vm.setGuideFocalLength(0f) else text.toFloatOrNull()?.takeIf { it > 0 && it.isFinite() }?.let(vm::setGuideFocalLength) }) { Text(stringResource(R.string.save)) }
                    Text(stringResource(R.string.gw_scale_help), fontSize = 12.sp)
                    geometry?.let { Text(stringResource(R.string.gw_pixel_um, it.pixelSizeUm * it.binning), fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun GuideCameraSettings(vm: CameraViewModel) {
    val exposure by vm.guideExposureUs.collectAsStateWithPageLifecycle()
    val gain by vm.guideGain.collectAsStateWithPageLifecycle()
    val capability by vm.guideGainCapability.collectAsStateWithPageLifecycle()
    val writing by vm.guideGainWriteInProgress.collectAsStateWithPageLifecycle()
    val format by vm.guidePixelFormat.collectAsStateWithPageLifecycle()
    val formats by vm.guideSupportedPixelFormats.collectAsStateWithPageLifecycle()
    val mode by vm.guideReadoutMode.collectAsStateWithPageLifecycle()
    val modes by vm.guideReadoutModes.collectAsStateWithPageLifecycle()
    val native by vm.guideNativeReadoutModes.collectAsStateWithPageLifecycle()
    val nativeId by vm.guideNativeReadoutId.collectAsStateWithPageLifecycle()
    val connection by vm.guideConnectionState.collectAsStateWithPageLifecycle()
    val enabled = connection is ConnectionState.Connected && !writing
    var seconds by remember(exposure) { mutableStateOf(number(exposure / 1000000, 2)) }
    OutlinedTextField(seconds, { seconds = it }, label = { Text(stringResource(R.string.guide_exposure)) }, suffix = { Text("s") }, singleLine = true, enabled = enabled)
    TextButton(onClick = { seconds.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in .05f..5f }?.let { vm.setGuideExposure(it * 1000000) } }, enabled = enabled) { Text(stringResource(R.string.gw_apply_exposure)) }
    capability?.let { GainControl(capability = it, gain = gain, onGainChange = vm::setGuideGain, enabled = enabled, writeInProgress = writing) }
    Text(stringResource(R.string.gw_bit_depth), style = MaterialTheme.typography.labelMedium)
    GuideChoice(formats, format, { "${it.nativeBits} bit · ${it.name}" }, vm::setGuidePixelFormat, enabled)
    Text(stringResource(R.string.gw_readout), style = MaterialTheme.typography.labelMedium)
    if (native.isNotEmpty()) GuideChoice(native.map { it.id }, nativeId, { id -> native.firstOrNull { it.id == id }?.displayName ?: id }, vm::setGuideNativeReadoutMode, enabled)
    else GuideChoice(modes, mode, { it.displayName }, vm::setGuideReadoutMode, enabled)
    Text(stringResource(R.string.gw_raw_help), fontSize = 12.sp)
}

@Composable
private fun GuideCalibrationSettings(vm: CameraViewModel) {
    val steps by vm.guideCalibrationSteps.collectAsStateWithPageLifecycle()
    val distance by vm.guideCalibrationDistance.collectAsStateWithPageLifecycle()
    val pulse by vm.guideCalibrationPulseMs.collectAsStateWithPageLifecycle()
    val calibration by vm.guideCalibration.collectAsStateWithPageLifecycle()
    val calibrating by vm.guideCalibrating.collectAsStateWithPageLifecycle()
    val running by vm.guideRunning.collectAsStateWithPageLifecycle()
    val backlash by vm.guideBacklashEnabled.collectAsStateWithPageLifecycle()
    Text(stringResource(R.string.gw_calibration_help), fontSize = 12.sp)
    GuideSettingSlider(stringResource(R.string.gw_steps), steps.toFloat(), 5f..20f, { vm.setGuideCalibrationSteps(it.roundToInt()) }, !calibrating)
    GuideSettingSlider(stringResource(R.string.gw_distance), distance, 10f..50f, vm::setGuideCalibrationDistance, !calibrating)
    GuideSettingSlider(stringResource(R.string.calibration_pulse), pulse.toFloat(), 300f..5000f, { vm.setGuideCalibrationPulseMs(it.roundToInt()) }, !calibrating)
    GuideToggle(stringResource(R.string.gw_backlash), backlash, vm::setGuideBacklashEnabled)
    calibration?.let {
        Text(stringResource(R.string.guide_rates, it.eastRatePxPerSec, it.northRatePxPerSec), fontSize = 12.sp)
        Text(stringResource(R.string.gw_calibration_result, it.eastSteps, it.northSteps, it.raBacklashMs, it.decBacklashMs, it.fitResidualPx), fontSize = 12.sp)
    }
    Row {
        TextButton(onClick = { if (calibrating) vm.setGuideRunning(false) else vm.startGuideCalibration() }, enabled = !running) { Text(stringResource(if (calibrating) R.string.gw_stop_guiding else R.string.calibration)) }
        TextButton(onClick = vm::clearGuideCalibration, enabled = !calibrating && !running) { Text(stringResource(R.string.clear)) }
    }
}

@Composable
private fun GuideAlgorithmSettings(vm: CameraViewModel) {
    val algorithm by vm.guideAlgorithm.collectAsStateWithPageLifecycle()
    val multi by vm.guideMultiStarEnabled.collectAsStateWithPageLifecycle()
    val ra by vm.guideRaAggressiveness.collectAsStateWithPageLifecycle()
    val dec by vm.guideDecAggressiveness.collectAsStateWithPageLifecycle()
    val minMove by vm.guideMinMovePx.collectAsStateWithPageLifecycle()
    val reverseRa by vm.guideReverseRa.collectAsStateWithPageLifecycle()
    val reverseDec by vm.guideReverseDec.collectAsStateWithPageLifecycle()
    val names = GuideAlgorithm.entries.associateWith { stringResource(when (it) {
        GuideAlgorithm.HYSTERESIS -> R.string.guide_algorithm_hysteresis
        GuideAlgorithm.LOW_PASS -> R.string.guide_algorithm_low_pass
        GuideAlgorithm.RESIST_SWITCH -> R.string.guide_algorithm_resist_switch
        GuideAlgorithm.PREDICTIVE_RA -> R.string.guide_algorithm_predictive_ra
    }) }
    GuideChoice(GuideAlgorithm.entries, algorithm, { names.getValue(it) }, vm::setGuideAlgorithm)
    GuideToggle(stringResource(R.string.gw_multistar), multi, vm::setGuideMultiStarEnabled)
    GuideSettingSlider(stringResource(R.string.gw_ra_aggression), ra, .1f..1f, vm::setGuideRaAggressiveness)
    GuideSettingSlider(stringResource(R.string.gw_dec_aggression), dec, .1f..1f, vm::setGuideDecAggressiveness)
    GuideSettingSlider(stringResource(R.string.gw_min_move), minMove, .05f..1f, vm::setGuideMinMovePx)
    GuideToggle(stringResource(R.string.gw_reverse_ra), reverseRa, vm::setGuideReverseRa)
    GuideToggle(stringResource(R.string.gw_reverse_dec), reverseDec, vm::setGuideReverseDec)
}

@Composable
private fun GuideToggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 12.sp); Switch(checked, change)
    }
}

@Composable
private fun GuideSettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit, enabled: Boolean = true) {
    Text("$label · ${number(value)}", fontSize = 12.sp)
    var draft by remember(value) { mutableFloatStateOf(value) }
    Slider(draft.coerceIn(range), { draft = it }, valueRange = range, enabled = enabled, onValueChangeFinished = { change(draft) })
}

@Composable
private fun <T> GuideChoice(values: List<T>, selected: T, label: (T) -> String, change: (T) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled && values.isNotEmpty()) { Text(label(selected), fontSize = 12.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text(label(value)) }, onClick = { open = false; change(value) }) }
        }
    }
}
