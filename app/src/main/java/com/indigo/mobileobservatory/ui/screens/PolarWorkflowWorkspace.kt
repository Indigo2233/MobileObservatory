package com.indigo.mobileobservatory.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astrometry.solveInputFormat
import com.indigo.mobileobservatory.camera.CameraPreviewSettings
import com.indigo.mobileobservatory.media.*
import com.indigo.mobileobservatory.polar.*
import kotlinx.coroutines.*
import java.io.File

/** The image and current failure stay visible while the controls scroll. */
@Composable
internal fun PolarWorkflowLayout(
    modifier: Modifier,
    failureVisible: Boolean,
    resultVisible: Boolean = false,
    preview: @Composable (Modifier) -> Unit,
    controls: @Composable (Modifier) -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= 560.dp && maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                preview(Modifier.weight(1f).fillMaxHeight())
                controls(Modifier.widthIn(max = 360.dp).weight(.85f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                val fraction = if (failureVisible || resultVisible) .62f else .46f
                preview(Modifier.fillMaxWidth().weight(fraction))
                controls(Modifier.fillMaxWidth().weight(1f - fraction))
            }
        }
    }
}

@Composable
internal fun PolarExposureControls(
    seconds: String,
    onSecondsChange: (String) -> Unit,
    minimumUs: Float,
    maximumUs: Float,
    actualUs: Float?,
    connected: Boolean,
    busy: Boolean,
    onCapture: () -> Unit
) {
    val focus = LocalFocusManager.current
    var presets by remember { mutableStateOf(false) }
    val valid = PolarExposure.parseUs(seconds, minimumUs, maximumUs) != null
    OutlinedTextField(
        value = seconds, onValueChange = onSecondsChange,
        label = { Text(stringResource(R.string.polar_exposure_seconds)) },
        modifier = Modifier.fillMaxWidth().testTag("polar-exposure-seconds"),
        enabled = !busy, singleLine = true, isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        supportingText = {
            Text(when {
                busy -> stringResource(R.string.polar_exposure_locked)
                !valid -> stringResource(R.string.polar_exposure_out_of_range, minimumUs / 1_000_000f, maximumUs / 1_000_000f)
                actualUs != null && connected -> stringResource(R.string.polar_exposure_actual, PolarExposure.secondsText(actualUs))
                else -> stringResource(R.string.polar_exposure_applied_on_capture)
            })
        },
        trailingIcon = {
            Box {
                IconButton(onClick = { presets = true }, enabled = !busy) {
                    Icon(Icons.Default.ArrowDropDown, stringResource(R.string.polar_exposure_presets))
                }
                DropdownMenu(expanded = presets, onDismissRequest = { presets = false }) {
                    listOf("0.5", "1", "2", "3", "5", "10", "15", "30").filter {
                        PolarExposure.parseUs(it, minimumUs, maximumUs) != null
                    }.forEach { value ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.polar_exposure_value, value)) },
                            onClick = { onSecondsChange(value); focus.clearFocus(); presets = false })
                    }
                }
            }
        }
    )
    OutlinedButton(onClick = { focus.clearFocus(); onCapture() },
        enabled = connected && !busy && valid,
        modifier = Modifier.fillMaxWidth().testTag("polar-test-capture")) {
        Text(stringResource(R.string.polar_test_capture))
    }
    if (!connected) Text(stringResource(R.string.polar_capture_no_camera), style = MaterialTheme.typography.bodySmall)
}

