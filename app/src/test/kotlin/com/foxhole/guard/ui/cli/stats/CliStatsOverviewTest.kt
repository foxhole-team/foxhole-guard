package com.foxhole.guard.ui.cli.stats

import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.I2pTrafficSnapshot
import com.foxhole.core.model.NetworkType
import com.foxhole.core.model.OverallStatisticsUiItem
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.ProtocolMetricEvent
import com.foxhole.core.model.ProtocolMetricEventKind
import com.foxhole.core.model.ProtocolStatisticsUiItem
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CliStatsOverviewTest {
    @Test
    fun `day window cuts events the week window keeps`() {
        val events =
            listOf(
                metricEvent(NOW_MS - 2 * DAY_MS, protocol = "vless", kind = ProtocolMetricEventKind.PROBE_FAILURE),
                metricEvent(NOW_MS - HOUR_MS, protocol = "vless"),
            )
        val day = overview(window = StatisticsWindow.DAY, metricEvents = events)
        assertEquals(1, day.vpnAttempts)
        assertEquals(0, day.vpnFailures)
        val week = overview(window = StatisticsWindow.WEEK, metricEvents = events)
        assertEquals(2, week.vpnAttempts)
        assertEquals(1, week.vpnFailures)
    }

    @Test
    fun `traffic windows are cut by the same window`() {
        val windows =
            listOf(
                trafficWindow(NOW_MS - 2 * DAY_MS, rxBytes = 100L),
                trafficWindow(NOW_MS - HOUR_MS, rxBytes = 50L),
            )
        assertEquals(50L, overview(window = StatisticsWindow.DAY, appTrafficWindows = windows).vpnBytes)
        assertEquals(150L, overview(window = StatisticsWindow.WEEK, appTrafficWindows = windows).vpnBytes)
    }

    @Test
    fun `empty window with no lifetime data divides by nothing`() {
        val result = overview()
        assertEquals(0L, result.vpnBytes)
        assertEquals(0L, result.torBytes)
        assertEquals(0, result.vpnAttempts)
        assertEquals(0, result.vpnFailures)
        assertEquals(0f, result.vpnErrorRate, TOLERANCE)
        assertNull(result.avgLatencyMs)
        assertEquals(CliStatsOverviewSource.NONE, result.latencySource)
        assertEquals(CliStatsOverviewSource.NONE, result.errorSource)
        assertNull(result.worstProtocol)
        assertNull(result.bestProtocol)
        assertEquals(0f, CliStatsProtocolErrors(protocol = "vless", failures = 0, attempts = 0).errorRate, TOLERANCE)
    }

    @Test
    fun `worst and best protocols are picked by error rate`() {
        val result =
            overview(
                metricEvents =
                listOf(
                    metricEvent(NOW_MS - HOUR_MS, protocol = "naive", kind = ProtocolMetricEventKind.PROBE_FAILURE),
                    metricEvent(NOW_MS - HOUR_MS, protocol = "vless", kind = ProtocolMetricEventKind.PROBE_FAILURE),
                    metricEvent(NOW_MS - HOUR_MS, protocol = "vless"),
                    metricEvent(NOW_MS - HOUR_MS, protocol = "trojan"),
                    metricEvent(NOW_MS - HOUR_MS, protocol = "trojan"),
                ),
            )
        assertEquals(CliStatsOverviewSource.WINDOW, result.errorSource)
        assertEquals("naive", result.worstProtocol?.protocol)
        assertEquals(1f, result.worstProtocol?.errorRate ?: -1f, TOLERANCE)
        assertEquals("trojan", result.bestProtocol?.protocol)
        assertEquals(0f, result.bestProtocol?.errorRate ?: -1f, TOLERANCE)
    }

    @Test
    fun `a tie resolves by protocol name and does not depend on input order`() {
        val events =
            listOf(
                metricEvent(NOW_MS - HOUR_MS, protocol = "vless", kind = ProtocolMetricEventKind.PROBE_FAILURE),
                metricEvent(NOW_MS - HOUR_MS, protocol = "vless"),
                metricEvent(NOW_MS - HOUR_MS, protocol = "trojan", kind = ProtocolMetricEventKind.PROBE_FAILURE),
                metricEvent(NOW_MS - HOUR_MS, protocol = "trojan"),
            )
        val straight = overview(metricEvents = events)
        val reversed = overview(metricEvents = events.reversed())
        assertEquals("trojan", straight.worstProtocol?.protocol)
        assertEquals("trojan", straight.bestProtocol?.protocol)
        assertEquals(straight.worstProtocol, reversed.worstProtocol)
        assertEquals(straight.bestProtocol, reversed.bestProtocol)
    }

    @Test
    fun `a single protocol honestly occupies both rows`() {
        val result =
            overview(
                metricEvents =
                listOf(
                    metricEvent(NOW_MS - HOUR_MS, protocol = "vless", kind = ProtocolMetricEventKind.PROBE_FAILURE),
                    metricEvent(NOW_MS - HOUR_MS, protocol = "vless"),
                ),
            )
        assertEquals("vless", result.worstProtocol?.protocol)
        assertEquals("vless", result.bestProtocol?.protocol)
        assertEquals(result.worstProtocol, result.bestProtocol)
        assertEquals(0.5f, result.vpnErrorRate, TOLERANCE)
    }

    @Test
    fun `events without a protocol count as attempts but never win a protocol row`() {
        val result =
            overview(
                metricEvents =
                listOf(
                    metricEvent(NOW_MS - HOUR_MS, kind = ProtocolMetricEventKind.PROBE_FAILURE),
                    metricEvent(NOW_MS - HOUR_MS, protocol = "  "),
                    metricEvent(NOW_MS - HOUR_MS, protocol = "VLESS"),
                ),
            )
        assertEquals(3, result.vpnAttempts)
        assertEquals(1, result.vpnFailures)
        assertEquals("vless", result.worstProtocol?.protocol)
        assertEquals(1, result.worstProtocol?.attempts)
        assertEquals(0, result.worstProtocol?.failures)
    }

    @Test
    fun `server pings measure latency without counting as attempts`() {
        val result =
            overview(
                metricEvents =
                listOf(
                    metricEvent(NOW_MS - HOUR_MS, protocol = "vless", latencyMs = 200L),
                    metricEvent(
                        NOW_MS - HOUR_MS,
                        protocol = "vless",
                        kind = ProtocolMetricEventKind.SERVER_PING,
                        latencyMs = 100L,
                    ),
                ),
            )
        assertEquals(1, result.vpnAttempts)
        assertEquals(150L, result.avgLatencyMs)
        assertEquals(CliStatsOverviewSource.WINDOW, result.latencySource)
    }

    @Test
    fun `average latency ignores missing and non-positive measurements`() {
        val result =
            overview(
                metricEvents =
                listOf(
                    metricEvent(NOW_MS - HOUR_MS, latencyMs = null),
                    metricEvent(NOW_MS - HOUR_MS, latencyMs = 0L),
                    metricEvent(NOW_MS - HOUR_MS, latencyMs = -5L),
                    metricEvent(NOW_MS - HOUR_MS, latencyMs = 90L),
                ),
            )
        assertEquals(90L, result.avgLatencyMs)
    }

    @Test
    fun `an empty window falls back to the lifetime totals and marks them as such`() {
        val result =
            overview(
                metricEvents = listOf(metricEvent(NOW_MS - 2 * DAY_MS, protocol = "vless", latencyMs = 900L)),
                lifetimeTotal =
                OverallStatisticsUiItem(
                    totalBytes = 0L,
                    vpnSessions = 4,
                    successCount = 3,
                    failureCount = 1,
                    avgLatencyMs = 120L,
                    lastActivityAt = null,
                ),
                lifetimeProtocols =
                listOf(
                    protocolItem(ProtocolHint.VLESS, successCount = 2, failureCount = 0),
                    protocolItem(ProtocolHint.TROJAN, successCount = 1, failureCount = 1),
                    protocolItem(ProtocolHint.SHADOWSOCKS, successCount = 0, failureCount = 0),
                ),
            )
        assertEquals(CliStatsOverviewSource.LIFETIME, result.errorSource)
        assertEquals(4, result.vpnAttempts)
        assertEquals(1, result.vpnFailures)
        assertEquals(CliStatsOverviewSource.LIFETIME, result.latencySource)
        assertEquals(120L, result.avgLatencyMs)
        assertEquals("trojan", result.worstProtocol?.protocol)
        assertEquals("vless", result.bestProtocol?.protocol)
    }

    @Test
    fun `i2p bytes come from the session snapshot and ignore the window`() {
        val snapshot = I2pTrafficSnapshot(rxTotalBytes = 10L, txTotalBytes = 5L)
        assertEquals(15L, overview(window = StatisticsWindow.DAY, i2pTraffic = snapshot).i2pSessionBytes)
        assertEquals(15L, overview(window = StatisticsWindow.WEEK, i2pTraffic = snapshot).i2pSessionBytes)
    }

    @Test
    fun `tor bytes follow the route scope and are subtracted from the vpn lane`() {
        val windows =
            listOf(
                trafficWindow(NOW_MS - HOUR_MS, packageName = "com.tor.app", rxBytes = 40L),
                trafficWindow(NOW_MS - HOUR_MS, packageName = "com.plain.app", rxBytes = 60L),
            )
        val allApps =
            overview(
                appTrafficWindows = windows,
                settings = Settings(privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN)),
            )
        assertEquals(100L, allApps.torBytes)
        assertEquals(0L, allApps.vpnBytes)
        val selectedApps =
            overview(
                appTrafficWindows = windows,
                settings =
                Settings(
                    privacyRoute =
                    PrivacyRouteSettings(
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        scope = PrivacyRouteScope.SELECTED_APPS,
                    ),
                    expert = ExpertSettings(appAssignments = mapOf("com.tor.app" to AppTunnelLane.TOR)),
                ),
            )
        assertEquals(40L, selectedApps.torBytes)
        assertEquals(60L, selectedApps.vpnBytes)
    }
}

