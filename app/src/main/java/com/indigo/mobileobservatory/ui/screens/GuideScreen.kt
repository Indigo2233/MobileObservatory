package com.indigo.mobileobservatory.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.guide.*
import com.indigo.mobileobservatory.mount.MountConnectionState
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
    val selectedStar by viewModel.guideReferenceStar.collectAsStateWithPageLifecycle()
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
    if (panel == "settings" && foreground) {
        GuideSettingsDialog(viewModel, onDismiss = { panel = "" },
            guiding = running || calibrating || autoStarting, exposing = exposing || exposureRequested)
    }
    if (panel in listOf("devices", "star", "plots") && foreground) {
        AlertDialog(onDismissRequest = { panel = "" },
            title = { Text(stringResource(when (panel) {
                "devices" -> R.string.gw_devices
                "star" -> R.string.guide_star
                else -> R.string.gw_plot_settings
            })) },
            text = { Box(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                when (panel) {
                    "devices" -> GuideDevices(viewModel) { panel = "" }
                    "star" -> GuideStarPanel(display, factor, unit, Modifier.fillMaxWidth())
                    "plots" -> GuidePlotSettings(samples, { samples = it }, showPulses, { showPulses = it },
                        plotRange, { plotRange = it }, unit)
                }
            } }, confirmButton = {
                if (running || calibrating || autoStarting) TextButton(onClick = { viewModel.setGuideRunning(false) }) { Text(stringResource(R.string.gw_stop_guiding)) }
                if (exposing || exposureRequested) TextButton(onClick = { viewModel.setGuideExposureRunning(false) }) { Text(stringResource(R.string.gw_stop_exposure)) }
                TextButton(onClick = { panel = "" }) { Text(stringResource(R.string.close)) }
            })
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 480.dp
            val busy = running || calibrating || autoStarting
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
            GuideAction(R.string.gw_devices, Icons.Default.Link, true, Modifier.weight(1f), stacked) { panel = "devices" }
            GuideAction(if (exposing || exposureRequested) R.string.gw_stop_exposure else R.string.gw_start_exposure,
                if (exposing || exposureRequested) Icons.Default.Stop else Icons.Default.Loop, connected,
                Modifier.weight(1f).testTag("guide-exposure"), stacked) {
                viewModel.setGuideExposureRunning(!(exposing || exposureRequested))
            }
            GuideAction(R.string.gw_auto_select, Icons.Default.StarBorder,
                connected && !busy && !writing && display?.stars?.isNotEmpty() == true,
                Modifier.weight(1f).testTag("guide-auto-select"), stacked, viewModel::autoSelectGuideStar)
            GuideAction(when {
                calibrating -> R.string.gw_stop_calibration
                busy -> R.string.gw_stop_guiding
                else -> R.string.gw_start_guiding
            }, if (busy) Icons.Default.Stop else Icons.Default.GpsFixed,
                busy || connected && exposing && selectedStar != null && !writing,
                Modifier.weight(1f).testTag("guide-start-stop"), stacked) {
                if (busy) viewModel.setGuideRunning(false) else viewModel.startGuiding()
            }
            GuideAction(R.string.settings, Icons.Default.Settings, true, Modifier.weight(1f), stacked) { panel = "settings" }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val landscape = maxWidth >= 480.dp && maxWidth > maxHeight
            val diagnosticsWidth = (maxWidth * .36f).coerceIn(228.dp, 320.dp)
            val plotHeight = (maxHeight * if (landscape) .3f else .14f).coerceIn(64.dp, 96.dp)
            if (landscape) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GuidePreview(display, references, viewModel::selectGuideStar, status,
                        Modifier.weight(1f).fillMaxHeight(), foreground && panel.isEmpty())
                    Column(Modifier.width(diagnosticsWidth).fillMaxHeight()
                        .verticalScroll(rememberScrollState())) {
                        GuideStarSummary(display, factor, unit) { panel = "star" }
                        GuidePlots(window, factor, unit, plotRange, showPulses, plotHeight,
                            onSettings = { panel = "plots" }, onClear = viewModel::clearGuideHistory)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    GuidePreview(display, references, viewModel::selectGuideStar, status,
                        Modifier.weight(1f).fillMaxWidth(), foreground && panel.isEmpty())
                    GuideStarSummary(display, factor, unit) { panel = "star" }
                    GuidePlots(window, factor, unit, plotRange, showPulses, plotHeight,
                        onSettings = { panel = "plots" }, onClear = viewModel::clearGuideHistory)
                }
            }
        }
    }
}

