package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.R

@Composable
fun FilterWheelPanel(
    isConnected: Boolean,
    currentPosition: Int,
    isMoving: Boolean,
    slotNames: List<String>,
    slotCount: Int,
    onSetPosition: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isConnected) return

    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .width(176.dp)
            .clip(shape)
            .background(Color(0xCC000000))
            .border(1.dp, Color(0xAA666666), shape)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.filter_wheel_title),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            if (isMoving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = Color(0xFF44AAFF)
                )
            } else {
                IconButton(
                    onClick = onReset,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.reset),
                        tint = Color(0xAAFFFFFF),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // Slot grid
        val cols = if (slotCount <= 5) slotCount else (slotCount + 1) / 2
        val rows = if (slotCount <= 5) 1 else 2

        for (row in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (col in 0 until cols) {
                    val idx = row * cols + col
                    if (idx < slotCount) {
                        val isSelected = idx == currentPosition
                        val name = slotNames.getOrElse(idx) { "${idx + 1}" }
                        FilterSlotButton(
                            label = name,
                            isSelected = isSelected,
                            isMoving = isMoving,
                            onClick = { onSetPosition(idx) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSlotButton(
    label: String,
    isSelected: Boolean,
    isMoving: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        isSelected -> Color(0xFF1565C0)
        else -> Color(0xFF333333)
    }
    val borderColor = when {
        isSelected -> Color(0xFF42A5F5)
        else -> Color(0xFF666666)
    }

    Box(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
            .clickable(enabled = !isMoving) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else Color(0xCCFFFFFF),
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
