package com.foxhole.guard.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.TraceSectionMetric

@OptIn(ExperimentalMetricApi::class)
internal fun frameMetricsWithTrace(vararg traceSections: String) =
    listOf(FrameTimingMetric()) + traceMetrics(*traceSections)

@OptIn(ExperimentalMetricApi::class)
internal fun memoryFrameMetricsWithTrace(vararg traceSections: String) =
    listOf(
        FrameTimingMetric(),
        MemoryUsageMetric(MemoryUsageMetric.Mode.Last),
        MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
    ) + traceMetrics(*traceSections)

@OptIn(ExperimentalMetricApi::class)
internal fun traceMetrics(vararg traceSections: String) =
    traceSections.map { section ->
        TraceSectionMetric(
            sectionName = section,
            mode = TraceSectionMetric.Mode.Sum,
            label = section.traceMetricLabel(),
        )
    }

private fun String.traceMetricLabel(): String =
    replace("/", " ")
        .split(" ")
        .filter(String::isNotBlank)
        .joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }
