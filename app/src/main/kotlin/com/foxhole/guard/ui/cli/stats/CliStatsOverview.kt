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

/**
 * One time slice of the overview sparkline, split by network type: the same Tor-lane rule as the
 * rows above, I2P out of its own hourly store, and the firewall's untunnelled share of what is
 * left. Every lane is a measurement — a lane whose source recorded nothing for this slice is a
 * hard zero and draws no segment.
 */
@Immutable
internal data class CliStatsTrafficBucket(
    val vpnBytes: Long,
    val torBytes: Long,
    val i2pBytes: Long = 0L,
    val firewallBytes: Long = 0L,
    /** Physical device bytes; hop lanes may overlap (VPN+Tor), so their sum is not this total. */
    val observedBytes: Long = vpnBytes + torBytes + i2pBytes + firewallBytes,
) {
    val totalBytes: Long get() = observedBytes
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
 * The stats screen's summary group: VPN/TOR traffic, mean latency, VPN errors and the best/worst
 * protocols by error rate, all over [window].
 */
@Immutable
internal data class CliStatsOverview(
    val window: StatisticsWindow = StatisticsWindow.DAY,
    val vpnBytes: Long = 0L,
    val torBytes: Long = 0L,
    // I2P is summed from its own hourly store, which the same window cuts, and is carved out of the
    // aggregate app/device volume before the other route lanes are calculated. Zero means "the
    // store recorded nothing here", never "unknown".
    val i2pBytes: Long = 0L,
    val avgLatencyMs: Long? = null,
    val latencySource: CliStatsOverviewSource = CliStatsOverviewSource.NONE,
    val vpnFailures: Int = 0,
    val vpnAttempts: Int = 0,
    // Shared flag for VPN errors and both protocol entries: one source feeds all three.
    val errorSource: CliStatsOverviewSource = CliStatsOverviewSource.NONE,
    val worstProtocol: CliStatsProtocolErrors? = null,
    val bestProtocol: CliStatsProtocolErrors? = null,
    // Sparkline slices of the same window; empty when the window holds no samples at all.
    val buckets: List<CliStatsTrafficBucket> = emptyList(),
    // Busiest bucket's bytes over the bucket's seconds; null while the window carried nothing.
    val peakRateBytesPerSec: Long? = null,
    val appsWithTraffic: Int = 0,
    // The retention purge runs inside the window: totals cover less time than the dropdown claims.
    val windowExceedsRetention: Boolean = false,
) {
    val vpnErrorRate: Float
        get() = if (vpnAttempts <= 0) 0f else vpnFailures.toFloat() / vpnAttempts.toFloat()
}

/**
 * Pure summary calculator.
 *
 * VPN/TOR traffic uses the same recorded-route buckets as [cliStatsHero] but over an arbitrary
 * window. Current settings never rewrite traffic that was already measured.
 * Latency and errors come from [metricEvents] — the only source that can be sliced by time
 * (TrafficWindow.latencyMs is always null and the lifetime maps are cumulative). Lifetime
 * values are a fallback for an empty window and are tagged [CliStatsOverviewSource.LIFETIME].
 *
 * Events with an empty `protocol` (some smart-profile probes write it blank) count towards mean
 * latency and total errors but cannot win best/worst — they have no protocol identity.
 * SERVER_PING is not a connection attempt, yet its latency is a real measurement and counts.
 *
 * [deviceWindows] and [i2pBuckets] carry the facts per-app rows cannot express on their own: which
 * connection was up when, and how much of the aggregate belonged to I2P. Both are optional — the
 * runtime records them behind their own switches, and an absent source draws nothing instead of
 * guessing.
 */
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
    val bucketCount = cliStatsSparklineBucketCount(window)
    val buckets = cliStatsTrafficBuckets(
        samples = samples,
        windowMs = window.durationMs,
        nowMs = nowMs,
        bucketCount = bucketCount,
        deviceWindows = deviceWindows,
        i2pBuckets = i2pBuckets,
    )
    val bucketSeconds = (window.durationMs / bucketCount / 1_000L).coerceAtLeast(1L)
    val retentionMs = settings.statistics.effectiveRetention().retentionMillisOrNull()
    return CliStatsOverview(
        window = window,
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
