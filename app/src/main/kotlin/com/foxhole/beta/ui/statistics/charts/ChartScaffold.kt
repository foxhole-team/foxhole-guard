package com.foxhole.beta.ui.statistics.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.statistics.ChartDataQuality
import com.foxhole.beta.core.statistics.ChartModel

class ChartScope internal constructor(
    val model: ChartModel,
)

@Composable
fun ChartScaffold(
    model: ChartModel,
    modifier: Modifier = Modifier,
    header: @Composable (() -> Unit)? = null,
    content: @Composable ChartScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val subtitle = model.subtitle.orEmpty()
                if (subtitle.isNotBlank() || model.quality != ChartDataQuality.REAL) {
                    Text(
                        text = chartSubtitle(model),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            header?.invoke()
        }
        if (model.series.all { series -> series.points.isEmpty() }) {
            ChartEmptyState(model)
        } else {
            ChartScope(model).content()
            ChartAxisLabels(model)
            ChartLegend(legend = model.legend)
        }
    }
}

private fun chartSubtitle(model: ChartModel): String =
    listOfNotNull(
        model.subtitle,
        model.quality
            .takeUnless { quality -> quality == ChartDataQuality.REAL }
            ?.name
            ?.lowercase(),
    ).joinToString(" • ")

@Composable
private fun ChartEmptyState(model: ChartModel) {
    Text(
        text = model.emptyState.message ?: model.emptyState.title,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
