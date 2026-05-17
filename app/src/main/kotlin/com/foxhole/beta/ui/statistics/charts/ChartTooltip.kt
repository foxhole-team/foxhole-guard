package com.foxhole.beta.ui.statistics.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxhole.beta.core.statistics.ChartTooltipModel

@Composable
fun ChartTooltip(
    model: ChartTooltipModel,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = model.accessibilitySummary()
        },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = model.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            model.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            model.rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.colorToken?.let { token ->
                        val color = chartColor(token)
                        Canvas(modifier = Modifier.size(8.dp).clearAndSetSemantics {}) {
                            drawCircle(color)
                        }
                    }
                    Text(
                        text = "${row.label}: ${row.value}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
