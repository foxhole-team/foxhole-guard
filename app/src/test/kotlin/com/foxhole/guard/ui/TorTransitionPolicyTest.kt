package com.foxhole.guard.ui

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.AppliedTorRoute
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TorRoutePlacement
import com.foxhole.guard.R
import com.foxhole.guard.runtime.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorTransitionPolicyTest {
    @Test
    fun `selecting UDP while TOR is active requires disable prompt`() {
        assertTrue(shouldPromptDisableTorForUdpProtocol(torEnabledOrRuntimeActive = true, switchingToUdp = true))
        assertFalse(shouldPromptDisableTorForUdpProtocol(torEnabledOrRuntimeActive = true, switchingToUdp = false))
        assertFalse(shouldPromptDisableTorForUdpProtocol(torEnabledOrRuntimeActive = false, switchingToUdp = true))
    }

    @Test
    fun `starting TCP VPN while TOR-only is active requires placement prompt`() {
        assertTrue(
            shouldPromptStartTcpVpnWhileTorOnlyActive(
                torRoutePlacement = TorRoutePlacement.TOR_ONLY_DEVICE,
                targetProtocolIsUdp = false,
            ),
        )
        assertFalse(
            shouldPromptStartTcpVpnWhileTorOnlyActive(
                torRoutePlacement = TorRoutePlacement.TOR_ONLY_DEVICE,
                targetProtocolIsUdp = true,
            ),
        )
        assertFalse(
            shouldPromptStartTcpVpnWhileTorOnlyActive(
                torRoutePlacement = TorRoutePlacement.TOR_OVER_VPN,
                targetProtocolIsUdp = false,
            ),
        )
    }

    @Test
    fun `VPN plus TOR controls are derived only from the applied route`() {
        val applied =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                profileId = 42L,
                torActive = true,
                appliedTorRoute =
                AppliedTorRoute(
                    scope = PrivacyRouteScope.SELECTED_APPS,
                    bypassVpnTunnel = false,
                ),
            )

        assertEquals(PrivacyRouteScope.SELECTED_APPS, applied.appliedVpnTorRouteOrNull()?.scope)
        assertTrue(DashboardActionsCardUiState(connection = applied).hasVpnAndTorBothActive())
        assertTrue(applied.hasConfirmedVpnAndTor())
        assertTrue(shouldOfferVpnTorStopChoice(applied))
        assertTrue(shouldOfferVpnTorModeChoice(RoutingModePreset.TOR, applied))
        assertFalse(shouldOfferVpnTorModeChoice(RoutingModePreset.VPN, applied))
        assertFalse(shouldOfferVpnTorStopChoice(applied.copy(appliedTorRoute = null)))
        assertFalse(
            DashboardActionsCardUiState(connection = applied.copy(appliedTorRoute = null))
                .hasVpnAndTorBothActive(),
        )
        assertFalse(shouldOfferVpnTorStopChoice(applied.copy(torActive = false)))
        assertFalse(
            shouldOfferVpnTorStopChoice(
                applied.copy(profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID),
            ),
        )
    }

    @Test
    fun `resolves TOR route placement from settings and connection`() {
        assertEquals(
            TorRoutePlacement.OFF,
            resolveTorRoutePlacement(
                settings = Settings(privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.OFF)),
                connection = ConnectionSnapshot(state = ConnectionState.IDLE),
            ),
        )
        assertEquals(
            TorRoutePlacement.TOR_ONLY_DEVICE,
            resolveTorRoutePlacement(
                settings = Settings(privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN)),
                connection = ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                ),
            ),
        )
        assertEquals(
            TorRoutePlacement.TOR_OVER_VPN,
            resolveTorRoutePlacement(
                settings = Settings(privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.TOR_OVER_VPN)),
                connection = ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = 42L,
                ),
            ),
        )
        assertEquals(
            TorRoutePlacement.TOR_ON_DEVICE_WITH_VPN,
            resolveTorRoutePlacement(
                settings = Settings(
                    privacyRoute = PrivacyRouteSettings(
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        bypassVpnTunnel = true,
                    ),
                ),
                connection = ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    profileId = 42L,
                ),
            ),
        )
    }

    @Test
    fun `whole-device TOR is rejected while the VPN TUN includes only selected apps`() {
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    appAssignments = mapOf("com.example.vpn" to AppTunnelLane.VPN),
                ),
                privacyRoute =
                PrivacyRouteSettings(
                    permitted = true,
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    scope = PrivacyRouteScope.SELECTED_APPS,
                ),
            )

        assertTrue(privacyRouteScopeCollidesWithVpnIncludeSplit(settings, PrivacyRouteScope.ALL_APPS))
        assertFalse(privacyRouteScopeCollidesWithVpnIncludeSplit(settings, PrivacyRouteScope.SELECTED_APPS))
        assertFalse(
            privacyRouteScopeCollidesWithVpnIncludeSplit(
                settings.copy(privacyRoute = settings.privacyRoute.copy(permitted = false)),
                PrivacyRouteScope.ALL_APPS,
            ),
        )

        val disabled =
            settings.copy(
                privacyRoute =
                settings.privacyRoute.copy(
                    mode = PrivacyRouteMode.OFF,
                    scope = PrivacyRouteScope.ALL_APPS,
                ),
            )
        assertTrue(privacyRouteModeCollidesWithVpnIncludeSplit(disabled, PrivacyRouteMode.TOR_OVER_VPN))
        assertFalse(privacyRouteModeCollidesWithVpnIncludeSplit(disabled, PrivacyRouteMode.OFF))

        assertEquals(
            R.string.error_tor_all_apps_needs_full_tunnel,
            torOnlyToVpnPlacementBlockReason(disabled, bypassVpnTunnel = false),
        )
        assertEquals(
            R.string.privacy_route_all_apps_requires_no_vpn,
            torOnlyToVpnPlacementBlockReason(disabled, bypassVpnTunnel = true),
        )

        val enabledBesideVpn =
            disabled.copy(
                privacyRoute =
                disabled.privacyRoute.copy(
                    permitted = true,
                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                    bypassVpnTunnel = true,
                ),
            )
        assertTrue(
            torAllAppsBesideActiveVpnCollision(
                settings = enabledBesideVpn,
                primaryVpnActive = true,
            ),
        )
        assertFalse(
            torAllAppsBesideActiveVpnCollision(
                settings = enabledBesideVpn,
                primaryVpnActive = false,
            ),
        )
        assertFalse(
            torAllAppsBesideActiveVpnCollision(
                settings = enabledBesideVpn,
                scope = PrivacyRouteScope.SELECTED_APPS,
                primaryVpnActive = true,
            ),
        )
        assertFalse(routingPresetCollidesWithVpnIncludeSplit(RoutingModePreset.TOR, enabledBesideVpn))
        assertTrue(routingPresetCollidesWithVpnIncludeSplit(RoutingModePreset.VPN_TOR, enabledBesideVpn))
    }

    @Test
    fun `profile protocol lookup prefers requested option then selected option`() {
        val profile =
            Profile(
                id = 42L,
                name = "Smart",
                sourceType = ProfileSourceType.RAW_CONFIG_JSON,
                secretRef = "secret",
                protocolHint = ProtocolHint.VLESS,
                lastUpdatedAt = null,
                lastEtag = null,
                protocolOptions = listOf(
                    ProfileProtocolOption("tcp", "TCP", ProtocolHint.VLESS),
                    ProfileProtocolOption("udp", "UDP", ProtocolHint.WIREGUARD),
                ),
                selectedProtocolOptionId = "tcp",
                isActive = true,
            )

        assertEquals("udp", profile.protocolOptionOrDefault("udp")?.id)
        assertEquals("tcp", profile.protocolOptionOrDefault(null)?.id)
        assertEquals("tcp", profile.protocolOptionOrDefault("missing")?.id)
    }
}
