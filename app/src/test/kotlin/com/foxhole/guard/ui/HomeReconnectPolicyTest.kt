package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.runtime.FoxholeVpnService
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
                    protocolHint = ProtocolHint.CUSTOM_CONFIG,
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
        // Attach/detach of the Tor overlay keeps the runtime shape: hot reload.
        assertEquals(
            RoutingChangeAction.HOT_RELOAD,
            resolveRoutingChangeAction(
                old = RoutingChangeState(TrafficMode.TUNNEL),
                new = RoutingChangeState(TrafficMode.TUNNEL),
            ),
        )
        // Turning Tor off while the standalone tor-only session is engaged stops that session.
        assertEquals(
            RoutingChangeAction.FULL_SWITCH,
            resolveRoutingChangeAction(
                old = RoutingChangeState(TrafficMode.TUNNEL, torOnlyRuntime = true),
                new = RoutingChangeState(TrafficMode.TUNNEL, torOnlyRuntime = false),
            ),
        )
    }

    @Test
    fun `local guard sync is deferred while a profile tunnel is active`() {
        listOf(
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.RECONNECTING,
        ).forEach { state ->
            assertTrue(
                shouldDeferLocalGuardSyncForActiveProfileRuntime(
                    ConnectionSnapshot(state = state, profileId = 42L),
                    activeProfileVpnNetworkPresent = true,
                ),
            )
        }
        assertFalse(
            shouldDeferLocalGuardSyncForActiveProfileRuntime(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                ),
                activeProfileVpnNetworkPresent = true,
            ),
        )
        assertFalse(
            shouldDeferLocalGuardSyncForActiveProfileRuntime(
                ConnectionSnapshot(state = ConnectionState.IDLE, profileId = 42L),
                activeProfileVpnNetworkPresent = true,
            ),
        )
        assertFalse(
            "a retained profile snapshot without an Android VPN must not suppress local guard recovery",
            shouldDeferLocalGuardSyncForActiveProfileRuntime(
                ConnectionSnapshot(state = ConnectionState.CONNECTED, profileId = 42L),
                activeProfileVpnNetworkPresent = false,
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
