package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.GainCapability
import com.indigo.mobileobservatory.util.ImageUtils
import kotlin.math.ceil

/** Reposition the same preview/control nodes on rotation; do not recreate the camera view. */
@Composable
internal fun CapturePreviewLayout(portrait: Boolean, showControls: Boolean, previewAspectRatio: Float?,
    modifier: Modifier = Modifier, preview: @Composable () -> Unit,
    quickControls: @Composable () -> Unit, fullControls: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier) {
    val viewportHeight = constraints.maxHeight
    Layout(modifier = Modifier.fillMaxWidth().verticalScroll(scroll, enabled = portrait), content = {
        Box(Modifier.fillMaxSize().testTag("capture-preview")) { preview() }
        Box(Modifier.fillMaxWidth().testTag("capture-quick-controls")) { quickControls() }
        Box(Modifier.fillMaxSize()) { fullControls() }
    }) { nodes, constraints ->
        val width = constraints.maxWidth
        val height = viewportHeight
        val panelWidth = if (!portrait && showControls) minOf(300.dp.roundToPx(), width / 2) else 0
        val quick = if (portrait && showControls) nodes[1].measure(Constraints(minWidth = width, maxWidth = width,
            maxHeight = (height / 2).coerceAtLeast(0))) else null
        val quickHeight = quick?.height ?: 0
        // Width owns the scale in portrait. Short viewports can scroll the image and strip
        // without narrowing the image; the shutter/record bar stays outside this layout.
        val naturalPreviewHeight = previewAspectRatio?.takeIf { it.isFinite() && it > 0 }
            ?.let { ceil(width / it.toDouble()).toInt() } ?: (height - quickHeight)
        val previewHeight = if (portrait) maxOf(naturalPreviewHeight, height - quickHeight).coerceAtLeast(0) else height
        val image = nodes[0].measure(Constraints.fixed(width - panelWidth, previewHeight.coerceAtLeast(0)))
        // Portrait settings live in a bounded temporary sheet, not a second page below the image.
        val controls = if (showControls && !portrait) nodes[2].measure(Constraints.fixed(panelWidth, height)) else null
        layout(width, if (portrait) previewHeight + quickHeight else height) {
            image.placeRelative(0, 0)
            quick?.placeRelative(0, previewHeight)
            controls?.placeRelative(width - panelWidth, 0)
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortraitCaptureControls(exposureUs: Float, exposureMin: Float, exposureMax: Float,
    gain: Float, capability: GainCapability?, gainBusy: Boolean, manualEnabled: Boolean,
    autoStretch: Boolean, settingsOpen: Boolean, onExposure: (Float) -> Unit, onGain: (Float) -> Unit,
    onStretch: (Boolean) -> Unit, onSettings: () -> Unit,
    recording: Boolean, onStopRecording: () -> Unit, safetyControls: @Composable () -> Unit = {}) {
    var edit by rememberSaveable { mutableStateOf<String?>(null) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            CaptureParameterCell(stringResource(R.string.exposure), if (manualEnabled) ImageUtils.formatExposure(exposureUs) else stringResource(R.string.capture_auto),
                Modifier.weight(1f).testTag("capture-exposure"), enabled = manualEnabled, selected = edit == "exposure") { edit = "exposure" }
            CaptureParameterCell(stringResource(R.string.gain), if (manualEnabled) gain.toString().removeSuffix(".0") else stringResource(R.string.capture_auto),
                Modifier.weight(1f).testTag("capture-gain"), enabled = manualEnabled && capability != null && !gainBusy, selected = edit == "gain") { edit = "gain" }
            CaptureParameterCell(stringResource(R.string.capture_stretch), stringResource(if (autoStretch) R.string.capture_auto else R.string.capture_off),
                Modifier.weight(1f).testTag("capture-stretch"), selected = autoStretch, disclosure = false) { onStretch(!autoStretch) }
            TextButton(onClick = onSettings, modifier = Modifier.weight(1f).heightIn(min = 64.dp).testTag("capture-settings-open"),
                colors = ButtonDefaults.textButtonColors(contentColor = if (settingsOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Tune, null, Modifier.size(20.dp))
                    Text(stringResource(R.string.capture_settings), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (edit != null) ModalBottomSheet(onDismissRequest = { edit = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PopupWindowBackHandler { edit = null }
        Column(Modifier.fillMaxWidth().testTag("capture-parameter-sheet").heightIn(max = (LocalConfiguration.current.screenHeightDp * .5f).dp).navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp)) {
            safetyControls()
            CaptureSheetHeader(stringResource(if (edit == "exposure") R.string.exposure else R.string.gain), recording, onStopRecording) { edit = null }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (edit == "exposure") ExposureSlider(exposureUs, exposureMin, exposureMax, enabled = manualEnabled, onExposureChange = onExposure)
                else capability?.let { GainControl(capability = it, gain = gain, onGainChange = onGain, enabled = manualEnabled, writeInProgress = gainBusy) }
            }
        }
    }
}

@Composable
private fun CaptureParameterCell(label: String, value: String, modifier: Modifier, enabled: Boolean = true,
    selected: Boolean = false, disclosure: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 64.dp),
        contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.textButtonColors(
            contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.labelLarge)
                if (disclosure) Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun CaptureSheetHeader(title: String, recording: Boolean, onStopRecording: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (recording) TextButton(onClick = onStopRecording) { Text(stringResource(R.string.stop_recording), color = MaterialTheme.colorScheme.error) }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureControlPanelHost(portrait: Boolean, settingsOpen: Boolean, recording: Boolean,
    onStopRecording: () -> Unit, onClose: () -> Unit, safetyControls: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    if (!portrait) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp, content = content)
    else if (settingsOpen) ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PopupWindowBackHandler(onBack = onClose)
        Column(Modifier.fillMaxWidth().testTag("capture-settings-sheet").heightIn(max = (LocalConfiguration.current.screenHeightDp * .55f).dp).navigationBarsPadding()) {
            safetyControls()
            Box(Modifier.padding(horizontal = 16.dp)) { CaptureSheetHeader(stringResource(R.string.capture_settings), recording, onStopRecording, onClose) }
            Box(Modifier.weight(1f, fill = false)) { content() }
        }
    }
}
