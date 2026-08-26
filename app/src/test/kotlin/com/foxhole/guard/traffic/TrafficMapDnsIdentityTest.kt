package com.foxhole.guard.traffic

import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.TrafficMapPoint
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.guard.ui.cli.map.CliRouteMode
import com.foxhole.guard.ui.cli.map.cliLiveMapRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrafficMapDnsIdentityTest {
    @Test
    fun `only current runtime dns evidence reaches the map and empty snapshots clear it`() =
        runBlocking {
            val repository = TrafficMapRepository()
            val runtimeSamples =
                MutableStateFlow(
                    TrafficMapRuntimeSnapshotSamples(
                        generation = 41L,
                        samples = emptyList(),
                    ),
                )
            val trackingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val trackingJob =
                repository.startDestinationCountryTrackingFromSamples(
                    scope = trackingScope,
                    connectionSamples = runtimeSamples,
                )
            val state =
                repository.trafficMapState(
                    scope = stateScope,
                    originIpInfo = MutableStateFlow(ipInfo("AU")),
                    routeIpInfo = MutableStateFlow(ipInfo("NL")),
                    torIpInfo = MutableStateFlow(ipInfo("DE")),
                    runtimeAvailable = MutableStateFlow(true),
                )
            try {
                runtimeSamples.value =
                    TrafficMapRuntimeSnapshotSamples(
                        generation = 41L,
                        samples = listOf(dnsSample("dns-fi", "FI", 32L)),
                    )
                assertEquals(
                    "FI",
                    withTimeout(2_000L) {
                        state.filter { snapshot -> snapshot.dnsServer?.countryCode == "FI" }.first()
                    }.dnsServer?.countryCode,
                )

                runtimeSamples.value = TrafficMapRuntimeSnapshotSamples(generation = 41L, samples = emptyList())
                assertNull(
                    withTimeout(2_000L) {
                        state.filter { snapshot -> snapshot.dnsServer == null }.first()
                    }.dnsServer,
                )

                runtimeSamples.value =
                    TrafficMapRuntimeSnapshotSamples(
                        generation = 42L,
                        samples = listOf(dnsSample("dns-se", "SE", 64L)),
                    )
                assertEquals(
                    "SE",
                    withTimeout(2_000L) {
                        state.filter { snapshot -> snapshot.dnsServer?.countryCode == "SE" }.first()
                    }.dnsServer?.countryCode,
                )

                runtimeSamples.value = TrafficMapRuntimeSnapshotSamples(generation = 43L, samples = emptyList())
                assertNull(
                    withTimeout(2_000L) {
                        state.filter { snapshot -> snapshot.dnsServer == null }.first()
                    }.dnsServer,
                )
            } finally {
                trackingJob.cancel()
                trackingScope.cancel()
                stateScope.cancel()
            }
        }

    @Test
    fun `vpn tor and public route identities never substitute for missing dns evidence`() {
        val repository = TrafficMapRepository()
        val state =
            repository.trafficMapStateSnapshot(
                originIpInfo = ipInfo("AU"),
                routeIpInfo = ipInfo("NL"),
                torIpInfo = ipInfo("DE"),
                runtimeAvailable = true,
                destinations = emptyList(),
            )

        assertEquals("AU", state.originCountryCode)
        assertEquals("NL", state.vpnRoute?.countryCode)
        assertEquals("DE", state.torExit?.countryCode)
        assertNull(state.dnsServer)
    }

    @Test
    fun `missing dns evidence stays absent in vpn vpn tor tor only and local guard modes`() {
        val state =
            TrafficMapUiState(
                vpnRoute = point("NL"),
                torExit = point("DE"),
                dnsServer = null,
            )
        val modes =
            listOf(
                CliRouteMode(engaged = true, vpn = true),
                CliRouteMode(engaged = true, vpn = true, tor = true),
                CliRouteMode(engaged = true, tor = true),
                CliRouteMode(engaged = true, firewall = true),
            )

        modes.forEach { mode ->
            assertNull(cliLiveMapRoute(state, mode, confirmedTorCountryCode = "DE").dnsServer)
        }
    }

    @Test
    fun `dns sample requires its own live destination and never falls back to configured host`() {
        val liveEvidence =
            RuntimeConnectionSnapshot(
                generation = 7L,
                connections =
                listOf(
                    RuntimeConnectionRecord(
                        connectionId = "dns-live",
                        lane = "vpn",
                        outboundType = "dns",
                        outboundTag = null,
                        destination = "resolver.example:53",
                        domain = "resolver.example",
                        network = "udp",
                        protocol = null,
                        bytesTx = 12L,
                        bytesRx = 20L,
                    ),
                ),
            ).toTrafficMapConnectionSamples(
                maxConnections = 10,
                countryCodeForDestination = { candidate -> "FI".takeIf { candidate == "resolver.example" } },
            )
        val missingEvidence =
            RuntimeConnectionSnapshot(
                generation = 7L,
                connections =
                listOf(
                    RuntimeConnectionRecord(
                        connectionId = "dns-missing",
                        lane = "vpn",
                        outboundType = "dns",
                        outboundTag = null,
                        destination = null,
                        domain = null,
                        network = "udp",
                        protocol = null,
                        bytesTx = 12L,
                        bytesRx = 20L,
                    ),
                ),
            ).toTrafficMapConnectionSamples(
                maxConnections = 10,
                countryCodeForDestination = { candidate -> "AU".takeIf { candidate == "configured.example" } },
            )

        assertEquals("FI", liveEvidence.single().countryCode)
        assertEquals(TrafficMapConnectionSampleKind.DNS_SERVER, liveEvidence.single().kind)
        assertTrue(missingEvidence.isEmpty())
    }

    @Test
    fun `conflicting live resolver countries do not invent one dns point`() {
        val state = MutableStateFlow(TrafficMapDnsResolverSnapshot())

        state.replaceDnsResolverSnapshot(
            generation = 9L,
            samples = listOf(
                dnsSample("dns-fi", "FI", 10L),
                dnsSample("dns-se", "SE", 20L),
            ),
        )

        assertEquals(9L, state.value.generation)
        assertNull(state.value.aggregate?.countryCode)
    }

    @Test
    fun `dns generation fence rejects late snapshots and accepts current clears and newer evidence`() {
        val state = MutableStateFlow(TrafficMapDnsResolverSnapshot())

        state.replaceDnsResolverSnapshot(
            generation = 42L,
            samples = listOf(dnsSample("dns-se", "SE", 42L)),
        )
        state.replaceDnsResolverSnapshot(
            generation = 41L,
            samples = listOf(dnsSample("dns-fi-late", "FI", 41L)),
        )
        assertEquals(42L, state.value.generation)
        assertEquals("SE", state.value.aggregate?.countryCode)

        state.replaceDnsResolverSnapshot(generation = 42L, samples = emptyList())
        assertEquals(42L, state.value.generation)
        assertNull(state.value.aggregate)

        state.replaceDnsResolverSnapshot(
            generation = 43L,
            samples = listOf(dnsSample("dns-fi-current", "FI", 43L)),
        )
        assertEquals(43L, state.value.generation)
        assertEquals("FI", state.value.aggregate?.countryCode)
    }

    @Test
    fun `runtime map stays evidence only while home uses a public resolver probe`() {
        val repository = source("guard/traffic/TrafficMapRepository.kt")
        val samples = source("guard/traffic/TrafficMapConnectionSamples.kt")
        val runtime = source("guard/runtime/FoxholeVpnServiceTrafficSupport.kt")
        val home = source("guard/ui/HomeViewModel.kt")
        val facts = source("guard/ui/cli/home/CliHomeFacts.kt")
        val resolver = source("guard/runtime/PublicDnsIdentityResolver.kt")

        assertFalse(repository.contains("dnsServerCountryProvider"))
        assertFalse(repository.contains("trafficMapDnsServerFallbackPoint"))
        assertFalse(samples.contains("dnsServerHost"))
        assertFalse(runtime.contains("dnsServerCountryProvider"))
        assertTrue(home.contains("publicDnsIdentityResolver"))
        assertTrue(home.contains("publicDnsIdentityMutable"))
        assertFalse(facts.contains("trafficMap.dnsServer"))
        assertTrue(resolver.contains("whoami.ds.akahelp.net"))
        assertTrue(resolver.contains("countryCodeForIpAddress(serverAddress)"))
    }
}

private fun dnsSample(
    id: String,
    countryCode: String,
    bytes: Long,
): TrafficMapConnectionSample =
    TrafficMapConnectionSample(
        connectionId = id,
        countryCode = countryCode,
        bytes = bytes,
        kind = TrafficMapConnectionSampleKind.DNS_SERVER,
    )

private fun ipInfo(countryCode: String): IpInfo =
    IpInfo(
        ip = "198.51.100.1",
        ipv4 = "198.51.100.1",
        countryCode = countryCode,
        countryName = countryCode,
        city = null,
        isp = null,
        fetchedAt = 1L,
    )

private fun point(countryCode: String): TrafficMapPoint {
    val coordinate = TrafficMapRepository.TrafficMapCountryCoordinates.getValue(countryCode)
    return TrafficMapPoint(
        countryCode = countryCode,
        label = coordinate.label,
        lat = coordinate.lat,
        lon = coordinate.lon,
        bytes = 1L,
        connections = 1,
    )
}

private fun source(path: String): String =
    listOf(
        File("src/main/kotlin/com/foxhole/$path"),
        File("app/src/main/kotlin/com/foxhole/$path"),
    ).first(File::isFile).readText()
