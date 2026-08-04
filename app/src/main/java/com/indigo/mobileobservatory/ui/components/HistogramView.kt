package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.camera.HistogramData
import kotlin.math.ln
import kotlin.math.max

@Composable
fun HistogramView(
    histogramData: HistogramData?,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
    ) {
        drawRect(Color(0xFF0A0A0E), size = size)

        val data = histogramData ?: return@Canvas
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
        drawPath(path, Color(0xFF5070C0), style = Fill)

        val bpX = data.blackPoint.toFloat() / numBins * size.width
        drawLine(Color(0xFFFF6666), Offset(bpX, 0f), Offset(bpX, size.height), strokeWidth = 1.5f)

        val wpX = data.whitePoint.toFloat() / numBins * size.width
        drawLine(Color(0xFF66FF66), Offset(wpX, 0f), Offset(wpX, size.height), strokeWidth = 1.5f)
    }
}
