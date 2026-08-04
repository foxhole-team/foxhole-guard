package com.foxhole.guard.traffic

import com.foxhole.core.model.DnsFilterCategory
import com.foxhole.guard.runtime.shouldIncludeRuntimeProcessInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxCoreRuntimeStatsTrackerTest {
    @Test
    fun `dns stats and traffic map share the FoxCore observer`() {
        val observerSource = source("guard/traffic/FoxCoreRuntimeConnectionObserver.kt")
        val dnsTrackerSource = source("guard/traffic/FoxCoreRuntimeStatsTracker.kt")
        val trafficMapSamplesSource = source("guard/traffic/TrafficMapConnectionSamples.kt")
        // The traffic-sampling wiring was extracted from FoxholeVpnService into its support file.
        val trafficSupportSource = source("guard/runtime/FoxholeVpnServiceTrafficSupport.kt")

        assertTrue(observerSource.contains("runtimeTrafficMapJson()"))
        assertTrue(observerSource.contains("drainRuntimeAuditEventsJson("))
        assertTrue(dnsTrackerSource.contains("runtimeConnectionSnapshots: Flow<RuntimeConnectionSnapshot>"))
        assertFalse(trafficMapSamplesSource.contains("recordDnsConnection("))
        assertTrue(trafficSupportSource.contains("shareIn("))
        assertTrue(trafficSupportSource.contains("startDestinationCountryTrackingFromSamples("))
    }

    @Test
    fun `traffic map parser carries generation and authoritative DNS counters`() {
        val snapshot =
            parseTrafficMap(
                source =
                """
                {
                  "generation": 17,
                  "connections": [],
                  "omitted_rows": 0,
                  "dns": {"queries": 9, "blocked": 3, "allowed": 6}
                }
                """.trimIndent(),
                includeProcessInfo = false,
            )

        assertEquals(17L, snapshot.generation)
        assertEquals(9L, snapshot.dnsQueries)
        assertEquals(3L, snapshot.dnsBlocked)
        assertEquals(6L, snapshot.dnsAllowed)
    }

    @Test
    fun `audit parser keeps typed DNS block attribution and reports drops`() {
        val drain =
            parseRuntimeAuditDrain(
                """
                {
                  "events": [
                    {
                      "type": "dns_blocked",
                      "reason": "policy",
                      "domain": "ads.example",
                      "package": "com.example.app",
                      "category": "ads"
                    },
                    {
                      "type": "outbound_unavailable",
                      "id": "default",
                      "kind": "tor",
                      "reason": "permissions",
                      "message": "outbound 'default' failed: state directory is not writable",
                      "attempts": 2
                    },
                    {"type": "config_applied", "revision": 2, "previous_revision": 1}
                  ],
                  "dropped": 4
                }
                """.trimIndent(),
            )

        assertEquals(4L, drain.dropped)
        assertEquals(
            listOf(
                RuntimeAuditEvent.DnsBlocked(
                    domain = "ads.example",
                    category = DnsFilterCategory.ADS,
                    packageName = "com.example.app",
                ),
                RuntimeAuditEvent.OutboundUnavailable(
                    id = "default",
                    kind = "tor",
                    reason = "permissions",
                    message = "outbound 'default' failed: state directory is not writable",
                    attempts = 2,
                ),
            ),
            drain.events,
        )
    }

    @Test
    fun `cumulative DNS counters reset cleanly on a new generation`() {
        assertEquals(3L, cumulativeCounterDelta(current = 8L, previous = 5L, generationChanged = false))
        assertEquals(2L, cumulativeCounterDelta(current = 2L, previous = 8L, generationChanged = true))
        assertEquals(2L, cumulativeCounterDelta(current = 2L, previous = 8L, generationChanged = false))
    }

    @Test
    fun `per-app traffic alone keeps process attribution enabled`() {
        assertTrue(
            shouldIncludeRuntimeProcessInfo(
                networkActivityEnabled = false,
                appTrafficEnabled = true,
            ),
        )
        assertFalse(
            shouldIncludeRuntimeProcessInfo(
                networkActivityEnabled = false,
                appTrafficEnabled = false,
            ),
        )
    }

    @Test
    fun `runtime endpoint parser splits ipv4 host and port`() {
        val endpoint = "149.154.167.41:443".toRuntimeEndpoint()

        assertEquals("149.154.167.41", endpoint.host)
        assertEquals(443, endpoint.port)
    }

    @Test
    fun `runtime endpoint parser keeps bracketed ipv6 readable`() {
        val endpoint = "[2a00:1450:4010:c0f::65]:443".toRuntimeEndpoint()

        assertEquals("2a00:1450:4010:c0f::65", endpoint.host)
        assertEquals(443, endpoint.port)
    }

    @Test
    fun `runtime endpoint parser keeps raw ipv6 without treating suffix as port`() {
        val endpoint = "2a00:1450:4010:c0f::65".toRuntimeEndpoint()

        assertEquals("2a00:1450:4010:c0f::65", endpoint.host)
        assertNull(endpoint.port)
    }

    @Test
    fun `tor lane comes from the route recorded by FoxCore`() {
        val tor =
            runtimeConnectionRecord(
                lane = "tor",
                outboundType = "tor",
                outboundTag = "tor",
            )

        assertTrue(tor.belongsToTorLane(torOnlyRuntimeActive = false))
    }

    @Test
    fun `vpn route is not misclassified when its member name mentions tor`() {
        val vpn =
            runtimeConnectionRecord(
                lane = "vpn",
                outboundType = "tor",
                outboundTag = "tor-over-vpn",
            )

        assertFalse(vpn.belongsToTorLane(torOnlyRuntimeActive = false))
    }

    private fun runtimeConnectionRecord(
        lane: String,
        outboundType: String,
        outboundTag: String,
    ): RuntimeConnectionRecord =
        RuntimeConnectionRecord(
            connectionId = "test",
            lane = lane,
            outboundType = outboundType,
            outboundTag = outboundTag,
            destination = null,
            domain = null,
            network = null,
            protocol = null,
            bytesTx = 0L,
            bytesRx = 0L,
        )

    private fun source(relativePath: String): String =
        listOf(
            java.io.File("src/main/kotlin/com/foxhole/$relativePath"),
            java.io.File("app/src/main/kotlin/com/foxhole/$relativePath"),
            java.io.File("../app/src/main/kotlin/com/foxhole/$relativePath"),
        ).first { file -> file.isFile }.readText()
}
