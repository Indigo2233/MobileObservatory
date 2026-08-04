package com.indigo.mobileobservatory.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.PhoneCameraCapability
import com.indigo.mobileobservatory.camera.PhoneLensRole
import com.indigo.mobileobservatory.camera.PhoneSkyCapture
import com.indigo.mobileobservatory.camera.PhoneSkyCaptureStore
import com.indigo.mobileobservatory.permissions.CameraPermissionPolicy
import com.indigo.mobileobservatory.pointing.StarExtractionResult
import com.indigo.mobileobservatory.pointing.WideFieldStarExtractor
import com.indigo.mobileobservatory.recording.FITSWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

private const val GO_MIN_STARS = 15
private const val GO_MIN_LIMITING_MAG = 5.5f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PhoneCameraDebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var exposureS by remember { mutableFloatStateOf(2f) }
    var iso by remember { mutableFloatStateOf(800f) }
    var preferRaw by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    var capabilityText by remember { mutableStateOf("") }
    var extraction by remember { mutableStateOf<StarExtractionResult?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lastSavePath by remember { mutableStateOf<String?>(null) }
    var storageUsage by remember { mutableLongStateOf(0L) }
    var lenses by remember { mutableStateOf<List<PhoneCameraCapability>>(emptyList()) }
    var selectedCameraId by remember { mutableStateOf<String?>(null) }
    var permissionAsked by remember { mutableStateOf(false) }
    var pendingAfterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun isCameraGranted(): Boolean {
        val perms = CameraPermissionPolicy.requiredPermissions()
            .filter {
                ContextCompat.checkSelfPermission(context, it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            .toSet()
        return CameraPermissionPolicy.isGranted(perms)
    }

    var hasPermission by remember { mutableStateOf(isCameraGranted()) }

    fun refreshLenses() {
        if (!isCameraGranted()) {
            hasPermission = false
            lenses = emptyList()
            status = context.getString(R.string.phone_camera_waiting_permission)
            return
        }
        hasPermission = true
        try {
            val list = PhoneCameraCapability.enumerateBackCameras(context)
            lenses = list
            if (selectedCameraId == null || list.none { it.cameraId == selectedCameraId }) {
                selectedCameraId = list.firstOrNull { it.lensRole == PhoneLensRole.MAIN }?.cameraId
                    ?: list.firstOrNull()?.cameraId
            }
            capabilityText = if (list.isEmpty()) {
                PhoneCameraCapability.debugCameraIdDump(context)
            } else {
                list.joinToString("\n\n") { it.summaryLines().joinToString("\n") }
            }
            status = if (list.isEmpty()) {
                context.getString(R.string.phone_camera_lens_none) + "\n" +
                    PhoneCameraCapability.debugCameraIdDump(context)
            } else {
                context.getString(R.string.phone_camera_lenses_found, list.size)
            }
        } catch (t: Throwable) {
            lenses = emptyList()
            status = "Enumerate failed: ${t.message}\n${PhoneCameraCapability.debugCameraIdDump(context)}"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionAsked = true
        val ok = CameraPermissionPolicy.isGranted(result.filterValues { it }.keys) || isCameraGranted()
        hasPermission = ok
        if (ok) {
            val pending = pendingAfterPermission
            pendingAfterPermission = null
            refreshLenses()
            pending?.invoke()
        } else {
            pendingAfterPermission = null
            val forever = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    android.Manifest.permission.CAMERA
                ) &&
                permissionAsked
            status = if (forever) {
                context.getString(R.string.phone_camera_permission_denied_forever)
            } else {
                context.getString(R.string.phone_camera_permission_required)
            }
        }
    }

    fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun withCameraPermission(action: () -> Unit) {
        if (isCameraGranted()) {
            hasPermission = true
            action()
            return
        }
        pendingAfterPermission = action
        permissionLauncher.launch(CameraPermissionPolicy.requiredPermissions().toTypedArray())
    }

    // Re-check when returning from system settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val grantedNow = isCameraGranted()
                if (grantedNow != hasPermission) {
                    hasPermission = grantedNow
                }
                if (grantedNow && lenses.isEmpty()) {
                    refreshLenses()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        storageUsage = withContext(Dispatchers.IO) {
            PhoneSkyCaptureStore.enforceRetention(context)
            PhoneSkyCaptureStore.usageBytes(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.phone_camera_debug)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.phone_camera_m0_hint),
                style = MaterialTheme.typography.bodySmall
            )

            if (!hasPermission) {
                Text(
                    stringResource(R.string.phone_camera_waiting_permission),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = { withCameraPermission { refreshLenses() } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.phone_camera_grant_permission))
                }
                OutlinedButton(
                    onClick = { openAppSettings() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.phone_camera_open_settings))
                }
            }

            Text(
                stringResource(R.string.phone_camera_lens),
                style = MaterialTheme.typography.titleSmall
            )
            if (hasPermission && lenses.isEmpty()) {
                Text(
                    stringResource(R.string.phone_camera_lens_none),
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (lenses.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    lenses.forEach { lens ->
                        FilterChip(
                            selected = lens.cameraId == selectedCameraId,
                            onClick = {
                                selectedCameraId = lens.cameraId
                                capabilityText = lens.summaryLines().joinToString("\n")
                            },
                            enabled = !busy,
                            label = { Text(lens.displayLabel) }
                        )
                    }
                }
                Text(
                    stringResource(R.string.phone_camera_lens_hint),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            val selectedLens = lenses.firstOrNull { it.cameraId == selectedCameraId }
            val maxExposureUi = ((selectedLens?.maxExposureSeconds ?: 8.0)
                .coerceIn(2.0, 10.0)).toFloat()
            val minExposureUi = 0.5f

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.phone_camera_exposure_s) +
                        ": ${"%.1f".format(exposureS.coerceIn(minExposureUi, maxExposureUi))}" +
                        (selectedLens?.maxExposureSeconds?.let {
                            " (HAL max ${"%.1f".format(it)}s)"
                        } ?: ""),
                    modifier = Modifier.weight(1f)
                )
            }
            Slider(
                value = exposureS.coerceIn(minExposureUi, maxExposureUi),
                onValueChange = { exposureS = it },
                valueRange = minExposureUi..maxExposureUi,
                steps = ((maxExposureUi - minExposureUi) / 0.5f).toInt().coerceAtLeast(1) - 1,
                enabled = !busy
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.phone_camera_iso) + ": ${iso.toInt()}",
                    modifier = Modifier.weight(1f)
                )
            }
            Slider(
                value = iso,
                onValueChange = { iso = it },
                valueRange = 100f..3200f,
                steps = 30,
                enabled = !busy
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = preferRaw,
                    onCheckedChange = { preferRaw = it },
                    enabled = !busy
                )
                Text(stringResource(R.string.phone_camera_prefer_raw))
            }

            Button(
                onClick = {
                    withCameraPermission { refreshLenses() }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.phone_camera_probe_capabilities))
            }

            Button(
                onClick = {
                    withCameraPermission {
                        val cameraId = selectedCameraId
                        if (cameraId == null) {
                            status = context.getString(R.string.phone_camera_lens_none)
                            return@withCameraPermission
                        }
                        busy = true
                        status = "Capturing…"
                        scope.launch {
                            try {
                                val dir = PhoneSkyCaptureStore.directory(context)
                                val stamp = PhoneSkyCaptureStore.newBaseName()
                                val dngFile = if (preferRaw) File(dir, "$stamp.dng") else null
                                val result = withContext(Dispatchers.Default) {
                                    PhoneSkyCapture(context).capture(
                                        exposureSeconds = exposureS.coerceIn(
                                            minExposureUi,
                                            maxExposureUi
                                        ).toDouble(),
                                        iso = iso.toInt(),
                                        preferRaw = preferRaw,
                                        cameraId = cameraId,
                                        dngOutputFile = dngFile
                                    )
                                }
                                val stars = withContext(Dispatchers.Default) {
                                    WideFieldStarExtractor.extractFromFrame(
                                        frame = result.frame,
                                        maxStars = 200,
                                        fovWidthDeg = result.fovWidthDeg,
                                        fovHeightDeg = result.fovHeightDeg
                                    )
                                }
                                extraction = stars
                                preview = withContext(Dispatchers.Default) {
                                    stretchPreview(result.frame.data, result.frame.width, result.frame.height, result.frame.pixelFormat.bytesPerPixel)
                                        .also { drawStars(it, stars) }
                                }

                                val fitsFile = File(dir, "$stamp.fits")
                                withContext(Dispatchers.IO) {
                                    FITSWriter().write(
                                        file = fitsFile,
                                        frame = result.frame,
                                        exposureSeconds = (result.exposureNs / 1e9).toFloat(),
                                        gain = result.iso.toFloat(),
                                        cameraName = "PhoneCamera/${result.capability.displayLabel}"
                                    )
                                }
                                if (result.dngPath == null) dngFile?.delete()
                                val reclaimed = withContext(Dispatchers.IO) {
                                    PhoneSkyCaptureStore.enforceRetention(context)
                                }
                                storageUsage = withContext(Dispatchers.IO) {
                                    PhoneSkyCaptureStore.usageBytes(context)
                                }
                                lastSavePath = buildString {
                                    append(fitsFile.absolutePath)
                                    result.dngPath?.let { append('\n').append(it) }
                                    result.dngError?.let { append("\nDNG failed: ").append(it) }
                                    if (reclaimed > 0) {
                                        append("\nauto-purged ")
                                        append(PhoneSkyCaptureStore.formatBytes(reclaimed))
                                    }
                                }

                                val pass = stars.stars.size >= GO_MIN_STARS &&
                                    (stars.estimatedLimitingMagnitude ?: 0f) >= GO_MIN_LIMITING_MAG
                                status = buildString {
                                    append(if (pass) context.getString(R.string.phone_camera_go_pass)
                                    else context.getString(R.string.phone_camera_go_fail))
                                    append('\n')
                                    append("lens=${result.capability.displayLabel}")
                                    append('\n')
                                    append(
                                        "stars=${stars.stars.size} limMag=" +
                                            (stars.estimatedLimitingMagnitude?.let { "%.2f".format(it) } ?: "?")
                                    )
                                    append(" bgσ=${"%.2f".format(stars.backgroundSigma)}")
                                    append('\n')
                                    append(
                                        "RAW=${result.usedRaw} ${result.frame.width}×${result.frame.height} " +
                                            "exp=${result.exposureNs / 1e9}s ISO=${result.iso}"
                                    )
                                    append('\n')
                                    append(
                                        "sessionOpen=${result.sessionOpenLatencyMs}ms " +
                                            "capture=${result.captureLatencyMs}ms"
                                    )
                                    result.fovWidthDeg?.let { w ->
                                        result.fovHeightDeg?.let { h ->
                                            append("\nFOV≈${"%.1f".format(w)}°×${"%.1f".format(h)}°")
                                        }
                                    }
                                }
                                capabilityText = result.capability.summaryLines().joinToString("\n")
                            } catch (t: Throwable) {
                                status = "Capture failed: ${t.javaClass.simpleName}: ${t.message}"
                                extraction = null
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.phone_camera_capture))
            }

            if (status.isNotBlank()) {
                Text(status, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            lastSavePath?.let {
                Text(
                    stringResource(R.string.phone_camera_saved, it),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        R.string.phone_camera_storage_usage,
                        PhoneSkyCaptureStore.formatBytes(storageUsage),
                        PhoneSkyCaptureStore.formatBytes(PhoneSkyCaptureStore.DEFAULT_MAX_BYTES)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            val freed = withContext(Dispatchers.IO) {
                                PhoneSkyCaptureStore.clear(context)
                            }
                            storageUsage = 0L
                            lastSavePath = null
                            status = context.getString(
                                R.string.phone_camera_storage_cleared,
                                PhoneSkyCaptureStore.formatBytes(freed)
                            )
                        }
                    },
                    enabled = !busy && storageUsage > 0
                ) {
                    Text(stringResource(R.string.phone_camera_storage_clear))
                }
            }

            preview?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            extraction?.let { ext ->
                Text(
                    "Top stars (snr): " +
                        ext.stars.take(10).joinToString { "(${"%.0f".format(it.x)},${"%.0f".format(it.y)}:${"%.1f".format(it.snr)})" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (capabilityText.isNotBlank()) {
                OutlinedTextField(
                    value = capabilityText,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("CameraCharacteristics") }
                )
            }
        }
    }
}

private fun stretchPreview(data: ByteArray, width: Int, height: Int, bpp: Int): Bitmap {
    val sample = FloatArray(min(4096, width * height / 16))
    var n = 0
    for (y in 0 until height step 8) {
        for (x in 0 until width step 8) {
            if (n >= sample.size) break
            sample[n++] = readPixel(data, width, x, y, bpp)
        }
    }
    sample.sort(0, n)
    val lo = sample[(n * 0.05).toInt().coerceIn(0, n - 1)]
    val hi = sample[(n * 0.995).toInt().coerceIn(0, n - 1)].coerceAtLeast(lo + 1f)
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
    val row = IntArray(width)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val v = ((readPixel(data, width, x, y, bpp) - lo) / (hi - lo) * 255f)
                .toInt().coerceIn(0, 255)
            row[x] = AndroidColor.rgb(v, v, v)
        }
        bmp.setPixels(row, 0, width, 0, y, width, 1)
    }
    return bmp
}

private fun drawStars(bitmap: Bitmap, result: StarExtractionResult) {
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        color = AndroidColor.RED
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = max(1f, bitmap.width / 400f)
        isAntiAlias = true
    }
    val r = max(3f, bitmap.width / 200f)
    for (star in result.stars.take(80)) {
        canvas.drawCircle(star.x, star.y, r, paint)
    }
}

private fun readPixel(data: ByteArray, width: Int, x: Int, y: Int, bpp: Int): Float {
    val i = (y * width + x) * bpp
    return if (bpp >= 2) {
        ((data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)).toFloat()
    } else {
        (data[i].toInt() and 0xFF).toFloat()
    }
}
