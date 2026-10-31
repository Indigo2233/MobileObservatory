package com.indigo.mobileobservatory.ui.screens

import com.indigo.mobileobservatory.R

import androidx.compose.ui.res.stringResource

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.astrometry.AstapRunner
import com.indigo.mobileobservatory.astrometry.PlateSolveEngine
import com.indigo.mobileobservatory.astrometry.PlateSolveInputKind
import com.indigo.mobileobservatory.astrometry.PlateSolveRouting
import com.indigo.mobileobservatory.astrometry.AstapDatabase
import com.indigo.mobileobservatory.astrometry.D50Manager
import com.indigo.mobileobservatory.astrometry.D50Status
import com.indigo.mobileobservatory.astrometry.DownloadProgress
import com.indigo.mobileobservatory.astrometry.FitsSolveHintReader
import com.indigo.mobileobservatory.astrometry.FitsSolveHints
import com.indigo.mobileobservatory.astrometry.PlateSolveOptics
import com.indigo.mobileobservatory.astrometry.PlateSolveResult
import com.indigo.mobileobservatory.astro.OpticsEquipment
import com.indigo.mobileobservatory.camera.CameraInfo
import com.indigo.mobileobservatory.mount.MountCoordinates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun PlateSolveScreen(
    initialFile: File? = null,
    autoSolve: Boolean = false,
    mountCoordinates: MountCoordinates? = null,
    cameraInfo: CameraInfo? = null,
    cameraFrameHints: FitsSolveHints = PlateSolveOptics.cameraHints(cameraInfo),
    onSolved: (PlateSolveResult) -> Unit = {},
    recordingsDir: File? = null,
    capturesDir: File? = null,
    excludeFile: File? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val d50Manager = remember { D50Manager(context.applicationContext) }
    val runner = remember { AstapRunner(context.applicationContext) }
    val prefs = remember { context.applicationContext.getSharedPreferences("mobile_observatory", android.content.Context.MODE_PRIVATE) }

    var d50Status by remember { mutableStateOf(d50Manager.status()) }
    var selectedDatabase by remember { mutableStateOf(d50Status.database ?: AstapDatabase.D20) }
    var selectedPath by rememberSaveable(initialFile?.absolutePath) { mutableStateOf(initialFile?.absolutePath) }
    val selectedFile = remember(selectedPath) { selectedPath?.let(::File) }
    var showImageLibrary by rememberSaveable { mutableStateOf(false) }
    var loadingFile by remember { mutableStateOf(false) }
    var focalLengthText by rememberSaveable { mutableStateOf(prefs.getFloat("plate_focal_length_mm", 0f).takeIf { it > 0f }?.toString() ?: "") }
    var pixelSizeText by rememberSaveable {
        mutableStateOf(
            prefs.getFloat("plate_pixel_size_um", 0f).takeIf { it > 0f }?.let { formatPixelSizeUm(it.toDouble()) }
                ?: ""
        )
    }
    var selectedSensorId by rememberSaveable {
        mutableStateOf(prefs.getString("plate_sensor_id", "") ?: "")
    }
    var sensorEdited by rememberSaveable(selectedPath) { mutableStateOf(false) }
    val progressFlow = remember { MutableStateFlow(DownloadProgress()) }
    val progress by progressFlow.collectAsState()
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    val downloadFailedText = stringResource(R.string.download_failed)
    val downloadCancelledText = stringResource(R.string.download_cancelled)
    val plateSolveFailedText = stringResource(R.string.plate_solve_failed)
    var solving by remember { mutableStateOf(false) }
    var solveJob by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<PlateSolveResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var useMountHint by remember(mountCoordinates) { mutableStateOf(mountCoordinates != null) }
    var autoSolveAttempted by rememberSaveable(initialFile?.absolutePath, autoSolve) { mutableStateOf(false) }

    LaunchedEffect(result) {
        result?.takeIf { it.success }?.let(onSolved)
    }

    fun selectImage(file: File) {
        selectedPath = file.absolutePath
        result = null
        error = null
        autoSolveAttempted = true
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                loadingFile = true
                error = null
                try {
                    selectImage(runner.copyUriToCache(uri))
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) {
                    error = context.getString(R.string.plate_image_load_failed)
                } finally { loadingFile = false }
            }
        }
    }

    if (showImageLibrary) AppImagePicker(recordingsDir, capturesDir, excludeFile,
        onDismiss = { showImageLibrary = false },
        onSelect = { file -> selectImage(file); showImageLibrary = false })

    val connectedSensor = remember(cameraInfo) {
        cameraInfo?.let {
            OpticsEquipment.connectedSensor(it.pixelSizeUm, it.sensorWidth, it.sensorHeight, it.name)
        }
    }
    val sensors = remember(connectedSensor) { listOfNotNull(connectedSensor) + OpticsEquipment.defaultSensors }
    val loadedHints by produceState<Pair<File?, FitsSolveHints>?>(null, selectedFile) {
        value = selectedFile to if (selectedFile != null) {
            withContext(Dispatchers.IO) { FitsSolveHintReader.read(selectedFile) }
        } else FitsSolveHints()
    }
    val imageHints = loadedHints?.takeIf { it.first == selectedFile }?.second ?: FitsSolveHints()
    val currentCameraInput = selectedFile == null || autoSolve && selectedFile == initialFile
    val automaticSensor = PlateSolveOptics.imageSensor(imageHints, stringResource(R.string.solve_image_sensor))
        ?: connectedSensor.takeIf { currentCameraInput }

    LaunchedEffect(sensors, selectedSensorId, imageHints, automaticSensor, sensorEdited) {
        if (sensors.none { it.id == selectedSensorId }) {
            selectedSensorId = OpticsEquipment.matchCatalogSensor(
                imageHints.width, imageHints.height, pixelSizeText.toDoubleOrNull())?.id
                ?: OpticsEquipment.CUSTOM_SENSOR_ID
        }
        if (!sensorEdited && automaticSensor == null && selectedSensorId == OpticsEquipment.CUSTOM_SENSOR_ID &&
            imageHints.width > 0 && imageHints.height > 0) {
            OpticsEquipment.matchCatalogSensor(imageHints.width, imageHints.height, pixelSizeText.toDoubleOrNull())
                ?.let { selectedSensorId = it.id }
        }
        val sensor = sensors.firstOrNull { it.id == selectedSensorId }
        if (sensor != null && sensor.id != OpticsEquipment.CUSTOM_SENSOR_ID) {
            val formatted = formatPixelSizeUm(sensor.pixelSizeUm)
            if (pixelSizeText != formatted) pixelSizeText = formatted
        }
    }

    LaunchedEffect(imageHints) {
        if (focalLengthText.isBlank() && imageHints.focalLengthMm?.let { it.isFinite() && it > 0 } == true) {
            focalLengthText = "%.1f".format(Locale.US, imageHints.focalLengthMm)
        }
    }
    val manualSensor = sensors.firstOrNull { it.id == selectedSensorId }
    val typedPixel = pixelSizeText.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val inputPixel = automaticSensor?.pixelSizeUm ?: manualSensor?.let { pixelSizeForSensor(it, typedPixel) } ?: typedPixel
    val catalogHeight = (automaticSensor ?: manualSensor)?.takeIf { it.id != OpticsEquipment.CUSTOM_SENSOR_ID }?.heightPx ?: 0
    val userFocal = focalLengthText.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val previewHints = if (selectedFile == null) cameraFrameHints else imageHints
    val scale = PlateSolveOptics.scaleHint(
        hints = if (previewHints.height > 0) previewHints else previewHints.copy(height = catalogHeight),
        userFocalLengthMm = userFocal,
        userPixelSizeUm = inputPixel
    )
    val computedFovDeg = scale.fovHeightDeg
    val hintText = buildString {
        if (scale.width > 0 && scale.height > 0) append("${scale.width}x${scale.height}")
        if (scale.pixelSizeUm != null) append("  pixel=${formatPixelSizeUm(scale.pixelSizeUm)}um")
        append("  bin=${scale.binning}")
        if (scale.focalLengthMm != null) append("  focal=${"%.1f".format(Locale.US, scale.focalLengthMm)}mm")
        scale.fovHeightDeg?.let { append("  fovH=${"%.4f".format(Locale.US, it)}°") }
    }.trim()

    fun solveSelectedFile() {
        val file = selectedFile ?: return
        val focal = focalLengthText.toFloatOrNull()?.takeIf { it > 0f }
        val pixel = pixelSizeText.toFloatOrNull()?.takeIf { it > 0f }
        val fallbackPixel = inputPixel
        val fallbackHeight = catalogHeight
        prefs.edit().apply {
            focal?.let { putFloat("plate_focal_length_mm", it) }
            pixel?.let { putFloat("plate_pixel_size_um", it) }
            putString("plate_sensor_id", selectedSensorId)
        }.apply()
        solveJob = scope.launch {
            solving = true
            error = null
            result = null
            try {
                val hints = withContext(Dispatchers.IO) { FitsSolveHintReader.read(file) }
                val scalePixel = PlateSolveOptics.pixelSizeForImage(hints, fallbackPixel)
                val fov = PlateSolveOptics.astapFovDeg(
                    hints = hints,
                    userFocalLengthMm = focal?.toDouble(),
                    userPixelSizeUm = scalePixel,
                    catalogHeightPx = fallbackHeight
                )?.coerceIn(0.2, 6.0)
                if (fov == null) {
                    result = PlateSolveResult(false, context.getString(R.string.plate_solve_need_optics))
                    return@launch
                }
                result = if (PlateSolveRouting.select(
                        PlateSolveInputKind.EXTERNAL_CAMERA,
                        focal?.toDouble()
                    ) == PlateSolveEngine.ASTAP
                ) {
                    val solved = runner.solve(file, fov, if (useMountHint) mountCoordinates else null)
                    solved.copy(
                        measuredFocalLengthMm = PlateSolveOptics.impliedFocalLengthMm(
                            scalePixel,
                            solved.arcsecPerPixel,
                            hints.binning
                        )
                    )
                } else {
                    PlateSolveResult(false, "ASTAP requires a confirmed external focal length of at least 200 mm.")
                }
            } catch (cancelled: CancellationException) {
                result = PlateSolveResult(false, context.getString(R.string.stopped))
                throw cancelled
            } catch (e: Throwable) {
                result = PlateSolveResult(
                    success = false,
                    message = e.message ?: plateSolveFailedText,
                    log = e.stackTraceToString()
                )
            } finally {
                solving = false
                solveJob = null
            }
        }
    }

    val canSolve = selectedFile != null && loadedHints?.first == selectedFile && userFocal != null &&
        d50Status.installed && !progress.active && !solving && !loadingFile && computedFovDeg != null
    LaunchedEffect(autoSolve, canSolve) {
        if (autoSolve && canSolve && !autoSolveAttempted) {
            autoSolveAttempted = true
            solveSelectedFile()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                text = stringResource(if (autoSolve) R.string.plate_solve_current_frame else R.string.plate_solve),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showImageLibrary = true },
                enabled = !progress.active && !solving && !loadingFile,
                modifier = Modifier.testTag("plate-choose-library")) {
                Text(stringResource(R.string.plate_image_library))
            }
            TextButton(onClick = { picker.launch(arrayOf("image/*", "application/fits", "application/octet-stream", "*/*")) },
                enabled = !progress.active && !solving && !loadingFile,
                modifier = Modifier.testTag("plate-choose-device")) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.plate_image_device))
            }
        }
        if (loadingFile) LinearProgressIndicator(Modifier.fillMaxWidth())

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DatabaseCard(
                status = d50Status,
                selectedDatabase = selectedDatabase,
                progress = progress,
                onSelectDatabase = { selectedDatabase = it },
                onDownload = {
                    if (downloadJob?.isActive == true || progressFlow.value.active) return@DatabaseCard
                    progressFlow.value = DownloadProgress(active = true, message = "Preparing ${selectedDatabase.displayName}...")
                    downloadJob = scope.launch {
                        error = null
                        try {
                            withContext(Dispatchers.IO) {
                                d50Manager.downloadAndInstall(selectedDatabase) { update ->
                                    progressFlow.value = update
                                }
                            }
                            d50Status = d50Manager.status()
                            progressFlow.value = DownloadProgress(active = false)
                        } catch (e: CancellationException) {
                            progressFlow.value = DownloadProgress(active = false, message = downloadCancelledText)
                            throw e
                        } catch (e: Throwable) {
                            error = e.message ?: downloadFailedText
                            progressFlow.value = DownloadProgress(active = false)
                        }
                    }
                },
                onCancel = {
                    downloadJob?.cancel()
                },
                onDelete = {
                    d50Manager.deleteDatabase()
                    d50Status = d50Manager.status()
                }
            )

            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.image_desc), style = MaterialTheme.typography.titleSmall)
                    Text(selectedFile?.name ?: stringResource(R.string.no_image_selected), fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, modifier = Modifier.testTag("plate-selected-image"))
                    if (hintText.isNotBlank()) {
                        Text(hintText, color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
                    }
                    if (mountCoordinates != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.mount_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                Text(
                                    "${mountCoordinates.formatRa()}  ${mountCoordinates.formatDec()}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = useMountHint,
                                onCheckedChange = { useMountHint = it }
                            )
                        }
                    }
                    SolveOpticsFields(
                        focalLengthText = focalLengthText,
                        onFocalLengthChange = { focalLengthText = it },
                        selectedSensorId = selectedSensorId,
                        onSensorSelected = { sensor ->
                            sensorEdited = true
                            selectedSensorId = sensor.id
                            pixelSizeText = formatPixelSizeUm(sensor.pixelSizeUm)
                        },
                        pixelSizeText = pixelSizeText,
                        onPixelSizeChange = { sensorEdited = true; pixelSizeText = it },
                        sensors = sensors,
                        computedFovHeightDeg = computedFovDeg,
                        enabled = !progress.active && !solving,
                        automaticSensor = automaticSensor
                    )
                    Button(
                        onClick = ::solveSelectedFile,
                        enabled = canSolve
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (solving) R.string.solving else R.string.solve))
                    }
                }
            }

            if (solving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { solveJob?.cancel() },
                    modifier = Modifier.testTag("plate-solve-stop")) {
                    Text(stringResource(R.string.stop))
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            result?.let { solveResult ->
                ResultCard(solveResult, showLog, onToggleLog = { showLog = !showLog })
            }
        }
    }
}

