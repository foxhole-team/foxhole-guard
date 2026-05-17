package com.foxhole.beta.ui.statistics.charts

import com.foxhole.beta.core.statistics.ChartLegendModel
import com.foxhole.beta.core.statistics.ChartModel
import com.foxhole.beta.core.statistics.ChartTooltipModel

internal fun DonutChartModel.accessibilitySummary(): String =
    buildString {
        append(title)
        val total = segments.sumOf(DonutSegment::value)
        if (total > 0.0) {
            append(", ")
            append(total.toLong())
            append(". ")
            append(
                segments
                    .filter { segment -> segment.value > 0.0 }
                    .joinToString { segment -> "${segment.label} ${segment.value.toLong()}" },
            )
        }
    }

internal fun ChartModel.accessibilitySummary(): String =
    buildString {
        append(title)
        subtitle?.takeIf(String::isNotBlank)?.let { value ->
            append(", ")
            append(value)
        }
        val totals =
            series
                .filter { item -> item.points.isNotEmpty() }
                .joinToString { item -> "${item.label} ${item.points.sumOf { point -> point.y }.toLong()}" }
        if (totals.isNotBlank()) {
            append(". ")
            append(totals)
        }
    }

internal fun ChartLegendModel.accessibilitySummary(): String =
    items
        .joinToString { item ->
            buildString {
                append(item.label)
                item.value?.let { value -> append(" ").append(value) }
                item.description?.let { description -> append(" ").append(description) }
            }
        }

internal fun ChartTooltipModel.accessibilitySummary(): String =
    buildString {
        append(title)
        subtitle?.takeIf(String::isNotBlank)?.let { value ->
            append(", ")
            append(value)
        }
        val rowSummary = rows.joinToString { row -> "${row.label} ${row.value}" }
        if (rowSummary.isNotBlank()) {
            append(". ")
            append(rowSummary)
        }
    }
