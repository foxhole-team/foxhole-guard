package com.foxhole.guard.ui
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.model.NetworkType
import com.foxhole.core.model.TrafficWindow
import com.foxhole.core.model.VpnMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeActivityStreamsPreviewTest {
    @Test
    fun `diagnostics preview keeps latest five hundred chronological entries`() {
        val diagnosticEntries =
            (1..600).map { index ->
                DiagnosticEntry(
                    timestamp = index.toLong(),
                    tag = "runtime",
                    message = "entry=$index",
                )
            }
        val streams =
            homeActivityStreamsPreview(
                diagnosticEntries = diagnosticEntries,
                anomalyEvents = emptyList(),
                appTrafficWindows = emptyList(),
                networkActivityEvents = emptyList(),
                trafficWindows = emptyList(),
                reconnectState = reconnectState(),
            )

        assertEquals(DIAGNOSTICS_PREVIEW_LIMIT, streams.diagnosticEntries.size)
        assertEquals("entry=101", streams.diagnosticEntries.first().message)
        assertEquals("entry=600", streams.diagnosticEntries.last().message)
    }

    @Test
    fun `activity previews keep newest descending entries within limits`() {
        val anomalyEvents =
            (1..260).map { index -> anomalyEvent(createdAtMs = index.toLong()) }.asReversed()
        val appTrafficWindows =
            (1..160).map { index -> appTrafficWindow(startedAtMs = index.toLong()) }.asReversed()
        val networkActivityEvents =
            (1..260).map { index -> networkActivityEvent(timestampMs = index.toLong()) }.asReversed()
        val trafficWindows =
            (1..160).map { index -> trafficWindow(startedAtMs = index.toLong()) }.asReversed()
        val streams =
            homeActivityStreamsPreview(
                diagnosticEntries = emptyList(),
                anomalyEvents = anomalyEvents,
                appTrafficWindows = appTrafficWindows,
                networkActivityEvents = networkActivityEvents,
                trafficWindows = trafficWindows,
                reconnectState = reconnectState(),
            )

        assertEquals(ANOMALY_EVENTS_UI_LIMIT, streams.anomalyEvents.size)
        assertEquals(260L, streams.anomalyEvents.first().createdAtMs)
        assertEquals(61L, streams.anomalyEvents.last().createdAtMs)
        assertEquals(APP_TRAFFIC_WINDOWS_UI_LIMIT, streams.appTrafficWindows.size)
        assertEquals(160L, streams.appTrafficWindows.first().startedAtMs)
        assertEquals(41L, streams.appTrafficWindows.last().startedAtMs)
        assertEquals(NETWORK_ACTIVITY_EVENTS_UI_LIMIT, streams.networkActivityEvents.size)
        assertEquals(260L, streams.networkActivityEvents.first().timestampMs)
        assertEquals(61L, streams.networkActivityEvents.last().timestampMs)
        assertEquals(TRAFFIC_WINDOWS_UI_LIMIT, streams.trafficWindows.size)
        assertEquals(160L, streams.trafficWindows.first().startedAtMs)
        assertEquals(41L, streams.trafficWindows.last().startedAtMs)
    }

    private fun reconnectState(): HomeReconnectStreams =
        HomeReconnectStreams(
            inProgress = false,
            promptUntilElapsedMs = 0L,
            runtimeReconnectRequired = false,
        )

    private fun anomalyEvent(createdAtMs: Long): AnomalyEvent =
        AnomalyEvent(
            createdAtMs = createdAtMs,
            type = AnomalyType.TOTAL_TRAFFIC_SPIKE,
            severity = AnomalySeverity.ACTIVITY_LOG,
            score = 1,
            reason = "test",
            evidence = emptyMap(),
        )

    private fun appTrafficWindow(startedAtMs: Long): AppTrafficWindow =
        AppTrafficWindow(
            packageName = "com.example.$startedAtMs",
            startedAtMs = startedAtMs,
            durationSec = 1,
            rxBytes = 1,
            txBytes = 1,
            foreground = null,
            networkType = NetworkType.UNKNOWN,
        )

    private fun networkActivityEvent(timestampMs: Long): NetworkActivityEvent =
        NetworkActivityEvent(
            timestampMs = timestampMs,
            packageNames = listOf("com.example"),
            protocol = "tcp",
            remoteHost = "example.com",
            remotePort = 443,
            countryCode = null,
            bytesRx = 1,
            bytesTx = 1,
            profileId = null,
            sessionId = null,
        )

    private fun trafficWindow(startedAtMs: Long): TrafficWindow =
        TrafficWindow(
            startedAtMs = startedAtMs,
            durationSec = 1,
            networkType = NetworkType.UNKNOWN,
            vpnMode = VpnMode.NORMAL,
            profileId = null,
            protocol = null,
            rxBytes = 1,
            txBytes = 1,
            blockedDns = 0,
            allowedDns = 0,
            reconnects = 0,
            latencyMs = null,
            destinationCountries = emptyMap(),
        )
}