@Composable
private fun DatabaseCard(
    status: D50Status,
    selectedDatabase: AstapDatabase,
    progress: DownloadProgress,
    onSelectDatabase: (AstapDatabase) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.astap_database), style = MaterialTheme.typography.titleSmall)
            AstapDatabase.entries.forEach { database ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RadioButton(
                        modifier = Modifier.testTag("solve-database-${database.name}"),
                        selected = selectedDatabase == database,
                        onClick = { onSelectDatabase(database) },
                        enabled = !progress.active && !status.installed
                    )
                    Text(
                        text = stringResource(
                            if (database == AstapDatabase.D20) R.string.astap_d20_database else R.string.astap_d50_database
                        ),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            Text(
                if (status.installed) {
                    stringResource(
                        R.string.database_installed_named,
                        status.database!!.displayName,
                        status.fileCount,
                        formatBytes(status.totalBytes)
                    )
                } else {
                    stringResource(R.string.database_not_installed_named, selectedDatabase.downloadSizeDescription)
                },
                fontSize = 12.sp
            )
            if (progress.active) {
                if (progress.totalBytes > 0) {
                    val fraction = progress.bytesRead.toFloat() / progress.totalBytes
                    LinearProgressIndicator(progress = fraction.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                    Text("${progress.message}  ${formatBytes(progress.bytesRead)} / ${formatBytes(progress.totalBytes)}", fontSize = 12.sp)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(progress.message, fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress.active) {
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                } else {
                    Button(onClick = onDownload, enabled = !status.installed) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.download_database, selectedDatabase.displayName))
                    }
                    OutlinedButton(onClick = onDelete, enabled = status.installed) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: PlateSolveResult, showLog: Boolean, onToggleLog: () -> Unit) {
    val color = if (result.success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(result.message, color = color, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.elapsed_seconds, result.elapsedMs / 1000.0), fontSize = 12.sp)
            if (result.success) {
                ResultLine("RA", "${result.raHms}  (${fmt(result.raDeg)} deg)")
                ResultLine("Dec", "${result.decDms}  (${fmt(result.decDeg)} deg)")
                ResultLine("FOV", "${fmt(result.fovWidthDeg)} x ${fmt(result.fovHeightDeg)} deg")
                ResultLine("Scale", "${fmt(result.arcsecPerPixel)} arcsec/px")
                result.measuredFocalLengthMm?.let {
                    ResultLine(
                        stringResource(R.string.solved_focal_length_mm),
                        "%.1f mm".format(Locale.US, it)
                    )
                }
                ResultLine("Rotation", "${fmt(result.rotationDeg)} deg")
                result.wcsHeaderPath?.let { ResultLine("WCS", it) }
            }
            TextButton(onClick = onToggleLog, enabled = result.log.isNotBlank()) {
                Text(stringResource(if (showLog) R.string.hide_log else R.string.show_log))
            }
            if (showLog) {
                Text(
                    result.log.ifBlank { "(empty)" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ResultLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(88.dp))
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

private fun fmt(value: Double?): String {
    return if (value == null) "--" else "%.6f".format(Locale.US, value)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "--"
    val mb = bytes / 1024.0 / 1024.0
    return if (mb < 1024) "%.1f MB".format(Locale.US, mb) else "%.2f GB".format(Locale.US, mb / 1024.0)
}
