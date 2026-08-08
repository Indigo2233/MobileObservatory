package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.ExposureLimits
import com.indigo.mobileobservatory.util.ImageUtils
import kotlin.math.exp
import kotlin.math.ln
import androidx.compose.ui.res.stringResource

@Composable
fun ExposureSlider(
    exposureUs: Float,
    minUs: Float = 100f,
    maxUs: Float = 1_000_000f,
    longExposure: Boolean = false,
    enabled: Boolean = true,
    onExposureChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeMin = minUs.coerceAtLeast(1f)
    val safeMax = maxUs.coerceAtLeast(safeMin + 1f)
    val logMin = ln(safeMin)
    val logMax = ln(safeMax)
    val logCurrent = ln(exposureUs.coerceIn(safeMin, safeMax))
    var sliderPos by remember(exposureUs, safeMin, safeMax) {
        mutableFloatStateOf(((logCurrent - logMin) / (logMax - logMin)).coerceIn(0f, 1f))
    }

    val focusManager = LocalFocusManager.current
    var textFocused by remember { mutableStateOf(false) }
    var textValue by remember { mutableStateOf(ImageUtils.formatExposure(exposureUs)) }

    LaunchedEffect(exposureUs) {
        if (!textFocused) {
            textValue = ImageUtils.formatExposure(exposureUs)
        }
    }

    fun commitText() {
        val parsed = ImageUtils.parseExposureUs(textValue) ?: run {
            textValue = ImageUtils.formatExposure(exposureUs)
            return
        }
        val clamped = parsed.coerceIn(safeMin, safeMax)
        textValue = ImageUtils.formatExposure(clamped)
        onExposureChange(clamped)
    }

    val presets = ExposureLimits.presets(longExposure, safeMax)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.exposure),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = textValue,
                onValueChange = { if (enabled) textValue = it },
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        commitText()
                        focusManager.clearFocus()
                    }
                ),
                modifier = Modifier
                    .width(110.dp)
                    .height(48.dp)
                    .onFocusChanged { state ->
                        if (textFocused && !state.isFocused) {
                            commitText()
                        }
                        textFocused = state.isFocused
                    }
            )
        }
        Slider(
            value = sliderPos,
            onValueChange = { pos ->
                sliderPos = pos
                val logVal = logMin + pos * (logMax - logMin)
                onExposureChange(exp(logVal))
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (preset in presets) {
                TextButton(
                    onClick = { onExposureChange(preset) },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        ImageUtils.formatExposure(preset),
                        fontSize = 10.sp,
                        color = if (kotlin.math.abs(exposureUs - preset) < preset * 0.05f)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
