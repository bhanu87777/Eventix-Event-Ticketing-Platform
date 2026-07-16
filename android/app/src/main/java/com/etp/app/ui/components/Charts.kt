package com.etp.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.etp.app.data.SalesPoint

/**
 * Hand-drawn bar chart for sales-per-day — one small dataset, no zoom or
 * tooltips, so a chart library would be pure weight.
 */
@Composable
fun SalesBarChart(points: List<SalesPoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) {
        Text(
            "No sales yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 24.dp),
        )
        return
    }
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline
    val max = points.maxOf { it.tickets }.coerceAtLeast(1)

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            // Recessive baseline + midline.
            drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), 2f)
            drawLine(gridColor.copy(alpha = 0.4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)

            val slot = size.width / points.size
            val barWidth = (slot * 0.6f).coerceAtMost(64f)
            points.forEachIndexed { i, p ->
                val h = (p.tickets.toFloat() / max) * (size.height - 8f)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(slot * i + (slot - barWidth) / 2, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(8f, 8f),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            // First / middle / last date labels — enough orientation for a sparkline-scale chart.
            val labels = when {
                points.size >= 3 -> listOf(points.first(), points[points.size / 2], points.last())
                else -> points
            }.map { it.date.takeLast(5) } // MM-DD
            labels.forEachIndexed { i, label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = when (i) {
                        0 -> androidx.compose.ui.text.style.TextAlign.Start
                        labels.lastIndex -> androidx.compose.ui.text.style.TextAlign.End
                        else -> androidx.compose.ui.text.style.TextAlign.Center
                    },
                )
            }
        }
    }
}
