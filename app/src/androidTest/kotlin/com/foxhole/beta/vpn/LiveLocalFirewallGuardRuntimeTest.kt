package com.foxhole.beta.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.MainActivity
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.StatisticsMetric
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.network.DNS_INDEPENDENT_IP_INFO_ENDPOINT
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.ui.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.net.InetAddress
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class LiveLocalFirewallGuardRuntimeTest {
    @Test
    fun localFirewallGuardStartsAndStopsVpnNetwork() =
        runBlocking {
            val app = liveLocalGuardApp()
            val blockedPackage = firstInstalledPackageExcept(app.packageName)
            val viewModel = HomeViewModel(app)
            var latestTrafficMapState = viewModel.trafficMapUiState.value
            val trafficMapCollectionJob =
                launch {
                    viewModel.trafficMapUiState.collect { state ->
                        latestTrafficMapState = state
                    }
                }
            try {
                startLocalFirewallGuard(app, blockedPackage)
                assertTrue(
                    "traffic map did not become available for active local firewall guard",
                    waitForCondition(timeoutMs = 10_000L) {
                        latestTrafficMapState.isAvailable
                    },
                )
                assertTrue(
                    "local guard start diagnostic missing",
                    app.container.diagnosticsLogger.entries.value.any {
                        it.tag == "connection" && it.message.contains("local guard started")
                    },
                )
            } finally {
                trafficMapCollectionJob.cancel()
                stopLocalFirewallGuard(app)
            }
            assertNoFoxholeVpn(app)
        }

    @Test
    fun localFirewallGuardPreservesDashboardInternetAndDns() =
        runBlocking {
            val app = liveLocalGuardApp()
            val blockedPackage = firstInstalledPackageExcept(app.packageName)
            val startedAt = System.currentTimeMillis()
            val dnsHosts = listOf("ipwho.is", "cloudflare.com", "api.ipify.org", "example.com", "google.com")
            val baselineResolvableDnsHosts = baselineResolvableDnsHosts(dnsHosts)
            val httpEndpoints =
                listOf(
                    DNS_INDEPENDENT_IP_INFO_ENDPOINT,
                    "https://example.com",
                    "https://www.google.com/generate_204",
                    "https://cloudflare.com/cdn-cgi/trace",
                )
            val baselineReachableHttpEndpoints = baselineReachableHttpEndpoints(app, httpEndpoints)
            try {
                startLocalFirewallGuard(app, blockedPackage)

                val failures = mutableListOf<String>()
                baselineReachableHttpEndpoints.forEach { endpoint ->
                    runCatching {
                        app.container.ipInfoRepository.probe(endpoint, callTimeoutMs = CONNECTIVITY_PROBE_TIMEOUT_MS)
                    }.onFailure { error ->
                        failures += "http $endpoint failed: ${error.javaClass.simpleName}:${error.message.orEmpty().take(120)}"
                    }
                }

                baselineResolvableDnsHosts.forEach { host ->
                    runCatching {
                        withContext(Dispatchers.IO) {
                            InetAddress.getAllByName(host).toList()
                        }
                    }.onSuccess { addresses ->
                        if (addresses.isEmpty()) {
                            failures += "dns $host returned no addresses"
                        }
                    }.onFailure { error ->
                        failures += "dns $host failed: ${error.javaClass.simpleName}:${error.message.orEmpty().take(120)}"
                    }
                }

                runCatching {
                    app.container.connectionController.refreshIpInfo(IpInfoFetchMode.ENTRY_QUICK)
                }.onFailure { error ->
                    failures += "dashboard ip refresh failed: ${error.javaClass.simpleName}:${error.message.orEmpty().take(120)}"
                }

                val diagnosticsFailures =
                    localGuardNetworkFailureDiagnostics(
                        app = app,
                        startedAt = startedAt,
                        baselineUnresolvableDnsHosts = dnsHosts - baselineResolvableDnsHosts.toSet(),
                    )
                if (diagnosticsFailures.isNotEmpty()) {
                    failures += "diagnostics: ${diagnosticsFailures.joinToString(" | ") { it.take(160) }}"
                }
                assertTrue("local firewall guard connectivity failures: ${failures.joinToString(" | ")}", failures.isEmpty())

                val holdMs = liveLocalGuardHoldMs()
                if (holdMs > 0L) {
                    Log.d(TEST_TAG, "liveLocalGuardInternet ready holdMs=$holdMs")
                    shell("input keyevent KEYCODE_HOME")
                    delay(holdMs)
                }
            } finally {
                stopLocalFirewallGuard(app)
            }
            assertNoFoxholeVpn(app)
        }

    @Test
    fun firewallToggleThirtyTimesNoRuntimeLeak() =
        runBlocking {
            assumeTrue(
                "live local firewall stress test is disabled; pass foxhole.liveLocalGuardStress=1 to run it",
                InstrumentationRegistry.getArguments().getString("foxhole.liveLocalGuardStress") == "1",
            )
            val app = liveLocalGuardApp()
            val blockedPackage = firstInstalledPackageExcept(app.packageName)
            val cycleCount = longArgument("foxhole.localGuardStressCycles", 30L).toInt().coerceIn(1, 100)
            val startedAt = System.currentTimeMillis()
            val dnsHosts = listOf("ipwho.is", "cloudflare.com", "api.ipify.org")
            val baselineResolvableDnsHosts = baselineResolvableDnsHosts(dnsHosts)
            val samples = mutableListOf<LocalGuardStressSample>()

            try {
                repeat(cycleCount) { index ->
                    val cycle = index + 1
                    Log.d(TEST_TAG, "localGuardStress cycleStart=$cycle/$cycleCount")
                    startLocalFirewallGuard(app, blockedPackage)
                    runCatching {
                        app.container.connectionController.refreshIpInfo(IpInfoFetchMode.ENTRY_QUICK)
                    }.getOrElse { error ->
                        throw AssertionError(
                            "local guard stress cycle $cycle dashboard ip refresh failed: " +
                                "${error.javaClass.simpleName}:${error.message.orEmpty().take(120)}",
                            error,
                        )
                    }
                    stopLocalFirewallGuard(app)
                    assertNoFoxholeVpn(app)
                    val sample = captureLocalGuardStressSample(cycle)
                    samples += sample
                    Log.d(
                        TEST_TAG,
                        "localGuardStress cycleStopped=$cycle pssKb=${sample.pssKb ?: "unknown"} javaHeapKb=${sample.javaHeapKb} threads=${sample.threadCount}",
                    )
                }
            } finally {
                stopLocalFirewallGuard(app)
            }

            val diagnosticsFailures =
                localGuardNetworkFailureDiagnostics(
                    app = app,
                    startedAt = startedAt,
                    baselineUnresolvableDnsHosts = dnsHosts - baselineResolvableDnsHosts.toSet(),
                )
            assertTrue(
                "local firewall stress network diagnostics failures: ${diagnosticsFailures.joinToString(" | ") { it.take(160) }}",
                diagnosticsFailures.isEmpty(),
            )
            assertLocalGuardStressMemoryStable(samples)
            assertNoFoxholeVpn(app)
        }

    private suspend fun baselineReachableHttpEndpoints(
        app: FoxholeApplication,
        endpoints: List<String>,
    ): List<String> =
        endpoints.filter { endpoint ->
            val reachable =
                runCatching {
                    app.container.ipInfoRepository.probe(
                        endpoint = endpoint,
                        callTimeoutMs = CONNECTIVITY_PROBE_TIMEOUT_MS,
                    )
                }.isSuccess
            if (!reachable) {
                Log.d(TEST_TAG, "baseline http unavailable endpoint=$endpoint")
            }
            reachable
        }.also { reachableEndpoints ->
            assertTrue("no baseline HTTP endpoints reachable before local firewall guard", reachableEndpoints.isNotEmpty())
        }

    private suspend fun waitForNoFoxholeVpn(app: FoxholeApplication): Boolean =
        waitForCondition(timeoutMs = 15_000L) {
            !app.container.connectionController.hasActiveVpnNetwork() &&
                !hasActiveFoxholeVpnNetwork(app.packageName)
        }

    private suspend fun assertNoFoxholeVpn(app: FoxholeApplication) {
        assertTrue("local firewall guard VPN network remained active after Stop action", waitForNoFoxholeVpn(app))
    }

    private suspend fun liveLocalGuardApp(): FoxholeApplication {
        assumeTrue(
            "live local firewall guard test is disabled; pass foxhole.liveLocalGuard=1 to run it",
            InstrumentationRegistry.getArguments().getString("foxhole.liveLocalGuard") == "1",
        )
        val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
        shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        if (VpnService.prepare(app) != null) {
            assumeTrue(
                "live local firewall guard requires pre-granted Android VPN consent or foxhole.requestVpnPermission=1",
                InstrumentationRegistry.getArguments().getString("foxhole.requestVpnPermission") == "1",
            )
            assertTrue("Android VPN consent was not approved", requestVpnPermission(app))
        }
        assumeTrue("live local firewall guard requires Android VPN consent", VpnService.prepare(app) == null)
        resetLocalGuardTestState(app)
        return app
    }

    private suspend fun requestVpnPermission(app: FoxholeApplication): Boolean {
        val prepareIntent = VpnService.prepare(app) ?: return true
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity =
            instrumentation.startActivitySync(
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        instrumentation.runOnMainSync {
            activity.startActivityForResult(prepareIntent, VPN_PERMISSION_REQUEST_CODE)
        }
        return withTimeoutOrNull(VPN_PERMISSION_TIMEOUT_MS) {
            while (VpnService.prepare(app) != null) {
                approveVpnPermissionDialogIfPresent()
                delay(VPN_PERMISSION_POLL_MS)
            }
            true
        } ?: false
    }

    private fun approveVpnPermissionDialogIfPresent() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val approveButton =
            device.findObject(By.res("android:id/button1"))
                ?: device.findObject(By.res("com.android.vpndialogs:id/confirm"))
                ?: device.wait(
                    Until.findObject(
                        By.text(
                            Pattern.compile(
                                "^(OK|Ok|Allow|Разрешить|Да)$",
                                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
                            ),
                        ),
                    ),
                    VPN_PERMISSION_DIALOG_WAIT_MS,
                )
        runCatching { approveButton?.click() }
    }

    private suspend fun resetLocalGuardTestState(app: FoxholeApplication) {
        app.container.diagnosticsLogger.clear()
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
        app.container.diagnosticsLogger.clear()
    }

    private suspend fun startLocalFirewallGuard(
        app: FoxholeApplication,
        blockedPackage: String,
    ) {
        app.container.settingsRepository.updateStatisticsEnabled(true)
        app.container.settingsRepository.updateStatisticsMetricEnabled(StatisticsMetric.APP_TRAFFIC, true)
        app.container.settingsRepository.updateStatisticsMetricEnabled(StatisticsMetric.COUNTRY_TRAFFIC, true)
        app.container.settingsRepository.updateTrafficMapEnabled(true)
        app.container.settingsRepository.updateAppTrafficStatsEnabled(true)
        app.container.settingsRepository.updateNetworkActivityLogging(true)
        app.container.settingsRepository.updateNetworkActivityPersistentLogging(true)
        app.container.settingsRepository.updateBlockedPackages(listOf(blockedPackage))
        app.container.settingsRepository.updateBlockAppsAlways(true)
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
        assertEquals(FoxholeVpnService.LOCAL_GUARD_PROFILE_ID, app.container.connectionController.snapshot.value.profileId)
        assertEquals(LocalGuardMode.FIREWALL, app.container.settingsRepository.current().localGuardModeOrNull())
        assertTrue(
            "local firewall guard config did not include blocked package",
            app.container.runtimeConfigAssembler
                .assembleLocalGuard(app.container.settingsRepository.current(), LocalGuardMode.FIREWALL)
                .contains(blockedPackage),
        )
        assertTrue(
            "local firewall guard did not exclude FoxHole from the Android VPN app split",
            app.container.runtimeConfigAssembler
                .assembleLocalGuard(app.container.settingsRepository.current(), LocalGuardMode.FIREWALL)
                .contains(app.packageName),
        )
    }

    private suspend fun stopLocalFirewallGuard(app: FoxholeApplication) {
        FoxholeConnectionServiceContract.startForegroundService(
            context = app,
            mode = TrafficMode.TUNNEL,
            action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
            suppressLocalGuard = true,
        )
        waitForNoFoxholeVpn(app)
        app.container.settingsRepository.updateFirewallEnabled(false)
        app.container.settingsRepository.updateNetworkActivityLogging(false)
        app.container.settingsRepository.updateNetworkActivityPersistentLogging(false)
        app.container.settingsRepository.updateTrafficMapEnabled(false)
        app.container.settingsRepository.updateBlockedPackages(emptyList())
        app.container.settingsRepository.updateBlockAppsAlways(false)
    }

    private fun localGuardNetworkFailureDiagnostics(
        app: FoxholeApplication,
        startedAt: Long,
        baselineUnresolvableDnsHosts: List<String>,
    ): List<String> =
        app.container.diagnosticsLogger.entries.value
            .asSequence()
            .filter { entry -> entry.timestamp >= startedAt }
            .map { entry -> "${entry.tag}: ${entry.message}" }
            .filter { message ->
                message.isLocalGuardNetworkFailure(baselineUnresolvableDnsHosts)
            }.toList()

    private suspend fun baselineResolvableDnsHosts(hosts: List<String>): List<String> =
        hosts.filter { host ->
            val resolvable =
                runCatching {
                    withContext(Dispatchers.IO) {
                        InetAddress.getAllByName(host).isNotEmpty()
                    }
                }.getOrDefault(false)
            if (!resolvable) {
                Log.d(TEST_TAG, "baseline dns unavailable host=$host")
            }
            resolvable
        }.also { resolvedHosts ->
            assertTrue("no baseline DNS hosts resolved before local firewall guard", resolvedHosts.isNotEmpty())
        }

    private fun String.isLocalGuardNetworkFailure(baselineUnresolvableDnsHosts: List<String>): Boolean {
        if (contains("Binding socket to network", ignoreCase = true) || contains("EPERM", ignoreCase = true)) {
            return true
        }
        if (contains("geo refresh failed", ignoreCase = true)) {
            return true
        }
        if (!contains("UnknownHostException", ignoreCase = true)) {
            return false
        }
        return baselineUnresolvableDnsHosts.none { host -> contains(host, ignoreCase = true) }
    }

    private fun liveLocalGuardHoldMs(): Long =
        InstrumentationRegistry
            .getArguments()
            .getString("foxhole.liveLocalGuardHoldMs")
            ?.toLongOrNull()
            ?: 0L

    private fun longArgument(name: String, defaultValue: Long): Long =
        InstrumentationRegistry
            .getArguments()
            .getString(name)
            ?.toLongOrNull()
            ?: defaultValue

    private fun captureLocalGuardStressSample(cycle: Int): LocalGuardStressSample {
        val runtime = Runtime.getRuntime()
        return LocalGuardStressSample(
            cycle = cycle,
            pssKb = readCurrentPssKb(),
            javaHeapKb = ((runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_KB).coerceAtLeast(0L),
            threadCount = runCatching { Thread.getAllStackTraces().size }.getOrDefault(-1),
        )
    }

    private fun assertLocalGuardStressMemoryStable(samples: List<LocalGuardStressSample>) {
        val pssSamples = samples.mapNotNull(LocalGuardStressSample::pssKb)
        if (pssSamples.size >= 3) {
            val strictlyMonotonicGrowth = pssSamples.zipWithNext().all { (before, after) -> after > before }
            assertTrue("local guard stress PSS grew monotonically: $pssSamples", !strictlyMonotonicGrowth)
            val first = pssSamples.first()
            val last = pssSamples.last()
            val maxDeltaKb = maxOf(LOCAL_GUARD_STRESS_PSS_DELTA_LIMIT_KB, first / 2)
            assertTrue(
                "local guard stress PSS delta too high firstKb=$first lastKb=$last samples=$pssSamples",
                last <= first + maxDeltaKb,
            )
        }

        val javaHeapSamples = samples.map(LocalGuardStressSample::javaHeapKb)
        if (javaHeapSamples.size >= 3) {
            val first = javaHeapSamples.first()
            val last = javaHeapSamples.last()
            val maxDeltaKb = maxOf(LOCAL_GUARD_STRESS_JAVA_HEAP_DELTA_LIMIT_KB, first / 2)
            assertTrue(
                "local guard stress Java heap delta too high firstKb=$first lastKb=$last samples=$javaHeapSamples",
                last <= first + maxDeltaKb,
            )
        }
    }

    private fun readCurrentPssKb(): Long? =
        runCatching {
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            memoryInfo.totalPss.takeIf { it >= 0 }?.toLong()
        }.getOrNull()

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

    private data class LocalGuardStressSample(
        val cycle: Int,
        val pssKb: Long?,
        val javaHeapKb: Long,
        val threadCount: Int,
    )

    private companion object {
        const val TEST_TAG = "LiveLocalFirewallGuard"
        const val CONNECTIVITY_PROBE_TIMEOUT_MS = 5_000L
        const val BYTES_PER_KB = 1024L
        const val LOCAL_GUARD_STRESS_PSS_DELTA_LIMIT_KB = 150L * 1024L
        const val LOCAL_GUARD_STRESS_JAVA_HEAP_DELTA_LIMIT_KB = 80L * 1024L
        const val VPN_PERMISSION_REQUEST_CODE = 7302
        const val VPN_PERMISSION_TIMEOUT_MS = 45_000L
        const val VPN_PERMISSION_POLL_MS = 500L
        const val VPN_PERMISSION_DIALOG_WAIT_MS = 1_000L
    }
}