/** A dialog pins its source, so subsequent captures cannot replace the image being inspected. */
@Composable
internal fun PolarWorkflowPreview(
    file: File?,
    label: String,
    livePreview: Bitmap?,
    sample: PolarSample?,
    status: String,
    error: String?,
    busy: Boolean,
    onStop: () -> Unit,
    inspectFile: File?,
    onInspect: (File?) -> Unit,
    adjustment: PolarAdjustmentSnapshot? = null,
    modifier: Modifier = Modifier,
    previewSettings: CameraPreviewSettings = CameraPreviewSettings()
) {
    val source = inspectFile ?: file
    val pinnedAdjustment = remember(inspectFile) { adjustment?.takeIf { it.file == inspectFile } }
    val imageAdjustment = (if (inspectFile != null) pinnedAdjustment else adjustment)?.takeIf { it.file == source }
    val overlayColors = polarOverlayColors()
    val key = source?.let { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
    var prepared by remember(key, previewSettings) { mutableStateOf<PreparedCameraPreview?>(null) }
    var bitmap by remember(key, previewSettings) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(key, previewSettings) { mutableStateOf(source != null) }
    var previewError by remember(key, previewSettings) { mutableStateOf<String?>(null) }
    var inputFormat by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key, previewSettings) {
        if (source == null) return@LaunchedEffect
        val task = currentCoroutineContext()
        var owned: Bitmap? = null
        var published = false
        try {
            val image = withContext(Dispatchers.IO) {
                inputFormat = if (source.extension.lowercase() in setOf("fit", "fits")) {
                    val header = com.indigo.mobileobservatory.util.FitsHeaderParser.read(source)
                    header.solveInputFormat()
                } else source.extension.uppercase()
                val decoded = CameraImagePreview.prepare(source, previewSettings, cancelled = { !task.isActive })
                owned = decoded.bitmap
                decoded
            }
            ensureActive()
            prepared = image; bitmap = owned; published = true
        } catch (cancel: CancellationException) { throw cancel
        } catch (_: OutOfMemoryError) { previewError = "memory"
        } catch (failure: Exception) { previewError = failure.message ?: failure.javaClass.simpleName
        } finally { loading = false; if (!published) owned?.recycle() }
    }
    // Published bitmaps can still be referenced by a Compose draw node/render thread
    // after disposal. Let the runtime release them once those references disappear.
    var showLog by remember { mutableStateOf(false) }
    var lastFailure by remember { mutableStateOf<PolarSample?>(null) }
    var diagnosticSample by remember { mutableStateOf<PolarSample?>(null) }
    var diagnosticError by remember { mutableStateOf<String?>(null) }
    var diagnosticFormat by remember { mutableStateOf<String?>(null) }
    val failed = sample?.takeIf { it.phase == PolarSamplePhase.FAILED }
    LaunchedEffect(failed) { if (failed != null) lastFailure = failed }
    val details = failed?.failureDetails()
    val clipboard = LocalClipboardManager.current
    BoxWithConstraints(modifier.padding(horizontal = 12.dp)) {
        // Limit the error panel using the actual viewport, including large font layouts.
        val footerMaxHeight = if (adjustment != null) minOf(220.dp, maxHeight * .43f) else minOf(144.dp, maxHeight * .52f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                if (busy) OutlinedButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
                TextButton(onClick = { source?.let { onInspect(it) } }, enabled = source?.isFile == true,
                    modifier = Modifier.testTag("polar-preview-expand")) { Text(stringResource(R.string.polar_view_sample)) }
                if (source != null || details != null || error != null) IconButton(onClick = {
                    diagnosticSample = sample?.takeIf { it.result != null } ?: lastFailure ?: sample
                    diagnosticError = error
                    diagnosticFormat = inputFormat
                    showLog = true
                },
                    modifier = Modifier.testTag("polar-diagnostics-expand")) {
                    Icon(Icons.Default.Info, stringResource(R.string.show_log))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center) {
                val image = bitmap ?: livePreview?.takeIf { source == null && !it.isRecycled }
                if (image != null) Box(Modifier.fillMaxSize().testTag("polar-main-preview")) {
                    PolarCorrectionImage(image, prepared?.originalWidth ?: image.width, prepared?.originalHeight ?: image.height,
                        imageAdjustment?.geometry, Modifier.fillMaxSize().clickable(enabled = source != null) { onInspect(source) })
                    imageAdjustment?.let { PolarErrorReticleOverlay(it) }
                }
                else Text(stringResource(R.string.polar_preview_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                if (loading) CircularProgressIndicator(Modifier.size(28.dp))
                previewError?.let {
                    Text(stringResource(R.string.polar_preview_failed, it), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                }
            }
            Column(Modifier.heightIn(max = footerMaxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (details == null && error == null) adjustment?.let { PolarCorrectionSummary(it) }
                inputFormat?.let { Text(stringResource(R.string.solve_input_format, it), style = MaterialTheme.typography.bodySmall) }
                if (sample?.result?.log?.contains("attempt=uint16_sum2") == true) {
                    Text(stringResource(R.string.solve_sum_retry), style = MaterialTheme.typography.bodySmall)
                }
                if (details == null && error == null && (adjustment == null || busy)) {
                    Text(status.ifBlank { stringResource(R.string.polar_preview_ready) },
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (details != null || error != null) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().testTag("polar-current-failure").semantics(mergeDescendants = true) {}) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (details != null) {
                                Text(stringResource(failureTitle(details.reason)), style = MaterialTheme.typography.labelLarge)
                                details.detectedStars?.let { Text(stringResource(R.string.polar_detected_stars, it), style = MaterialTheme.typography.bodySmall) }
                                Text(details.nativeMessage, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(failureAdvice(details.reason)), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            } else Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (details != null || error != null) adjustment?.let { PolarCorrectionSummary(it) }
            }
        }
    }
    if (showLog) {
        val diagnostics = buildString {
            appendLine(diagnosticFormat.orEmpty())
            appendLine("displayOnly=true monochromePreview=${previewSettings.monochrome}")
            appendLine(diagnosticError.orEmpty())
            appendLine("phase=${diagnosticSample?.phase} failedPhase=${diagnosticSample?.failedPhase}")
            appendLine("image=${diagnosticSample?.file?.absolutePath} capturedAt=${diagnosticSample?.capturedAt}")
            appendLine(diagnosticSample?.failure.orEmpty())
            append(diagnosticSample?.result?.log.orEmpty())
        }
        AlertDialog(onDismissRequest = { showLog = false }, title = { Text(stringResource(R.string.show_log)) },
            text = { SelectionContainer { Text(diagnostics.take(64_000),
                Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text(stringResource(R.string.close)) } },
            dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(diagnostics)) }) { Text(stringResource(R.string.polar_copy_diagnostics)) } })
    }
    if (inspectFile != null) {
        val navigation = remember { MediaInspectionNavigation() }
        Dialog(onDismissRequest = { if (!navigation.onBack()) onInspect(null) },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().testTag("polar-fullscreen-preview")) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.polar_image_inspection), Modifier.weight(1f))
                        if (busy) TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
                        TextButton(onClick = { onInspect(null) }, modifier = Modifier.testTag("polar-preview-close")) { Text(stringResource(R.string.close)) }
                    }
                    Text(stringResource(R.string.solve_input_raw_hint), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                    pinnedAdjustment?.let {
                        Column(Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState())) { PolarCorrectionSummary(it) }
                    }
                    val image = bitmap; val decoded = prepared
                    if (image != null && decoded != null) Box(Modifier.weight(1f).fillMaxWidth()) {
                        MediaImageInspection(
                        image = image, before = null, imageKey = key.orEmpty(), originalWidth = decoded.originalWidth,
                        originalHeight = decoded.originalHeight, nativeKey = decoded.settings to decoded.window,
                        renderNative = { region, _ ->
                            val task = currentCoroutineContext()
                            withContext(Dispatchers.IO) { CameraImagePreview.renderRegion(decoded, region) { !task.isActive } }
                        }, navigation = navigation, modifier = Modifier.fillMaxSize()
                            .then(if (pinnedAdjustment?.geometry != null) Modifier.testTag("polar-correction-inspection") else Modifier),
                        overlay = pinnedAdjustment?.geometry?.let { geometry ->
                            { offset, imageSize -> drawPolarCorrection(geometry, offset, imageSize, overlayColors) }
                        })
                        pinnedAdjustment?.let { PolarErrorReticleOverlay(it) }
                    }
                    else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (loading) CircularProgressIndicator()
                        else Text(stringResource(R.string.polar_preview_failed, previewError.orEmpty()))
                    }
                }
            }
        }
    }
}

