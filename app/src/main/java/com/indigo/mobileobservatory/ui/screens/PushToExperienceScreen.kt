package com.indigo.mobileobservatory.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PhoneCameraCapability
import com.indigo.mobileobservatory.camera.PhoneLensRole
import com.indigo.mobileobservatory.pointing.GuidanceProximity
import com.indigo.mobileobservatory.pointing.PhoneSiteProvider
import com.indigo.mobileobservatory.pointing.PhoneSkyAttitudeSource
import com.indigo.mobileobservatory.pointing.PhoneSkySolveStage
import com.indigo.mobileobservatory.pointing.PushToGuidance
import com.indigo.mobileobservatory.pointing.SkyAttitudeFix
import com.indigo.mobileobservatory.pointing.StarExtractionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

private val NightBlack = Color(0xFF050505)
private val NightRed = Color(0xFFFF8A80)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushToScreen(
    onBack: () -> Unit,
    onOpenTargets: () -> Unit = {},
    initialTargetName: String? = null,
    targetRaHours: Double = 5.588,
    targetDecDeg: Double = -5.391
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = remember { PhoneSkyAttitudeSource(context) }
    var currentFix by remember { mutableStateOf<SkyAttitudeFix?>(null) }
    var site by remember { mutableStateOf<ObserverSite?>(null) }
    var cameras by remember { mutableStateOf<List<PhoneCameraCapability>>(emptyList()) }
    var selectedCameraId by remember { mutableStateOf<String?>(null) }
    var exposureSeconds by remember { mutableFloatStateOf(2f) }
    var iso by remember { mutableFloatStateOf(1600f) }
    var preferRaw by remember { mutableStateOf(true) }
    var burstFrameCount by remember { mutableFloatStateOf(1f) }
    var cameraMenuOpen by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(true) }
    var solving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var capturedFrame by remember { mutableStateOf<FrameData?>(null) }
    var extraction by remember { mutableStateOf<StarExtractionResult?>(null) }
    var eyepieceFov by remember { mutableFloatStateOf(1.5f) }
    var previousProximity by remember { mutableStateOf<GuidanceProximity?>(null) }
    val targetName = initialTargetName ?: "M42 · Orion Nebula"

    fun applyCameraDefaults(camera: PhoneCameraCapability) {
        val defaults = defaultPushToSettings(camera)
        selectedCameraId = defaults.cameraId
        exposureSeconds = defaults.exposureSeconds.toFloat()
        iso = defaults.iso.toFloat()
        preferRaw = defaults.preferRaw
    }

    fun loadCameras() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        scope.launch {
            val found = withContext(Dispatchers.Default) {
                PhoneCameraCapability.enumerateBackCameras(context)
                    .filter { it.supportsManualSensor }
            }
            cameras = found
            val selected = found.firstOrNull { it.cameraId == selectedCameraId }
                ?: found.firstOrNull()
            if (selected != null && selected.cameraId != selectedCameraId) applyCameraDefaults(selected)
            if (found.isEmpty()) status = context.getString(R.string.phone_camera_lens_none)
        }
    }

    fun loadSite() {
        if (!PhoneSiteProvider.hasPermission(context)) return
        scope.launch {
            try {
                site = PhoneSiteProvider.currentSite(context)
                if (status.isBlank()) status = context.getString(R.string.push_to_live_ready)
            } catch (t: Throwable) {
                status = t.message ?: context.getString(R.string.phone_location_failed)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        loadSite()
        loadCameras()
    }

    DisposableEffect(source) {
        source.onFix = { currentFix = it }
        if (!source.start()) status = context.getString(R.string.push_to_sensor_unavailable)
        onDispose {
            source.onFix = null
            source.stop()
        }
    }

    LaunchedEffect(Unit) {
        loadSite()
        loadCameras()
        if (!PhoneSiteProvider.hasPermission(context) ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CAMERA
                )
            )
        }
    }

    val fix = currentFix ?: SkyAttitudeFix(
        altDeg = 0.0,
        azDeg = 0.0,
        timestampMs = System.currentTimeMillis(),
        sourceId = source.id
    )
    val now = Instant.ofEpochMilli(fix.timestampMs)
    val targetHorizontal = site?.let { observer ->
        CoordinateTransform.j2000ToTopocentric(
            EquatorialCoordinates(targetRaHours * 15.0, targetDecDeg),
            now,
            observer,
            refraction = null
        )
    }
    val targetAlt = targetHorizontal?.altitudeDeg ?: 0.0
    val targetAz = targetHorizontal?.azimuthDeg ?: 0.0
    val guidance = PushToGuidance.compute(
        currentAltDeg = fix.altDeg,
        currentAzDeg = fix.azDeg,
        targetAltDeg = targetAlt,
        targetAzDeg = targetAz,
        eyepieceFovDeg = eyepieceFov.toDouble(),
        previousProximity = previousProximity
    )
    SideEffect { previousProximity = guidance.proximity }
    val animatedAlt by animateFloatAsState(guidance.deltaAltDeg.toFloat(), label = "guide-alt")
    val animatedAz by animateFloatAsState(guidance.deltaAzDeg.toFloat(), label = "guide-az")
    val selectedCamera = cameras.firstOrNull { it.cameraId == selectedCameraId }
    val readyToCapture = site != null && currentFix != null && selectedCamera != null && !solving
    val moveLabel = when {
        guidance.proximity == GuidanceProximity.ON_TARGET -> stringResource(R.string.push_to_on_target)
        guidance.zenithDegenerate -> stringResource(R.string.push_to_zenith_warn)
        else -> listOf(
            PushToGuidance.signedHint(guidance.deltaAltDeg, "↑", "↓"),
            PushToGuidance.signedHint(guidance.deltaAzDeg, "→", "←")
        ).filter(String::isNotBlank).joinToString("   ")
    }

    Scaffold(
        containerColor = NightBlack,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NightBlack,
                    titleContentColor = NightRed,
                    navigationIconContentColor = NightRed
                ),
                title = {
                    Column {
                        Text(targetName, maxLines = 1, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(
                                if (source.plateSolved) R.string.push_to_step_guiding
                                else R.string.push_to_step_setup
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (source.plateSolved) Color(0xFF80CBC4) else Color(0xFFFFCC80)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = onOpenTargets) {
                        Text(stringResource(R.string.push_to_change_target), color = NightRed)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(NightBlack)
        ) {
            WorkflowStrip(
                targetReady = targetHorizontal != null,
                cameraReady = selectedCamera != null,
                solved = source.plateSolved
            )

            if (source.plateSolved && site != null) {
                PushToNightChart(
                    currentAltDeg = fix.altDeg,
                    currentAzDeg = fix.azDeg,
                    targetAltDeg = targetAlt,
                    targetAzDeg = targetAz,
                    site = site!!,
                    instant = now,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.52f)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.35f)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SseReticle(
                        cmd = guidance,
                        deltaAlt = animatedAlt.toDouble(),
                        deltaAz = animatedAz.toDouble(),
                        eyepieceFovDeg = eyepieceFov.toDouble(),
                        color = guidanceColor(guidance.proximity),
                        pulseAlpha = 0.85f,
                        modifier = Modifier.weight(1f).fillMaxSize()
                    )
                    Column(
                        modifier = Modifier.weight(0.8f).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            moveLabel,
                            color = guidanceColor(guidance.proximity),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            stringResource(
                                R.string.push_to_distance_remaining,
                                PushToGuidance.formatDegrees(guidance.separationDeg)
                            ),
                            color = Color(0xFFBCAAA4),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(R.string.push_to_first_time_help),
                        color = Color(0xFFFFCCBC),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    capturedFrame?.let { frame ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF120909))) {
                            PhoneSkyPreview(frame, extraction, Modifier.padding(4.dp))
                            Text(
                                stringResource(
                                    R.string.push_to_star_count,
                                    extraction?.stars?.size ?: 0
                                ),
                                modifier = Modifier.padding(8.dp),
                                color = if ((extraction?.stars?.size ?: 0) >= 15) Color(0xFF80CBC4)
                                else Color(0xFFFFAB91),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    CaptureSettingsCard(
                        cameras = cameras,
                        selectedCamera = selectedCamera,
                        cameraMenuOpen = cameraMenuOpen,
                        onCameraMenuChange = { cameraMenuOpen = it },
                        onCameraSelected = {
                            applyCameraDefaults(it)
                            cameraMenuOpen = false
                        },
                        exposureSeconds = exposureSeconds,
                        onExposureChanged = { exposureSeconds = it },
                        iso = iso,
                        onIsoChanged = { iso = it },
                        preferRaw = preferRaw,
                        onPreferRawChanged = { preferRaw = it },
                        burstFrameCount = burstFrameCount,
                        onBurstFrameCountChanged = { burstFrameCount = it },
                        expanded = true,
                        collapsible = false,
                        onExpandedChanged = {}
                    )
                }
            }

            if (source.plateSolved) {
                CaptureSettingsCard(
                    cameras = cameras,
                    selectedCamera = selectedCamera,
                    cameraMenuOpen = cameraMenuOpen,
                    onCameraMenuChange = { cameraMenuOpen = it },
                    onCameraSelected = {
                        applyCameraDefaults(it)
                        cameraMenuOpen = false
                    },
                    exposureSeconds = exposureSeconds,
                    onExposureChanged = { exposureSeconds = it },
                    iso = iso,
                    onIsoChanged = { iso = it },
                    preferRaw = preferRaw,
                    onPreferRawChanged = { preferRaw = it },
                    burstFrameCount = burstFrameCount,
                    onBurstFrameCountChanged = { burstFrameCount = it },
                    expanded = settingsExpanded,
                    collapsible = true,
                    onExpandedChanged = { settingsExpanded = it }
                )
            }

            if (status.isNotBlank()) {
                Text(
                    status,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                    color = Color(0xFFBCAAA4),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    enabled = readyToCapture,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val observer = site ?: return@FilledTonalButton
                        val camera = selectedCamera ?: return@FilledTonalButton
                        solving = true
                        capturedFrame = null
                        extraction = null
                        scope.launch {
                            val result = source.captureAndSolve(
                                site = observer,
                                exposureSeconds = exposureSeconds.toDouble(),
                                iso = iso.toInt(),
                                cameraId = camera.cameraId,
                                preferRaw = preferRaw,
                                burstFrameCount = burstFrameCount.toInt(),
                                onProgress = { stage ->
                                    status = context.getString(
                                        when (stage) {
                                            PhoneSkySolveStage.CAPTURING -> R.string.push_to_stage_capturing
                                            PhoneSkySolveStage.EXTRACTING_STARS -> R.string.push_to_stage_extracting
                                            PhoneSkySolveStage.SOLVING -> R.string.push_to_stage_solving
                                            PhoneSkySolveStage.COMPLETE -> R.string.push_to_stage_complete
                                        }
                                    )
                                },
                                onCapture = { frame, stars, count ->
                                    capturedFrame = frame
                                    extraction = stars
                                    status = if (count > 1) {
                                        context.getString(R.string.push_to_stage_stacked, count)
                                    } else {
                                        context.getString(R.string.push_to_stage_extracting)
                                    }
                                },
                                onBurstProgress = { completed, total ->
                                    status = context.getString(
                                        R.string.push_to_stage_burst_capturing,
                                        completed,
                                        total
                                    )
                                }
                            )
                            capturedFrame = result.frame ?: capturedFrame
                            extraction = result.extraction ?: extraction
                            status = if (result.success) {
                                context.getString(
                                    R.string.push_to_solve_success,
                                    result.extraction?.stars?.size ?: 0
                                )
                            } else {
                                context.getString(R.string.push_to_solve_failed_detail, result.message)
                            }
                            if (result.success) settingsExpanded = false
                            solving = false
                        }
                    }
                ) {
                    Text(
                        stringResource(
                            when {
                                solving -> R.string.push_to_solving
                                source.plateSolved -> R.string.push_to_resolve
                                else -> R.string.push_to_start_alignment
                            }
                        )
                    )
                }
                TextButton(onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.CAMERA
                        )
                    )
                }) {
                    Text(stringResource(R.string.push_to_permissions), color = NightRed)
                }
            }
        }
    }
}

