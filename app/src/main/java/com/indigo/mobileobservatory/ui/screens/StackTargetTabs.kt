package com.indigo.mobileobservatory.ui.screens

import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.stacking.StackTarget

@Composable internal fun stackTargetLabel(target: StackTarget): String = stringResource(
    if (target == StackTarget.PLANETARY) R.string.stack_target_planetary else R.string.stack_target_deep_sky)

/** The same subject choice is available in both acquisition and post-processing workflows. */
@Composable internal fun StackTargetTabs(selected: StackTarget, enabled: Boolean = true, onSelect: (StackTarget) -> Unit) {
    val targets = listOf(StackTarget.PLANETARY, StackTarget.DEEP_SKY)
    TabRow(selectedTabIndex = targets.indexOf(selected)) {
        targets.forEach { target -> Tab(selected = target == selected, enabled = enabled,
            onClick = { onSelect(target) }, text = { Text(stackTargetLabel(target)) },
            modifier = Modifier.testTag("stack-target-${target.name}")) }
    }
}
