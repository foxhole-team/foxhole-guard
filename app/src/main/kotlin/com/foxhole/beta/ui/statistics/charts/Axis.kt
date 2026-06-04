package com.foxhole.beta.ui.statistics.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.statistics.ChartModel

@Composable
fun ChartAxisLabels(model: ChartModel) {
    val ticks = model.xAxis.ticks.filter { tick -> tick.label.isNotBlank() }
    val tokens = chartVisualTokens()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (ticks.isEmpty()) {
            Text(
                text = model.xAxis.label,
                style = MaterialTheme.typography.labelSmall,
                color = tokens.axisColor,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        } else {
            val visibleTicks = ticks.take(6)
            visibleTicks.forEachIndexed { index, tick ->
                Text(
                    text = tick.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.axisColor,
                    textAlign =
                    when (index) {
                        0 -> TextAlign.Start
                        visibleTicks.lastIndex -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}
