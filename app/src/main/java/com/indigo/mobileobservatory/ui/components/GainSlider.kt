package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.camera.GainCapability
import com.indigo.mobileobservatory.camera.GainValueNormalizer
import kotlin.math.roundToInt

@Composable
fun GainControl(
    capability: GainCapability,
    gain: Float,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gainDbEquivalent: Float? = null
) {
    val allowedValues = remember(capability) {
        capability.allowedValues
            .asSequence()
            .filter { it.isFinite() && it in minOf(capability.min, capability.max)..maxOf(capability.min, capability.max) }
            .distinct()
            .sorted()
            .toList()
    }
    var editingText by remember { mutableStateOf(false) }
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
        }
    }

    fun updateDraft(value: Float) {
        draftValue = GainValueNormalizer.normalize(capability, value)
        inputText = GainValueNormalizer.displayValue(capability, draftValue)
    }

    fun commit(value: Float) {
        updateDraft(value)
        editingText = false
        onGainChange(draftValue)
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
            Column {
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
            }
            OutlinedTextField(
                value = inputText,
                onValueChange = {
                    editingText = true
                    inputText = it
                },
                enabled = enabled,
                singleLine = true,
                suffix = capability.unit?.let { unit -> { Text(unit) } },
                textStyle = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (capability.decimalPlaces == 0) KeyboardType.Number else KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { commit(inputText.toFloatOrNull() ?: gain) }
                ),
                modifier = Modifier
                    .width(120.dp)
                    .onFocusChanged { focusState ->
                        if (editingText && !focusState.isFocused) {
                            commit(inputText.toFloatOrNull() ?: gain)
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
            enabled = enabled && capability.min != capability.max,
            modifier = Modifier.fillMaxWidth(),
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
                        enabled = enabled,
                        label = { Text("${preset.label} ${GainValueNormalizer.displayValue(capability, preset.value)}") }
                    )
                }
            }
        }
    }
}
