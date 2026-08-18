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
        val trafficSupportSource = source("guard/runtime/FoxholeVpnServiceTrafficSupport.kt")

        assertTrue(observerSource.contains("runtimeTrafficMapJson()"))
        assertTrue(observerSource.contains("drainRuntimeAuditEventsJson("))
        assertTrue(observerSource.contains("drainRuntimeTrafficEventsJson("))
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
    fun `traffic map parser keeps authoritative cumulative lane counters`() {
        val snapshot =
            parseTrafficMap(
                source =
                """
                {
                  "generation": 17,
                  "connections": [],
                  "lanes": [
                    {"lane":"vpn","bytes_up":100,"bytes_down":200,"flows_opened":2,"flows_live":0},
                    {"lane":"i2p","bytes_up":31,"bytes_down":47,"flows_opened":1,"flows_live":0}
                  ]
                }
                """.trimIndent(),
                includeProcessInfo = false,
            )

        assertEquals(
            RuntimeLaneTraffic(lane = "i2p", bytesTx = 31L, bytesRx = 47L),
            snapshot.lanes.single { lane -> lane.lane == "i2p" },
        )
    }

    @Test
    fun `lane cursor counts closed flow bytes and fences a new runtime generation`() {
        val cursor = RuntimeLaneTrafficCursor()

        val first =
            cursor.record(
                RuntimeConnectionSnapshot(
                    generation = 7,
                    connections = emptyList(),
                    lanes = listOf(RuntimeLaneTraffic("i2p", bytesTx = 10, bytesRx = 20)),
                ),
            )!!
        val afterClosedFlow =
            cursor.record(
                RuntimeConnectionSnapshot(
                    generation = 7,
                    connections = emptyList(),
                    lanes = listOf(RuntimeLaneTraffic("i2p", bytesTx = 110, bytesRx = 220)),
                ),
            )!!
        val restarted =
            cursor.record(
                RuntimeConnectionSnapshot(
                    generation = 8,
                    connections = emptyList(),
                    lanes = listOf(RuntimeLaneTraffic("i2p", bytesTx = 3, bytesRx = 4)),
                ),
            )!!

        assertEquals(RuntimeLaneTrafficDelta(bytesTx = 10, bytesRx = 20), first.getValue("i2p"))
        assertEquals(RuntimeLaneTrafficDelta(bytesTx = 100, bytesRx = 200), afterClosedFlow.getValue("i2p"))
        assertEquals(RuntimeLaneTrafficDelta(bytesTx = 3, bytesRx = 4), restarted.getValue("i2p"))
    }

    @Test
    fun `lane cursor advances without recording while collection is disabled`() {
        val cursor = RuntimeLaneTrafficCursor()
        cursor.advanceWithoutRecording(
            RuntimeConnectionSnapshot(
                generation = 3,
                lanes = listOf(RuntimeLaneTraffic("i2p", bytesTx = 500, bytesRx = 700)),
            ),
        )

        val delta =
            cursor.record(
                RuntimeConnectionSnapshot(
                    generation = 3,
                    lanes = listOf(RuntimeLaneTraffic("i2p", bytesTx = 520, bytesRx = 730)),
                ),
            )!!

        assertEquals(RuntimeLaneTrafficDelta(bytesTx = 20, bytesRx = 30), delta.getValue("i2p"))
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
                    reason = "policy",
                ),
                RuntimeAuditEvent.OutboundUnavailable(
                    id = "default",
                    kind = "tor",
                    reason = "permissions",
                    message = "outbound 'default' failed: state directory is not writable",
                    attempts = 2,
                ),
                RuntimeAuditEvent.ConfigApplied(
                    revision = 2,
                    previousRevision = 1,
                ),
            ),
            drain.events,
        )
    }

    @Test
    fun `traffic event drain preserves a flow that opened and closed between snapshots`() {
        val drain =
            parseRuntimeTrafficDrain(
                """
                {
                  "events": [
                    {"type":"opened","id":7,"transport":"tcp","host":"ignored.example","port":443,
                     "route":{"lane":"vpn","outbound":"vless"},"bytes_up":0,"bytes_down":0},
                    {"type":"closed","id":7,"transport":"tcp","host":"short.example","port":443,
                     "route":{"lane":"vpn","outbound":"vless"},
                     "packages":["com.example.app"],"uid":10123,"bytes_up":17,"bytes_down":29}
                  ],
                  "dropped": 0
                }
                """.trimIndent(),
            )

        assertEquals(1, drain.closedConnections.size)
        assertEquals("short.example:443", drain.closedConnections.single().destination)
        assertEquals(listOf("com.example.app"), drain.closedConnections.single().packageNames)
        assertEquals(17L, drain.closedConnections.single().bytesTx)
        assertEquals(29L, drain.closedConnections.single().bytesRx)
    }

    @Test
    fun `shared uid candidates never become an arbitrary exact package`() {
        val snapshot =
            parseTrafficMap(
                source =
                """
                {
                  "connections": [{
                    "id": 9,
                    "transport": "tcp",
                    "host": "shared.example",
                    "port": 443,
                    "route": {"lane":"vpn","outbound":"vless"},
                    "attribution": {"kind":"shared_uid","uid":10123,
                      "candidates":["com.example.b","com.example.a"]},
                    "packages": [],
                    "bytes_up": 10,
                    "bytes_down": 20
                  }]
                }
                """.trimIndent(),
                includeProcessInfo = true,
            )

        val connection = snapshot.connections.single()
        assertTrue(connection.packageNames.isEmpty())
        assertEquals(listOf("com.example.a", "com.example.b"), connection.sharedUidCandidates)
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
