package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.GainCapability
import com.indigo.mobileobservatory.camera.GainHelperKind
import com.indigo.mobileobservatory.camera.GainValueNormalizer
import kotlin.math.roundToInt

@Composable
fun GainControl(
    capability: GainCapability,
    gain: Float,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gainDbEquivalent: Float? = null,
    writeInProgress: Boolean = false
) {
    val allowedValues = remember(capability) {
        capability.allowedValues
            .asSequence()
            .filter { it.isFinite() && it in minOf(capability.min, capability.max)..maxOf(capability.min, capability.max) }
            .distinct()
            .sorted()
            .toList()
    }
    val interactive = enabled && !writeInProgress && !capability.isReadOnly
    var editingText by remember { mutableStateOf(false) }
    var inputInvalid by remember { mutableStateOf(false) }
    var draftValue by remember(capability, gain) {
        mutableFloatStateOf(GainValueNormalizer.normalize(capability, gain))
    }
    var inputText by remember(capability, gain) {
        mutableStateOf(GainValueNormalizer.displayValue(capability, gain))
    }

    LaunchedEffect(capability, gain) {
        if (!editingText) {
            draftValue = GainValueNormalizer.normalize(capability, gain)
            inputText = GainValueNormalizer.displayValue(capability, gain)
            inputInvalid = false
        }
    }

    fun updateDraft(value: Float) {
        draftValue = GainValueNormalizer.normalize(capability, value)
        inputText = GainValueNormalizer.displayValue(capability, draftValue)
        inputInvalid = false
    }

    fun commit(value: Float) {
        updateDraft(value)
        editingText = false
        onGainChange(draftValue)
    }

    fun commitFromText() {
        val parsed = GainValueNormalizer.parseInput(inputText)
        if (parsed == null) {
            inputInvalid = true
            inputText = GainValueNormalizer.displayValue(capability, gain)
            editingText = false
            return
        }
        commit(parsed)
    }

    val sliderPosition = if (allowedValues.isNotEmpty()) {
        val index = allowedValues.indices.minByOrNull { index ->
            kotlin.math.abs(allowedValues[index] - draftValue)
        } ?: 0
        if (allowedValues.lastIndex > 0) index.toFloat() / allowedValues.lastIndex else 0f
    } else {
        val lower = minOf(capability.min, capability.max)
        val range = (maxOf(capability.min, capability.max) - lower).takeIf { it > 0f } ?: 1f
        ((draftValue - lower) / range).coerceIn(0f, 1f)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    capability.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                gainDbEquivalent?.let { db ->
                    Text(
                        "%.1f dB".format(db),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (capability.helperKind == GainHelperKind.VENDOR_NATIVE) {
                    Text(
                        stringResource(R.string.gain_vendor_native_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (writeInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .testTag("gain_write_busy"),
                    strokeWidth = 2.dp
                )
            }
            OutlinedTextField(
                value = inputText,
                onValueChange = {
                    editingText = true
                    inputInvalid = false
                    inputText = it
                },
                enabled = interactive,
                isError = inputInvalid,
                singleLine = true,
                suffix = capability.unit?.let { unit -> { Text(unit) } },
                supportingText = if (inputInvalid) {
                    { Text(stringResource(R.string.gain_input_invalid)) }
                } else {
                    null
                },
                textStyle = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (capability.decimalPlaces == 0) KeyboardType.Number else KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { commitFromText() }
                ),
                modifier = Modifier
                    .width(120.dp)
                    .testTag("gain_input")
                    .onFocusChanged { focusState ->
                        if (editingText && !focusState.isFocused) {
                            commitFromText()
                        }
                    }
            )
        }
        Slider(
            value = sliderPosition,
            onValueChange = { position ->
                val value = if (allowedValues.isNotEmpty()) {
                    val index = (position * allowedValues.lastIndex).roundToInt()
                        .coerceIn(allowedValues.indices)
                    allowedValues[index]
                } else {
                    minOf(capability.min, capability.max) + position * kotlin.math.abs(capability.max - capability.min)
                }
                updateDraft(value)
            },
            onValueChangeFinished = { commit(draftValue) },
            enabled = interactive,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("gain_slider"),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        val presets = remember(capability) { GainValueNormalizer.filteredPresets(capability) }
        if (presets.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.forEach { preset ->
                    AssistChip(
                        onClick = { commit(preset.value) },
                        enabled = interactive,
                        label = { Text("${preset.label} ${GainValueNormalizer.displayValue(capability, preset.value)}") }
                    )
                }
            }
        }
    }
}