private const val NOW_MS = 1_700_000_000_000L
private const val HOUR_MS = 60L * 60L * 1000L
private const val DAY_MS = 24L * HOUR_MS
private const val TOLERANCE = 0.0001f

private fun overview(
    window: StatisticsWindow = StatisticsWindow.DAY,
    appTrafficWindows: List<AppTrafficWindow> = emptyList(),
    metricEvents: List<ProtocolMetricEvent> = emptyList(),
    i2pTraffic: I2pTrafficSnapshot = I2pTrafficSnapshot(),
    settings: Settings = Settings(),
    lifetimeTotal: OverallStatisticsUiItem = emptyLifetimeTotal(),
    lifetimeProtocols: List<ProtocolStatisticsUiItem> = emptyList(),
): CliStatsOverview =
    cliStatsOverview(
        window = window,
        appTrafficWindows = appTrafficWindows,
        metricEvents = metricEvents,
        i2pTraffic = i2pTraffic,
        settings = settings,
        lifetimeTotal = lifetimeTotal,
        lifetimeProtocols = lifetimeProtocols,
        nowMs = NOW_MS,
    )

private fun emptyLifetimeTotal(): OverallStatisticsUiItem =
    OverallStatisticsUiItem(
        totalBytes = 0L,
        vpnSessions = 0,
        successCount = 0,
        failureCount = 0,
        avgLatencyMs = null,
        lastActivityAt = null,
    )

