package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.pointing.*

@Composable internal fun AutomaticCalibrationConfirmation(plan: AutomaticCalibrationPlan, enabled: Boolean = true,
    onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.wfp_auto_sampling)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wfp_auto_confirmation))
            Text(stringResource(R.string.wfp_auto_range, plan.constraints.minimumAltitudeDeg, plan.constraints.maximumAltitudeDeg, plan.constraints.maximumStepDeg))
            plan.targets.forEachIndexed { index, target ->
                Text(stringResource(R.string.wfp_auto_target, index + 1, target.star.name ?: "HIP ${target.star.hip}", target.altitudeDeg, target.azimuthDeg))
            }
        } }, confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(stringResource(R.string.wfp_auto_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
