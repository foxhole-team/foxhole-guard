package com.foxhole.beta.vpn

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
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.MainActivity
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.Profile
import java.io.FileInputStream
import java.util.regex.Pattern
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
        container.settingsRepository.updateKillSwitchEnabled(false)
        container.settingsRepository.updateFirewallEnabled(false)
        disconnectAndWait(context)

        val activeProfile =
            container.profileRepository.importProfile(
                rawInput = LIVE_SMOKE_DIRECT_PROFILE,
                preferredName = "Live Smoke Direct",
            )
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
        predicate: () -> Boolean,
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
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
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

    private fun assertDeviceDnsResolution(phase: String) {
        var pingGoogle = ""
        val deadline = System.currentTimeMillis() + DNS_PING_RETRY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            pingGoogle = shell("ping -c 1 -W 5 google.com")
            if (
                !pingGoogle.contains("unknown host", ignoreCase = true) &&
                pingGoogle.contains("1 received")
            ) {
                return
            }
            Thread.sleep(DNS_PING_RETRY_INTERVAL_MS)
        }
        assertFalse(
            "$phase DNS resolution failed. ping=$pingGoogle",
            pingGoogle.contains("unknown host", ignoreCase = true),
        )
        assertTrue(
            "$phase DNS ping did not receive a response. ping=$pingGoogle",
            pingGoogle.contains("1 received"),
        )
    }

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

    private companion object {
        private const val DNS_PING_RETRY_TIMEOUT_MS = 15_000L
        private const val DNS_PING_RETRY_INTERVAL_MS = 500L
        private const val VPN_PERMISSION_REQUEST_CODE = 7302
        private const val VPN_PERMISSION_TIMEOUT_MS = 45_000L
        private const val VPN_PERMISSION_POLL_MS = 500L
        private const val VPN_PERMISSION_DIALOG_WAIT_MS = 1_000L
        private val LIVE_SMOKE_DIRECT_PROFILE =
            """
            {
              "outbounds": [
                { "type": "direct", "tag": "direct-upstream" }
              ]
            }
            """.trimIndent()
    }
}
