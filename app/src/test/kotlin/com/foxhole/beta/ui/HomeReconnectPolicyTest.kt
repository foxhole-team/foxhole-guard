package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.vpn.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeReconnectPolicyTest {
    @Test
    fun `profile reconnect prompt window is thirteen seconds`() {
        assertTrue(HomeViewModel.PROFILE_RECONNECT_PROMPT_WINDOW_MS == 13_000L)
    }

    @Test
    fun `home route carries reconnect prompt deadline`() {
        val routeState =
            HomeUiState(
                reconnectRequired = true,
                profileReconnectPromptUntilElapsedMs = 42_000L,
            ).toHomeRouteUiState()

        assertTrue(routeState.reconnectRequired)
        assertTrue(routeState.profileReconnectPromptUntilElapsedMs == 42_000L)
    }

    @Test
    fun `switching smart profile protocol while connected requires reconnect`() {
        assertTrue(
            isProfileReconnectRequired(
                activeProfile = profile(id = 7L, protocolHint = ProtocolHint.TROJAN),
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = 7L,
                        protocolHint = ProtocolHint.OUTLINE,
                    ),
            ),
        )
    }

    @Test
    fun `matching connected protocol keeps reconnect cleared`() {
        assertFalse(
            isProfileReconnectRequired(
                activeProfile = profile(id = 7L, protocolHint = ProtocolHint.OUTLINE),
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = 7L,
                        protocolHint = ProtocolHint.OUTLINE,
                    ),
            ),
        )
    }

    @Test
    fun `switching active profile still requires reconnect`() {
        assertTrue(
            isProfileReconnectRequired(
                activeProfile = profile(id = 8L, protocolHint = ProtocolHint.OUTLINE),
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = 7L,
                        protocolHint = ProtocolHint.OUTLINE,
                    ),
            ),
        )
    }

    @Test
    fun `switching selected option with same protocol requires reconnect`() {
        assertTrue(
            isProfileReconnectRequired(
                activeProfile =
                    profile(
                        id = 7L,
                        protocolHint = ProtocolHint.VLESS,
                        selectedProtocolOptionId = "vless-b",
                        protocolOptions =
                            listOf(
                                option("vless-a", ProtocolHint.VLESS),
                                option("vless-b", ProtocolHint.VLESS),
                            ),
                    ),
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = 7L,
                        protocolHint = ProtocolHint.VLESS,
                        protocolOptionId = "vless-a",
                    ),
            ),
        )
    }

    @Test
    fun `matching selected option keeps reconnect cleared`() {
        assertFalse(
            isProfileReconnectRequired(
                activeProfile =
                    profile(
                        id = 7L,
                        protocolHint = ProtocolHint.VLESS,
                        selectedProtocolOptionId = "vless-a",
                        protocolOptions = listOf(option("vless-a", ProtocolHint.VLESS)),
                    ),
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = 7L,
                        protocolHint = ProtocolHint.VLESS,
                        protocolOptionId = "vless-a",
                    ),
            ),
        )
    }

    @Test
    fun `local guard runtime does not ask to reconnect selected profile`() {
        assertFalse(
            isProfileReconnectRequired(
                activeProfile = profile(id = 7L, protocolHint = ProtocolHint.VLESS),
                connection =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                        protocolHint = ProtocolHint.SING_BOX,
                    ),
            ),
        )
    }

    @Test
    fun `reconnect in progress owns top dashboard status`() {
        assertEquals(
            ConnectionState.RECONNECTING,
            homeTopStatusState(
                HomeRouteUiState(
                    connection = ConnectionSnapshot(state = ConnectionState.IDLE),
                    reconnectInProgress = true,
                ),
            ),
        )
    }

    @Test
    fun `runtime reload target ignores local guard and inactive profiles`() {
        assertEquals(
            7L,
            resolveActiveRuntimeProfileIdForReload(
                snapshot =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        trafficMode = TrafficMode.TUNNEL,
                        profileId = 7L,
                    ),
                activeProfileId = 7L,
            ),
        )
        assertEquals(
            null,
            resolveActiveRuntimeProfileIdForReload(
                snapshot =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        trafficMode = TrafficMode.TUNNEL,
                        profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                    ),
                activeProfileId = 7L,
            ),
        )
        assertEquals(
            null,
            resolveActiveRuntimeProfileIdForReload(
                snapshot =
                    ConnectionSnapshot(
                        state = ConnectionState.IDLE,
                        trafficMode = TrafficMode.TUNNEL,
                        profileId = 7L,
                    ),
                activeProfileId = 7L,
            ),
        )
    }

    @Test
    fun `standalone tor runtime remains a reload target while active`() {
        assertEquals(
            FoxholeVpnService.TOR_ONLY_PROFILE_ID,
            resolveActiveRuntimeProfileIdForReload(
                snapshot =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        trafficMode = TrafficMode.TUNNEL,
                        profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                    ),
                activeProfileId = null,
            ),
        )
    }

    @Test
    fun `tor over vpn mode changes hot reload instead of reconnecting tunnel`() {
        assertTrue(
            shouldUseHotReloadForPrivacyRouteModeChange(
                nextMode = PrivacyRouteMode.TOR_OVER_VPN,
                torOnlyRuntimeActive = false,
            ),
        )
        assertTrue(
            shouldUseHotReloadForPrivacyRouteModeChange(
                nextMode = PrivacyRouteMode.OFF,
                torOnlyRuntimeActive = false,
            ),
        )
        assertFalse(
            shouldUseHotReloadForPrivacyRouteModeChange(
                nextMode = PrivacyRouteMode.OFF,
                torOnlyRuntimeActive = true,
            ),
        )
    }

    @Test
    fun `disabling active split tunnel disconnects instead of prompting restart`() {
        assertTrue(
            shouldDisconnectWhenDisablingActiveSplitTunnel(
                currentMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                nextMode = PerAppRoutingMode.FULL_TUNNEL,
                activeRuntime = true,
            ),
        )
        assertTrue(
            shouldDisconnectWhenDisablingActiveSplitTunnel(
                currentMode = PerAppRoutingMode.EXCLUDE_SELECTED_APPS,
                nextMode = PerAppRoutingMode.FULL_TUNNEL,
                activeRuntime = true,
            ),
        )
        assertFalse(
            shouldDisconnectWhenDisablingActiveSplitTunnel(
                currentMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                nextMode = PerAppRoutingMode.FULL_TUNNEL,
                activeRuntime = false,
            ),
        )
        assertFalse(
            shouldDisconnectWhenDisablingActiveSplitTunnel(
                currentMode = PerAppRoutingMode.FULL_TUNNEL,
                nextMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                activeRuntime = true,
            ),
        )
    }

    @Test
    fun `local guard sync is deferred while a profile tunnel is active`() {
        assertTrue(
            shouldDeferLocalGuardSyncForActiveProfileRuntime(
                ConnectionSnapshot(state = ConnectionState.CONNECTED, profileId = 42L),
            ),
        )
        assertFalse(
            shouldDeferLocalGuardSyncForActiveProfileRuntime(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                ),
            ),
        )
        assertFalse(
            shouldDeferLocalGuardSyncForActiveProfileRuntime(
                ConnectionSnapshot(state = ConnectionState.IDLE, profileId = 42L),
            ),
        )
    }

    @Test
    fun `firewall dashboard refresh waits for active local guard runtime`() {
        val firewallSettings = Settings(expert = ExpertSettings(firewallEnabled = true))

        assertTrue(
            shouldRefreshLocalGuardDashboardIpAfterSettingsChange(
                snapshot =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                    ),
                settings = firewallSettings,
            ),
        )
        assertFalse(
            shouldRefreshLocalGuardDashboardIpAfterSettingsChange(
                snapshot = ConnectionSnapshot(state = ConnectionState.CONNECTED, profileId = 42L),
                settings = firewallSettings,
            ),
        )
        assertFalse(
            shouldRefreshLocalGuardDashboardIpAfterSettingsChange(
                snapshot =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                    ),
                settings = Settings(expert = ExpertSettings(firewallEnabled = false)),
            ),
        )
    }

    private fun profile(
        id: Long,
        protocolHint: ProtocolHint,
        selectedProtocolOptionId: String? = null,
        protocolOptions: List<ProfileProtocolOption> = emptyList(),
    ) = Profile(
        id = id,
        name = "Foxhole",
        sourceType = ProfileSourceType.SUBSCRIPTION_URL,
        secretRef = "secret-$id",
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
        displayName = id,
        protocolHint = protocolHint,
    )
}
