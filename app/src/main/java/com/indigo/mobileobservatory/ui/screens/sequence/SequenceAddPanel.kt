@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.indigo.mobileobservatory.ui.screens.sequence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.sequence.SequenceCatalogEntry
import com.indigo.mobileobservatory.sequence.SequenceSkyTarget
import com.indigo.mobileobservatory.sequence.SequenceSlot
import com.indigo.mobileobservatory.sequence.catalog.SupportLevel
import com.indigo.mobileobservatory.sequence.sequenceCatalog

internal enum class SequenceAddTab { Instructions, Templates, Targets }

@Composable
internal fun SequenceAddPanel(
    slot: SequenceSlot,
    chinese: Boolean,
    templates: List<String>,
    onPickInstruction: (SequenceCatalogEntry) -> Unit,
    onPickTemplate: (String) -> Unit,
    skyTarget: SequenceSkyTarget? = null,
    onAddSkyTarget: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var tab by remember { mutableStateOf(SequenceAddTab.Instructions) }
    var showAll by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = tab == SequenceAddTab.Instructions,
                onClick = { tab = SequenceAddTab.Instructions },
                label = { Text(stringResource(R.string.sequence_tab_instructions)) }
            )
            FilterChip(
                selected = tab == SequenceAddTab.Templates,
                onClick = { tab = SequenceAddTab.Templates },
                label = { Text(stringResource(R.string.sequence_templates)) }
            )
            FilterChip(
                selected = tab == SequenceAddTab.Targets,
                onClick = { tab = SequenceAddTab.Targets },
                label = { Text(stringResource(R.string.sequence_tab_targets)) }
            )
        }
        when (tab) {
            SequenceAddTab.Instructions -> {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.sequence_search)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showAll, onCheckedChange = { showAll = it })
                    Text(stringResource(R.string.sequence_show_all_instructions))
                }
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val needle = query.trim()
                    sequenceCatalog(includeHidden = showAll)
                        .filter { it.slot == slot }
                        .filter { entry ->
                            needle.isEmpty() ||
                                entry.titleZh.contains(needle, ignoreCase = true) ||
                                entry.titleEn.contains(needle, ignoreCase = true) ||
                                entry.groupZh.contains(needle, ignoreCase = true)
                        }
                        .groupBy { if (chinese) it.groupZh else it.groupEn }
                        .forEach { (group, entries) ->
                            Text(group, style = MaterialTheme.typography.titleSmall)
                            entries.forEach { entry ->
                                val dim = entry.supportLevel != SupportLevel.Execute
                                Button(
                                    onClick = { onPickInstruction(entry) },
                                    modifier = Modifier.fillMaxWidth().alpha(if (dim) 0.7f else 1f)
                                ) {
                                    val mark = when (entry.slot) {
                                        SequenceSlot.Trigger -> "⚡ "
                                        SequenceSlot.Condition -> "☰ "
                                        SequenceSlot.Item -> if (entry.id.endsWith("Container") || entry.id == "SmartExposure" || entry.id == "TakeManyExposures") "▣ " else "≡ "
                                    }
                                    val badge = when (entry.supportLevel) {
                                        SupportLevel.Pause -> " · ${stringResource(R.string.sequence_support_pause)}"
                                        SupportLevel.Retain -> " · ${stringResource(R.string.sequence_support_retain)}"
                                        SupportLevel.Execute -> ""
                                    }
                                    Text(mark + (if (chinese) entry.titleZh else entry.titleEn) + badge)
                                }
                            }
                        }
                }
            }
            SequenceAddTab.Templates -> {
                if (templates.isEmpty()) {
                    Text(
                        stringResource(R.string.sequence_templates_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    templates.forEach { name ->
                        TextButton(onClick = { onPickTemplate(name) }, modifier = Modifier.fillMaxWidth()) {
                            Text(name)
                        }
                    }
                }
            }
            SequenceAddTab.Targets -> {
                val sky = skyTarget
                if (sky == null) {
                    Text(
                        stringResource(R.string.sequence_targets_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Text(sky.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.sequence_sky_target_summary,
                            sky.raHours,
                            sky.decDeg,
                            sky.positionAngleDeg
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Button(onClick = onAddSkyTarget, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.sequence_add_from_star_map))
                    }
                }
            }
        }
    }
}
