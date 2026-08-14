package com.foxhole.guard.ui
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStateIsolationTest {
    @Test
    fun `settings route can omit live traffic installed apps and activity payloads`() {
        val state =
            HomeUiState(
                traffic =
                TrafficSnapshot(
                    available = true,
                    rxBytesPerSec = 42L,
                    txBytesPerSec = 24L,
                    rxTotalBytes = 4_200L,
                    txTotalBytes = 2_400L,
                    sampledAt = 10L,
                ),
                installedApps =
                listOf(
                    InstalledAppOption(
                        packageName = "com.example",
                        label = "Example",
                        isSystemApp = false,
                    ),
                ),
                diagnosticEntries =
                listOf(
                    DiagnosticEntry(
                        timestamp = 1L,
                        tag = "runtime",
                        message = "diagnostic",
                    ),
                ),
            )

        val route =
            state.toSettingsRouteUiState(
                includeLiveTraffic = false,
                includeInstalledApps = false,
                includeActivityState = false,
            )

        assertEquals(TrafficSnapshot(), route.traffic)
        assertTrue(route.installedApps.isEmpty())
        assertTrue(route.diagnosticEntries.isEmpty())
        assertTrue(route.anomalyEvents.isEmpty())
        assertTrue(route.appTrafficWindows.isEmpty())
        assertTrue(route.networkActivityEvents.isEmpty())
        assertTrue(route.trafficWindows.isEmpty())
    }

    @Test
    fun `traffic settings route maps only required settings and subscription state`() {
        val settings = Settings()
        val route =
            HomeProfileStreams(
                profiles =
                listOf(
                    Profile(
                        id = 1L,
                        name = "Subscription",
                        sourceType = ProfileSourceType.SUBSCRIPTION_URL,
                        secretRef = "secret",
                        protocolHint = ProtocolHint.VLESS,
                        lastUpdatedAt = null,
                        lastEtag = null,
                        isActive = false,
                    ),
                ),
                activeProfile = null,
                settings = settings,
            ).toTrafficSettingsRouteUiState()

        assertEquals(settings, route.settings)
        assertTrue(route.hasSubscriptionProfile)
    }

    @Test
    fun `statistics route mapping keeps live traffic installed apps and activity payloads without diagnostics`() {
        val traffic = TrafficSnapshot(available = true, rxBytesPerSec = 7L, txBytesPerSec = 8L, sampledAt = 20L)
        val installedApps =
            listOf(
                InstalledAppOption(
                    packageName = "com.example",
                    label = "Example",
                    isSystemApp = false,
                ),
            )
        val state =
            HomeUiState(
                traffic = traffic,
                installedApps = installedApps,
            )

        val route = state.toStatisticsRouteUiState()

        assertEquals(traffic, route.traffic)
        assertEquals(installedApps, route.installedApps)
    }

    @Test
    fun `dashboard route model ignores diagnostics payload changes`() {
        val base =
            HomeUiState(
                traffic = TrafficSnapshot(available = true, rxBytesPerSec = 1L),
            )
        val withDiagnostics =
            base.copy(
                diagnosticEntries =
                listOf(
                    DiagnosticEntry(
                        timestamp = 3L,
                        tag = "runtime",
                        message = "diagnostic",
                    ),
                ),
            )

        assertEquals(base.toHomeRouteUiState(), withDiagnostics.toHomeRouteUiState())
    }

    @Test
    fun `dashboard route model can omit live traffic ticks`() {
        val traffic =
            TrafficSnapshot(
                available = true,
                rxBytesPerSec = 512L,
                txBytesPerSec = 128L,
                rxTotalBytes = 4096L,
                txTotalBytes = 1024L,
                sampledAt = 42L,
            )

        val route = HomeUiState(traffic = traffic).toHomeRouteUiState(includeLiveTraffic = false)

        assertEquals(TrafficSnapshot(), route.traffic)
    }

    @Test
    fun `dashboard traffic content ignores sampled at only updates`() {
        val base =
            TrafficSnapshot(
                available = true,
                rxBytesPerSec = 512L,
                txBytesPerSec = 128L,
                rxTotalBytes = 4096L,
                txTotalBytes = 1024L,
                sampledAt = 42L,
            )

        assertTrue(base.hasSameDashboardTrafficContentAs(base.copy(sampledAt = 43L)))
        assertFalse(base.hasSameDashboardTrafficContentAs(base.copy(rxBytesPerSec = 768L)))
        assertFalse(base.hasSameDashboardTrafficContentAs(base.copy(txTotalBytes = 2048L)))
        assertFalse(base.hasSameDashboardTrafficContentAs(base.copy(available = false)))
    }

    @Test
    fun `routing summary route can omit installed app inventory`() {
        val state =
            HomeUiState(
                installedApps =
                listOf(
                    InstalledAppOption(
                        packageName = "com.example",
                        label = "Example",
                        isSystemApp = false,
                    ),
                ),
                installedAppsLoading = true,
                installedAppsLoaded = true,
            )

        val summary = state.toRoutingRouteUiState(includeInstalledApps = false)
        val picker = state.toRoutingRouteUiState(includeInstalledApps = true)

        assertTrue(summary.installedApps.isEmpty())
        assertEquals(false, summary.installedAppsLoading)
        assertEquals(false, summary.installedAppsLoaded)
        assertEquals(state.installedApps, picker.installedApps)
        assertEquals(true, picker.installedAppsLoading)
        assertEquals(true, picker.installedAppsLoaded)
    }
}