private fun failureTitle(reason: PolarFailureReason): Int = when (reason) {
    PolarFailureReason.CAPTURE -> R.string.polar_reason_capture
    PolarFailureReason.OPTICS -> R.string.polar_reason_optics
    PolarFailureReason.STARS -> R.string.polar_reason_stars
    PolarFailureReason.DATABASE -> R.string.polar_reason_database
    PolarFailureReason.TIMEOUT -> R.string.polar_reason_timeout
    PolarFailureReason.EXECUTION -> R.string.polar_reason_execution
    PolarFailureReason.IMAGE -> R.string.polar_reason_image
    PolarFailureReason.MEMORY -> R.string.polar_reason_memory
    PolarFailureReason.NO_SOLUTION -> R.string.polar_reason_no_solution
}

private fun failureAdvice(reason: PolarFailureReason): Int = when (reason) {
    PolarFailureReason.CAPTURE -> R.string.polar_capture_advice
    PolarFailureReason.OPTICS -> R.string.polar_optics_advice
    PolarFailureReason.STARS -> R.string.polar_stars_advice
    PolarFailureReason.DATABASE -> R.string.polar_database_advice
    PolarFailureReason.TIMEOUT -> R.string.polar_timeout_advice
    PolarFailureReason.EXECUTION -> R.string.polar_execution_advice
    PolarFailureReason.IMAGE -> R.string.polar_image_advice
    PolarFailureReason.MEMORY -> R.string.polar_memory_advice
    PolarFailureReason.NO_SOLUTION -> R.string.polar_solve_advice
}
