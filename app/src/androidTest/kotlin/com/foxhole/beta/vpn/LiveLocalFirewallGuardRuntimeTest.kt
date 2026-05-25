package com.foxhole.beta.vpn

import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.StatisticsMetric
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.ui.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class LiveLocalFirewallGuardRuntimeTest {
    @Test
    fun localFirewallGuardStartsAndStopsVpnNetwork() =
        runBlocking {
            assumeTrue(
                "live local firewall guard test is disabled; pass foxhole.liveLocalGuard=1 to run it",
                InstrumentationRegistry.getArguments().getString("foxhole.liveLocalGuard") == "1",
            )
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            assumeTrue("live local firewall guard requires pre-granted Android VPN consent", VpnService.prepare(app) == null)

            app.container.settingsRepository.updateKillSwitchEnabled(false)
            app.container.settingsRepository.updateNetworkActivityLogging(false)
            app.container.settingsRepository.updateNetworkActivityPersistentLogging(false)
            app.container.settingsRepository.updateStatisticsEnabled(false)
            app.container.settingsRepository.updateTrafficMapEnabled(false)
            app.container.settingsRepository.updateAppTrafficStatsEnabled(false)
            app.container.settingsRepository.updateBlockedPackages(emptyList())
            app.container.settingsRepository.updateBlockAppsAlways(false)
            app.container.settingsRepository.updateFirewallEnabled(false)
            app.container.connectionController.disconnect()
            FoxholeConnectionServiceContract.stopAllServices(app)
            waitForNoFoxholeVpn(app)

            val blockedPackage = firstInstalledPackageExcept(app.packageName)
            app.container.settingsRepository.updateStatisticsEnabled(true)
            app.container.settingsRepository.updateStatisticsMetricEnabled(StatisticsMetric.APP_TRAFFIC, true)
            app.container.settingsRepository.updateStatisticsMetricEnabled(StatisticsMetric.COUNTRY_TRAFFIC, true)
            app.container.settingsRepository.updateTrafficMapEnabled(true)
            app.container.settingsRepository.updateAppTrafficStatsEnabled(true)
            app.container.settingsRepository.updateNetworkActivityLogging(true)
            app.container.settingsRepository.updateNetworkActivityPersistentLogging(true)
            app.container.settingsRepository.updateBlockedPackages(listOf(blockedPackage))
            app.container.settingsRepository.updateBlockAppsAlways(true)
            val viewModel = HomeViewModel(app)
            var latestTrafficMapState = viewModel.trafficMapUiState.value
            val trafficMapCollectionJob =
                launch {
                    viewModel.trafficMapUiState.collect { state ->
                        latestTrafficMapState = state
                    }
                }
            app.container.settingsRepository.updateFirewallEnabled(true)
            app.container.connectionController.syncLocalGuard()

            assertTrue(
                "local firewall guard did not expose an active VPN network",
                waitForCondition(timeoutMs = 20_000L) {
                    app.container.connectionController.hasActiveVpnNetwork() ||
                        hasActiveFoxholeVpnNetwork(app.packageName)
                },
            )
            assertTrue(
                "local firewall guard did not publish connected snapshot",
                waitForCondition(timeoutMs = 10_000L) {
                    app.container.connectionController.snapshot.value.state == ConnectionState.CONNECTED &&
                        app.container.connectionController.snapshot.value.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
                },
            )
            assertEquals(ConnectionState.CONNECTED, app.container.connectionController.snapshot.value.state)
            assertEquals(
                FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                app.container.connectionController.snapshot.value.profileId,
            )
            assertEquals(LocalGuardMode.FIREWALL, app.container.settingsRepository.current().localGuardModeOrNull())
            assertTrue(
                "local firewall guard config did not include blocked package",
                app.container.runtimeConfigAssembler
                    .assembleLocalGuard(app.container.settingsRepository.current(), LocalGuardMode.FIREWALL)
                    .contains(blockedPackage),
            )
            assertTrue(
                "traffic map did not become available for active local firewall guard",
                waitForCondition(timeoutMs = 10_000L) {
                    latestTrafficMapState.isAvailable
                },
            )
            trafficMapCollectionJob.cancel()
            assertTrue(
                "local guard start diagnostic missing",
                app.container.diagnosticsLogger.entries.value.any {
                    it.tag == "connection" && it.message.contains("local guard started")
                },
            )

            FoxholeConnectionServiceContract.startForegroundService(
                context = app,
                mode = TrafficMode.TUNNEL,
                action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
                suppressLocalGuard = true,
            )
            waitForNoFoxholeVpn(app)

            assertFalse(
                "local firewall guard VPN network remained active after Stop action",
                app.container.connectionController.hasActiveVpnNetwork() ||
                    hasActiveFoxholeVpnNetwork(app.packageName),
            )
            app.container.settingsRepository.updateFirewallEnabled(false)
            app.container.settingsRepository.updateNetworkActivityLogging(false)
            app.container.settingsRepository.updateNetworkActivityPersistentLogging(false)
            app.container.settingsRepository.updateTrafficMapEnabled(false)
        }

    private suspend fun waitForNoFoxholeVpn(app: FoxholeApplication) {
        waitForCondition(timeoutMs = 15_000L) {
            !app.container.connectionController.hasActiveVpnNetwork() &&
                !hasActiveFoxholeVpnNetwork(app.packageName)
        }
    }

    private suspend fun waitForCondition(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) {
                return true
            }
            delay(250L)
        }
        return predicate()
    }

    private fun hasActiveFoxholeVpnNetwork(packageName: String): Boolean =
        shell("dumpsys connectivity").contains("VPN CONNECTED extra: VPN:$packageName")

    private fun firstInstalledPackageExcept(packageName: String): String =
        shell("cmd package list packages")
            .lineSequence()
            .map { line -> line.removePrefix("package:").trim() }
            .firstOrNull { candidate -> candidate.isNotBlank() && candidate != packageName }
            ?: "com.android.settings"

    private fun shell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
                return reader.readText()
            }
        }
    }
}
