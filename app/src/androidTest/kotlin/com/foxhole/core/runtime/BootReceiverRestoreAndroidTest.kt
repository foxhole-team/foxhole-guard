package com.foxhole.core.runtime

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.core.settings.updateDnsReplaceSystemDns
import com.foxhole.guard.core.settings.updateFirewallEnabled
import com.foxhole.guard.core.settings.updateTrafficMode
import com.foxhole.guard.runtime.handleBootReceiverAction
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BootReceiverRestoreAndroidTest {
    @After
    fun tearDown() {
        val app = app()
        RuntimeResumeStateStore.clear(app)
        FoxholeVpnRuntimeBridge.clearTransientState()
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot())
    }

    @Test
    fun aBootCompletedWithoutAutoStartOrLocalGuardSkipsRestoreAndDoesNotCrash() =
        runBlocking {
            val app = resetForBootReceiverTest()
            app.container.settingsRepository.updateAutoStartOnBoot(false)
            app.container.settingsRepository.updateFirewallEnabled(false)
            app.container.settingsRepository.updateDnsReplaceSystemDns(false)
            app.container.settingsRepository.updateTrafficMode(TrafficMode.TUNNEL)

            handleBootReceiverAction(app, Intent.ACTION_BOOT_COMPLETED, app.appGraph)

            assertTrue(
                "boot skip diagnostic missing; diagnostics=${diagnosticSummary(app)}",
                waitForDiagnostic(app, "boot restore skipped: auto start disabled"),
            )
            assertFalse("boot skip unexpectedly activated runtime", FoxholeVpnRuntimeBridge.snapshot.value.state.isActive())
            assertNoFatalOrAnrLogcat(app)
    }

    @Test
    fun bPackageReplacedWithoutStoredRuntimeSkipsRecoveryAndDoesNotCrash() =
        runBlocking {
            val app = resetForBootReceiverTest()
            app.container.settingsRepository.updateAutoStartOnBoot(false)
            app.container.settingsRepository.updateFirewallEnabled(false)
            app.container.settingsRepository.updateDnsReplaceSystemDns(false)

            handleBootReceiverAction(app, Intent.ACTION_MY_PACKAGE_REPLACED, app.appGraph)

            assertTrue(
                "package replace diagnostic missing; diagnostics=${diagnosticSummary(app)}",
                waitForDiagnostic(app, "app_update_replaced"),
            )
            assertTrue(
                "package replace skip diagnostic missing; diagnostics=${diagnosticSummary(app)}",
                waitForDiagnostic(app, "package replace restore skipped: no active runtime"),
            )
            assertNoFatalOrAnrLogcat(app)
        }

    @Test
    fun cBootCompletedWithAutostartRequestsRestoreAndDoesNotCrash() =
        runBlocking {
            val app = resetForBootReceiverTest()
            app.container.settingsRepository.updateAutoStartOnBoot(true)
            app.container.settingsRepository.updateFirewallEnabled(false)
            app.container.settingsRepository.updateDnsReplaceSystemDns(false)
            app.container.settingsRepository.updateTrafficMode(TrafficMode.TUNNEL)

            handleBootReceiverAction(app, Intent.ACTION_BOOT_COMPLETED, app.appGraph)

            assertTrue(
                "boot restore diagnostic missing; diagnostics=${diagnosticSummary(app)}",
                waitForDiagnostic(app, "boot restore requested"),
            )
            assertTrue(
                "boot restore did not start or publish a graceful blocked/fallback state; diagnostics=${diagnosticSummary(app)}",
                waitForCondition {
                    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
                    snapshot.state != ConnectionState.IDLE ||
                    app.container.diagnosticsLogger.entries.value.any { entry ->
                        entry.tag == "connection" &&
                            (
                                entry.message.contains("restore") ||
                                    entry.message.contains("foreground service start blocked")
                            )
                    }
                },
            )
            assertNoFatalOrAnrLogcat(app)
        }

    private suspend fun resetForBootReceiverTest(): FoxholeApplication {
        val app = app()
        shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        shell("logcat -c")
        RuntimeResumeStateStore.clear(app)
        FoxholeVpnRuntimeBridge.clearTransientState()
        FoxholeVpnRuntimeBridge.update(ConnectionSnapshot())
        app.container.diagnosticsLogger.clear()
        delay(250)
        return app
    }

    private suspend fun waitForDiagnostic(
        app: FoxholeApplication,
        message: String,
        timeoutMs: Long = WAIT_TIMEOUT_MS,
    ): Boolean =
        waitForCondition(timeoutMs) {
            app.container.diagnosticsLogger.entries.value.any { entry ->
                entry.message.contains(message)
            }
        }

    private suspend fun waitForCondition(
        timeoutMs: Long = WAIT_TIMEOUT_MS,
        block: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (block()) {
                return true
            }
            delay(100)
        }
        return block()
    }

    private fun diagnosticSummary(app: FoxholeApplication): String =
        app.container.diagnosticsLogger.entries.value.joinToString(" || ") { entry ->
            "[${entry.tag}] ${entry.message}"
        }

    private fun assertNoFatalOrAnrLogcat(app: FoxholeApplication) {
        val logs = shell("logcat -d -v brief -s AndroidRuntime:E ActivityManager:E")
        assertFalse("fatal exception in logcat after boot receiver action:\n$logs", logs.contains("FATAL EXCEPTION"))
        assertFalse(
            "ANR in logcat after boot receiver action:\n$logs",
            logs.contains("ANR in ${app.packageName}") ||
                logs.contains("Application Not Responding: ${app.packageName}"),
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

    private fun app(): FoxholeApplication =
        ApplicationProvider.getApplicationContext()

    private fun ConnectionState.isActive(): Boolean =
        this == ConnectionState.CONNECTING ||
            this == ConnectionState.CONNECTED ||
            this == ConnectionState.RECONNECTING

    private companion object {
        private const val WAIT_TIMEOUT_MS = 10_000L
    }
}
