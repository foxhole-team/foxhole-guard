package com.foxhole.guard.statistics

import androidx.compose.runtime.Immutable

enum class StatsRange {
    LIVE_15M,
    HOUR_1,
    HOURS_24,
    DAYS_7,
    DAYS_31,
    MONTHS_3,
    ALL,
}

enum class TimeLabelMode {
    CLOCK,
    DAY_AND_TIME,
    DATE,
    MONTH,
}

@Immutable
data class ChartModel(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val range: StatsRange,
    val xAxis: ChartAxis,
    val yAxis: ChartAxis,
    val series: List<ChartSeries>,
    val legend: ChartLegendModel,
    val markers: List<ChartMarker> = emptyList(),
    val emptyState: ChartEmptyState,
    val updatedAtMs: Long,
    val quality: ChartDataQuality = ChartDataQuality.REAL,
)

@Immutable
data class ChartAxis(
    val label: String,
    val min: Double,
    val max: Double,
    val ticks: List<ChartTick>,
    val formatter: ChartValueFormatter,
)

@Immutable
data class ChartTick(
    val value: Double,
    val label: String,
    val major: Boolean = true,
)

@Immutable
data class ChartSeries(
    val id: String,
    val label: String,
    val kind: ChartSeriesKind,
    val colorToken: ChartColorToken,
    val points: List<ChartPoint>,
    val visibleByDefault: Boolean = true,
)

@Immutable
data class ChartPoint(
    val x: Long,
    val y: Double,
    val y2: Double? = null,
    val label: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Immutable
data class ChartMarker(
    val x: Long,
    val y: Double? = null,
    val label: String,
    val colorToken: ChartColorToken,
    val tooltip: ChartTooltipModel? = null,
)

@Immutable
data class ChartEmptyState(
    val title: String,
    val message: String? = null,
)

@Immutable
data class ChartLegendModel(
    val items: List<ChartLegendItem>,
    val placement: LegendPlacement = LegendPlacement.BOTTOM,
    val wrapping: Boolean = true,
)

@Immutable
data class ChartLegendItem(
    val seriesId: String,
    val label: String,
    val colorToken: ChartColorToken,
    val value: String? = null,
    val description: String? = null,
)

@Immutable
data class ChartTooltipModel(
    val title: String,
    val subtitle: String? = null,
    val rows: List<ChartTooltipRow>,
)

@Immutable
data class ChartTooltipRow(
    val label: String,
    val value: String,
    val colorToken: ChartColorToken? = null,
)

enum class ChartSeriesKind {
    LINE,
    AREA,
    BAR,
    STACKED_BAR,
    EVENT_DOT,
    RANGE_BAR,
}

enum class ChartValueFormatter {
    BYTES,
    BYTES_PER_SECOND,
    COUNT,
    PERCENT,
    SCORE,
    LATENCY_MS,
    TIME,
    TEXT,
}

enum class LegendPlacement {
    TOP,
    BOTTOM,
    INLINE,
}

enum class ChartDataQuality {
    REAL,
    ESTIMATED,
    SYNTHETIC,
    PARTIAL,
}

enum class ChartColorToken {
    TX,
    RX,
    TOTAL,
    SUCCESS,
    ERROR,
    WARNING,
    TOR,
    VPN,
    DIRECT,
    DNS_ALLOWED,
    DNS_BLOCKED,
    ADS,
    TRACKERS,
    TELEMETRY,
    MALICIOUS,
    WIFI,
    MOBILE,
    UNKNOWN,
    COUNTRY_1,
    COUNTRY_2,
    COUNTRY_3,
    COUNTRY_4,
    COUNTRY_5,
    OTHER,
}
