package com.foxhole.core.runtime

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.Profile
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.ui.cli.CliMainActivity
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import com.foxhole.guard.core.settings.updateBlockAppsAlways
import com.foxhole.guard.core.settings.updateBlockedPackages
import com.foxhole.guard.core.settings.updateDnsSettings
import com.foxhole.guard.core.settings.updateFirewallEnabled
import com.foxhole.guard.core.settings.updateNetworkActivityLogging
import com.foxhole.guard.core.settings.updateTrafficMapEnabled
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.onDnsSettingsChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class VpnRuntimeSmokeTest {
    @Test
    fun activeProfileConnectsWithWorkingDnsAndIpInfo() =
        runBlocking {
            val context = requireLiveVpnSmokeContext()
            val profile = prepareLiveVpnSmoke(context)

            try {
                connectAndAssertReady(context, profile.id, phase = "initial connect")
                assertDeviceDnsResolution(phase = "initial connect")
                assertVpnDnsConfigured(context, phase = "initial connect")
            } finally {
                disconnectAndWait(context)
            }
        }

    @Test
    fun activeProfileReconnectsAfterDisconnectAndDnsStillWorks() =
        runBlocking {
            val context = requireLiveVpnSmokeContext()
            val profile = prepareLiveVpnSmoke(context)

            try {
                connectAndAssertReady(context, profile.id, phase = "initial connect")
                disconnectAndWait(context)
                connectAndAssertReady(context, profile.id, phase = "reconnect")
                assertDeviceDnsResolution(phase = "reconnect")
                assertVpnDnsConfigured(context, phase = "reconnect")
            } finally {
                disconnectAndWait(context)
            }
        }

    @Test
    fun firewallToggleWhileProfileVpnActiveDoesNotSwitchRuntimeOrBreakDns() =
        runBlocking {
            val context = requireLiveVpnSmokeContext()
            val container = context.appGraph
            val profile = prepareLiveVpnSmoke(context)
            val blockedPackage = firstInstalledPackageExcept(context.packageName)

            try {
                val connected = connectAndAssertReady(context, profile.id, phase = "before firewall toggle")
                assertEquals("smoke profile was not the active runtime before firewall toggle", profile.id, connected.profileId)

                container.diagnosticsLogger.clear()
                container.settingsRepository.updateNetworkActivityLogging(true)
                container.settingsRepository.updateBlockedPackages(listOf(blockedPackage))
                container.settingsRepository.updateBlockAppsAlways(true)
                container.settingsRepository.updateFirewallEnabled(true)
                container.connectionController.syncLocalGuard()

                val afterEnable =
                    waitForCondition(context, timeoutMs = 5_000L) {
                        container.diagnosticsLogger.entries.value.any { entry ->
                            entry.tag == "connection" &&
                                entry.message.contains("local guard sync deferred: active profile runtime")
                        }
                    }
                assertTrue(
                    "firewall enable did not record active-profile local guard deferral. diagnostics=${diagnosticSummary(container.diagnosticsLogger)}",
                    container.diagnosticsLogger.entries.value.any { entry ->
                        entry.tag == "connection" &&
                            entry.message.contains("local guard sync deferred: active profile runtime")
                    },
                )
                assertEquals(
                    "firewall enable switched away from active VPN profile. diagnostics=${diagnosticSummary(container.diagnosticsLogger)}",
                    profile.id,
                    afterEnable.profileId,
                )
                assertEquals(
                    "firewall enable changed active VPN state. diagnostics=${diagnosticSummary(container.diagnosticsLogger)}",
                    ConnectionState.CONNECTED,
                    afterEnable.state,
                )
                assertDeviceDnsResolution(phase = "after firewall enable on active VPN")
                assertVpnDnsConfigured(context, phase = "after firewall enable on active VPN")

                container.settingsRepository.updateFirewallEnabled(false)
                container.settingsRepository.updateNetworkActivityLogging(false)
                container.settingsRepository.updateBlockedPackages(emptyList())
                container.settingsRepository.updateBlockAppsAlways(false)
                container.connectionController.syncLocalGuard()

                val afterDisable =
                    waitForCondition(context, timeoutMs = 5_000L) {
                        container.connectionController.snapshot.value.state == ConnectionState.CONNECTED &&
                            container.connectionController.snapshot.value.profileId == profile.id
                    }
                assertEquals(
                    "firewall disable stopped or replaced active VPN profile. diagnostics=${diagnosticSummary(container.diagnosticsLogger)}",
                    profile.id,
                    afterDisable.profileId,
                )
                assertEquals(ConnectionState.CONNECTED, afterDisable.state)
                assertDeviceDnsResolution(phase = "after firewall disable on active VPN")
                assertVpnDnsConfigured(context, phase = "after firewall disable on active VPN")
            } finally {
                container.settingsRepository.updateFirewallEnabled(false)
                container.settingsRepository.updateNetworkActivityLogging(false)
                container.settingsRepository.updateBlockedPackages(emptyList())
                container.settingsRepository.updateBlockAppsAlways(false)
                disconnectAndWait(context)
            }
        }

    @Test
    fun dnsFilterEnablePreflightsVerifiedRuleSetAndKeepsVpnDnsWorking() =
        runBlocking {
            val context = requireLiveVpnSmokeContext()
            val container = context.appGraph
            val profile = prepareLiveVpnSmoke(context)
            val viewModel = HomeViewModel(context)

            try {
                connectAndAssertReady(context, profile.id, phase = "before dns filter enable")
                File(context.filesDir, DNS_RULE_SET_DIR).deleteRecursively()
                container.diagnosticsLogger.clear()

                viewModel.onDnsSettingsChanged(
                    DnsSettings(
                        filteringEnabled = true,
                        blockAds = true,
                        blockTrackers = true,
                        blockAppTelemetry = true,
                        blockMaliciousDomains = true,
                        autoUpdateFilters = false,
                    ),
                )

                val afterDnsEnable =
                    waitForCondition(context, timeoutMs = 60_000L) {
                        val dns = container.settingsRepository.current().dns
                        val verifiedRuleSetReady =
                            runCatching { container.dnsFilterAssetInstaller.prepareVerifiedOrNull() != null }
                                .getOrDefault(false)
                        dns.dnsRuleSetFilteringEnabled() &&
                            verifiedRuleSetReady &&
                            container.connectionController.snapshot.value.state == ConnectionState.CONNECTED &&
                            container.connectionController.snapshot.value.profileId == profile.id
                    }

                val dns = container.settingsRepository.current().dns
                assertTrue(
                    "DNS filter was applied without enabled rule-set settings. dns=$dns diagnostics=${diagnosticSummary(container.diagnosticsLogger)}",
                    dns.dnsRuleSetFilteringEnabled(),
                )
                assertTrue(
                    "verified DNS rule set was not prepared before runtime DNS use. diagnostics=${diagnosticSummary(container.diagnosticsLogger)}",
                    container.dnsFilterAssetInstaller.prepareVerifiedOrNull() != null,
                )
                assertEquals(
                    "DNS filter enable lost the active VPN profile. diagnostics=${diagnosticSummary(container.diagnosticsLogger)}",
                    profile.id,
                    afterDnsEnable.profileId,
                )
                assertEquals(ConnectionState.CONNECTED, afterDnsEnable.state)
                assertFalse(
                    "DNS preflight reported failure. diagnostics=${diagnosticSummary(container.diagnosticsLogger)}",
                    diagnosticSummary(container.diagnosticsLogger).contains("filter preflight failed"),
                )
                assertDeviceDnsResolution(phase = "after dns filter enable")
                assertVpnDnsConfigured(context, phase = "after dns filter enable")
            } finally {
                container.settingsRepository.updateDnsSettings(DnsSettings())
                disconnectAndWait(context)
            }
        }

    /**
     * The map has to LIGHT UP for a live tunnel — that is the regression this guards: the state
     * builder used to call the geoip resolver inside the map's own flow, and the first lookup
     * parses the whole range table behind a lock, so every map emission (availability included)
     * queued behind a multi-second parse and the card stayed on standby over a connected tunnel.
     *
     * The live profile is staged by the host test runner. Raw `direct` engine documents are not a
     * public profile format anymore, so this test cannot silently replace a real protocol tunnel
     * with a bypass-only fixture.
     */
    @Test
    fun trafficMapRemainsAvailableWhileLiveTunnelCarriesTraffic() =
        runBlocking {
            val context = requireLiveVpnSmokeContext()
            val container = context.appGraph
            val profile = prepareLiveVpnSmoke(context)
            val viewModel = HomeViewModel(context)
            // The map state is shared Eagerly, so its StateFlow VALUE is the source of truth here.
            // Reading it through a collector coroutine would make the assertion depend on that
            // coroutine getting scheduled, turning a dispatch stall into a "map unavailable" fail.
            fun trafficMapState() = viewModel.trafficMapUiState.value

            try {
                container.settingsRepository.updateTrafficMapEnabled(true)
                connectAndAssertReady(context, profile.id, phase = "before traffic map probe")
                assertTrue(
                    "traffic map did not become available for active VPN profile. " +
                        "state=${trafficMapState()} " +
                        "trafficMapEnabled=${container.settingsRepository.current().ui.trafficMapEnabled} " +
                        "snapshot=${container.connectionController.snapshot.value} " +
                        "hasVpnNetwork=${container.connectionController.hasActiveVpnNetwork()}",
                    waitForCondition(context, timeoutMs = 10_000L) {
                        trafficMapState().isAvailable
                    }.let { trafficMapState().isAvailable },
                )

                repeat(TRAFFIC_MAP_TCP_PROBE_ATTEMPTS) {
                    shell(TRAFFIC_MAP_TCP_PROBE_COMMAND)
                    delay(TRAFFIC_MAP_TCP_PROBE_INTERVAL_MS)
                }

                // The map keeps publishing while traffic flows (the flow is not wedged).
                assertTrue(
                    "traffic map stopped being available while tunnel traffic flowed. " +
                        "state=${trafficMapState()} diagnostics=${diagnosticSummary(container.diagnosticsLogger)}",
                    trafficMapState().isAvailable,
                )
            } finally {
                disconnectAndWait(context)
            }
        }

    @Test
    fun wifiToCellularSwitchKeepsVpnConnectedAndDnsWorking() =
        runBlocking {
            val context = requireLiveVpnSmokeContext()
            val profile = prepareLiveVpnSmoke(context)
            assumeTrue(
                "Wi-Fi -> LTE smoke requires an available Wi-Fi upstream before connect",
                hasNonVpnInternetTransport(context, NetworkCapabilities.TRANSPORT_WIFI),
            )
            assumeTrue(
                "Wi-Fi -> LTE smoke requires an available cellular upstream before switching",
                hasNonVpnInternetTransport(context, NetworkCapabilities.TRANSPORT_CELLULAR),
            )

            try {
                connectAndAssertReady(context, profile.id, phase = "before network switch")
                shell("svc wifi disable")
                assertTrue(
                    "device did not expose a cellular upstream after Wi-Fi disable. transports=${availableNonVpnTransportNames(context)}",
                    waitForNonVpnTransport(
                        context = context,
                        transport = NetworkCapabilities.TRANSPORT_CELLULAR,
                        timeoutMs = 45_000L,
                    ),
                )
                val snapshot =
                    waitForConnectionState(context, timeoutMs = 60_000L) { state ->
                        state == ConnectionState.CONNECTED || state == ConnectionState.ERROR
                    }
                assertEquals(
                    "VPN did not stay connected after Wi-Fi -> LTE switch. diagnostics=${diagnosticSummary(context.appGraph.diagnosticsLogger)}",
                    ConnectionState.CONNECTED,
                    snapshot.state,
                )
                assertDeviceDnsResolution(phase = "after Wi-Fi -> LTE switch")
                assertVpnDnsConfigured(context, phase = "after Wi-Fi -> LTE switch")
            } finally {
                shell("svc wifi enable")
                waitForNonVpnTransport(
                    context = context,
                    transport = NetworkCapabilities.TRANSPORT_WIFI,
                    timeoutMs = 30_000L,
                )
                disconnectAndWait(context)
            }
        }

    private fun requireLiveVpnSmokeContext(): FoxholeApplication {
        assumeTrue(
            "live VPN smoke tests are disabled; pass foxhole.liveVpnSmoke=1 to run device runtime checks",
            InstrumentationRegistry.getArguments().getString("foxhole.liveVpnSmoke") == "1",
        )
        return ApplicationProvider.getApplicationContext()
    }

    private suspend fun prepareLiveVpnSmoke(context: FoxholeApplication): Profile {
        val container = context.appGraph
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        container.settingsRepository.updateFirewallEnabled(false)
        disconnectAndWait(context)

        val rawProfile = stagedLiveVpnProfile(context)
        val activeProfile =
            container.profileRepository.importProfile(
                rawInput = rawProfile,
                preferredName = "Live VPN Smoke",
            )
        container.profileRepository.setActiveProfile(activeProfile.id)
        if (VpnService.prepare(context) != null) {
            assumeTrue(
                "device smoke requires pre-granted Android VPN consent or foxhole.requestVpnPermission=1",
                InstrumentationRegistry.getArguments().getString("foxhole.requestVpnPermission") == "1",
            )
            assertTrue("Android VPN consent was not approved", requestVpnPermission(context))
        }
        assumeTrue("device smoke requires Android VPN consent", VpnService.prepare(context) == null)
        return activeProfile
    }

    private fun stagedLiveVpnProfile(context: FoxholeApplication): String {
        val profileFile = File(context.filesDir, LIVE_SMOKE_PROFILE_FILE)
        assumeTrue(
            "live VPN smoke requires a staged supported profile; set FOXHOLE_LIVE_VPN_PROFILE_RAW_FILE",
            profileFile.isFile && profileFile.length() in 1..MAX_LIVE_SMOKE_PROFILE_BYTES,
        )
        val rawProfile = profileFile.readText().trim()
        runCatching { profileFile.delete() }
        assumeTrue("staged live VPN smoke profile is empty", rawProfile.isNotEmpty())
        return rawProfile
    }

    private suspend fun connectAndAssertReady(
        context: FoxholeApplication,
        profileId: Long,
        phase: String,
    ): ConnectionSnapshot {
        val container = context.appGraph
        container.connectionController.connect(profileId)

        val snapshot =
            waitForConnectionState(context, timeoutMs = 45_000L) { state ->
                state == ConnectionState.CONNECTED || state == ConnectionState.ERROR
            }
        assertEquals(
            "$phase did not reach connected. diagnostics=${diagnosticSummary(container.diagnosticsLogger)}",
            ConnectionState.CONNECTED,
            snapshot.state,
        )

        waitForCondition(context, timeoutMs = 20_000L) {
            container.connectionController.ipInfo.value != null
        }
        assertNotNull(
            "$phase did not populate ip info. diagnostics=${diagnosticSummary(container.diagnosticsLogger)}",
            container.connectionController.ipInfo.value,
        )
        return snapshot
    }

    private suspend fun disconnectAndWait(context: FoxholeApplication) {
        val container = context.appGraph
        container.connectionController.disconnect()
        waitForConnectionState(context, timeoutMs = 15_000L) { state ->
            state == ConnectionState.IDLE || state == ConnectionState.ERROR
        }
        FoxholeConnectionServiceContract.stopAllServices(context)
    }

    private suspend fun waitForConnectionState(
        context: FoxholeApplication,
        timeoutMs: Long,
        predicate: (ConnectionState) -> Boolean,
    ): ConnectionSnapshot {
        val container = context.appGraph
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snapshot = container.connectionController.snapshot.value
            if (predicate(snapshot.state)) {
                return snapshot
            }
            delay(500)
        }
        return container.connectionController.snapshot.value
    }

    private suspend fun waitForCondition(
        context: FoxholeApplication,
        timeoutMs: Long,
        predicate: suspend () -> Boolean,
    ): ConnectionSnapshot {
        val container = context.appGraph
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snapshot = container.connectionController.snapshot.value
            if (predicate()) {
                return snapshot
            }
            delay(500)
        }
        return container.connectionController.snapshot.value
    }

    private suspend fun waitForNonVpnTransport(
        context: FoxholeApplication,
        transport: Int,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasNonVpnInternetTransport(context, transport)) {
                return true
            }
            delay(500)
        }
        return false
    }

    // VALIDATED, not merely present: a phone with a SIM that is out of service still advertises a
    // cellular network with INTERNET capability, and the Wi-Fi -> LTE smoke then "switched" onto a
    // transport that carries no packets at all and failed on DNS instead of skipping.
    private fun hasNonVpnInternetTransport(
        context: FoxholeApplication,
        transport: Int,
    ): Boolean =
        ConnectivityNetworkRegistry.snapshot(context)
            .mapNotNull { network -> context.connectivityManager().getNetworkCapabilities(network) }
            .any { capabilities ->
                capabilities.hasTransport(transport) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }

    private fun availableNonVpnTransportNames(context: FoxholeApplication): String =
        ConnectivityNetworkRegistry.snapshot(context)
            .mapNotNull { network -> context.connectivityManager().getNetworkCapabilities(network) }
            .filter { capabilities ->
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .flatMap { capabilities ->
                listOfNotNull(
                    "wifi".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                    "cellular".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
                    "ethernet".takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
                )
            }
            .distinct()
            .joinToString()
            .ifBlank { "none" }

    private fun FoxholeApplication.connectivityManager(): ConnectivityManager =
        getSystemService(ConnectivityManager::class.java)

    // What this asserts is RESOLUTION: ping prints "PING host (a.b.c.d)" once the name resolved,
    // and that line is the DNS fact. An ICMP echo REPLY is a different claim — the tunnel's direct
    // outbound and the upstream both get a say, and a freshly attached cellular upstream routinely
    // drops the echo while DNS and TCP are perfectly healthy (tunnel reachability is already
    // asserted by the vpn-bound validation probe and the IP refresh). A failed resolution prints
    // nothing to stdout ("unknown host" goes to stderr, which the shell helper does not capture),
    // so an empty result is a failure too.
    private fun assertDeviceDnsResolution(phase: String) {
        var pingGoogle = ""
        val deadline = System.currentTimeMillis() + DNS_PING_RETRY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            pingGoogle = shell("ping -c 1 -W 5 google.com")
            if (pingGoogle.resolvedDnsName()) {
                return
            }
            Thread.sleep(DNS_PING_RETRY_INTERVAL_MS)
        }
        assertFalse(
            "$phase DNS resolution failed. ping=$pingGoogle",
            pingGoogle.contains("unknown host", ignoreCase = true),
        )
        assertTrue(
            "$phase DNS name did not resolve. ping=$pingGoogle",
            pingGoogle.resolvedDnsName(),
        )
    }

    private fun String.resolvedDnsName(): Boolean =
        !contains("unknown host", ignoreCase = true) &&
            DNS_RESOLVED_ADDRESS_PATTERN.matcher(this).find()

    private fun assertVpnDnsConfigured(
        context: FoxholeApplication,
        phase: String,
    ) {
        val connectivity = shell("dumpsys connectivity")
        val vpnMarker = "VPN CONNECTED extra: VPN:${context.packageName}"
        assertTrue("$phase missing VPN network in connectivity dump", connectivity.contains(vpnMarker))
        val vpnSection = connectivity.substringAfter(vpnMarker)
        assertTrue("$phase missing VPN DNS addresses in connectivity dump", vpnSection.contains("DnsAddresses:"))
        val dnsLine = vpnSection.lineSequence().firstOrNull { it.contains("DnsAddresses:") }.orEmpty()
        assertTrue(
            "$phase did not advertise a FoxHole-managed DNS endpoint to Android. dns=$dnsLine",
            dnsLine.contains("/1.1.1.1") || dnsLine.contains("/172.19.0.2"),
        )
    }

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

    private fun diagnosticSummary(logger: DiagnosticsLogger): String =
        logger.entries.value.joinToString(" || ") { entry -> "[${entry.tag}] ${entry.message}" }

    private suspend fun requestVpnPermission(app: FoxholeApplication): Boolean {
        val prepareIntent = VpnService.prepare(app) ?: return true
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity =
            instrumentation.startActivitySync(
                Intent(app, CliMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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

    private companion object {
        private const val DNS_PING_RETRY_TIMEOUT_MS = 15_000L
        private const val DNS_PING_RETRY_INTERVAL_MS = 500L
        private const val VPN_PERMISSION_REQUEST_CODE = 7302
        private const val VPN_PERMISSION_TIMEOUT_MS = 45_000L
        private const val VPN_PERMISSION_POLL_MS = 500L
        private const val VPN_PERMISSION_DIALOG_WAIT_MS = 1_000L
        private const val DNS_RULE_SET_DIR = "dns-rule-sets"
        private const val LIVE_SMOKE_PROFILE_FILE = "live-vpn-smoke.profile"
        private const val MAX_LIVE_SMOKE_PROFILE_BYTES = 2L * 1024L * 1024L
        private const val TRAFFIC_MAP_TCP_PROBE_ATTEMPTS = 4
        private const val TRAFFIC_MAP_TCP_PROBE_INTERVAL_MS = 1_000L
        private const val TRAFFIC_MAP_TCP_PROBE_COMMAND =
            "sh -c 'printf \"HEAD / HTTP/1.0\\r\\nHost: 1.1.1.1\\r\\n\\r\\n\" | nc -w 5 1.1.1.1 80 >/dev/null 2>&1'"
        // "PING google.com (142.250.74.174) 56(84) bytes of data" — the parenthesised address is
        // ping telling us the name resolved.
        private val DNS_RESOLVED_ADDRESS_PATTERN: Pattern =
            Pattern.compile("""PING\s+\S+\s+\(([0-9a-fA-F:.]+)\)""")
    }
}
