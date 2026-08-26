package com.foxhole.guard.ui.cli.stats

import androidx.compose.runtime.Immutable
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.I2pTrafficBucket
import com.foxhole.core.model.OverallStatisticsUiItem
import com.foxhole.core.model.ProtocolMetricEvent
import com.foxhole.core.model.ProtocolMetricEventKind
import com.foxhole.core.model.ProtocolStatisticsUiItem
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsWindow
import com.foxhole.core.model.TrafficWindow
import com.foxhole.core.model.effectiveRetention
import java.time.ZoneOffset

internal enum class CliStatsOverviewSource {
    WINDOW,

    LIFETIME,

    NONE,
}

@Immutable
internal data class CliStatsTrafficBucket(
    val vpnBytes: Long,
    val torBytes: Long,
    val i2pBytes: Long = 0L,
    val firewallBytes: Long = 0L,
    val observedBytes: Long = vpnBytes + torBytes + i2pBytes + firewallBytes,
    val sampled: Boolean = true,
) {
    val totalBytes: Long get() = observedBytes
}

@Immutable
internal data class CliStatsProtocolErrors(
    val protocol: String,
    val failures: Int,
    val attempts: Int,
) {
    val errorRate: Float get() = if (attempts <= 0) 0f else failures.toFloat() / attempts.toFloat()
}

@Immutable
internal data class CliStatsOverview(
    val window: StatisticsWindow = StatisticsWindow.DAY,
    val vpnBytes: Long = 0L,
    val torBytes: Long = 0L,
    val i2pBytes: Long = 0L,
    val avgLatencyMs: Long? = null,
    val latencySource: CliStatsOverviewSource = CliStatsOverviewSource.NONE,
    val vpnFailures: Int = 0,
    val vpnAttempts: Int = 0,
    val errorSource: CliStatsOverviewSource = CliStatsOverviewSource.NONE,
    val worstProtocol: CliStatsProtocolErrors? = null,
    val bestProtocol: CliStatsProtocolErrors? = null,
    val buckets: List<CliStatsTrafficBucket> = emptyList(),
    val axis: CliStatsChartAxis = cliStatsChartAxis(StatisticsWindow.DAY, 0L, ZoneOffset.UTC),
    val peakRateBytesPerSec: Long? = null,
    val appsWithTraffic: Int = 0,
    val windowExceedsRetention: Boolean = false,
) {
    val vpnErrorRate: Float
        get() = if (vpnAttempts <= 0) 0f else vpnFailures.toFloat() / vpnAttempts.toFloat()
}

internal fun cliStatsOverview(
    window: StatisticsWindow,
    appTrafficWindows: List<AppTrafficWindow>,
    metricEvents: List<ProtocolMetricEvent>,
    settings: Settings,
    lifetimeTotal: OverallStatisticsUiItem,
    lifetimeProtocols: List<ProtocolStatisticsUiItem>,
    nowMs: Long,
    deviceWindows: List<TrafficWindow> = emptyList(),
    i2pBuckets: List<I2pTrafficBucket> = emptyList(),
): CliStatsOverview {
    val cutoff = nowMs - window.durationMs
    val samples = appTrafficWindows.filter { sample -> sample.startedAtMs >= cutoff }
    val events = metricEvents.filter { event -> event.timestampMs >= cutoff }
    val errors = errorSlice(events, lifetimeTotal, lifetimeProtocols)
    val windowLatencyMs = averageLatencyMs(events)
    val lifetimeLatencyMs = lifetimeTotal.avgLatencyMs?.takeIf { latency -> latency > 0L }
    val axis = cliStatsChartAxis(window = window, nowMs = nowMs)
    val bucketCount = axis.bucketCount
    val buckets = cliStatsTrafficBuckets(
        samples = samples,
        windowMs = axis.windowMs,
        nowMs = axis.endMs,
        bucketCount = bucketCount,
        deviceWindows = deviceWindows,
        i2pBuckets = i2pBuckets,
    )
    val bucketSeconds = (axis.bucketMs / 1_000L).coerceAtLeast(1L)
    val retentionMs = settings.statistics.effectiveRetention().retentionMillisOrNull()
    return CliStatsOverview(
        window = window,
        axis = axis,
        vpnBytes = buckets.sumOf { bucket -> bucket.vpnBytes },
        torBytes = buckets.sumOf { bucket -> bucket.torBytes },
        i2pBytes = buckets.sumOf { bucket -> bucket.i2pBytes },
        avgLatencyMs = windowLatencyMs ?: lifetimeLatencyMs,
        latencySource =
        when {
            windowLatencyMs != null -> CliStatsOverviewSource.WINDOW
            lifetimeLatencyMs != null -> CliStatsOverviewSource.LIFETIME
            else -> CliStatsOverviewSource.NONE
        },
        vpnFailures = errors.failures,
        vpnAttempts = errors.attempts,
        errorSource = errors.source,
        worstProtocol =
        errors.protocols.minWithOrNull(
            compareByDescending<CliStatsProtocolErrors> { entry -> entry.errorRate }
                .thenBy { entry -> entry.protocol },
        ),
        bestProtocol =
        errors.protocols.minWithOrNull(
            compareBy<CliStatsProtocolErrors> { entry -> entry.errorRate }
                .thenBy { entry -> entry.protocol },
        ),
        buckets = buckets,
        peakRateBytesPerSec =
        buckets.maxOfOrNull { bucket -> bucket.totalBytes }
            ?.takeIf { bytes -> bytes > 0L }
            ?.let { bytes -> bytes / bucketSeconds },
        appsWithTraffic =
        samples
            .asSequence()
            .filter { sample -> sample.totalBytes > 0L }
            .map { sample -> sample.packageName }
            .distinct()
            .count(),
        windowExceedsRetention = retentionMs != null && retentionMs < window.durationMs,
    )
}

