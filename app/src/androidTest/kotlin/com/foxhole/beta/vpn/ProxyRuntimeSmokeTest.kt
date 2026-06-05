package com.foxhole.beta.vpn

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.MainActivity
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.ProxySurfaceMode
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProxyRuntimeSmokeTest {
    @Test
    fun activeProfileConnectsThroughPlainProxyServiceWithoutVpnNetwork() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val container = context.appGraph
            val previousSettings = container.settingsRepository.current()
            var importedProfileId: Long? = null
            var networkWhitelistedUids: Set<Int> = emptySet()

            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            networkWhitelistedUids = allowBackgroundNetworkingForTest(context)
            bringTargetAppToForeground(context)
            stabilizeDisconnectedState(context)
            val publicHttpsPreflight = directPublicHttpsReachable(context)
            assumeTrue(
                "proxy smoke requires direct public HTTPS reachability: ${publicHttpsPreflight.summary}",
                publicHttpsPreflight.reachable,
            )
            container.settingsRepository.replaceForTests(previousSettings.proxySmokeSettings())
            assertEquals(TrafficMode.PROXY, container.settingsRepository.current().traffic.mode)

            try {
                val profile =
                    container.profileRepository.importProfile(
                        rawInput = DIRECT_PROXY_SMOKE_PROFILE,
                        preferredName = "Proxy Smoke",
                    ).also { importedProfile ->
                        importedProfileId = importedProfile.id
                        container.profileRepository.setActiveProfile(importedProfile.id)
                    }

                container.connectionController.connect(profile.id)

                val snapshot =
                    waitForCondition(timeoutMs = PROXY_START_TIMEOUT_MS) {
                        val current = container.connectionController.snapshot.value
                        current.state == ConnectionState.CONNECTED || current.state == ConnectionState.ERROR
                    }
                assertEquals(
                    "proxy smoke did not reach connected. diagnostics=${diagnosticSummary(context)}",
                    ConnectionState.CONNECTED,
                    snapshot.state,
                )
                assertEquals(TrafficMode.PROXY, snapshot.trafficMode)

                val services = shell("dumpsys activity services ${context.packageName}")
                assertTrue("expected FoxholeProxyService. services=$services", services.contains("FoxholeProxyService"))
                assertFalse("unexpected FoxholeVpnService. services=$services", services.contains("FoxholeVpnService"))

                val connectivity = shell("dumpsys connectivity")
                assertFalse(connectivity.contains("VPN CONNECTED extra: VPN:${context.packageName}"))
            } finally {
                stabilizeDisconnectedState(context)
                importedProfileId?.let { container.profileRepository.deleteProfile(it) }
                restoreSettings(container.settingsRepository, previousSettings)
                restoreBackgroundNetworkingForTest(networkWhitelistedUids)
            }
        }

    private suspend fun waitForCondition(
        timeoutMs: Long,
        predicate: () -> Boolean,
    ): com.foxhole.beta.core.model.ConnectionSnapshot {
        val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
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

    private fun shell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
                return reader.readText()
            }
        }
    }

    private fun diagnosticSummary(context: FoxholeApplication): String =
        context.appGraph.diagnosticsLogger.entries.value.joinToString(" || ") { entry -> "[${entry.tag}] ${entry.message}" }

    private suspend fun directPublicHttpsReachable(context: FoxholeApplication): PublicHttpsPreflight {
        val failures = mutableListOf<String>()
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val upstreamNetwork =
            connectivityManager
                ?.preferredNonVpnInternetNetwork(ConnectivityNetworkRegistry.snapshot(context))
        val probePaths =
            listOf(PublicHttpsProbePath(name = "default")) +
                listOfNotNull(
                    upstreamNetwork?.let { network ->
                        PublicHttpsProbePath(
                            name = "explicit-upstream",
                            network = network,
                            resolverNetwork = network,
                        )
                    },
                )
        val deadline = System.currentTimeMillis() + PROXY_SMOKE_PREFLIGHT_TOTAL_TIMEOUT_MS
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt += 1
            PROXY_SMOKE_PUBLIC_ENDPOINTS.forEach { endpoint ->
                probePaths.forEach { path ->
                    val result =
                        runCatching {
                            context.appGraph.ipInfoRepository.probe(
                                endpoint = endpoint,
                                callTimeoutMs = PROXY_SMOKE_PREFLIGHT_TIMEOUT_MS,
                                network = path.network,
                                resolverNetwork = path.resolverNetwork,
                            )
                        }
                    if (result.isSuccess) {
                        val summary = "path=${path.name} endpoint=$endpoint attempt=$attempt"
                        Log.d(TEST_TAG, "direct public HTTPS preflight ok: $summary")
                        return PublicHttpsPreflight(reachable = true, summary = summary)
                    }
                    val error = result.exceptionOrNull()
                    val reason = "${error?.javaClass?.simpleName.orEmpty()}:${error?.message.orEmpty().take(160)}"
                    failures +=
                        "attempt=$attempt path=${path.name} endpoint=$endpoint reason=$reason"
                    Log.w(TEST_TAG, "direct public HTTPS preflight failed: ${failures.last()}", error)
                }
            }
            delay(PROXY_SMOKE_PREFLIGHT_RETRY_DELAY_MS)
        }
        return PublicHttpsPreflight(
            reachable = false,
            summary =
                failures
                    .takeLast(PROXY_SMOKE_PREFLIGHT_MAX_FAILURES_IN_SUMMARY)
                    .joinToString(" | ")
                    .ifBlank { "no probe attempts completed" },
        )
    }

    private fun allowBackgroundNetworkingForTest(context: FoxholeApplication): Set<Int> {
        val uids =
            buildSet {
                add(android.os.Process.myUid())
                addAll(packageUids(context.packageName))
                addAll(packageUids("${context.packageName}.test"))
            }
        uids.forEach { uid ->
            shell("cmd netpolicy add app-idle-whitelist $uid")
            shell("cmd netpolicy add restrict-background-whitelist $uid")
            shell("cmd connectivity set-background-networking-enabled-for-uid $uid true")
        }
        Log.d(TEST_TAG, "enabled instrumentation networking for uids=${uids.joinToString(",")}")
        return uids
    }

    private suspend fun bringTargetAppToForeground(context: FoxholeApplication) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        delay(PROXY_SMOKE_FOREGROUND_SETTLE_MS)
    }

    private fun restoreBackgroundNetworkingForTest(uids: Set<Int>) {
        uids.forEach { uid ->
            shell("cmd netpolicy remove app-idle-whitelist $uid")
            shell("cmd netpolicy remove restrict-background-whitelist $uid")
            shell("cmd connectivity set-background-networking-enabled-for-uid $uid false")
        }
    }

    private fun packageUids(packageName: String): Set<Int> =
        shell("cmd package list packages -U")
            .lineSequence()
            .firstOrNull { line -> line.startsWith("package:$packageName ") }
            ?.substringAfter("uid:", missingDelimiterValue = "")
            ?.split(',')
            ?.mapNotNull { value -> value.trim().toIntOrNull() }
            ?.toSet()
            .orEmpty()

    private suspend fun stabilizeDisconnectedState(context: FoxholeApplication) {
        val container = context.appGraph
        if (hasActiveFoxholeVpnNetwork(context)) {
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                ),
            )
        }
        if (disconnectDispatchModeOrNull(container.connectionController.snapshot.value) != null || hasActiveFoxholeVpnNetwork(context)) {
            container.connectionController.disconnect()
            waitForCondition(timeoutMs = RUNTIME_SHUTDOWN_TIMEOUT_MS) {
                container.connectionController.snapshot.value.state in setOf(ConnectionState.IDLE, ConnectionState.ERROR)
            }
        }
        waitForRuntimeToStop(context, timeoutMs = RUNTIME_SHUTDOWN_TIMEOUT_MS)
        if (hasFoxholeServices(context)) {
            FoxholeConnectionServiceContract.stopAllServices(context)
            waitForRuntimeToStop(context, timeoutMs = RUNTIME_SHUTDOWN_TIMEOUT_MS)
        }
        FoxholeVpnRuntimeBridge.clearTransientState()
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot(trafficMode = container.settingsRepository.current().traffic.mode))
    }

    private suspend fun waitForRuntimeToStop(
        context: FoxholeApplication,
        timeoutMs: Long,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!hasFoxholeServices(context) && !hasActiveFoxholeVpnNetwork(context)) {
                return
            }
            delay(250)
        }
    }

    private fun hasFoxholeServices(context: FoxholeApplication): Boolean {
        val services = shell("dumpsys activity services ${context.packageName}")
        return services.contains("FoxholeProxyService") || services.contains("FoxholeVpnService")
    }

    private fun hasActiveFoxholeVpnNetwork(context: FoxholeApplication): Boolean =
        shell("dumpsys connectivity").contains("VPN CONNECTED extra: VPN:${context.packageName}")

    private suspend fun restoreSettings(
        repository: com.foxhole.beta.core.settings.SettingsRepository,
        settings: Settings,
    ) {
        repository.replaceForTests(settings)
    }

    private companion object {
        private const val TEST_TAG = "ProxyRuntimeSmokeTest"
        private const val PROXY_START_TIMEOUT_MS = 90_000L
        private const val RUNTIME_SHUTDOWN_TIMEOUT_MS = 15_000L
        private const val PROXY_SMOKE_HTTP_PORT = 18089
        private const val PROXY_SMOKE_PREFLIGHT_TIMEOUT_MS = 5_000L
        private const val PROXY_SMOKE_PREFLIGHT_TOTAL_TIMEOUT_MS = 45_000L
        private const val PROXY_SMOKE_PREFLIGHT_RETRY_DELAY_MS = 1_000L
        private const val PROXY_SMOKE_PREFLIGHT_MAX_FAILURES_IN_SUMMARY = 8
        private const val PROXY_SMOKE_FOREGROUND_SETTLE_MS = 2_000L
        private val PROXY_SMOKE_PUBLIC_ENDPOINTS =
            listOf(
                "https://cp.cloudflare.com/generate_204",
                "https://www.gstatic.com/generate_204",
                "https://1.1.1.1/cdn-cgi/trace",
                "https://www.google.com/generate_204",
            )

        val DIRECT_PROXY_SMOKE_PROFILE =
            """
            {
              "outbounds": [
                { "type": "direct", "tag": "direct-upstream" }
              ]
            }
            """.trimIndent()

        private fun Settings.proxySmokeSettings(): Settings =
            copy(
                connection = connection.copy(safeModeEnabled = false),
                traffic = traffic.copy(mode = TrafficMode.PROXY),
                expert =
                    expert.copy(
                        localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.HTTP,
                                lanProxyMode = expert.localSurfaces.lanProxyMode,
                                socks = expert.localSurfaces.socks,
                                http = ProxyInboundSettings(enabled = true, host = "127.0.0.1", port = PROXY_SMOKE_HTTP_PORT),
                                mixed = expert.localSurfaces.mixed,
                                allowLanAccess = false,
                                clashApi = expert.localSurfaces.clashApi.copy(enabled = false),
                                v2RayApi = expert.localSurfaces.v2RayApi.copy(enabled = false),
                                auth = LocalAuthSettings(enabled = false),
                                lanAuth = expert.localSurfaces.lanAuth,
                            ),
                    ),
            )
    }

    private data class PublicHttpsPreflight(
        val reachable: Boolean,
        val summary: String,
    )

    private data class PublicHttpsProbePath(
        val name: String,
        val network: Network? = null,
        val resolverNetwork: Network? = null,
    )
}
