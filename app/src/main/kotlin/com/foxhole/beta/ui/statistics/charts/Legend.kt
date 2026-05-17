package com.foxhole.beta.ui.statistics.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.statistics.ChartLegendModel

@Composable
fun ChartLegend(
    legend: ChartLegendModel,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = legend.accessibilitySummary()
        },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        legend.items.forEach { item ->
            val label =
                buildString {
                    append(item.label)
                    item.value?.let { value -> append(": ").append(value) }
                    item.description?.let { description -> append(" ").append(description) }
                }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = chartColor(item.colorToken)
                Canvas(modifier = Modifier.size(8.dp).clearAndSetSemantics {}) {
                    drawCircle(color)
                }
                Text(
                    text = label,
                    modifier = Modifier.padding(end = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