private fun metricEvent(
    timestampMs: Long,
    protocol: String = "",
    kind: ProtocolMetricEventKind = ProtocolMetricEventKind.PROBE_SUCCESS,
    latencyMs: Long? = null,
): ProtocolMetricEvent =
    ProtocolMetricEvent(
        timestampMs = timestampMs,
        profileId = 1L,
        optionId = null,
        protocol = protocol,
        kind = kind,
        latencyMs = latencyMs,
    )

private fun trafficWindow(
    startedAtMs: Long,
    packageName: String = "com.plain.app",
    rxBytes: Long = 0L,
    txBytes: Long = 0L,
): AppTrafficWindow =
    AppTrafficWindow(
        packageName = packageName,
        startedAtMs = startedAtMs,
        durationSec = 60,
        rxBytes = rxBytes,
        txBytes = txBytes,
        foreground = null,
        networkType = NetworkType.UNKNOWN,
    )

private fun protocolItem(
    protocol: ProtocolHint,
    successCount: Int,
    failureCount: Int,
): ProtocolStatisticsUiItem =
    ProtocolStatisticsUiItem(
        protocol = protocol,
        successCount = successCount,
        failureCount = failureCount,
        rxBytes = 0L,
        txBytes = 0L,
        avgLatencyMs = null,
        lastUsedAt = null,
    )
