package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.KnownApplicationIdentity
import com.foxhole.core.model.PendingQuarantineAppDetails
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.blockedLanePackages
import com.foxhole.core.model.packages
import com.foxhole.core.model.tunnelSelectedPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingModePresetMappingTest {
    private val selection = listOf("com.app.one", "com.app.two")
    private val newAppIdentity =
        KnownApplicationIdentity(
            packageName = "com.new.app",
            signingCertificateSha256 = "11".repeat(32),
            firstSeenAtMs = 1_000L,
        )
    private val pendingDetails =
        PendingQuarantineAppDetails(
            packageName = "com.new.app",
            label = "New app",
            detectedAt = 2_000L,
        )
    private val base =
        Settings(
            expert =
            ExpertSettings(
                perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                appAssignments = selection.associateWith { AppTunnelLane.VPN },
            ),
            privacyRoute = PrivacyRouteSettings(permitted = true),
        )

    @Test
    fun `vpn preset turns tor off and inherits the configured split`() {
        val result = applyRoutingModePresetTo(base, RoutingModePreset.VPN, PrivacyRouteScope.ALL_APPS)

        assertEquals(TrafficMode.TUNNEL, result.traffic.mode)
        assertEquals(PerAppRoutingMode.INCLUDE_SELECTED_APPS, result.expert.perAppRoutingMode)
        assertEquals(PrivacyRouteMode.OFF, result.privacyRoute.mode)
        assertEquals(selection, result.expert.tunnelSelectedPackages())
    }

    @Test
    fun `vpn plus tor keeps the vpn device-wide while tor carries only the selected apps`() {
        val deviceWide =
            base.copy(
                expert =
                base.expert.copy(
                    perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
                    appAssignments = selection.associateWith { AppTunnelLane.TOR },
                ),
            )

        val result = applyRoutingModePresetTo(deviceWide, RoutingModePreset.VPN_TOR, PrivacyRouteScope.SELECTED_APPS)

        assertEquals(PerAppRoutingMode.FULL_TUNNEL, result.expert.perAppRoutingMode)
        assertEquals(PrivacyRouteScope.SELECTED_APPS, result.privacyRoute.scope)
        assertEquals(selection, result.expert.packages(AppTunnelLane.TOR))
    }

    @Test
    fun `split presets pick the per-app mode and leave tor off`() {
        val include = applyRoutingModePresetTo(base, RoutingModePreset.SPLIT_INCLUDE, PrivacyRouteScope.ALL_APPS)
        val exclude = applyRoutingModePresetTo(base, RoutingModePreset.SPLIT_EXCLUDE, PrivacyRouteScope.ALL_APPS)

        assertEquals(PerAppRoutingMode.INCLUDE_SELECTED_APPS, include.expert.perAppRoutingMode)
        assertEquals(PerAppRoutingMode.EXCLUDE_SELECTED_APPS, exclude.expert.perAppRoutingMode)
        assertEquals(PrivacyRouteMode.OFF, include.privacyRoute.mode)
        assertEquals(PrivacyRouteMode.OFF, exclude.privacyRoute.mode)
    }

    @Test
    fun `tor preset is tor-only and adopts the shared selection for its scope`() {
        val result = applyRoutingModePresetTo(base, RoutingModePreset.TOR, PrivacyRouteScope.SELECTED_APPS)

        assertEquals(PrivacyRouteMode.TOR_OVER_VPN, result.privacyRoute.mode)
        assertTrue(result.privacyRoute.bypassVpnTunnel)
        assertEquals(PrivacyRouteScope.SELECTED_APPS, result.privacyRoute.scope)
        assertEquals(selection, result.expert.packages(AppTunnelLane.VPN))
        assertEquals(emptyList<String>(), result.expert.packages(AppTunnelLane.TOR))
        assertEquals(PerAppRoutingMode.INCLUDE_SELECTED_APPS, result.expert.perAppRoutingMode)
    }

    @Test
    fun `vpn plus tor preset detours through the tunnel with the same already-picked apps`() {
        val result = applyRoutingModePresetTo(base, RoutingModePreset.VPN_TOR, PrivacyRouteScope.SELECTED_APPS)

        assertEquals(PrivacyRouteMode.TOR_OVER_VPN, result.privacyRoute.mode)
        assertFalse(result.privacyRoute.bypassVpnTunnel)
        assertEquals(selection, result.expert.packages(AppTunnelLane.VPN))
        assertEquals(emptyList<String>(), result.expert.packages(AppTunnelLane.TOR))
    }

    @Test
    fun `mode switching never flips the tor consent gate`() {
        val granted = applyRoutingModePresetTo(base, RoutingModePreset.VPN, PrivacyRouteScope.ALL_APPS)
        val notGranted =
            applyRoutingModePresetTo(
                base.copy(privacyRoute = base.privacyRoute.copy(permitted = false)),
                RoutingModePreset.VPN_TOR,
                PrivacyRouteScope.ALL_APPS,
            )

        assertTrue(granted.privacyRoute.permitted)
        assertFalse(notGranted.privacyRoute.permitted)
    }

    @Test
    fun `allow decision removes automatic quarantine block`() {
        val quarantined =
            base.copy(
                expert =
                base.expert.copy(
                    firewallEnabled = true,
                    appAssignments = base.expert.appAssignments + ("com.new.app" to AppTunnelLane.BLOCK),
                    pendingQuarantinePackages = listOf("com.new.app"),
                    pendingQuarantineAppDetails = listOf(pendingDetails),
                    blockedPackagesEnabled = true,
                    blockAppsAlways = true,
                ),
            )

        val result =
            resolveQuarantinedAppIn(
                quarantined,
                "com.new.app",
                keepBlocked = false,
                identity = newAppIdentity,
            )

        assertTrue(result.expert.pendingQuarantinePackages.isEmpty())
        assertTrue(result.expert.pendingQuarantineAppDetails.isEmpty())
        assertTrue(result.expert.blockedLanePackages().isEmpty())
        assertFalse(result.expert.blockedPackagesEnabled)
        assertFalse(result.expert.blockAppsAlways)
        assertTrue(result.expert.firewallEnabled)
        assertEquals(listOf(newAppIdentity), result.expert.quarantineKnownApplications)
    }

    @Test
    fun `allow decision without a current identity stays quarantined`() {
        val quarantined = base.copy(expert = base.expert.quarantinePackage("com.new.app", pendingDetails))

        val result = resolveQuarantinedAppIn(quarantined, "com.new.app", keepBlocked = false)

        assertEquals(quarantined, result)
        assertEquals(listOf(pendingDetails), result.expert.pendingQuarantineAppDetails)
    }

    @Test
    fun `block decision keeps deny rule but completes pending action`() {
        val quarantined =
            base.copy(
                expert =
                base.expert.copy(
                    appAssignments = base.expert.appAssignments + ("com.new.app" to AppTunnelLane.BLOCK),
                    pendingQuarantinePackages = listOf("com.new.app"),
                    pendingQuarantineAppDetails = listOf(pendingDetails),
                ),
            )

        val result = resolveQuarantinedAppIn(quarantined, "com.new.app", keepBlocked = true)

        assertTrue(result.expert.pendingQuarantinePackages.isEmpty())
        assertTrue(result.expert.pendingQuarantineAppDetails.isEmpty())
        assertEquals(listOf("com.new.app"), result.expert.blockedLanePackages())
        assertTrue(result.expert.blockedPackagesEnabled)
        assertTrue(result.expert.blockAppsAlways)
    }

    @Test
    fun `new app quarantine is persistent pending block state`() {
        val result = base.expert.quarantinePackage("com.new.app").quarantinePackage("com.new.app")

        assertEquals(listOf("com.new.app"), result.pendingQuarantinePackages)
        assertEquals(listOf("com.new.app"), result.blockedLanePackages())
        assertTrue(result.firewallEnabled)
        assertTrue(result.blockedPackagesEnabled)
        assertTrue(result.blockAppsAlways)
    }

    @Test
    fun `late duplicate decision cannot change a resolved quarantine`() {
        val quarantined =
            base.copy(
                expert = base.expert.quarantinePackage("com.new.app"),
            )
        val allowed =
            resolveQuarantinedAppIn(
                quarantined,
                "com.new.app",
                keepBlocked = false,
                identity = newAppIdentity,
            )

        assertEquals(
            allowed,
            resolveQuarantinedAppIn(allowed, "com.new.app", keepBlocked = true),
        )
    }
}
