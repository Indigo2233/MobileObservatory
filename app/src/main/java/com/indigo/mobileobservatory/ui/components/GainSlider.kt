package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.indigo.mobileobservatory.R

@Composable
fun GainSlider(
    gain: Float,
    minGain: Float = 0f,
    maxGain: Float = 24f,
    onGainChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val range = maxGain - minGain
    var sliderPos by remember(gain) {
        mutableFloatStateOf(if (range > 0f) (gain - minGain) / range else 0f)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.gain),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.gain_format).format(gain),
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
                onGainChange(minGain + pos * range)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}
