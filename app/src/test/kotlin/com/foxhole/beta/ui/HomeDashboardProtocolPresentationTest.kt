package com.foxhole.beta.ui

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import com.foxhole.beta.core.settings.smartStartEnabledProtocolSetHash
import com.foxhole.beta.vpn.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("LargeClass")
class HomeDashboardProtocolPresentationTest {
    @Test
    fun `auto connect uses live candidate list so dashboard selector matches analysis status`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "outline",
                protocolOptions =
                    listOf(
                        option("outline", ProtocolHint.OUTLINE),
                    ),
            )

        val resolved =
            resolveHomeDashboardProtocolPresentation(
                activeProfile = activeProfile,
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "trojan",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "trojan",
                                    displayName = "TROJAN",
                                    protocolHint = ProtocolHint.TROJAN,
                                ),
                                AutoConnectProbeOptionUiState(
                                    optionId = "outline",
                                    displayName = "OUTLINE",
                                    protocolHint = ProtocolHint.OUTLINE,
                                ),
                            ),
                    ),
            )

        assertEquals("trojan", resolved.selectedProtocolOptionId)
        assertEquals(ProtocolHint.TROJAN, resolved.protocolHint)
        assertEquals(listOf("trojan", "outline"), resolved.protocolOptions.map(ProfileProtocolOption::id))
        assertTrue(resolved.protocolOptions.first { option -> option.id == "trojan" }.isSelected)
    }

    @Test
    fun `smart start first analysis info is shown when no baseline exists`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "outline",
                protocolOptions =
                    listOf(
                        option("outline", ProtocolHint.OUTLINE),
                        option("trojan", ProtocolHint.TROJAN),
                    ),
            )

        assertTrue(
            shouldShowSmartStartFirstAnalysisInfo(
                HomeRouteUiState(
                    activeProfile = activeProfile,
                    settings = Settings(),
                ),
            ),
        )
    }

    @Test
    fun `smart start first analysis info is skipped after baseline exists`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "outline",
                protocolOptions =
                    listOf(
                        option("outline", ProtocolHint.OUTLINE),
                        option("trojan", ProtocolHint.TROJAN),
                    ),
            )
        val enabledProtocolSetHash = smartStartEnabledProtocolSetHash(listOf("outline", "trojan"))

        assertFalse(
            shouldShowSmartStartFirstAnalysisInfo(
                HomeRouteUiState(
                    activeProfile = activeProfile,
                    settings =
                        Settings(
                            smartProfilePreferences =
                                listOf(
                                    SmartProfilePreference(
                                        profileId = activeProfile.id,
                                        smartStartBaselineReady = true,
                                        recommendedProtocolIds = listOf("outline", "trojan"),
                                        enabledProtocolSetHash = enabledProtocolSetHash,
                                    ),
                                ),
                        ),
                ),
            ),
        )
    }

    @Test
    fun `manual metrics refresh keeps dashboard selector pinned to saved protocol`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "outline",
                protocolOptions =
                    listOf(
                        option("outline", ProtocolHint.OUTLINE),
                        option("trojan", ProtocolHint.TROJAN),
                    ),
            )

        val resolved =
            resolveHomeDashboardProtocolPresentation(
                activeProfile = activeProfile,
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "trojan",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "trojan",
                                    displayName = "TROJAN",
                                    protocolHint = ProtocolHint.TROJAN,
                                ),
                            ),
                    ),
                pinSelectionToProfile = true,
            )

        assertEquals("outline", resolved.selectedProtocolOptionId)
        assertEquals(ProtocolHint.OUTLINE, resolved.protocolHint)
        assertEquals(listOf("outline", "trojan"), resolved.protocolOptions.map(ProfileProtocolOption::id))
        assertTrue(resolved.protocolOptions.first { option -> option.id == "outline" }.isSelected)
    }

    @Test
    fun `idle dashboard keeps persisted protocol selection`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "outline",
                protocolOptions =
                    listOf(
                        option("outline", ProtocolHint.OUTLINE),
                        option("trojan", ProtocolHint.TROJAN),
                    ),
            )

        val resolved =
            resolveHomeDashboardProtocolPresentation(
                activeProfile = activeProfile,
                autoConnect = AutoConnectUiState(),
            )

        assertEquals("outline", resolved.selectedProtocolOptionId)
        assertEquals(ProtocolHint.OUTLINE, resolved.protocolHint)
        assertEquals(listOf("outline", "trojan"), resolved.protocolOptions.map(ProfileProtocolOption::id))
        assertTrue(resolved.protocolOptions.first { option -> option.id == "outline" }.isSelected)
    }

    @Test
    fun `connected dashboard keeps protocol presentation pinned to running connection`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "trojan",
                protocolOptions =
                    listOf(
                        option("outline", ProtocolHint.OUTLINE),
                        option("trojan", ProtocolHint.TROJAN),
                    ),
            )
        val connection =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                profileId = activeProfile.id,
                protocolHint = ProtocolHint.OUTLINE,
            )

        val resolved =
            resolveHomeDashboardProtocolPresentation(
                activeProfile = activeProfile,
                connection = connection,
                autoConnect = AutoConnectUiState(),
            )

        assertEquals("outline", resolved.selectedProtocolOptionId)
        assertEquals("outline", resolveDashboardLatencyOptionId(activeProfile, connection))
        assertEquals(ProtocolHint.OUTLINE, resolved.protocolHint)
        assertTrue(resolved.protocolOptions.first { option -> option.id == "outline" }.isSelected)
        assertFalse(resolved.protocolOptions.first { option -> option.id == "trojan" }.isSelected)
    }

    @Test
    fun `dashboard selected option falls back to the only supported protocol when selection is not persisted`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = null,
                protocolOptions =
                    listOf(
                        option("outline", ProtocolHint.OUTLINE),
                    ),
            )

        assertEquals("outline", resolveDashboardSelectedOptionId(activeProfile))
    }

    @Test
    fun `dashboard hides Smart start action for one supported protocol`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "vless",
                protocolHint = ProtocolHint.VLESS,
                protocolOptions = emptyList(),
                sourceType = ProfileSourceType.SHARE_URI,
            )

        assertFalse(shouldShowAutoConnectAction(activeProfile))
    }

    @Test
    fun `dashboard hides Smart start action for one explicit protocol option`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "vless",
                sourceType = ProfileSourceType.SHARE_URI,
                protocolOptions =
                    listOf(
                        option("vless", ProtocolHint.VLESS),
                    ),
            )

        assertFalse(shouldShowAutoConnectAction(activeProfile))
    }

    @Test
    fun `dashboard exposes Smart start action for subscription profile with one protocol`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "vless",
                protocolHint = ProtocolHint.VLESS,
                protocolOptions = emptyList(),
            )

        assertTrue(shouldShowAutoConnectAction(activeProfile))
    }

    @Test
    fun `dashboard exposes Smart start action for smart profile`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "vless",
                protocolOptions =
                    listOf(
                        option("vless", ProtocolHint.VLESS),
                        option("wireguard", ProtocolHint.WIREGUARD),
                    ),
            )

        assertTrue(shouldShowAutoConnectAction(activeProfile))
    }

    @Test
    fun `dashboard selected option keeps explicit saved selection`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "trojan",
                protocolOptions =
                    listOf(
                        option("outline", ProtocolHint.OUTLINE),
                        option("trojan", ProtocolHint.TROJAN),
                    ),
            )

        assertEquals("trojan", resolveDashboardSelectedOptionId(activeProfile))
    }

    @Test
    fun `dashboard latency option falls back to protocol hint for single profile without protocol options`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = null,
                protocolHint = ProtocolHint.SHADOWSOCKS,
                protocolOptions = emptyList(),
            )

        assertEquals("shadowsocks", resolveDashboardLatencyOptionId(activeProfile))
    }

    @Test
    fun `dashboard auto connect latency ignores failed probe duration and keeps down state`() {
        val state =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTING),
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "trojan",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "trojan",
                                    displayName = "TROJAN",
                                    protocolHint = ProtocolHint.TROJAN,
                                    status = AutoConnectProbeStatus.FAILED,
                                    latencyMs = 30_000L,
                                ),
                            ),
                    ),
            )

        assertNull(resolveDashboardSelectedLatencyMs(state))
        assertTrue(resolveDashboardSelectedLatencyDown(state))
    }

    @Test
    fun `dashboard auto connect does not fall back to stale success ping when current candidate is down`() {
        val state =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTING),
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "trojan",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "outline",
                                    displayName = "OUTLINE",
                                    protocolHint = ProtocolHint.OUTLINE,
                                    status = AutoConnectProbeStatus.SUCCESS,
                                    latencyMs = 184L,
                                ),
                                AutoConnectProbeOptionUiState(
                                    optionId = "trojan",
                                    displayName = "TROJAN",
                                    protocolHint = ProtocolHint.TROJAN,
                                    status = AutoConnectProbeStatus.FAILED,
                                    latencyMs = 30_000L,
                                ),
                            ),
                    ),
            )

        assertNull(resolveDashboardSelectedLatencyMs(state))
        assertTrue(resolveDashboardSelectedLatencyDown(state))
    }

    @Test
    fun `dashboard auto connect does not show stale success latency for current testing candidate`() {
        val state =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTING),
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "trojan",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "outline",
                                    displayName = "OUTLINE",
                                    protocolHint = ProtocolHint.OUTLINE,
                                    status = AutoConnectProbeStatus.SUCCESS,
                                    latencyMs = 184L,
                                ),
                                AutoConnectProbeOptionUiState(
                                    optionId = "trojan",
                                    displayName = "TROJAN",
                                    protocolHint = ProtocolHint.TROJAN,
                                    status = AutoConnectProbeStatus.TESTING,
                                ),
                            ),
                    ),
            )

        assertNull(resolveDashboardSelectedLatencyMs(state))
        assertFalse(resolveDashboardSelectedLatencyDown(state))
        assertFalse(resolveDashboardSelectedLatencyUnavailable(state))
    }

    @Test
    fun `manual metrics refresh shows current smart start probe state`() {
        val state =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTED),
                selectedProtocolLatencyMs = 222L,
                protocolMetricsRefreshing = true,
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "trojan",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "trojan",
                                    displayName = "TROJAN",
                                    protocolHint = ProtocolHint.TROJAN,
                                    status = AutoConnectProbeStatus.FAILED,
                                    latencyMs = 30_000L,
                                ),
                            ),
                    ),
            )

        assertNull(resolveDashboardSelectedLatencyMs(state))
        assertTrue(resolveDashboardSelectedLatencyDown(state))
    }

    @Test
    fun `dashboard auto connect latency keeps successful probe value`() {
        val state =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTING),
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "outline",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "outline",
                                    displayName = "OUTLINE",
                                    protocolHint = ProtocolHint.OUTLINE,
                                    status = AutoConnectProbeStatus.SUCCESS,
                                    latencyMs = 184L,
                                ),
                            ),
                    ),
            )

        assertEquals(184L, resolveDashboardSelectedLatencyMs(state))
    }

    @Test
    fun `dashboard auto connect shows unavailable when current candidate has no request latency`() {
        val state =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTING),
                autoConnect =
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = "outline",
                        options =
                            listOf(
                                AutoConnectProbeOptionUiState(
                                    optionId = "outline",
                                    displayName = "OUTLINE",
                                    protocolHint = ProtocolHint.OUTLINE,
                                    status = AutoConnectProbeStatus.SUCCESS,
                                    latencyUnavailable = true,
                                ),
                            ),
                    ),
            )

        assertNull(resolveDashboardSelectedLatencyMs(state))
        assertFalse(resolveDashboardSelectedLatencyDown(state))
        assertTrue(resolveDashboardSelectedLatencyUnavailable(state))
    }

    @Test
    fun `connected dashboard shows unavailable when request latency refresh failed`() {
        val state =
            HomeRouteUiState(
                connection = ConnectionSnapshot(state = ConnectionState.CONNECTED),
                selectedProtocolLatencyUnavailable = true,
            )

        assertNull(resolveDashboardSelectedLatencyMs(state))
        assertFalse(resolveDashboardSelectedLatencyDown(state))
        assertTrue(resolveDashboardSelectedLatencyUnavailable(state))
    }

    @Test
    fun `connected dashboard waits for latency before rendering final network text`() {
        assertFalse(
            shouldRenderDashboardConnectionDetails(
                connectionState = ConnectionState.CONNECTED,
                activeProfile = profile(selectedProtocolOptionId = "outline", protocolOptions = listOf(option("outline", ProtocolHint.OUTLINE))),
                selectedLatencyMs = null,
                selectedLatencyDown = false,
                selectedLatencyUnavailable = false,
                selectedServerPingMs = 87L,
                selectedServerPingUnavailable = false,
                selectedServerPingUnsupported = false,
            ),
        )
    }

    @Test
    fun `connected dashboard renders when latency and server ping states are complete`() {
        assertTrue(
            shouldRenderDashboardConnectionDetails(
                connectionState = ConnectionState.CONNECTED,
                activeProfile = profile(selectedProtocolOptionId = "outline", protocolOptions = listOf(option("outline", ProtocolHint.OUTLINE))),
                selectedLatencyMs = 430L,
                selectedLatencyDown = false,
                selectedLatencyUnavailable = false,
                selectedServerPingMs = null,
                selectedServerPingUnavailable = false,
                selectedServerPingUnsupported = true,
            ),
        )
    }

    @Test
    fun `profiles route exposes currently refreshed smart protocol for menu highlight`() {
        val state =
            HomeUiState(
                profiles =
                    listOf(
                        profile(
                            selectedProtocolOptionId = "outline",
                            protocolOptions =
                                listOf(
                                    option("outline", ProtocolHint.OUTLINE),
                                    option("trojan", ProtocolHint.TROJAN),
                                ),
                        ),
                    ),
            )

        val resolved =
            buildProfilesRouteUiState(
                state = state,
                autoConnect = AutoConnectUiState(),
                protocolMetrics =
                    ProtocolMetricsUiState(
                        refreshingProfileIds = setOf(1L),
                        refreshingOptionIdByProfileId = mapOf(1L to "trojan"),
                    ),
                networkFingerprintKey = null,
            )

        assertEquals(mapOf(1L to "trojan"), resolved.smartProfileMetricsRefreshingOptionIdByProfileId)
    }

    @Test
    fun `profiles route mirrors dashboard reconnect latency refresh for active profile`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "wireguard",
                protocolOptions =
                    listOf(
                        option("trojan", ProtocolHint.TROJAN),
                        option("wireguard", ProtocolHint.WIREGUARD),
                    ),
            )
        val state =
            HomeUiState(
                profiles = listOf(activeProfile),
                activeProfile = activeProfile,
                connection = ConnectionSnapshot(state = ConnectionState.RECONNECTING),
                dashboardConnectionMetricsLoading = true,
            )

        val resolved =
            buildProfilesRouteUiState(
                state = state,
                autoConnect = AutoConnectUiState(),
                protocolMetrics = ProtocolMetricsUiState(),
                networkFingerprintKey = null,
            )

        assertEquals(setOf(1L), resolved.smartProfileMetricsRefreshingProfileIds)
        assertEquals(mapOf(1L to "wireguard"), resolved.smartProfileMetricsRefreshingOptionIdByProfileId)
    }

    @Test
    fun `profiles route lets live unavailable state replace stale remembered latency`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "vless",
                protocolOptions =
                    listOf(
                        option("vless", ProtocolHint.VLESS),
                        option("trojan", ProtocolHint.TROJAN),
                    ),
            )
        val state =
            HomeUiState(
                profiles = listOf(activeProfile),
                activeProfile = activeProfile,
                settings =
                    Settings(
                        smartProfilePreferences =
                            listOf(
                                SmartProfilePreference(
                                    profileId = 1L,
                                    protocolMemories =
                                        listOf(
                                            SmartProfileProtocolMemory(
                                                optionId = "vless",
                                                lastLatencyMs = 240L,
                                                lastSuccessAt = System.currentTimeMillis(),
                                            ),
                                        ),
                                ),
                            ),
                    ),
            )

        val resolved =
            buildProfilesRouteUiState(
                state = state,
                autoConnect = AutoConnectUiState(),
                protocolMetrics = ProtocolMetricsUiState(),
                profileOptionLatencies = emptyMap(),
                profileOptionLatencyUnavailable = setOf(ProfileOptionLatencyKey(1L, "vless")),
                networkFingerprintKey = null,
            )

        assertEquals(setOf("vless"), resolved.smartProfileLatencyUnavailable(1L))
        assertNull(resolved.smartStartRememberedLatency(1L)["vless"])
    }

    @Test
    fun `profiles route renders remembered latency blocked state as unavailable not no data`() {
        val now = System.currentTimeMillis()
        val activeProfile =
            profile(
                selectedProtocolOptionId = "vless",
                protocolOptions =
                    listOf(
                        option("vless", ProtocolHint.VLESS),
                        option("trojan", ProtocolHint.TROJAN),
                    ),
            )
        val state =
            HomeUiState(
                profiles = listOf(activeProfile),
                activeProfile = activeProfile,
                settings =
                    Settings(
                        smartProfilePreferences =
                            listOf(
                                SmartProfilePreference(
                                    profileId = 1L,
                                    recommendedProtocolIds = listOf("vless", "trojan"),
                                    protocolMemories =
                                        listOf(
                                            SmartProfileProtocolMemory(
                                                optionId = "vless",
                                                lastSuccessAt = now - 1_000L,
                                                lastLatencyMs = null,
                                                lastReasonCode = AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED,
                                            ),
                                            SmartProfileProtocolMemory(
                                                optionId = "trojan",
                                                lastSuccessAt = now - 1_000L,
                                                lastLatencyMs = 180L,
                                            ),
                                        ),
                                ),
                            ),
                    ),
            )

        val resolved =
            buildProfilesRouteUiState(
                state = state,
                autoConnect = AutoConnectUiState(),
                protocolMetrics = ProtocolMetricsUiState(),
                networkFingerprintKey = null,
            )

        assertEquals(setOf("vless"), resolved.smartProfileLatencyUnavailable(1L))
        assertEquals(mapOf("trojan" to 180L), resolved.smartStartRememberedLatency(1L))
        assertEquals(mapOf(1L to "trojan"), resolved.recommendedProtocolOptionByProfileId)
        assertEquals(mapOf(1L to setOf("trojan")), resolved.recommendedProtocolOptionsByProfileId)
    }

    @Test
    fun `home route exposes currently refreshed smart protocol for dashboard menu`() {
        val state =
            HomeUiState(
                activeProfile =
                    profile(
                        selectedProtocolOptionId = "outline",
                        protocolOptions =
                            listOf(
                                option("outline", ProtocolHint.OUTLINE),
                                option("wireguard", ProtocolHint.WIREGUARD),
                            ),
                    ),
            )

        val resolved =
            buildHomeRouteUiState(
                state = state,
                autoConnect = AutoConnectUiState(),
                profileOptionLatencies = emptyMap(),
                profileOptionLatencyUnavailable = emptySet(),
                protocolMetrics =
                    ProtocolMetricsUiState(
                        refreshingProfileIds = setOf(1L),
                        refreshingOptionIdByProfileId = mapOf(1L to "wireguard"),
                    ),
                currentNetworkFingerprintKey = null,
            )

        assertTrue(resolved.protocolMetricsRefreshing)
        assertEquals("wireguard", resolved.protocolMetricsRefreshingOptionId)
    }

    @Test
    fun `analysis status label can render current protocol without falling back to Smart start`() {
        listOf(
            ProtocolHint.OUTLINE to "OUTLINE",
            ProtocolHint.WIREGUARD to "WIREGUARD",
            ProtocolHint.SHADOWSOCKS to "SHADOWSOCKS",
        ).forEach { (protocol, expectedLabel) ->
            val label =
                autoConnectAnalysisProtocolLabel(
                    AutoConnectUiState(
                        running = true,
                        currentOptionId = protocol.name.lowercase(),
                        currentProtocolHint = protocol,
                        options = emptyList(),
                    ),
                )

            assertEquals(expectedLabel, label)
        }
    }

    @Test
    fun `connected protocol option id drives dashboard selected option`() {
        val activeProfile =
            profile(
                selectedProtocolOptionId = "vless-b",
                protocolHint = ProtocolHint.VLESS,
                protocolOptions =
                    listOf(
                        option("vless-a", ProtocolHint.VLESS),
                        option("vless-b", ProtocolHint.VLESS),
                    ),
            )

        val selectedOptionId =
            resolveDashboardSelectedOptionId(
                activeProfile = activeProfile,
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = 1L,
                        protocolHint = ProtocolHint.VLESS,
                        protocolOptionId = "vless-a",
                    ),
            )

        assertEquals("vless-a", selectedOptionId)
    }

    @Test
    fun `dashboard remembered latency keeps connected card from showing unavailable`() {
        val resolved =
            resolveDashboardLatencyPresentation(
                HomeRouteUiState(
                    activeProfile =
                        profile(
                            selectedProtocolOptionId = "outline",
                            protocolOptions = listOf(option("outline", ProtocolHint.OUTLINE)),
                        ),
                    connection =
                        ConnectionSnapshot(
                            state = ConnectionState.CONNECTED,
                            profileId = 1L,
                            protocolHint = ProtocolHint.OUTLINE,
                        ),
                    selectedProtocolLatencyUnavailable = true,
                    smartStartRememberedLatenciesByOptionId = mapOf("outline" to 426L),
                ),
            )

        assertEquals(426L, resolved.latencyMs)
        assertFalse(resolved.isUnavailable)
        assertFalse(resolved.isDown)
    }

    @Test
    fun `local guard firewall does not show stale vpn profile latency`() {
        val resolved =
            resolveDashboardLatencyPresentation(
                HomeRouteUiState(
                    activeProfile =
                        profile(
                            selectedProtocolOptionId = "outline",
                            protocolOptions = listOf(option("outline", ProtocolHint.OUTLINE)),
                        ),
                    connection =
                        ConnectionSnapshot(
                            state = ConnectionState.CONNECTED,
                            profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                            protocolHint = ProtocolHint.SING_BOX,
                        ),
                    selectedProtocolLatencyMs = 426L,
                    smartStartRememberedLatenciesByOptionId = mapOf("outline" to 426L),
                    protocolDownOptionIds = setOf("outline"),
                    selectedProtocolLatencyUnavailable = true,
                ),
            )

        assertNull(resolved.latencyMs)
        assertFalse(resolved.isDown)
        assertFalse(resolved.isUnavailable)
    }

    @Test
    fun `dashboard live latency wins over stale down status`() {
        val resolved =
            resolveDashboardLatencyPresentation(
                HomeRouteUiState(
                    activeProfile =
                        profile(
                            selectedProtocolOptionId = "outline",
                            protocolOptions = listOf(option("outline", ProtocolHint.OUTLINE)),
                        ),
                    connection =
                        ConnectionSnapshot(
                            state = ConnectionState.CONNECTED,
                            profileId = 1L,
                            protocolHint = ProtocolHint.OUTLINE,
                        ),
                    selectedProtocolLatencyMs = 426L,
                    selectedProtocolLatencyUnavailable = true,
                    protocolDownOptionIds = setOf("outline"),
                ),
            )

        assertEquals(426L, resolved.latencyMs)
        assertFalse(resolved.isDown)
        assertFalse(resolved.isUnavailable)
    }

    @Test
    fun `dashboard down status is shown after refresh when no live latency is available`() {
        val resolved =
            resolveDashboardLatencyPresentation(
                HomeRouteUiState(
                    activeProfile =
                        profile(
                            selectedProtocolOptionId = "outline",
                            protocolOptions = listOf(option("outline", ProtocolHint.OUTLINE)),
                        ),
                    connection =
                        ConnectionSnapshot(
                            state = ConnectionState.CONNECTED,
                            profileId = 1L,
                            protocolHint = ProtocolHint.OUTLINE,
                        ),
                    protocolDownOptionIds = setOf("outline"),
                ),
            )

        assertNull(resolved.latencyMs)
        assertTrue(resolved.isDown)
        assertFalse(resolved.isUnavailable)
    }

    @Test
    fun `dashboard latency waits instead of showing down while selected protocol refreshes`() {
        val resolved =
            resolveDashboardLatencyPresentation(
                HomeRouteUiState(
                    activeProfile =
                        profile(
                            selectedProtocolOptionId = "outline",
                            protocolOptions = listOf(option("outline", ProtocolHint.OUTLINE)),
                        ),
                    connection =
                        ConnectionSnapshot(
                            state = ConnectionState.CONNECTED,
                            profileId = 1L,
                            protocolHint = ProtocolHint.OUTLINE,
                        ),
                    protocolMetricsRefreshing = true,
                    protocolMetricsRefreshingOptionId = "outline",
                    protocolDownOptionIds = setOf("outline"),
                    selectedProtocolLatencyUnavailable = true,
                ),
            )

        assertNull(resolved.latencyMs)
        assertFalse(resolved.isDown)
        assertFalse(resolved.isUnavailable)
    }

    private fun profile(
        selectedProtocolOptionId: String?,
        protocolHint: ProtocolHint = ProtocolHint.OUTLINE,
        protocolOptions: List<ProfileProtocolOption>,
        sourceType: ProfileSourceType = ProfileSourceType.SUBSCRIPTION_URL,
    ) = Profile(
        id = 1L,
        name = "Foxhole vpn direct",
        sourceType = sourceType,
        secretRef = "secret-1",
        protocolHint = protocolHint,
        lastUpdatedAt = null,
        lastEtag = null,
        subscriptionExpiresAt = null,
        protocolOptions = protocolOptions,
        selectedProtocolOptionId = selectedProtocolOptionId,
        isActive = true,
    )

    private fun option(
        id: String,
        protocolHint: ProtocolHint,
    ) = ProfileProtocolOption(
        id = id,
        displayName = protocolHint.name,
        protocolHint = protocolHint,
        isSelected = false,
    )
}
