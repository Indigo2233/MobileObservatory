package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.camera.HistogramData
import com.indigo.mobileobservatory.camera.Roi
import kotlin.math.ln
import kotlin.math.max
import androidx.compose.ui.res.stringResource
import com.indigo.mobileobservatory.R

@Composable
fun OverlayPanel(
    histogram: HistogramData?,
    roi: Roi,
    sensorWidth: Int,
    sensorHeight: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.width(120.dp),
        color = Color(0x80000000),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(stringResource(R.string.histogram), color = Color(0x99FFFFFF), fontSize = 8.sp)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                val data = histogram ?: return@Canvas
                val bins = data.bins
                val numBins = bins.size
                val barWidth = size.width / numBins
                val maxLog = ln(max(data.maxCount, 1).toFloat() + 1f)

                val path = Path().apply {
                    moveTo(0f, size.height)
                    for (i in bins.indices) {
                        val logVal = ln(bins[i].toFloat() + 1f) / maxLog
                        val barH = logVal * size.height
                        lineTo(i * barWidth, size.height - barH)
                    }
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(path, Color(0xAA5070C0), style = Fill)

                val bpX = data.blackPoint.toFloat() / numBins * size.width
                drawLine(Color(0xCCFF6666), Offset(bpX, 0f), Offset(bpX, size.height), strokeWidth = 1f)

                val wpX = data.whitePoint.toFloat() / numBins * size.width
                drawLine(Color(0xCC66FF66), Offset(wpX, 0f), Offset(wpX, size.height), strokeWidth = 1f)
            }

            Text(stringResource(R.string.section_roi), color = Color(0x99FFFFFF), fontSize = 8.sp)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(35.dp)
            ) {
                if (sensorWidth <= 0 || sensorHeight <= 0) return@Canvas

                val aspect = sensorWidth.toFloat() / sensorHeight
                val mapW: Float
                val mapH: Float
                if (size.width / size.height > aspect) {
                    mapH = size.height
                    mapW = mapH * aspect
                } else {
                    mapW = size.width
                    mapH = mapW / aspect
                }
                val ox = (size.width - mapW) / 2f
                val oy = (size.height - mapH) / 2f

                drawRect(Color(0x44FFFFFF), Offset(ox, oy), Size(mapW, mapH), style = Stroke(1f))

                val rx = ox + roi.x.toFloat() / sensorWidth * mapW
                val ry = oy + roi.y.toFloat() / sensorHeight * mapH
                val rw = roi.width.toFloat() / sensorWidth * mapW
                val rh = roi.height.toFloat() / sensorHeight * mapH
                drawRect(Color(0x66FFD700), Offset(rx, ry), Size(rw, rh), style = Fill)
                drawRect(Color(0xCCFFD700), Offset(rx, ry), Size(rw, rh), style = Stroke(1f))
            }

            Text(
                "${roi.width}x${roi.height}@(${roi.x},${roi.y})",
                color = Color(0x80FFFFFF),
                fontSize = 8.sp
            )
        }
    }
}
