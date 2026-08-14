package com.foxhole.guard.ui.cli.stats

import com.foxhole.core.model.NetworkType
import com.foxhole.core.model.ProfileTrafficTotal
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.ProtocolMetricEvent
import com.foxhole.core.model.ProtocolMetricEventKind
import com.foxhole.core.model.StatisticsWindow
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TrafficWindow
import com.foxhole.core.model.TransportProtocol
import com.foxhole.core.model.VpnMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CliStatsVpnTablesTest {
    @Test
    fun `VPN protocol and transport tables use only facts inside selected period`() {
        val now = 2_000_000_000L
        val tables = cliStatsVpnTables(
            window = StatisticsWindow.DAY,
            deviceWindows = listOf(
                traffic(now - 1_000L, 1L, "vless", rx = 100L, tx = 20L),
                traffic(now - 2_000L, 2L, "hysteria2", rx = 50L, tx = 30L),
                traffic(now - StatisticsWindow.DAY.durationMs - 1L, 1L, "vless", rx = 9_000L, tx = 9_000L),
                traffic(now - 500L, TOR_ONLY_PROFILE_ID, "tor", rx = 7_000L, tx = 7_000L),
            ),
            metricEvents = listOf(
                metric(now - 1_000L, 1L, "vless", ProtocolMetricEventKind.PROBE_SUCCESS, latency = 100L),
                metric(now - 900L, 1L, "vless", ProtocolMetricEventKind.PROBE_FAILURE, latency = 300L),
                metric(now - 800L, 2L, "hysteria2", ProtocolMetricEventKind.PROBE_SUCCESS, latency = 80L),
                metric(
                    now - StatisticsWindow.DAY.durationMs - 1L,
                    1L,
                    "vless",
                    ProtocolMetricEventKind.PROBE_FAILURE,
                    latency = 9_999L,
                ),
            ),
            profileTrafficTotals = listOf(
                total(1L, "vless", ProtocolHint.VLESS, TransportProtocol.TCP),
                total(2L, "hysteria2", ProtocolHint.HYSTERIA2, TransportProtocol.UDP),
            ),
            nowMs = now,
        )

        assertEquals(listOf("vless", "hysteria2"), tables.protocols.map { row -> row.protocol })
        assertEquals(120L, tables.protocols[0].rxBytes + tables.protocols[0].txBytes)
        assertEquals(200L, tables.protocols[0].avgLatencyMs)
        assertEquals(listOf(TransportProtocol.TCP, TransportProtocol.UDP), tables.transports.map { it.transport })
        assertEquals(120L, tables.transports[0].rxBytes + tables.transports[0].txBytes)
        assertEquals(0.5f, tables.transports[0].errorRateOrNull ?: -1f, 0.0001f)
        assertEquals(80L, tables.transports[1].rxBytes + tables.transports[1].txBytes)
        assertEquals(0f, tables.transports[1].errorRateOrNull ?: -1f, 0.0001f)
    }

    @Test
    fun `unknown transport remains absent and never invents an error percentage`() {
        val now = 2_000_000_000L
        val tables = cliStatsVpnTables(
            window = StatisticsWindow.DAY,
            deviceWindows = listOf(traffic(now - 1L, 3L, "shadowsocks", rx = 4L, tx = 5L)),
            metricEvents = emptyList(),
            profileTrafficTotals =
            listOf(total(3L, "shadowsocks", ProtocolHint.SHADOWSOCKS, TransportProtocol.UNKNOWN)),
            nowMs = now,
        )

        assertEquals(1, tables.protocols.size)
        assertNull(tables.protocols.single().avgLatencyMs)
        assertEquals(emptyList<CliStatsVpnTransportRow>(), tables.transports)
    }

    private fun traffic(
        at: Long,
        profileId: Long,
        protocol: String,
        rx: Long,
        tx: Long,
    ): TrafficWindow =
        TrafficWindow(
            startedAtMs = at,
            durationSec = 30,
            networkType = NetworkType.WIFI,
            vpnMode = VpnMode.NORMAL,
            profileId = profileId.toString(),
            protocol = protocol,
            rxBytes = rx,
            txBytes = tx,
            blockedDns = 0,
            allowedDns = 0,
            reconnects = 0,
            latencyMs = null,
            destinationCountries = emptyMap(),
        )

    private fun metric(
        at: Long,
        profileId: Long,
        protocol: String,
        kind: ProtocolMetricEventKind,
        latency: Long,
    ): ProtocolMetricEvent =
        ProtocolMetricEvent(
            timestampMs = at,
            profileId = profileId,
            optionId = protocol,
            protocol = protocol,
            kind = kind,
            latencyMs = latency,
        )

    private fun total(
        profileId: Long,
        optionId: String,
        protocol: ProtocolHint,
        transport: TransportProtocol,
    ): ProfileTrafficTotal =
        ProfileTrafficTotal(
            profileId = profileId,
            profileName = optionId,
            protocolHint = protocol,
            protocolOptionId = optionId,
            transport = transport,
        )
}
