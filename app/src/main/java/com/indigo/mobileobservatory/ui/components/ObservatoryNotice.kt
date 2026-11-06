@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class NoticeTone { INFO, WARNING, ERROR }

@Composable
fun ObservatoryNotice(
    title: String,
    body: String,
    tone: NoticeTone,
    modifier: Modifier = Modifier,
    primaryAction: (@Composable () -> Unit)? = null,
    secondaryAction: (@Composable () -> Unit)? = null
) {
    val container = when (tone) {
        NoticeTone.INFO -> MaterialTheme.colorScheme.surfaceVariant
        NoticeTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        NoticeTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (tone) {
        NoticeTone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        NoticeTone.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        NoticeTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    val icon = when (tone) {
        NoticeTone.INFO -> Icons.Default.Info
        NoticeTone.WARNING -> Icons.Default.Warning
        NoticeTone.ERROR -> Icons.Default.Error
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(icon, null, Modifier.size(20.dp))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(body, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (primaryAction != null || secondaryAction != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    primaryAction?.invoke()
                    secondaryAction?.invoke()
                }
            }
        }
    }
}
