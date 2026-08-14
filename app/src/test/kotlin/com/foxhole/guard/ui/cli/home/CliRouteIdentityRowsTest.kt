package com.foxhole.guard.ui.cli.home

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TorPhaseSnapshot
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.TorIdentityProbePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliRouteIdentityRowsTest {
    @Test
    fun `vpn and tor identities stay separate in a combined route`() {
        val vpn = ipInfo(ip = "198.51.100.10", countryCode = "us", city = "New York")
        val tor = ipInfo(ip = "203.0.113.20", countryCode = "de")
        val identities =
            cliRouteIdentities(
                home = HomeRouteUiState(ipInfo = vpn, torIpInfo = tor),
                runtimes = CliActiveRuntimes(vpn = true, proxy = false, tor = true, torBesideVpn = false, i2p = false),
            )

        assertEquals(
            listOf(
                CliRouteIdentity(CliRouteIdentityKind.VPN, vpn),
                CliRouteIdentity(CliRouteIdentityKind.TOR, tor.copy(countryCode = "DE")),
            ),
            identities,
        )
        assertEquals("US · New York · 198.51.100.10", cliRouteIdentityValue(vpn))
        assertEquals("DE · 203.0.113.20", cliRouteIdentityValue(tor, includeCity = false))
    }

    @Test
    fun `missing identity remains an explicit placeholder`() {
        assertEquals("—", cliRouteIdentityValue(null))
    }

    @Test
    fun `dns identity keeps protocol country and address order`() {
        assertEquals("DoH · US · 1.1.1.1", cliDnsIdentityValue("DoH", "us", "1.1.1.1"))
        assertEquals("DoT · dns.example", cliDnsIdentityValue("DoT", null, "dns.example"))
    }

    @Test
    fun `tor identity activity follows runtime and probe truth only`() {
        val absent: IpInfo? = null
        assertFalse(
            cliTorIdentityLoading(TorPhaseSnapshot(), TorIdentityProbePhase.IDLE, absent),
        )
        assertTrue(
            cliTorIdentityLoading(
                TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTING),
                TorIdentityProbePhase.IDLE,
                absent,
            ),
        )
        assertTrue(
            cliTorIdentityLoading(
                TorPhaseSnapshot(phase = TorNetworkPhase.BUILDING_CIRCUITS),
                TorIdentityProbePhase.LOOKING_UP,
                absent,
            ),
        )
        assertTrue(
            cliTorIdentityLoading(
                TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
                TorIdentityProbePhase.LOOKING_UP,
                absent,
            ),
        )
        assertFalse(
            cliTorIdentityLoading(
                TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
                TorIdentityProbePhase.FAILED,
                absent,
            ),
        )
        assertFalse(
            cliTorIdentityLoading(
                TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
                TorIdentityProbePhase.CANCELLED,
                absent,
            ),
        )
        assertFalse(
            cliTorIdentityLoading(
                TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
                TorIdentityProbePhase.CONFIRMED,
                ipInfo(ip = "203.0.113.20", countryCode = "de"),
            ),
        )
    }

    @Test
    fun `selected tor mode keeps one idle slot without inventing activity`() {
        assertTrue(
            cliTorIdentitySlotVisible(
                torModeEnabled = true,
                torLoading = false,
                hasTorIdentityRow = false,
            ),
        )
        assertTrue(
            cliTorIdentitySlotVisible(
                torModeEnabled = false,
                torLoading = true,
                hasTorIdentityRow = false,
            ),
        )
        assertFalse(
            cliTorIdentitySlotVisible(
                torModeEnabled = true,
                torLoading = true,
                hasTorIdentityRow = true,
            ),
        )
    }

    @Test
    fun `vpn tor tunnel and proxy keep separate live spinners until each identity arrives`() {
        listOf(TrafficMode.TUNNEL, TrafficMode.PROXY).forEach { trafficMode ->
            val connecting =
                HomeRouteUiState(
                    connection = route(ConnectionState.CONNECTING, trafficMode, torActive = true),
                    torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTING),
                )
            val connectingFacts =
                cliRouteIdentityFactStates(
                    home = connecting,
                    runtimes = activeRuntimes(connecting, torOnlyLive = false),
                    identityLoading = true,
                    torIdentityProbePhase = TorIdentityProbePhase.IDLE,
                )

            assertEquals(
                "$trafficMode connecting slots",
                listOf(CliRouteIdentityKind.VPN, CliRouteIdentityKind.TOR),
                connectingFacts.map { it.identity.kind },
            )
            assertTrue("$trafficMode VPN identity must be pending", connectingFacts[0].loading)
            assertTrue("$trafficMode TOR phase must be pending", connectingFacts[1].loading)

            val vpnProven =
                connecting.copy(
                    connection = route(ConnectionState.CONNECTED, trafficMode, torActive = true),
                    ipInfo = ipInfo(ip = "198.51.100.10", countryCode = "us"),
                    torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
                )
            val vpnProvenFacts =
                cliRouteIdentityFactStates(
                    home = vpnProven,
                    runtimes = activeRuntimes(vpnProven, torOnlyLive = false),
                    identityLoading = false,
                    torIdentityProbePhase = TorIdentityProbePhase.LOOKING_UP,
                )

            assertFalse("$trafficMode VPN spinner must end with identity", vpnProvenFacts[0].loading)
            assertTrue("$trafficMode TOR spinner must wait for exit identity", vpnProvenFacts[1].loading)

            val bothProven =
                vpnProven.copy(torIpInfo = ipInfo(ip = "203.0.113.20", countryCode = "de"))
            val bothProvenFacts =
                cliRouteIdentityFactStates(
                    home = bothProven,
                    runtimes = activeRuntimes(bothProven, torOnlyLive = false),
                    identityLoading = false,
                    torIdentityProbePhase = TorIdentityProbePhase.CONFIRMED,
                )
            assertFalse(bothProvenFacts.any(CliRouteIdentityFactState::loading))
        }
    }

    @Test
    fun `tor only and combined routes share real phase loading while settings alone stay static`() {
        val torOnly =
            HomeRouteUiState(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTING,
                    profileId = TOR_ONLY_PROFILE_ID,
                ),
                torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.BUILDING_CIRCUITS),
            )
        val torOnlyFacts =
            cliRouteIdentityFactStates(
                home = torOnly,
                runtimes = activeRuntimes(torOnly, torOnlyLive = false),
                identityLoading = false,
                torIdentityProbePhase = TorIdentityProbePhase.IDLE,
            )
        assertEquals(listOf(CliRouteIdentityKind.TOR), torOnlyFacts.map { it.identity.kind })
        assertTrue(torOnlyFacts.single().loading)

        val configuredOnly =
            HomeRouteUiState(
                settings = Settings(
                    privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN),
                ),
            )
        val configuredFacts =
            cliRouteIdentityFactStates(
                home = configuredOnly,
                runtimes = activeRuntimes(configuredOnly, torOnlyLive = false),
                identityLoading = false,
                torIdentityProbePhase = TorIdentityProbePhase.LOOKING_UP,
            )
        assertFalse(configuredFacts.single().live)
        assertFalse(configuredFacts.single().loading)
    }

    @Test
    fun `tor probe failure and offline phase always clear identity spinner`() {
        val connectedTor =
            HomeRouteUiState(
                connection = route(ConnectionState.CONNECTED, TrafficMode.PROXY, torActive = true),
                ipInfo = ipInfo(ip = "198.51.100.10", countryCode = "us"),
                torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
            )
        val failed =
            cliRouteIdentityFactStates(
                home = connectedTor,
                runtimes = activeRuntimes(connectedTor, torOnlyLive = false),
                identityLoading = false,
                torIdentityProbePhase = TorIdentityProbePhase.FAILED,
            )
        assertFalse(failed.single { it.identity.kind == CliRouteIdentityKind.TOR }.loading)

        val offline = connectedTor.copy(torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.OFFLINE))
        val offlineFacts =
            cliRouteIdentityFactStates(
                home = offline,
                runtimes = activeRuntimes(offline, torOnlyLive = false),
                identityLoading = false,
                torIdentityProbePhase = TorIdentityProbePhase.IDLE,
            )
        assertFalse(offlineFacts.single { it.identity.kind == CliRouteIdentityKind.TOR }.loading)
    }

    @Test
    fun `latency spinner follows only a measurable vpn refresh`() {
        val vpnRefreshing =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTED, profileId = 7L),
                dashboardConnectionMetricsLoading = true,
            )
        val vpnTorRefreshing =
            vpnRefreshing.copy(connection = vpnRefreshing.connection.copy(torActive = true))

        assertTrue(cliHomeLatencyLoading(vpnRefreshing, connected = true))
        assertTrue(cliHomeLatencyLoading(vpnTorRefreshing, connected = true))
        assertFalse(cliHomeLatencyLoading(vpnRefreshing, connected = false))
        assertFalse(cliHomeLatencyLoading(HomeRouteUiState(), connected = true))
    }

    @Test
    fun `tor only latency is terminal unavailable even when metrics loading is stale`() {
        val sentinelTorOnly =
            HomeRouteUiState(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = TOR_ONLY_PROFILE_ID,
                ),
                dashboardConnectionMetricsLoading = true,
            )
        val legacyTorOnly =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
                dashboardConnectionMetricsLoading = true,
            )

        listOf(sentinelTorOnly to true, legacyTorOnly to false).forEach { (home, connected) ->
            assertFalse(cliHomeLatencyLoading(home, connected))
            assertTrue(cliHomeLatencyUnavailable(home, connected))
        }
    }

    @Test
    fun `vpn and vpn tor display the selected public route probe`() {
        val profile = latencyProfile()
        val vpn =
            HomeRouteUiState(
                activeProfile = profile,
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = profile.id,
                    protocolOptionId = "vless",
                ),
                protocolTunnelPingsByOptionId = mapOf("vless" to 84L),
            )
        val vpnTor = vpn.copy(connection = vpn.connection.copy(torActive = true))

        assertEquals(84L, cliHomeLatencyMs(vpn, connected = true))
        assertEquals(84L, cliHomeLatencyMs(vpnTor, connected = true))
        assertFalse(cliHomeLatencyUnavailable(vpn, connected = true))
        assertFalse(cliHomeLatencyUnavailable(vpnTor, connected = true))
    }

    @Test
    fun `failed bounded vpn probe becomes unavailable after loading lease ends`() {
        val profile = latencyProfile()
        val failed =
            HomeRouteUiState(
                activeProfile = profile,
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = profile.id,
                    protocolOptionId = "vless",
                ),
                protocolTunnelPingUnavailableOptionIds = setOf("vless"),
            )

        assertTrue(cliHomeLatencyUnavailable(failed, connected = true))
        assertFalse(
            cliHomeLatencyUnavailable(
                failed.copy(dashboardConnectionMetricsLoading = true),
                connected = true,
            ),
        )
        assertFalse(cliHomeLatencyUnavailable(failed, connected = false))
    }

    @Test
    fun `disconnect clears tor only latency unavailable presentation`() {
        val disconnected =
            HomeRouteUiState(
                connection =
                ConnectionSnapshot(
                    state = ConnectionState.IDLE,
                    profileId = TOR_ONLY_PROFILE_ID,
                ),
            )

        assertFalse(cliHomeLatencyLoading(disconnected, connected = false))
        assertFalse(cliHomeLatencyUnavailable(disconnected, connected = false))
    }

    @Test
    fun `vpn plus tor exposes both real identity rows`() {
        val vpnTor =
            HomeRouteUiState(
                connection = route(ConnectionState.CONNECTED, TrafficMode.TUNNEL, torActive = true),
                torPhase = TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
            )
        val facts =
            cliRouteIdentityFactStates(
                home = vpnTor,
                runtimes = activeRuntimes(vpnTor, torOnlyLive = false),
                identityLoading = false,
                torIdentityProbePhase = TorIdentityProbePhase.CONFIRMED,
            )

        assertEquals(2, facts.size)
        assertEquals(
            listOf(CliRouteIdentityKind.VPN, CliRouteIdentityKind.TOR),
            facts.map { fact -> fact.identity.kind },
        )
    }

    @Test
    fun `identity rows do not reserve an invisible tor line and spinner text swaps share one row floor`() {
        val identity = source("home/CliRouteIdentityRows.kt")
        val keyValue = source("components/CliText.kt")
        val spinner = source("components/CliSpinner.kt")
        val keyValueBlock =
            keyValue
                .substringAfter("internal fun CliKeyValue(")
                .substringBefore("/** Informational sub-row")

        assertFalse(identity.contains("CliRouteIdentitySizingGhost"))
        assertFalse(identity.contains("CLI_ROUTE_IDENTITY_RESERVED_ROW_COUNT"))
        assertTrue(spinner.contains("internal val cliSpinnerSlotSize = 16.dp"))
        assertTrue(keyValueBlock.contains(".defaultMinSize(minHeight = cliSpinnerSlotSize)"))
    }

    private fun ipInfo(
        ip: String,
        countryCode: String,
        city: String? = null,
    ) = IpInfo(
        ip = ip,
        countryCode = countryCode,
        countryName = null,
        city = city,
        isp = null,
        fetchedAt = 0L,
    )

    private fun route(
        state: ConnectionState,
        trafficMode: TrafficMode,
        torActive: Boolean,
    ) = ConnectionSnapshot(
        state = state,
        trafficMode = trafficMode,
        profileId = 7L,
        profileName = "fox",
        torActive = torActive,
    )

    private fun latencyProfile(): Profile =
        Profile(
            id = 7L,
            name = "VPN",
            sourceType = ProfileSourceType.SUBSCRIPTION_URL,
            secretRef = "secret",
            protocolHint = ProtocolHint.VLESS,
            lastUpdatedAt = null,
            lastEtag = null,
            protocolOptions =
            listOf(
                ProfileProtocolOption(
                    id = "vless",
                    displayName = "VLESS",
                    protocolHint = ProtocolHint.VLESS,
                    isSelected = true,
                ),
            ),
            selectedProtocolOptionId = "vless",
            isActive = true,
        )

    private fun source(relative: String): String =
        File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative").readText()
}
