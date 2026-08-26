package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticsRetention(
    val retentionHours: Int,
    val maxEntries: Int,
) {
    HOURS_6(retentionHours = 6, maxEntries = 1_500),
    HOURS_24(retentionHours = 24, maxEntries = 5_000),
    DAYS_2(retentionHours = 48, maxEntries = 7_000),
    DAYS_3(retentionHours = 72, maxEntries = 9_000),
    DAYS_7(retentionHours = 168, maxEntries = 15_000),
    DAYS_14(retentionHours = 336, maxEntries = 20_000),
    DAYS_30(retentionHours = 720, maxEntries = 30_000),
}

@Serializable
enum class StatisticsRetention {
    WEEK,
    MONTH,
    MONTHS_3,
    FOREVER,
}

@Serializable
enum class RetentionPreset {
    DAY,
    WEEK,
    MONTH,
    FOREVER,
    CUSTOM,
}

@Serializable
@Immutable
data class RetentionPolicy(
    val preset: RetentionPreset = RetentionPreset.DAY,
    val customDays: Int = 30,
) {
    fun normalizedCustomDays(): Int = customDays.coerceIn(1, MAX_CUSTOM_DAYS)

    fun retentionMillisOrNull(): Long? =
        when (preset) {
            RetentionPreset.DAY -> DAY_MILLIS
            RetentionPreset.WEEK -> 7L * DAY_MILLIS
            RetentionPreset.MONTH -> 31L * DAY_MILLIS
            RetentionPreset.FOREVER -> null
            RetentionPreset.CUSTOM -> normalizedCustomDays() * DAY_MILLIS
        }

    fun cutoffOrNull(nowMs: Long): Long? = retentionMillisOrNull()?.let { millis -> nowMs - millis }

    val maxEntries: Int
        get() {
            val millis = retentionMillisOrNull() ?: return MAX_ENTRIES_LARGE
            return when {
                millis <= DAY_MILLIS -> MAX_ENTRIES_SMALL
                millis <= 7L * DAY_MILLIS -> MAX_ENTRIES_MEDIUM
                else -> MAX_ENTRIES_LARGE
            }
        }

    companion object {
        const val MAX_CUSTOM_DAYS = 365
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
        private const val MAX_ENTRIES_SMALL = 5_000
        private const val MAX_ENTRIES_MEDIUM = 15_000
        private const val MAX_ENTRIES_LARGE = 30_000
    }
}

fun DiagnosticsRetention.toRetentionPolicy(): RetentionPolicy =
    when (this) {
        DiagnosticsRetention.HOURS_6,
        DiagnosticsRetention.HOURS_24,
        -> RetentionPolicy(RetentionPreset.DAY)
        DiagnosticsRetention.DAYS_2,
        DiagnosticsRetention.DAYS_3,
        DiagnosticsRetention.DAYS_7,
        -> RetentionPolicy(RetentionPreset.WEEK)
        DiagnosticsRetention.DAYS_14,
        DiagnosticsRetention.DAYS_30,
        -> RetentionPolicy(RetentionPreset.MONTH)
    }

fun StatisticsRetention.toRetentionPolicy(): RetentionPolicy =
    when (this) {
        StatisticsRetention.WEEK -> RetentionPolicy(RetentionPreset.WEEK)
        StatisticsRetention.MONTH,
        StatisticsRetention.MONTHS_3,
        -> RetentionPolicy(RetentionPreset.MONTH)
        StatisticsRetention.FOREVER -> RetentionPolicy(RetentionPreset.FOREVER)
    }

fun ExpertSettings.effectiveDiagnosticsRetention(): RetentionPolicy =
    diagnosticsRetentionPolicy ?: diagnosticsRetention.toRetentionPolicy()

fun StatisticsSettings.effectiveRetention(): RetentionPolicy =
    retentionPolicy ?: retention.toRetentionPolicy()

fun RetentionPolicy.toStatisticsDisplayRetention(): StatisticsRetention =
    when (preset) {
        RetentionPreset.DAY,
        RetentionPreset.WEEK,
        -> StatisticsRetention.WEEK
        RetentionPreset.MONTH -> StatisticsRetention.MONTH
        RetentionPreset.FOREVER -> StatisticsRetention.FOREVER
        RetentionPreset.CUSTOM -> {
            val days = normalizedCustomDays()
            when {
                days <= 7 -> StatisticsRetention.WEEK
                days <= 31 -> StatisticsRetention.MONTH
                days <= 93 -> StatisticsRetention.MONTHS_3
                else -> StatisticsRetention.FOREVER
            }
        }
    }

@Serializable
enum class StatisticsRefreshInterval(val seconds: Int) {
    SECONDS_1(1),
    SECONDS_3(3),
    SECONDS_5(5),
    SECONDS_10(10),
}

enum class StatisticsMetric {
    PROFILE_TRAFFIC,
    APP_TRAFFIC,
    DNS_FILTERING,
    COUNTRY_TRAFFIC,
    ANOMALIES,
    APP_CHANGES,
}
