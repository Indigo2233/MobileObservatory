package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R

enum class CalibrationMode {
    DAYTIME,
    STAR,
    MAIN_CAMERA
}

/**
 * M4 calibration wizard shell. Steps are UI-only until plate-solve / ASTAP hooks land.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationWizardScreen(onBack: () -> Unit) {
    var mode by remember { mutableStateOf(CalibrationMode.DAYTIME) }
    var step by remember { mutableIntStateOf(0) }
    var samples by remember { mutableIntStateOf(0) }
    var residualArcmin by remember { mutableStateOf<Double?>(null) }

    val stepCount = when (mode) {
        CalibrationMode.DAYTIME -> 2
        CalibrationMode.STAR -> 3
        CalibrationMode.MAIN_CAMERA -> 3
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calibration_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.calibration_shell_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == CalibrationMode.DAYTIME,
                    onClick = { mode = CalibrationMode.DAYTIME; step = 0 },
                    label = { Text(stringResource(R.string.calibration_mode_day)) }
                )
                FilterChip(
                    selected = mode == CalibrationMode.STAR,
                    onClick = { mode = CalibrationMode.STAR; step = 0 },
                    label = { Text(stringResource(R.string.calibration_mode_star)) }
                )
                FilterChip(
                    selected = mode == CalibrationMode.MAIN_CAMERA,
                    onClick = { mode = CalibrationMode.MAIN_CAMERA; step = 0 },
                    label = { Text(stringResource(R.string.calibration_mode_main)) }
                )
            }

            Text(
                stringResource(R.string.calibration_step, step + 1, stepCount),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                when (mode) {
                    CalibrationMode.DAYTIME -> when (step) {
                        0 -> stringResource(R.string.calibration_day_step0)
                        else -> stringResource(R.string.calibration_day_step1)
                    }
                    CalibrationMode.STAR -> when (step) {
                        0 -> stringResource(R.string.calibration_star_step0)
                        1 -> stringResource(R.string.calibration_star_step1)
                        else -> stringResource(R.string.calibration_star_step2)
                    }
                    CalibrationMode.MAIN_CAMERA -> when (step) {
                        0 -> stringResource(R.string.calibration_main_step0)
                        1 -> stringResource(R.string.calibration_main_step1)
                        else -> stringResource(R.string.calibration_main_step2)
                    }
                },
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                stringResource(R.string.calibration_samples, samples),
                style = MaterialTheme.typography.bodySmall
            )
            residualArcmin?.let {
                Text(
                    stringResource(R.string.calibration_residual, it),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { if (step > 0) step-- },
                    enabled = step > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.calibration_prev))
                }
                Button(
                    onClick = {
                        if (step < stepCount - 1) {
                            step++
                            if (mode != CalibrationMode.DAYTIME) {
                                samples++
                                residualArcmin = (8.0 - samples).coerceAtLeast(2.0)
                            }
                        } else {
                            if (mode == CalibrationMode.DAYTIME) {
                                samples = 1
                                residualArcmin = 12.0
                            } else if (samples < 3) {
                                samples++
                                residualArcmin = (8.0 - samples).coerceAtLeast(2.0)
                                step = 0
                            } else {
                                residualArcmin = 2.5
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (step < stepCount - 1) stringResource(R.string.calibration_next)
                        else stringResource(R.string.calibration_capture_sample)
                    )
                }
            }

            if (samples >= 3 || (mode == CalibrationMode.DAYTIME && residualArcmin != null)) {
                Text(
                    stringResource(R.string.calibration_done_shell),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
