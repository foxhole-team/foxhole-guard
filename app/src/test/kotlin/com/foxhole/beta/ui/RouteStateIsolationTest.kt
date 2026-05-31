package com.foxhole.beta.ui

import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.TrafficSnapshot
import org.junit.Assert.assertEquals
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
    fun `statistics route mapping keeps live traffic installed apps and activity payloads`() {
        val traffic = TrafficSnapshot(available = true, rxBytesPerSec = 7L, txBytesPerSec = 8L, sampledAt = 20L)
        val installedApps =
            listOf(
                InstalledAppOption(
                    packageName = "com.example",
                    label = "Example",
                    isSystemApp = false,
                ),
            )
        val diagnosticEntries =
            listOf(
                DiagnosticEntry(
                    timestamp = 2L,
                    tag = "runtime",
                    message = "diagnostic",
                ),
            )
        val state =
            HomeUiState(
                traffic = traffic,
                installedApps = installedApps,
                diagnosticEntries = diagnosticEntries,
            )

        val route =
            state.toSettingsRouteUiState(
                includeLiveTraffic = true,
                includeInstalledApps = true,
                includeActivityState = true,
            )

        assertEquals(traffic, route.traffic)
        assertEquals(installedApps, route.installedApps)
        assertEquals(diagnosticEntries, route.diagnosticEntries)
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
