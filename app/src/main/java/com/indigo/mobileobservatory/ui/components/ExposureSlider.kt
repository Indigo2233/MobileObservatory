package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.util.ImageUtils
import kotlin.math.ln
import kotlin.math.exp
import androidx.compose.ui.res.stringResource
import com.indigo.mobileobservatory.R

@Composable
fun ExposureSlider(
    exposureUs: Float,
    minUs: Float = 100f,
    maxUs: Float = 1_000_000f,
    onExposureChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeMin = minUs.coerceAtLeast(1f)
    val safeMax = maxUs.coerceAtLeast(safeMin + 1f)
    val logMin = ln(safeMin)
    val logMax = ln(safeMax)
    val logCurrent = ln(exposureUs.coerceIn(safeMin, safeMax))
    var sliderPos by remember(exposureUs) {
        mutableFloatStateOf((logCurrent - logMin) / (logMax - logMin))
    }

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
            Text(
                ImageUtils.formatExposure(exposureUs),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = sliderPos,
            onValueChange = { pos ->
                sliderPos = pos
                val logVal = logMin + pos * (logMax - logMin)
                onExposureChange(exp(logVal))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        val presets = if (maxUs > 1_100_000f)
            listOf(10_000f, 100_000f, 1_000_000f, 5_000_000f, 10_000_000f, 30_000_000f, 60_000_000f, 300_000_000f)
        else
            listOf(1000f, 10_000f, 33_333f, 100_000f, 500_000f, 1_000_000f)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (preset in presets.filter { it <= maxUs }) {
                TextButton(
                    onClick = { onExposureChange(preset) },
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
