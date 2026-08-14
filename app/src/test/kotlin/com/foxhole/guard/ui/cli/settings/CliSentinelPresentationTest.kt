package com.foxhole.guard.ui.cli.settings

import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.AppTrafficWindow
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.model.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test

class CliSentinelPresentationTest {
    @Test
    fun `observed apps come from persisted activity and are ordered by last real event`() {
        val windows = listOf(appWindow("app.old", at = 100L, bytes = 10L), appWindow("app.zero", at = 500L, bytes = 0L))
        val network = listOf(networkEvent(listOf("app.network", "app.old"), at = 300L))
        val anomalies = listOf(anomaly("app.alert", at = 400L))

        assertEquals(
            listOf("app.alert", "app.network", "app.old"),
            observedSentinelPackages(windows, network, anomalies),
        )
    }

    @Test
    fun `observed apps deduplicate packages and ignore blank identities`() {
        assertEquals(
            listOf("app.same"),
            observedSentinelPackages(
                appTrafficWindows = listOf(appWindow("app.same", at = 10L, bytes = 1L)),
                networkActivityEvents = listOf(networkEvent(listOf("", "app.same"), at = 20L)),
                anomalyEvents = listOf(anomaly("app.same", at = 30L)),
            ),
        )
    }

    @Test
    fun `Sentinel presentation caps app icons and visible detection rows`() {
        val strip = sentinelAppIconStrip((1..9).map { index -> "app.$index" })

        assertEquals((1..6).map { index -> "app.$index" }, strip.packages)
        assertEquals(3, strip.extraCount)
        assertEquals(0, sentinelVisibleDetectionRowCount(-1))
        assertEquals(4, sentinelVisibleDetectionRowCount(4))
        assertEquals(10, sentinelVisibleDetectionRowCount(42))
    }

    private fun appWindow(packageName: String, at: Long, bytes: Long) =
        AppTrafficWindow(
            packageName = packageName,
            startedAtMs = at,
            durationSec = 60,
            rxBytes = bytes,
            txBytes = 0L,
            foreground = null,
            networkType = NetworkType.WIFI,
        )

    private fun networkEvent(packageNames: List<String>, at: Long) =
        NetworkActivityEvent(
            timestampMs = at,
            packageNames = packageNames,
            protocol = "tcp",
            remoteHost = "example.invalid",
            remotePort = 443,
            countryCode = null,
            bytesRx = 0L,
            bytesTx = 1L,
            profileId = null,
            sessionId = null,
        )

    private fun anomaly(packageName: String, at: Long) =
        AnomalyEvent(
            createdAtMs = at,
            type = AnomalyType.APP_BACKGROUND_TRAFFIC,
            severity = AnomalySeverity.ACTIVITY_LOG,
            score = 1,
            reason = "test",
            evidence = emptyMap(),
            packageName = packageName,
        )
}