@Composable
private fun GuidePreview(frame: GuideDisplayFrame?, references: List<GuideStar>, onSelect: (Float, Float) -> Unit,
    status: String, modifier: Modifier, active: Boolean) {
    Box(modifier) {
        GuideImage(frame, references, onSelect, Modifier.fillMaxSize(), active)
        if (status.isNotBlank()) Text(status,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).widthIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .84f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp).testTag("guide-status"),
            color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, lineHeight = 18.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GuideStarSummary(frame: GuideDisplayFrame?, factor: Float, unit: String, onDetails: () -> Unit) {
    val description = stringResource(R.string.gw_star_details)
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(onClickLabel = description, onClick = onDetails).testTag("guide-star-summary")
        .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val profile = frame?.profile
        if (profile != null) GuideStarThumbnail(profile, Modifier.size(40.dp))
        else Box(Modifier.size(40.dp).background(Color.Black), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.StarOutline, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.guide_star), fontSize = 13.sp, lineHeight = 18.sp)
            Text(if (profile == null) stringResource(R.string.no_star_detected) else
                "HFR ${number(profile.hfrPx * factor)} $unit · SNR ${number(frame.primary?.snr ?: 0f)}",
                fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, description, Modifier.size(20.dp))
    }
}

@Composable
private fun GuidePlots(window: GuidePlotWindow, factor: Float, unit: String, range: Float,
    pulses: Boolean, plotHeight: Dp, onSettings: () -> Unit, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("guide-plots")) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f)) {
                Text("RA", color = raColor, fontSize = 13.sp)
                Text(" / DEC", color = decColor, fontSize = 13.sp, maxLines = 1)
            }
            Text(stringResource(R.string.gw_sample_count, window.points.size, window.capacity),
                fontSize = 12.sp, maxLines = 1, modifier = Modifier.testTag("guide-sample-count"))
            IconButton(onClick = onSettings) { Icon(Icons.Default.Tune, stringResource(R.string.gw_plot_settings), Modifier.size(20.dp)) }
            IconButton(onClick = onClear) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.clear_graph), Modifier.size(20.dp)) }
        }
        Row(Modifier.fillMaxWidth().height(plotHeight).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                GuideGraph(window, factor, range, pulses, Modifier.fillMaxSize())
                Text("+${number(range, 0)} $unit", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.align(Alignment.TopEnd))
                Text("−${number(range, 0)} $unit", color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd))
            }
            GuideScatter(window, factor, range, Modifier.size(plotHeight))
        }
        val rms = remember(window.points) { GuideModule().rms(window.points) }
        Text(stringResource(R.string.gw_rms_compact, rms.raPx * factor, rms.decPx * factor, rms.totalPx * factor, unit),
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun GuidePlotSettings(samples: Int, onSamples: (Int) -> Unit, pulses: Boolean, onPulses: (Boolean) -> Unit,
    range: Float, onRange: (Float) -> Unit, unit: String) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.gw_samples))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(50, 100, 200).forEach { count ->
                FilterChip(selected = samples == count, onClick = { onSamples(count) },
                    label = { Text(count.toString()) }, modifier = Modifier.testTag("guide-samples-$count"))
            }
        }
        Text(stringResource(R.string.gw_plot_range))
        GuideChoice(listOf(1f, 2f, 4f, 8f, 16f), range, { "±${number(it, 0)} $unit" }, onRange)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = pulses, onCheckedChange = onPulses, modifier = Modifier.testTag("guide-pulses"))
            Text(stringResource(R.string.gw_corrections))
        }
    }
}

@Composable
private fun GuideAction(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean,
    modifier: Modifier, stacked: Boolean, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = modifier) {
        if (stacked) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(stringResource(label), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
            Text(stringResource(label), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
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
    val currentFrame by rememberUpdatedState(frame)
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
            val star = currentViewport.starAt(currentFrame?.stars.orEmpty(), point.x, point.y, 24.dp.toPx())
            if (star != null) currentSelect(star.x, star.y)
            else currentViewport.toImage(point.x, point.y)?.let { currentSelect(it.x, it.y) }
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
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState())
        .testTag("guide-star-details").padding(8.dp)) {
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
            GuideStarThumbnail(profile, Modifier.size(64.dp))
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
private fun GuideStarThumbnail(profile: GuideStarProfile, modifier: Modifier) {
    Canvas(modifier.background(Color.Black)) {
        val peak = (profile.peak - profile.background).coerceAtLeast(1f)
        profile.pixels.forEachIndexed { index, value ->
            val level = ((value - profile.background) / peak).coerceIn(0f, 1f)
            drawRect(Color(level, level, level), Offset(index % profile.width * size.width / profile.width,
                index / profile.width * size.height / profile.height),
                Size(size.width / profile.width + .1f, size.height / profile.height + .1f))
        }
    }
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
private fun <T> GuideChoice(values: List<T>, selected: T, label: (T) -> String, change: (T) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled && values.isNotEmpty()) { Text(label(selected), fontSize = 12.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text(label(value)) }, onClick = { open = false; change(value) }) }
        }
    }
}
