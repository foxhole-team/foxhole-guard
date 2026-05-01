package com.foxhole.beta.vpn

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.ConnectionSnapshot
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            stabilizeDisconnectedState(context)
            container.settingsRepository.updateTrafficMode(TrafficMode.PROXY)
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
        private const val PROXY_START_TIMEOUT_MS = 90_000L
        private const val RUNTIME_SHUTDOWN_TIMEOUT_MS = 15_000L

        val DIRECT_PROXY_SMOKE_PROFILE =
            """
            {
              "outbounds": [
                { "type": "direct", "tag": "direct-upstream" }
              ]
            }
            """.trimIndent()
    }
}
