package com.foxhole.guard.core.settings

import com.foxhole.core.model.TRAFFIC_CHART_RANGE_MAX_MINUTES
import com.foxhole.core.model.TRAFFIC_CHART_RANGE_MIN_MINUTES
import com.foxhole.core.model.TrafficCardView
import com.foxhole.core.model.TrafficChartPage

// The traffic-widget chart settings (view, split pager, live-window range). Chart monochrome
// rides the app-wide monochrome toggle (updateMonochromeTorTheme) since the appearance merge.

suspend fun SettingsRepository.updateTrafficCardView(value: TrafficCardView) =
    update { it.copy(ui = it.ui.copy(trafficCardView = value)) }

suspend fun SettingsRepository.updateTrafficChartCombined(value: Boolean) =
    update { it.copy(ui = it.ui.copy(trafficChartCombined = value)) }

suspend fun SettingsRepository.updateTrafficChartPage(value: TrafficChartPage) =
    update { it.copy(ui = it.ui.copy(trafficChartPage = value)) }

suspend fun SettingsRepository.updateTrafficChartRangeMinutes(value: Int) =
    update {
        it.copy(
            ui = it.ui.copy(
                trafficChartRangeMinutes =
                value.coerceIn(TRAFFIC_CHART_RANGE_MIN_MINUTES, TRAFFIC_CHART_RANGE_MAX_MINUTES),
            ),
        )
    }