private data class CliStatsErrorSlice(
    val failures: Int,
    val attempts: Int,
    val protocols: List<CliStatsProtocolErrors>,
    val source: CliStatsOverviewSource,
)

private fun errorSlice(
    windowEvents: List<ProtocolMetricEvent>,
    lifetimeTotal: OverallStatisticsUiItem,
    lifetimeProtocols: List<ProtocolStatisticsUiItem>,
): CliStatsErrorSlice {
    val windowAttempts = windowEvents.filter { event -> event.kind != ProtocolMetricEventKind.SERVER_PING }
    if (windowAttempts.isNotEmpty()) {
        return CliStatsErrorSlice(
            failures = windowAttempts.count { event -> event.kind == ProtocolMetricEventKind.PROBE_FAILURE },
            attempts = windowAttempts.size,
            protocols = windowProtocolErrors(windowAttempts),
            source = CliStatsOverviewSource.WINDOW,
        )
    }
    val protocols = lifetimeProtocolErrors(lifetimeProtocols)
    val attempts = (lifetimeTotal.successCount + lifetimeTotal.failureCount).coerceAtLeast(0)
    return CliStatsErrorSlice(
        failures = lifetimeTotal.failureCount.coerceAtLeast(0),
        attempts = attempts,
        protocols = protocols,
        source =
        if (attempts > 0 || protocols.isNotEmpty()) {
            CliStatsOverviewSource.LIFETIME
        } else {
            CliStatsOverviewSource.NONE
        },
    )
}

private fun averageLatencyMs(events: List<ProtocolMetricEvent>): Long? {
    val latencies = events.mapNotNull { event -> event.latencyMs?.takeIf { latency -> latency > 0L } }
    return if (latencies.isEmpty()) null else latencies.sum() / latencies.size
}

private fun windowProtocolErrors(attempts: List<ProtocolMetricEvent>): List<CliStatsProtocolErrors> =
    attempts
        .groupBy { event -> event.protocol.trim().lowercase() }
        .filterKeys(String::isNotEmpty)
        .map { (protocol, protocolEvents) ->
            CliStatsProtocolErrors(
                protocol = protocol,
                failures = protocolEvents.count { event -> event.kind == ProtocolMetricEventKind.PROBE_FAILURE },
                attempts = protocolEvents.size,
            )
        }

private fun lifetimeProtocolErrors(items: List<ProtocolStatisticsUiItem>): List<CliStatsProtocolErrors> =
    items
        .filter(ProtocolStatisticsUiItem::hasMeasuredAttempts)
        .map { item ->
            CliStatsProtocolErrors(
                protocol = item.protocol.name.lowercase(),
                failures = item.failureCount,
                attempts = item.totalAttempts,
            )
        }
