package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.pointing.CalibrationBlocker

/** The list that disables Start is also the complete, actionable explanation beside it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PointingCalibrationBlockers(issues: List<CalibrationBlocker>, onResolve: (CalibrationBlocker) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        issues.forEach { issue ->
            val label = stringResource(when (issue) {
                CalibrationBlocker.UNFIXED -> R.string.wfc_need_fixed
                CalibrationBlocker.UNLOCKED -> R.string.wfc_need_locked
                CalibrationBlocker.SITE -> R.string.wfc_need_site
                CalibrationBlocker.PHONE_PERMISSION -> R.string.wfc_camera_permission
                CalibrationBlocker.PHONE_LENS -> R.string.wfc_need_lens
                CalibrationBlocker.MAIN_OPTICS -> R.string.wfc_need_main_optics
                CalibrationBlocker.AUXILIARY_OPTICS -> R.string.wfc_need_auxiliary_optics
                CalibrationBlocker.AUXILIARY_CAMERA -> R.string.wfc_need_auxiliary_camera
                CalibrationBlocker.AUXILIARY_FORMAT -> R.string.wfc_need_raw
                CalibrationBlocker.GUIDING -> R.string.wfc_need_stop_guiding
                CalibrationBlocker.AUXILIARY_SETTINGS -> R.string.wfa_wait_settings
                CalibrationBlocker.AUXILIARY_EXPOSURE -> R.string.wfc_need_exposure
                CalibrationBlocker.MAIN_CAMERA -> R.string.wfc_need_main_camera
                CalibrationBlocker.DATABASE -> R.string.wfa_install_database
                CalibrationBlocker.CENTERING -> R.string.wfc_need_centering
            })
            if (issue == CalibrationBlocker.AUXILIARY_SETTINGS) {
                Text(label, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("pointing-blocker-${issue.name}"))
            } else OutlinedButton(onClick = { onResolve(issue) },
                modifier = Modifier.testTag("pointing-blocker-${issue.name}"),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(label)
            }
        }
    }
}

@Composable
internal fun PointingAdvancedToggle(expanded: Boolean, onClick: () -> Unit) {
    val state = stringResource(if (expanded) R.string.wfc_expanded else R.string.wfc_collapsed)
    TextButton(onClick = onClick, modifier = Modifier.testTag("pointing-options").semantics { stateDescription = state }) {
        Text(stringResource(R.string.wfc_advanced))
        Spacer(Modifier.width(4.dp))
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null, modifier = Modifier.testTag("pointing-options-arrow"))
    }
}
