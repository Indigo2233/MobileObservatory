package com.indigo.mobileobservatory.ui.screens

import com.indigo.mobileobservatory.R

import androidx.compose.ui.res.stringResource

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.astrometry.AstapRunner
import com.indigo.mobileobservatory.astrometry.AstapDatabase
import com.indigo.mobileobservatory.astrometry.D50Manager
import com.indigo.mobileobservatory.astrometry.D50Status
import com.indigo.mobileobservatory.astrometry.DownloadProgress
import com.indigo.mobileobservatory.astrometry.FitsSolveHintReader
import com.indigo.mobileobservatory.astrometry.PlateSolveResult
import com.indigo.mobileobservatory.mount.MountCoordinates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun PlateSolveScreen(
    initialFile: File? = null,
    mountCoordinates: MountCoordinates? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val d50Manager = remember { D50Manager(context.applicationContext) }
    val runner = remember { AstapRunner(context.applicationContext) }
    val prefs = remember { context.applicationContext.getSharedPreferences("mobile_observatory", android.content.Context.MODE_PRIVATE) }

    var d50Status by remember { mutableStateOf(d50Manager.status()) }
    var selectedDatabase by remember { mutableStateOf(d50Status.database ?: AstapDatabase.D20) }
    var selectedFile by remember(initialFile) { mutableStateOf(initialFile) }
    var fovText by remember { mutableStateOf("1.0") }
    var focalLengthText by remember { mutableStateOf(prefs.getFloat("plate_focal_length_mm", 0f).takeIf { it > 0f }?.toString() ?: "") }
    var hintText by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(DownloadProgress()) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    val downloadFailedText = stringResource(R.string.download_failed)
    val downloadCancelledText = stringResource(R.string.download_cancelled)
    val plateSolveFailedText = stringResource(R.string.plate_solve_failed)
    var solving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PlateSolveResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var useMountHint by remember(mountCoordinates) { mutableStateOf(mountCoordinates != null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                error = null
                result = null
                selectedFile = withContext(Dispatchers.IO) { runner.copyUriToCache(uri) }
            }
        }
    }

    LaunchedEffect(selectedFile) {
        val file = selectedFile ?: return@LaunchedEffect
        val hints = withContext(Dispatchers.IO) { FitsSolveHintReader.read(file) }
        val savedFocal = prefs.getFloat("plate_focal_length_mm", 0f).takeIf { it > 0f }?.toDouble()
        val focal = hints.focalLengthMm ?: savedFocal
        if (hints.fovHeightDeg != null) {
            fovText = "%.4f".format(Locale.US, hints.fovHeightDeg)
        } else if (hints.height > 0 && hints.pixelSizeUm != null && focal != null && focal > 0.0) {
            val fov = 206.265 * hints.pixelSizeUm * hints.binning / focal * hints.height / 3600.0
            fovText = "%.4f".format(Locale.US, fov)
        }
        if (hints.focalLengthMm != null) {
            focalLengthText = "%.1f".format(Locale.US, hints.focalLengthMm)
        }
        hintText = buildString {
            if (hints.width > 0 && hints.height > 0) append("${hints.width}x${hints.height}")
            if (hints.pixelSizeUm != null) append("  pixel=${"%.3f".format(Locale.US, hints.pixelSizeUm)}um")
            append("  bin=${hints.binning}")
            if (focal != null) append("  focal=${"%.1f".format(Locale.US, focal)}mm")
        }.trim()
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
                text = stringResource(R.string.plate_solve),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { picker.launch(arrayOf("image/*", "application/fits", "application/octet-stream", "*/*")) },
                enabled = !progress.active && !solving
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.select))
            }
        }

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
                    downloadJob = scope.launch {
                        error = null
                        try {
                            withContext(Dispatchers.IO) {
                                d50Manager.downloadAndInstall(selectedDatabase) { update ->
                                    scope.launch { progress = update }
                                }
                            }
                            d50Status = d50Manager.status()
                        } catch (e: Throwable) {
                            error = e.message ?: downloadFailedText
                            progress = DownloadProgress(active = false)
                        }
                    }
                },
                onCancel = {
                    downloadJob?.cancel()
                    progress = DownloadProgress(active = false, message = downloadCancelledText)
                },
                onDelete = {
                    d50Manager.deleteDatabase()
                    d50Status = d50Manager.status()
                }
            )

            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.image_desc), style = MaterialTheme.typography.titleSmall)
                    Text(selectedFile?.name ?: stringResource(R.string.no_image_selected), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
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
                    OutlinedTextField(
                        value = focalLengthText,
                        onValueChange = { focalLengthText = it },
                        label = { Text(stringResource(R.string.focal_length_mm)) },
                        singleLine = true,
                        modifier = Modifier.widthIn(max = 260.dp)
                    )
                    OutlinedTextField(
                        value = fovText,
                        onValueChange = { fovText = it },
                        label = { Text(stringResource(R.string.estimated_field_height_deg)) },
                        singleLine = true,
                        modifier = Modifier.widthIn(max = 260.dp)
                    )
                    Button(
                        onClick = {
                            val file = selectedFile ?: return@Button
                            val fov = fovText.toDoubleOrNull()?.coerceIn(0.2, 6.0) ?: 1.0
                            focalLengthText.toFloatOrNull()?.takeIf { it > 0f }?.let {
                                prefs.edit().putFloat("plate_focal_length_mm", it).apply()
                            }
                            scope.launch {
                                solving = true
                                error = null
                                result = null
                                try {
                                    result = runner.solve(file, fov, if (useMountHint) mountCoordinates else null)
                                } catch (e: Throwable) {
                                    result = PlateSolveResult(
                                        success = false,
                                        message = e.message ?: plateSolveFailedText,
                                        log = e.stackTraceToString()
                                    )
                                } finally {
                                    solving = false
                                }
                            }
                        },
                        enabled = selectedFile != null && d50Status.installed && !progress.active && !solving
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (solving) R.string.solving else R.string.solve))
                    }
                }
            }

            if (solving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
                val fraction = if (progress.totalBytes > 0) progress.bytesRead.toFloat() / progress.totalBytes else 0f
                LinearProgressIndicator(progress = fraction.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                Text("${progress.message}  ${formatBytes(progress.bytesRead)} / ${formatBytes(progress.totalBytes)}", fontSize = 12.sp)
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
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(68.dp))
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
