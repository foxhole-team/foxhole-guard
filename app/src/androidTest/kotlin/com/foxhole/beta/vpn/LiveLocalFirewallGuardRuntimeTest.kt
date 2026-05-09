package com.foxhole.beta.vpn

import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.beta.FoxholeApplication
import kotlinx.coroutines.delay
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
            app.container.settingsRepository.updateFirewallEnabled(false)
            app.container.connectionController.disconnect()
            FoxholeConnectionServiceContract.stopAllServices(app)
            waitForNoFoxholeVpn(app)

            app.container.settingsRepository.updateFirewallEnabled(true)
            app.container.connectionController.syncLocalGuard()

            assertTrue(
                "local firewall guard did not expose an active VPN network",
                waitForCondition(timeoutMs = 20_000L) {
                    app.container.connectionController.hasActiveVpnNetwork() ||
                        hasActiveFoxholeVpnNetwork(app.packageName)
                },
            )
            assertEquals(
                FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                app.container.connectionController.snapshot.value.profileId,
            )
            assertTrue(
                "local guard start diagnostic missing",
                app.container.diagnosticsLogger.entries.value.any {
                    it.tag == "connection" && it.message.contains("local guard started")
                },
            )

            app.container.settingsRepository.updateFirewallEnabled(false)
            app.container.connectionController.syncLocalGuard()
            waitForNoFoxholeVpn(app)

            assertFalse(
                "local firewall guard VPN network remained active after disabling firewall",
                app.container.connectionController.hasActiveVpnNetwork() ||
                    hasActiveFoxholeVpnNetwork(app.packageName),
            )
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

    private fun shell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
                return reader.readText()
            }
        }
    }
}
