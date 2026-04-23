package com.foxhole.beta.vpn

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.net.VpnService
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.ConnectionState
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
            if (InstrumentationRegistry.getArguments().getString("foxhole.liveVpnSmoke") != "1") {
                return@runBlocking
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val container = context.appGraph

            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            container.connectionController.disconnect()

            val activeProfile = container.profileRepository.getActiveProfile()
            assumeTrue("device smoke requires an existing active profile", activeProfile != null)
            assumeTrue("device smoke requires pre-granted Android VPN consent", VpnService.prepare(context) == null)
            val profile = requireNotNull(activeProfile)

            container.connectionController.connect(profile.id)

            val snapshot =
                waitForCondition(timeoutMs = 45_000L) {
                    val current = container.connectionController.snapshot.value
                    current.state == ConnectionState.CONNECTED || current.state == ConnectionState.ERROR
                }
            assertEquals(
                "connection did not reach connected. diagnostics=${diagnosticSummary(container.diagnosticsLogger)}",
                ConnectionState.CONNECTED,
                snapshot.state,
            )

            waitForCondition(timeoutMs = 20_000L) {
                container.connectionController.ipInfo.value != null
            }
            assertNotNull(
                "ip info was not populated. diagnostics=${diagnosticSummary(container.diagnosticsLogger)}",
                container.connectionController.ipInfo.value,
            )

            val pingGoogle = shell("ping -c 1 google.com")
            assertFalse(pingGoogle.contains("unknown host"))
            assertTrue(pingGoogle.contains("1 received"))

            val connectivity = shell("dumpsys connectivity")
            assertTrue(connectivity.contains("VPN CONNECTED extra: VPN:${context.packageName}"))
            assertTrue(connectivity.contains("DnsAddresses: [ /172.19.0.2 ]"))

            container.connectionController.disconnect()
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

    private fun diagnosticSummary(logger: DiagnosticsLogger): String =
        logger.entries.value.joinToString(" || ") { entry -> "[${entry.tag}] ${entry.message}" }
}
