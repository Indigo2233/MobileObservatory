package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.pointing.*
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel

/** Compatibility route; both sources use the same wizard and task. */
@Composable
fun AuxiliaryCalibrationScreen(viewModel: CameraViewModel, onBack: () -> Unit,
    onPlateSolve: () -> Unit = {}, onDeviceControl: (String) -> Unit = {}, onGuide: () -> Unit = {}) {
    CalibrationWizardScreen(viewModel, onBack, initialSource = PointingCameraSource.AUXILIARY,
        onPlateSolve = onPlateSolve, onDeviceControl = onDeviceControl, onGuide = onGuide)
}
@Composable
internal fun AuxiliaryReferenceStarChoices(stars: List<PhoneCatalogStar>, validation: Boolean,
    onChoose: (PhoneCatalogStar) -> Unit) {
    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        if (stars.isEmpty()) Text(stringResource(if (validation) R.string.wfa_no_validation_stars else R.string.wfp_no_stars))
        stars.forEach { candidate -> TextButton(onClick = { onChoose(candidate) }) {
            Text(candidate.name ?: "HIP ${candidate.hip}")
        } }
    }
}

/** Conditions are recoverable from both setup and sampling, without erasing the first sample. */
@Composable
internal fun AuxiliaryCalibrationRecovery(databaseReady: Boolean, auxiliaryMissing: Boolean,
    mainMissing: Boolean, guiding: Boolean, onPlateSolve: () -> Unit, onAuxiliaryConnect: () -> Unit,
    onMainConnect: () -> Unit, onGuide: () -> Unit) {
    if (!databaseReady) {
        Text(stringResource(R.string.wfa_database_required))
        OutlinedButton(onClick = onPlateSolve, modifier = Modifier.testTag("auxiliary-install-database")) {
            Text(stringResource(R.string.wfa_install_database))
        }
    }
    if (auxiliaryMissing) {
        Text(stringResource(R.string.wfa_no_camera))
        OutlinedButton(onClick = onAuxiliaryConnect, modifier = Modifier.testTag("auxiliary-reconnect")) {
            Text(stringResource(R.string.select_guide_camera))
        }
    }
    if (mainMissing) {
        Text(stringResource(R.string.wfa_connect_main))
        OutlinedButton(onClick = onMainConnect, modifier = Modifier.testTag("auxiliary-connect-main")) {
            Text(stringResource(R.string.camera))
        }
    }
    if (guiding) {
        Text(stringResource(R.string.wfa_stop_guiding))
        OutlinedButton(onClick = onGuide) { Text(stringResource(R.string.wfa_open_guiding)) }
    }
}
