package com.foxhole.guard.ui.cli.stats

import androidx.compose.runtime.Immutable
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.I2pTrafficSnapshot
import com.foxhole.core.model.OverallStatisticsUiItem
import com.foxhole.core.model.ProtocolMetricEvent
import com.foxhole.core.model.ProtocolMetricEventKind
import com.foxhole.core.model.ProtocolStatisticsUiItem
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsWindow

/**
 * Where a summary number came from. The UI must label this, or the lifetime fallback reads as
 * a broken dropdown — the figure does not move when the window changes.
 */
internal enum class CliStatsOverviewSource {
    /** Counted from events inside the selected window. */
    WINDOW,

    /** The window held no events: the value is lifetime and the window does not clip it. */
    LIFETIME,

    /** No data either way — render an empty state, not a zero. */
    NONE,
}

/** One protocol's errors over the window (or lifetime — see [CliStatsOverview.errorSource]). */
@Immutable
internal data class CliStatsProtocolErrors(
    // Already lower-case, as the runtime writes it — ready to print.
    val protocol: String,
    val failures: Int,
    val attempts: Int,
) {
    val errorRate: Float get() = if (attempts <= 0) 0f else failures.toFloat() / attempts.toFloat()
}

/**
 * The stats screen's summary group: per-lane traffic, mean latency, VPN errors and the
 * best/worst protocols by error rate, all over [window].
 */
@Immutable
internal data class CliStatsOverview(
    val window: StatisticsWindow = StatisticsWindow.DAY,
    val vpnBytes: Long = 0L,
    val torBytes: Long = 0L,
    // Session-scoped by construction: there is no persistent I2P accounting at all, so this
    // does not depend on [window] and the UI must label it as current-session traffic —
    // otherwise it lies on a week-long window.
    val i2pSessionBytes: Long = 0L,
    val avgLatencyMs: Long? = null,
    val latencySource: CliStatsOverviewSource = CliStatsOverviewSource.NONE,
    val vpnFailures: Int = 0,
    val vpnAttempts: Int = 0,
    // Shared flag for VPN errors and both protocol entries: one source feeds all three.
    val errorSource: CliStatsOverviewSource = CliStatsOverviewSource.NONE,
    val worstProtocol: CliStatsProtocolErrors? = null,
    val bestProtocol: CliStatsProtocolErrors? = null,
) {
    val vpnErrorRate: Float
        get() = if (vpnAttempts <= 0) 0f else vpnFailures.toFloat() / vpnAttempts.toFloat()
}

/**
 * Pure summary calculator.
 *
 * VPN/TOR traffic uses the same lane logic as [cliStatsHero] but over an arbitrary window.
 * Latency and errors come from [metricEvents] — the only source that can be sliced by time
 * (TrafficWindow.latencyMs is always null and the lifetime maps are cumulative). Lifetime
 * values are a fallback for an empty window and are tagged [CliStatsOverviewSource.LIFETIME].
 *
 * Events with an empty `protocol` (some smart-profile probes write it blank) count towards mean
 * latency and total errors but cannot win best/worst — they have no protocol identity.
 * SERVER_PING is not a connection attempt, yet its latency is a real measurement and counts.
 */
internal fun cliStatsOverview(
    window: StatisticsWindow,
    appTrafficWindows: List<AppTrafficWindow>,
    metricEvents: List<ProtocolMetricEvent>,
    i2pTraffic: I2pTrafficSnapshot,
    settings: Settings,
    lifetimeTotal: OverallStatisticsUiItem,
    lifetimeProtocols: List<ProtocolStatisticsUiItem>,
    nowMs: Long,
): CliStatsOverview {
    val cutoff = nowMs - window.durationMs
    val samples = appTrafficWindows.filter { sample -> sample.startedAtMs >= cutoff }
    val totalBytes = samples.sumOf { sample -> sample.totalBytes.coerceAtLeast(0L) }
    val torBytes = torRoutedBytes(samples, settings)
    val events = metricEvents.filter { event -> event.timestampMs >= cutoff }
    val errors = errorSlice(events, lifetimeTotal, lifetimeProtocols)
    val windowLatencyMs = averageLatencyMs(events)
    val lifetimeLatencyMs = lifetimeTotal.avgLatencyMs?.takeIf { latency -> latency > 0L }
    return CliStatsOverview(
        window = window,
        vpnBytes = (totalBytes - torBytes).coerceAtLeast(0L),
        torBytes = torBytes,
        i2pSessionBytes =
        i2pTraffic.rxTotalBytes.coerceAtLeast(0L) + i2pTraffic.txTotalBytes.coerceAtLeast(0L),
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
        // Ties break on protocol name so the order is reproducible. A lone protocol honestly
        // occupies both rows.
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
    )
}

// Connection attempts in the window, or the lifetime slice if the window held none.
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

/** Mean of the window's positive latencies; null when there was nothing to divide. */
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

// Empty-window fallback: lifetime per-protocol stats. Protocols without a single measured
// attempt are dropped, or "fewest errors" would go to a 0% that came from nowhere.
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
