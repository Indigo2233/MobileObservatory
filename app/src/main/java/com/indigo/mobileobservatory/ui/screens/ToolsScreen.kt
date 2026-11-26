package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R

/**
 * User-requested tools hub. These are shortcuts into existing tasks, with the
 * same navigation owner and ViewModels as the contextual workspace entries.
 */
@Composable
fun ToolsScreen(
    onOpenPlateSolve: () -> Unit,
    onOpenPolarAlignment: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenPlayer: () -> Unit,
    showSequenceEntry: Boolean,
    onOpenSequence: () -> Unit,
    onOpenStacking: () -> Unit = {},
    onOpenLiveStacking: () -> Unit = {},
    onOpenSolarScan: () -> Unit = {},
    modifier: Modifier = Modifier,
    wideLayout: Boolean = false,
    plateSolveStatus: String? = null,
    polarStatus: String? = null,
    guideStatus: String? = null,
    playerStatus: String? = null,
    sequenceStatus: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.cockpit_tools_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        val entries = buildList {
            add(ToolEntry("live_stack", stringResource(R.string.stack_live), null, Icons.Default.Layers, onOpenLiveStacking))
            add(ToolEntry("post_stack", stringResource(R.string.stack_offline_title), null, Icons.Default.Layers, onOpenStacking))
            add(
                ToolEntry(
                    key = "solar_scan",
                    title = stringResource(R.string.solar_scan),
                    detail = stringResource(R.string.solar_scan_hint),
                    icon = Icons.Default.WbSunny,
                    onClick = onOpenSolarScan
                )
            )
            add(
                ToolEntry(
                    key = "plate_solve",
                    title = stringResource(R.string.plate_solve),
                    detail = plateSolveStatus ?: stringResource(R.string.tools_plate_database_hint),
                    icon = Icons.Default.Search,
                    onClick = onOpenPlateSolve
                )
            )
            add(
                ToolEntry(
                    key = "polar",
                    title = stringResource(R.string.polar_alignment),
                    detail = polarStatus,
                    icon = Icons.Default.Explore,
                    onClick = onOpenPolarAlignment
                )
            )
            add(
                ToolEntry(
                    key = "guide",
                    title = stringResource(R.string.guiding),
                    detail = guideStatus,
                    icon = Icons.Default.PlayCircle,
                    onClick = onOpenGuide
                )
            )
            add(
                ToolEntry(
                    key = "player",
                    title = stringResource(R.string.wf_media),
                    detail = playerStatus,
                    icon = Icons.Default.Movie,
                    onClick = onOpenPlayer
                )
            )
            if (showSequenceEntry) {
                add(
                    ToolEntry(
                        key = "sequence",
                        title = stringResource(R.string.tab_sequence),
                        detail = sequenceStatus,
                        icon = Icons.Default.Description,
                        onClick = onOpenSequence
                    )
                )
            }
        }

        if (wideLayout) {
            entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { entry ->
                        ToolRow(entry = entry, modifier = Modifier.weight(1f))
                    }
                    if (row.size == 1) {
                        Box(Modifier.weight(1f))
                    }
                }
            }
        } else {
            entries.forEach { entry -> ToolRow(entry = entry) }
        }
    }
}

private data class ToolEntry(
    val key: String,
    val title: String,
    val detail: String?,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun ToolRow(
    entry: ToolEntry,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .testTag("tool-${entry.key}").clickable(onClick = entry.onClick)
                .semantics { contentDescription = entry.title }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(entry.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                entry.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Divider(Modifier.padding(start = 54.dp))
    }
}
