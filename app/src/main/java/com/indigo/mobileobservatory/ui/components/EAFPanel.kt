package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.indigo.mobileobservatory.camera.toupcam.EAFInfo

@Composable
fun EAFPanel(
    isConnected: Boolean,
    position: Int,
    isMoving: Boolean,
    temperature: Float?,
    eafInfo: EAFInfo?,
    onMoveTo: (Int) -> Unit,
    onMoveRelative: (Int) -> Unit,
    onHalt: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isConnected || eafInfo == null) return

    val shape = RoundedCornerShape(8.dp)
    var targetPosition by remember(position) { mutableIntStateOf(position) }

    Column(
        modifier = modifier
            .width(200.dp)
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
                stringResource(R.string.focuser_title),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (temperature != null) {
                    Text(
                        "%.1f°C".format(temperature),
                        color = Color(0xAAFFFFFF),
                        fontSize = 9.sp
                    )
                }
                if (isMoving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = Color(0xFF44AAFF)
                    )
                }
            }
        }

        Text(
            stringResource(R.string.pos_format, position, eafInfo.maxPosition),
            color = Color(0xCCFFFFFF),
            fontSize = 10.sp
        )

        Slider(
            value = targetPosition.toFloat(),
            onValueChange = { targetPosition = it.toInt() },
            onValueChangeFinished = { onMoveTo(targetPosition) },
            valueRange = eafInfo.minPosition.toFloat()..eafInfo.maxPosition.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF42A5F5),
                activeTrackColor = Color(0xFF1565C0),
                inactiveTrackColor = Color(0xFF444444)
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StepButton("<<", Color(0xFF333333)) { onMoveRelative(-eafInfo.coarseStep) }
            StepButton("<", Color(0xFF333333)) { onMoveRelative(-eafInfo.fineStep) }
            StepButton("■", Color(0xFFCC3333), onHalt)
            StepButton(">", Color(0xFF333333)) { onMoveRelative(eafInfo.fineStep) }
            StepButton(">>", Color(0xFF333333)) { onMoveRelative(eafInfo.coarseStep) }
        }
    }
}

@Composable
private fun StepButton(
    label: String,
    bgColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(1.dp, Color(0xFF666666), RoundedCornerShape(4.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color(0xCCFFFFFF),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}
