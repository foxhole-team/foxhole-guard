package com.foxhole.beta.ui.statistics.charts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.foxhole.beta.core.statistics.ChartModel

@Composable
fun StackedBarChart(
    model: ChartModel,
    modifier: Modifier = Modifier,
) {
    TimelineChart(model = model, modifier = modifier)
}
