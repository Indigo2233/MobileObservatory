package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.guide.GuideAlgorithm
import com.indigo.mobileobservatory.ui.components.GainControl
import com.indigo.mobileobservatory.ui.settings.GuideNumberSpec
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel

private val exposureSpec = GuideNumberSpec("0.05", "5", "0.05", 2)
private val stepsSpec = GuideNumberSpec("5", "20", "1")
private val distanceSpec = GuideNumberSpec("10", "50", "1", 2)
private val pulseSpec = GuideNumberSpec("300", "5000", "100")
private val aggressionSpec = GuideNumberSpec("10", "100", "1")
private val minMoveSpec = GuideNumberSpec("0.05", "1", "0.01", 2)
private val focalSpec = GuideNumberSpec("0", "10000", "1", 2, blankIsZero = true)

@Composable
internal fun GuideSettingsDialog(vm: CameraViewModel, onDismiss: () -> Unit, guiding: Boolean, exposing: Boolean) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf(R.string.guide_camera, R.string.calibration, R.string.gw_algorithms, R.string.gw_optics)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(12.dp).widthIn(max = 960.dp).fillMaxWidth().fillMaxHeight(.96f)
                .testTag("guide-settings-dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column {
                Text(stringResource(R.string.gw_settings_title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
                Divider()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val sidebar = maxWidth >= 680.dp && LocalDensity.current.fontScale <= 1.3f
                    if (sidebar) {
                        Row(Modifier.fillMaxSize()) {
                            Column(Modifier.width(168.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                                .padding(12.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                labels.forEachIndexed { index, label ->
                                    GuideSettingsCategory(stringResource(label), tab == index, { tab = index }, Modifier.fillMaxWidth())
                                }
                            }
                            Divider(Modifier.width(1.dp).fillMaxHeight())
                            GuideSettingsContent(vm, tab, Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp).selectableGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                labels.forEachIndexed { index, label ->
                                    GuideSettingsCategory(stringResource(label), tab == index, { tab = index })
                                }
                            }
                            GuideSettingsContent(vm, tab, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
                Divider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (guiding) OutlinedButton(onClick = { vm.setGuideRunning(false) }) { Text(stringResource(R.string.gw_stop_guiding)) }
                        if (exposing) OutlinedButton(onClick = { vm.setGuideExposureRunning(false) }) { Text(stringResource(R.string.gw_stop_exposure)) }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("guide-settings-close")) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

@Composable
private fun GuideSettingsCategory(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.selectable(selected = selected, onClick = onSelect, role = Role.Tab),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
    }
}

@Composable
private fun GuideSettingsContent(vm: CameraViewModel, tab: Int, modifier: Modifier) {
    // Each category owns its scroll position; selecting a category starts at its heading.
    key(tab) {
        Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp).testTag("guide-settings-content"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (tab) {
                0 -> GuideCameraSettings(vm)
                1 -> GuideCalibrationSettings(vm)
                2 -> GuideAlgorithmSettings(vm)
                3 -> GuideOpticsSettings(vm)
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
    GuideNumberSetting(stringResource(R.string.guide_exposure), exposure / 1_000_000, exposureSpec, "s", "exposure",
        { vm.setGuideExposure(it * 1_000_000) }, enabled)
    capability?.let { GainControl(capability = it, gain = gain, onGainChange = vm::setGuideGain, enabled = enabled, writeInProgress = writing) }
    GuideSettingsHeading(stringResource(R.string.gw_bit_depth))
    GuideSettingsChoice(formats, format, { "${it.nativeBits} bit · ${it.name}" }, vm::setGuidePixelFormat, enabled)
    GuideSettingsHeading(stringResource(R.string.gw_readout))
    if (native.isNotEmpty()) GuideSettingsChoice(native.map { it.id }, nativeId,
        { id -> native.firstOrNull { it.id == id }?.displayName ?: id }, vm::setGuideNativeReadoutMode, enabled)
    else GuideSettingsChoice(modes, mode, { it.displayName }, vm::setGuideReadoutMode, enabled)
    GuideSettingsHelp(stringResource(R.string.gw_raw_help))
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
    GuideNumberSetting(stringResource(R.string.gw_steps), steps.toFloat(), stepsSpec, "", "steps",
        { vm.setGuideCalibrationSteps(it.toInt()) }, !calibrating)
    GuideNumberSetting(stringResource(R.string.gw_travel_label), distance, distanceSpec, "px", "distance",
        vm::setGuideCalibrationDistance, !calibrating)
    GuideNumberSetting(stringResource(R.string.gw_pulse_label), pulse.toFloat(), pulseSpec, "ms", "pulse",
        { vm.setGuideCalibrationPulseMs(it.toInt()) }, !calibrating)
    Divider()
    GuideToggle(stringResource(R.string.gw_backlash), backlash, vm::setGuideBacklashEnabled)
    GuideSettingsHelp(stringResource(R.string.gw_calibration_help))
    calibration?.let {
        Text(stringResource(R.string.guide_rates, it.eastRatePxPerSec, it.northRatePxPerSec), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.gw_calibration_result, it.eastSteps, it.northSteps, it.raBacklashMs, it.decBacklashMs, it.fitResidualPx),
            style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { if (calibrating) vm.setGuideRunning(false) else vm.startGuideCalibration() }, enabled = !running) {
            Text(stringResource(if (calibrating) R.string.gw_stop_guiding else R.string.calibration))
        }
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
    GuideSettingsChoice(GuideAlgorithm.entries, algorithm, { names.getValue(it) }, vm::setGuideAlgorithm)
    GuideToggle(stringResource(R.string.gw_multistar), multi, vm::setGuideMultiStarEnabled)
    GuideNumberSetting(stringResource(R.string.gw_ra_aggression), ra * 100, aggressionSpec, "%", "ra",
        { vm.setGuideRaAggressiveness(it / 100) })
    GuideNumberSetting(stringResource(R.string.gw_dec_aggression), dec * 100, aggressionSpec, "%", "dec",
        { vm.setGuideDecAggressiveness(it / 100) })
    GuideNumberSetting(stringResource(R.string.gw_min_move_label), minMove, minMoveSpec, "px", "min-move", vm::setGuideMinMovePx)
    Divider()
    GuideToggle(stringResource(R.string.gw_reverse_ra), reverseRa, vm::setGuideReverseRa)
    GuideToggle(stringResource(R.string.gw_reverse_dec), reverseDec, vm::setGuideReverseDec)
}

@Composable
private fun GuideOpticsSettings(vm: CameraViewModel) {
    val focal by vm.guideFocalLength.collectAsStateWithPageLifecycle()
    val geometry by vm.guideCameraGeometry.collectAsStateWithPageLifecycle()
    GuideNumberSetting(stringResource(R.string.gw_focal_label), focal, focalSpec, "mm", "focal", vm::setGuideFocalLength)
    GuideSettingsHelp(stringResource(R.string.gw_focal_optional))
    GuideSettingsHelp(stringResource(R.string.gw_scale_help))
    geometry?.let { Text(stringResource(R.string.gw_pixel_um, it.pixelSizeUm * it.binning), style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun GuideNumberSetting(label: String, value: Float, spec: GuideNumberSpec, unit: String, id: String,
    onApply: (Float) -> Unit, enabled: Boolean = true) {
    var text by rememberSaveable(value) { mutableStateOf(spec.format(value)) }
    val parsed = spec.parse(text)
    val dirty = text != spec.format(value) || parsed?.toFloat() != value
    val invalid = dirty && parsed == null
    val focus = LocalFocusManager.current
    fun apply() {
        parsed?.let { text = spec.format(it); onApply(it.toFloat()); focus.clearFocus() }
    }
    fun adjust(direction: Int) {
        val next = spec.adjusted(text, value, direction)
        text = spec.format(next)
        onApply(next.toFloat())
        focus.clearFocus()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GuideSettingsHeading(label)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = { adjust(-1) }, enabled = enabled && (parsed?.toFloat() ?: value) > spec.minimumText.toFloat(),
                modifier = Modifier.size(48.dp).testTag("guide-$id-decrease")) {
                Icon(Icons.Default.Remove, stringResource(R.string.gw_decrease_value, label))
            }
            OutlinedTextField(text, { text = it }, enabled = enabled, singleLine = true, isError = invalid,
                suffix = if (unit.isEmpty()) null else ({ Text(unit) }),
                keyboardOptions = KeyboardOptions(keyboardType = if (spec.decimalPlaces == 0) KeyboardType.Number else KeyboardType.Decimal,
                    imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                modifier = Modifier.weight(1f).testTag("guide-$id-input").semantics { contentDescription = label })
            FilledTonalIconButton(onClick = { adjust(1) }, enabled = enabled && (parsed?.toFloat() ?: value) < spec.maximumText.toFloat(),
                modifier = Modifier.size(48.dp).testTag("guide-$id-increase")) {
                Icon(Icons.Default.Add, stringResource(R.string.gw_increase_value, label))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (invalid && spec.decimalPlaces == 0) stringResource(R.string.gw_integer_invalid, spec.minimumText, spec.maximumText)
                else if (invalid) stringResource(R.string.gw_decimal_invalid, spec.minimumText, spec.maximumText, spec.decimalPlaces)
                else stringResource(R.string.gw_number_range, spec.minimumText, spec.maximumText, spec.stepText),
                style = MaterialTheme.typography.bodySmall,
                color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { apply() }, enabled = enabled && parsed != null && dirty,
                modifier = Modifier.testTag("guide-$id-apply")) { Text(stringResource(R.string.gw_apply_value)) }
        }
    }
}

@Composable
private fun GuideSettingsHeading(text: String) { Text(text, style = MaterialTheme.typography.titleSmall) }

@Composable
private fun GuideSettingsHelp(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GuideToggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, change)
    }
}

@Composable
private fun <T> GuideSettingsChoice(values: List<T>, selected: T, label: (T) -> String, change: (T) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled && values.isNotEmpty()) { Text(label(selected)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text(label(value)) }, onClick = { open = false; change(value) }) }
        }
    }
}
