package com.foxhole.guard.ui.cli.stats

import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.I2pSettings
import com.foxhole.core.model.I2pTrafficBucket
import com.foxhole.core.model.I2pTrafficTotals
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.NetworkType
import com.foxhole.core.model.OverallStatisticsUiItem
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.ProtocolMetricEvent
import com.foxhole.core.model.ProtocolMetricEventKind
import com.foxhole.core.model.ProtocolStatisticsUiItem
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsRetention
import com.foxhole.core.model.StatisticsSettings
import com.foxhole.core.model.StatisticsWindow
import com.foxhole.core.model.TrafficWindow
import com.foxhole.core.model.VpnMode
import org.junit.Assert.assertArrayEquals
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
    fun `overview totals follow recorded routes after tor is switched off`() {
        val windows =
            listOf(
                trafficWindow(NOW_MS - HOUR_MS, packageName = "com.tor.app", rxBytes = 40L),
                trafficWindow(NOW_MS - HOUR_MS, packageName = "com.plain.app", rxBytes = 60L),
            )
        val result = overview(
            appTrafficWindows = windows,
            settings = Settings(),
            deviceWindows =
            listOf(
                deviceWindow(NOW_MS - HOUR_MS, rxBytes = 40L, vpnMode = VpnMode.TOR),
                deviceWindow(NOW_MS - HOUR_MS, rxBytes = 60L, vpnMode = VpnMode.NORMAL),
            ),
        )
        assertEquals(40L, result.torBytes)
        assertEquals(60L, result.vpnBytes)
    }

    @Test
    fun `month window keeps what the week window cuts`() {
        val windows =
            listOf(
                trafficWindow(NOW_MS - 20 * DAY_MS, rxBytes = 100L),
                trafficWindow(NOW_MS - 2 * DAY_MS, rxBytes = 50L),
            )
        assertEquals(50L, overview(window = StatisticsWindow.WEEK, appTrafficWindows = windows).vpnBytes)
        assertEquals(150L, overview(window = StatisticsWindow.MONTH, appTrafficWindows = windows).vpnBytes)
    }

    @Test
    fun `sparkline buckets split bytes by time and by the route that was recorded`() {
        val windowMs = 4L * HOUR_MS
        val buckets =
            cliStatsTrafficBuckets(
                samples =
                listOf(
                    trafficWindow(NOW_MS - windowMs + 1L, rxBytes = 100L),
                    trafficWindow(NOW_MS - HOUR_MS / 2, rxBytes = 30L),
                    trafficWindow(NOW_MS - 2 * windowMs, rxBytes = 999L),
                ),
                windowMs = windowMs,
                nowMs = NOW_MS,
                bucketCount = 4,
                deviceWindows =
                listOf(
                    deviceWindow(NOW_MS - windowMs + 1L, rxBytes = 10L, vpnMode = VpnMode.TOR),
                    deviceWindow(NOW_MS - HOUR_MS / 2, rxBytes = 10L, vpnMode = VpnMode.TOR),
                ),
            )
        assertEquals(4, buckets.size)
        assertEquals(100L, buckets[0].torBytes)
        assertEquals(0L, buckets[1].totalBytes)
        assertEquals(0L, buckets[2].totalBytes)
        assertEquals(30L, buckets[3].torBytes)
    }

    @Test
    fun `recorded tor traffic keeps its lane after the module is switched off`() {
        val buckets =
            cliStatsTrafficBuckets(
                samples = listOf(trafficWindow(NOW_MS - HOUR_MS / 2, rxBytes = 100L)),
                windowMs = HOUR_MS,
                nowMs = NOW_MS,
                bucketCount = 1,
                deviceWindows =
                listOf(deviceWindow(NOW_MS - HOUR_MS / 2, rxBytes = 5L, vpnMode = VpnMode.TOR)),
            )
        assertEquals(100L, buckets[0].torBytes)
        assertEquals(0L, buckets[0].vpnBytes)
    }

    @Test
    fun `a tor minute over the local guard is not counted twice`() {
        val buckets =
            cliStatsTrafficBuckets(
                samples = listOf(trafficWindow(NOW_MS - HOUR_MS / 2, rxBytes = 100L)),
                windowMs = HOUR_MS,
                nowMs = NOW_MS,
                bucketCount = 1,
                deviceWindows =
                listOf(
                    deviceWindow(
                        NOW_MS - HOUR_MS / 2,
                        rxBytes = 10L,
                        profileId = LOCAL_GUARD_PROFILE_ID.toString(),
                        vpnMode = VpnMode.TOR,
                    ),
                ),
            )
        assertEquals(100L, buckets[0].torBytes)
        assertEquals(0L, buckets[0].firewallBytes)
        assertEquals(100L, buckets[0].totalBytes)
    }

    @Test
    fun `bucket count follows the window the axis promises`() {
        assertEquals(24, cliStatsSparklineBucketCount(StatisticsWindow.DAY))
        assertEquals(7, cliStatsSparklineBucketCount(StatisticsWindow.WEEK))
        assertEquals(30, cliStatsSparklineBucketCount(StatisticsWindow.MONTH))
    }

    @Test
    fun `chart only allocates inner columns to visible lanes`() {
        assertArrayEquals(intArrayOf(), CliStatsChartLanes().visibleLaneIndexes())
        assertArrayEquals(intArrayOf(0, 1), CliStatsChartLanes(vpn = true, tor = true).visibleLaneIndexes())
        assertArrayEquals(
            intArrayOf(2, 3),
            CliStatsChartLanes(i2p = true, firewall = true).visibleLaneIndexes(),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 2, 3),
            CliStatsChartLanes(vpn = true, tor = true, i2p = true, firewall = true).visibleLaneIndexes(),
        )
    }

    @Test
    fun `disabled modules keep only lanes that contain historical traffic`() {
        val overview = CliStatsOverview(
            torBytes = 20L,
            i2pBytes = 30L,
            buckets = listOf(CliStatsTrafficBucket(vpnBytes = 0L, torBytes = 20L, i2pBytes = 30L, firewallBytes = 40L)),
        )
        assertEquals(
            CliStatsChartLanes(vpn = false, tor = true, i2p = true, firewall = true),
            cliStatsChartLanes(
                overview = overview,
                settings = Settings(),
                vpnActive = false,
                torActive = false,
                i2pActive = false,
            ),
        )
    }

    @Test
    fun `engaged modules allocate lanes before their first byte`() {
        val settings = Settings(
            privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN, permitted = true),
            i2p = I2pSettings(enabled = true, engaged = true),
        )
        assertEquals(
            CliStatsChartLanes(vpn = true, tor = true, i2p = true),
            cliStatsChartLanes(
                overview = CliStatsOverview(),
                settings = settings,
                vpnActive = true,
                torActive = false,
                i2pActive = false,
            ),
        )
        assertEquals(
            CliStatsChartLanes(),
            cliStatsChartLanes(
                overview = CliStatsOverview(),
                settings = settings.copy(
                    privacyRoute = PrivacyRouteSettings(permitted = true),
                    i2p = I2pSettings(enabled = true, engaged = false),
                ),
                vpnActive = false,
                torActive = false,
                i2pActive = false,
            ),
        )
    }

    @Test
    fun `chart ceiling stays on readable one two five boundaries`() {
        assertEquals(0L, cliStatsScaleCeiling(0L))
        assertEquals(1L, cliStatsScaleCeiling(1L))
        assertEquals(2L, cliStatsScaleCeiling(2L))
        assertEquals(5L, cliStatsScaleCeiling(3L))
        assertEquals(10L, cliStatsScaleCeiling(6L))
        assertEquals(200L, cliStatsScaleCeiling(199L))
        assertEquals(500L, cliStatsScaleCeiling(201L))
        assertEquals(1_000L, cliStatsScaleCeiling(501L))
    }

    @Test
    fun `hero keeps recorded tor total when current settings are off`() {
        val hero = cliStatsHero(
            windows = listOf(trafficWindow(NOW_MS - HOUR_MS, rxBytes = 80L)),
            deviceWindows = listOf(deviceWindow(NOW_MS - HOUR_MS, rxBytes = 80L, vpnMode = VpnMode.TOR)),
            nowMs = NOW_MS,
        )
        assertEquals(0L, hero.vpnBytes24h)
        assertEquals(80L, hero.torBytes24h)
    }

    @Test
    fun `hero does not report I2P client bytes as VPN traffic`() {
        val hero = cliStatsHero(
            windows = listOf(trafficWindow(NOW_MS - HOUR_MS, rxBytes = 80L)),
            deviceWindows = listOf(deviceWindow(NOW_MS - HOUR_MS, rxBytes = 80L)),
            i2pBuckets = listOf(i2pBucket(NOW_MS - HOUR_MS, ownBytes = 30L)),
            nowMs = NOW_MS,
        )

        assertEquals(50L, hero.vpnBytes24h)
        assertEquals(0L, hero.torBytes24h)
    }

    @Test
    fun `i2p hours land in their own bucket and add to the column`() {
        val windowMs = 4L * HOUR_MS
        val buckets =
            cliStatsTrafficBuckets(
                samples = listOf(trafficWindow(NOW_MS - HOUR_MS / 2, rxBytes = 30L)),
                windowMs = windowMs,
                nowMs = NOW_MS,
                bucketCount = 4,
                i2pBuckets =
                listOf(
                    i2pBucket(NOW_MS - windowMs + 1L, ownBytes = 70L, transitBytes = 500L),
                    i2pBucket(NOW_MS - 2 * windowMs, ownBytes = 999L),
                ),
            )
        assertEquals(70L, buckets[0].i2pBytes)
        assertEquals(70L, buckets[0].totalBytes)
        assertEquals(0L, buckets[3].i2pBytes)
        assertEquals(30L, buckets[3].vpnBytes)
    }

    @Test
    fun `I2P bytes move from the aggregate route into their own lane without double counting`() {
        val buckets =
            cliStatsTrafficBuckets(
                samples = listOf(trafficWindow(NOW_MS - HOUR_MS / 2, rxBytes = 100L)),
                windowMs = HOUR_MS,
                nowMs = NOW_MS,
                bucketCount = 1,
                i2pBuckets = listOf(i2pBucket(NOW_MS - HOUR_MS / 2, ownBytes = 30L)),
            )

        assertEquals(70L, buckets.single().vpnBytes)
        assertEquals(30L, buckets.single().i2pBytes)
        assertEquals(100L, buckets.single().totalBytes)
    }

    @Test
    fun `vpn plus tor keeps both hop series while I2P stays a carved out lane`() {
        val bucket =
            cliStatsTrafficBuckets(
                samples = listOf(trafficWindow(NOW_MS - HOUR_MS / 2, rxBytes = 100L)),
                windowMs = HOUR_MS,
                nowMs = NOW_MS,
                bucketCount = 1,
                deviceWindows =
                listOf(
                    deviceWindow(
                        NOW_MS - HOUR_MS / 2,
                        rxBytes = 100L,
                        profileId = "7",
                        vpnMode = VpnMode.TOR,
                    ),
                ),
                i2pBuckets = listOf(i2pBucket(NOW_MS - HOUR_MS / 2, ownBytes = 30L)),
            ).single()

        assertEquals(70L, bucket.vpnBytes)
        assertEquals(70L, bucket.torBytes)
        assertEquals(30L, bucket.i2pBytes)
        assertEquals(100L, bucket.totalBytes)
    }

    @Test
    fun `the local guard share re-labels untunnelled bytes without inventing any`() {
        val windowMs = 2L * HOUR_MS
        val buckets =
            cliStatsTrafficBuckets(
                samples = listOf(trafficWindow(NOW_MS - HOUR_MS / 2, rxBytes = 100L)),
                windowMs = windowMs,
                nowMs = NOW_MS,
                bucketCount = 2,
                deviceWindows =
                listOf(
                    deviceWindow(NOW_MS - HOUR_MS / 2, rxBytes = 30L, profileId = LOCAL_GUARD_PROFILE_ID.toString()),
                    deviceWindow(NOW_MS - HOUR_MS / 2, rxBytes = 70L, profileId = "7"),
                ),
            )
        assertEquals(30L, buckets[1].firewallBytes)
        assertEquals(70L, buckets[1].vpnBytes)
        assertEquals(100L, buckets[1].totalBytes)
    }

    @Test
    fun `without mode-tagged device windows every untunnelled byte stays on the vpn lane`() {
        val buckets =
            cliStatsTrafficBuckets(
                samples = listOf(trafficWindow(NOW_MS - HOUR_MS / 2, rxBytes = 100L)),
                windowMs = HOUR_MS,
                nowMs = NOW_MS,
                bucketCount = 1,
            )
        assertEquals(0L, buckets[0].firewallBytes)
        assertEquals(100L, buckets[0].vpnBytes)
    }

    @Test
    fun `device aggregate draws traffic when usage access has no app windows`() {
        val buckets =
            cliStatsTrafficBuckets(
                samples = emptyList(),
                windowMs = HOUR_MS,
                nowMs = NOW_MS,
                bucketCount = 1,
                deviceWindows =
                listOf(
                    deviceWindow(
                        NOW_MS - HOUR_MS / 2,
                        rxBytes = 64L,
                        vpnMode = VpnMode.TOR,
                    ),
                ),
            )

        assertEquals(64L, buckets.single().torBytes)
        assertEquals(0L, buckets.single().vpnBytes)
    }

    @Test
    fun `overview carries peak rate and the distinct app count`() {
        val result =
            overview(
                appTrafficWindows =
                listOf(
                    trafficWindow(NOW_MS - HOUR_MS, packageName = "a", rxBytes = 2_400L),
                    trafficWindow(NOW_MS - HOUR_MS, packageName = "b", rxBytes = 1_200L),
                    trafficWindow(NOW_MS - 5 * HOUR_MS, packageName = "a", rxBytes = 600L),
                ),
            )
        assertEquals(1L, result.peakRateBytesPerSec)
        assertEquals(2, result.appsWithTraffic)
    }

    @Test
    fun `window longer than retention raises the note flag`() {
        val weekRetention =
            Settings(statistics = StatisticsSettings(retention = StatisticsRetention.WEEK))
        assertEquals(
            true,
            overview(window = StatisticsWindow.MONTH, settings = weekRetention).windowExceedsRetention,
        )
        assertEquals(
            false,
            overview(window = StatisticsWindow.DAY, settings = weekRetention).windowExceedsRetention,
        )
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
    settings: Settings = Settings(),
    lifetimeTotal: OverallStatisticsUiItem = emptyLifetimeTotal(),
    lifetimeProtocols: List<ProtocolStatisticsUiItem> = emptyList(),
    deviceWindows: List<TrafficWindow> = emptyList(),
): CliStatsOverview =
    cliStatsOverview(
        window = window,
        appTrafficWindows = appTrafficWindows,
        metricEvents = metricEvents,
        settings = settings,
        lifetimeTotal = lifetimeTotal,
        lifetimeProtocols = lifetimeProtocols,
        nowMs = NOW_MS,
        deviceWindows = deviceWindows,
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

private fun i2pBucket(
    hourStartMs: Long,
    ownBytes: Long = 0L,
    transitBytes: Long = 0L,
): I2pTrafficBucket =
    I2pTrafficBucket(
        hourStartMs = hourStartMs,
        totals = I2pTrafficTotals(ownBytes = ownBytes, transitBytes = transitBytes),
    )

private fun deviceWindow(
    startedAtMs: Long,
    rxBytes: Long = 0L,
    txBytes: Long = 0L,
    profileId: String? = null,
    vpnMode: VpnMode = VpnMode.NORMAL,
): TrafficWindow =
    TrafficWindow(
        startedAtMs = startedAtMs,
        durationSec = 60,
        networkType = NetworkType.UNKNOWN,
        vpnMode = vpnMode,
        profileId = profileId,
        protocol = null,
        rxBytes = rxBytes,
        txBytes = txBytes,
        blockedDns = 0,
        allowedDns = 0,
        reconnects = 0,
        latencyMs = null,
        destinationCountries = emptyMap(),
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
