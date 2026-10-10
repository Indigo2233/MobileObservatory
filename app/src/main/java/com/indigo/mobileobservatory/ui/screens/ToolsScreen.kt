package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.theme.ObservatoryTheme

/**
 * Tool bench: the low-frequency entries (plate solve, polar alignment, guiding,
 * playback, sequence) live here instead of competing with the four imaging
 * destinations for space in the primary navigation.
 */
@Composable
fun ToolsScreen(
    onOpenPlateSolve: () -> Unit,
    onOpenPolarAlignment: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenPlayer: () -> Unit,
    showSequenceEntry: Boolean,
    onOpenSequence: () -> Unit,
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.cockpit_tools_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        val entries = buildList {
            add(
                ToolEntry(
                    key = "plate_solve",
                    title = stringResource(R.string.plate_solve),
                    hint = stringResource(R.string.cockpit_tools_plate_solve_hint),
                    status = plateSolveStatus,
                    icon = Icons.Default.Search,
                    onClick = onOpenPlateSolve
                )
            )
            add(
                ToolEntry(
                    key = "polar",
                    title = stringResource(R.string.polar_alignment),
                    hint = stringResource(R.string.cockpit_tools_polar_hint),
                    status = polarStatus,
                    icon = Icons.Default.Explore,
                    onClick = onOpenPolarAlignment
                )
            )
            add(
                ToolEntry(
                    key = "guide",
                    title = stringResource(R.string.guiding),
                    hint = stringResource(R.string.cockpit_tools_guide_hint),
                    status = guideStatus,
                    icon = Icons.Default.PlayCircle,
                    onClick = onOpenGuide
                )
            )
            add(
                ToolEntry(
                    key = "player",
                    title = stringResource(R.string.video_library),
                    hint = stringResource(R.string.cockpit_tools_player_hint),
                    status = playerStatus,
                    icon = Icons.Default.Movie,
                    onClick = onOpenPlayer
                )
            )
            if (showSequenceEntry) {
                add(
                    ToolEntry(
                        key = "sequence",
                        title = stringResource(R.string.tab_sequence),
                        hint = stringResource(R.string.cockpit_tools_sequence_hint),
                        status = sequenceStatus,
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
                        ToolCard(entry = entry, modifier = Modifier.weight(1f))
                    }
                    if (row.size == 1) {
                        Box(Modifier.weight(1f))
                    }
                }
            }
        } else {
            entries.forEach { entry -> ToolCard(entry = entry) }
        }
    }
}

private data class ToolEntry(
    val key: String,
    val title: String,
    val hint: String,
    val status: String?,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun ToolCard(
    entry: ToolEntry,
    modifier: Modifier = Modifier,
) {
    val cardColor = ObservatoryTheme.colors.card
    Surface(
        color = cardColor,
        shape = RoundedCornerShape(ObservatoryTheme.dimens.radiusLarge),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 84.dp)
            .clickable { entry.onClick() }
            .semantics { contentDescription = entry.title }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(ObservatoryTheme.dimens.radiusMedium)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = entry.status ?: entry.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
