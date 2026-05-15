package com.foxhole.beta.ui.statistics.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.foxhole.beta.core.statistics.ChartModel

@Composable
fun ChartAxisLabels(model: ChartModel) {
    val ticks = model.xAxis.ticks.filter { tick -> tick.label.isNotBlank() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (ticks.isEmpty()) {
            Text(
                text = model.xAxis.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ticks.take(6).forEach { tick ->
                Text(
                    text = tick.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