@Composable
private fun WorkflowStrip(targetReady: Boolean, cameraReady: Boolean, solved: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WorkflowChip("1", stringResource(R.string.push_to_workflow_target), targetReady)
        WorkflowChip("2", stringResource(R.string.push_to_workflow_camera), cameraReady)
        WorkflowChip("3", stringResource(R.string.push_to_workflow_solve), solved)
        WorkflowChip("4", stringResource(R.string.push_to_workflow_push), solved)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowChip(number: String, label: String, complete: Boolean) {
    FilterChip(
        selected = complete,
        onClick = {},
        enabled = false,
        label = { Text("$number  $label") }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureSettingsCard(
    cameras: List<PhoneCameraCapability>,
    selectedCamera: PhoneCameraCapability?,
    cameraMenuOpen: Boolean,
    onCameraMenuChange: (Boolean) -> Unit,
    onCameraSelected: (PhoneCameraCapability) -> Unit,
    exposureSeconds: Float,
    onExposureChanged: (Float) -> Unit,
    iso: Float,
    onIsoChanged: (Float) -> Unit,
    preferRaw: Boolean,
    onPreferRawChanged: (Boolean) -> Unit,
    burstFrameCount: Float,
    onBurstFrameCountChanged: (Float) -> Unit,
    expanded: Boolean,
    collapsible: Boolean,
    onExpandedChanged: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF120909)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.push_to_capture_settings),
                color = NightRed,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (collapsible) {
                IconButton(onClick = { onExpandedChanged(!expanded) }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = NightRed
                    )
                }
            }
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn()) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ExposedDropdownMenuBox(
                    expanded = cameraMenuOpen,
                    onExpandedChange = onCameraMenuChange
                ) {
                    OutlinedTextField(
                        value = selectedCamera?.let { localizedCameraLabel(it) }
                            ?: stringResource(R.string.phone_camera_lens_none),
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text(stringResource(R.string.phone_camera_lens)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(cameraMenuOpen) }
                    )
                    ExposedDropdownMenu(
                        expanded = cameraMenuOpen,
                        onDismissRequest = { onCameraMenuChange(false) }
                    ) {
                        cameras.forEach { camera ->
                            DropdownMenuItem(
                                text = { Text(localizedCameraLabel(camera)) },
                                onClick = { onCameraSelected(camera) }
                            )
                        }
                    }
                }
                val exposureMin = (selectedCamera?.minExposureSeconds ?: 0.1)
                    .coerceIn(0.01, 8.0).toFloat()
                val exposureMax = maxOf(
                    exposureMin + 0.01f,
                    (selectedCamera?.maxExposureSeconds ?: 8.0).coerceAtMost(8.0).toFloat()
                )
                Text(
                    stringResource(R.string.push_to_exposure_value, exposureSeconds),
                    color = Color(0xFFBCAAA4),
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = exposureSeconds.coerceIn(exposureMin, exposureMax),
                    onValueChange = onExposureChanged,
                    valueRange = exposureMin..exposureMax,
                    modifier = Modifier.height(32.dp)
                )
                Text(
                    stringResource(R.string.push_to_burst_frames_value, burstFrameCount.toInt()),
                    color = Color(0xFFBCAAA4),
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = burstFrameCount.coerceIn(1f, 16f),
                    onValueChange = { onBurstFrameCountChanged(it.toInt().toFloat()) },
                    valueRange = 1f..16f,
                    steps = 14,
                    modifier = Modifier.height(32.dp)
                )
                val isoMin = selectedCamera?.isoRange?.lower?.toFloat() ?: 100f
                val isoMax = maxOf(
                    isoMin + 1f,
                    selectedCamera?.isoRange?.upper?.toFloat() ?: 3200f
                )
                Text(
                    stringResource(R.string.phone_camera_iso) + ": ${iso.toInt()}",
                    color = Color(0xFFBCAAA4),
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = iso.coerceIn(isoMin, isoMax),
                    onValueChange = onIsoChanged,
                    valueRange = isoMin..isoMax,
                    modifier = Modifier.height(32.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.phone_camera_prefer_raw),
                        color = Color(0xFFBCAAA4),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = preferRaw,
                        onCheckedChange = onPreferRawChanged,
                        enabled = selectedCamera?.supportsRaw == true
                    )
                }
            }
        }
    }
}

private fun guidanceColor(proximity: GuidanceProximity): Color = when (proximity) {
    GuidanceProximity.FAR -> Color(0xFFFF5252)
    GuidanceProximity.MEDIUM -> Color(0xFFFFD740)
    GuidanceProximity.NEAR -> Color(0xFF69F0AE)
    GuidanceProximity.ON_TARGET -> Color(0xFF00E676)
}

@Composable
private fun localizedCameraLabel(camera: PhoneCameraCapability): String {
    val role = stringResource(
        when (camera.lensRole) {
            PhoneLensRole.ULTRA_WIDE -> R.string.push_to_lens_ultra_wide
            PhoneLensRole.MAIN -> R.string.push_to_lens_main
            PhoneLensRole.TELEPHOTO -> R.string.push_to_lens_telephoto
            PhoneLensRole.UNKNOWN -> R.string.push_to_lens_other
        }
    )
    val focal = camera.equivalentFocalLengthMm?.let { " · ${it.toInt()} mm" }.orEmpty()
    val raw = if (camera.supportsRaw) " · RAW" else ""
    return role + focal + raw
}
