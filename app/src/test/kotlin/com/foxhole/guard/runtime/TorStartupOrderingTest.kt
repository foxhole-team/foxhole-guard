package com.foxhole.guard.runtime

import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorStartupOrderingTest {
    private fun settings(
        mode: PrivacyRouteMode = PrivacyRouteMode.TOR_OVER_VPN,
        bypass: Boolean = false,
    ) = Settings(
        privacyRoute =
        PrivacyRouteSettings(
            mode = mode,
            scope = PrivacyRouteScope.ALL_APPS,
            bypassVpnTunnel = bypass,
            permitted = true,
        ),
    )

    @Test
    fun `in-tunnel tor defers on the first vpn connect`() {
        assertTrue(
            shouldDeferTorRouteForVpnFirstStartup(
                settings = settings(bypass = false),
                torOnlyConnect = false,
                trafficMode = TrafficMode.TUNNEL,
            ),
        )
    }

    @Test
    fun `tor beside the tunnel, tor-only connects and proxy mode never defer`() {
        assertFalse(
            shouldDeferTorRouteForVpnFirstStartup(
                settings = settings(bypass = true),
                torOnlyConnect = false,
                trafficMode = TrafficMode.TUNNEL,
            ),
        )
        assertFalse(
            shouldDeferTorRouteForVpnFirstStartup(
                settings = settings(),
                torOnlyConnect = true,
                trafficMode = TrafficMode.TUNNEL,
            ),
        )
        assertFalse(
            shouldDeferTorRouteForVpnFirstStartup(
                settings = settings(),
                torOnlyConnect = false,
                trafficMode = TrafficMode.PROXY,
            ),
        )
        assertFalse(
            shouldDeferTorRouteForVpnFirstStartup(
                settings = settings(mode = PrivacyRouteMode.OFF),
                torOnlyConnect = false,
                trafficMode = TrafficMode.TUNNEL,
            ),
        )
    }

    @Test
    fun `deferred session build strips the tor route and the validated hook upgrades it`() {
        val factorySource =
            java.io.File("src/main/kotlin/com/foxhole/guard/core/data/ProfileSessionFactory.kt").readText()
        val connectSource =
            java.io.File("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceConnectSupport.kt").readText()
        val validationSource =
            java.io.File("src/main/kotlin/com/foxhole/guard/runtime/RuntimeValidationCoordinator.kt").readText()
        val validationRunSource =
            java.io.File("src/main/kotlin/com/foxhole/guard/runtime/RuntimeValidationRun.kt").readText()
        val orderingSource =
            java.io.File("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceTorOrderingSupport.kt").readText()

        assertTrue(factorySource.contains("deferTorRoute && baseRuntimeSettings.privacyRoute.enabled"))
        assertTrue(factorySource.contains("privacyRoute.copy(mode = PrivacyRouteMode.OFF)"))
        assertTrue(connectSource.contains("shouldDeferTorRouteForVpnFirstStartup"))
        assertTrue(
            connectSource.contains(
                "pendingTorRouteUpgradeSessionId = if (deferTorRoute) session.correlationId else null",
            ),
        )
        assertTrue(validationSource.contains("scheduleDeferredTorRouteUpgrade(session)"))
        val deferredBarrier =
            validationRunSource.substringAfter(
                "pendingTorRouteUpgradeSessionId == validationRun.currentSession?.correlationId",
            ).substringBefore("return networkContext.vpnNetwork")
        assertTrue(deferredBarrier.contains("refreshTunnelIpForValidation"))
        assertTrue(
            deferredBarrier.contains(
                "publishSuccessfulValidationIpRefresh(validationRun, ipRefresh.getOrThrow())",
            ),
        )
        assertFalse(deferredBarrier.contains("refreshValidatedTunnelIpInfoBestEffort"))
        // The upgrade reacts to the validation event itself — no timers in the ordering path, and
        // a failed VPN start never schedules a tor-only fallback (a failure starts nothing).
        assertFalse(orderingSource.contains("delay("))
        listOf(connectSource, validationSource, orderingSource).forEach { source ->
            assertFalse(source.contains("maybeScheduleTorOnlyFallbackAfterVpnFailure"))
            assertFalse(source.contains("TorOnlyFallback"))
        }
    }
}
