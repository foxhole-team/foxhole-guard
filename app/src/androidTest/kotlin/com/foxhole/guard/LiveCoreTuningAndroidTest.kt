package com.foxhole.guard

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.CoreTuningSettings
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.I2pSettings
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSettings
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.RuntimeState
import com.foxhole.core.runtime.hasAttachedRuntimeResources
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.guard.core.security.LockState
import com.foxhole.guard.core.settings.CoreTuningParameter
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.runtime.ForegroundRuntimeStartResult
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.onCoreTuningChanged
import com.foxhole.guard.ui.onResetCoreTuning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
internal class LiveCoreTuningAndroidTest {
    @Test
    fun eightValuesPersistFromHomeCallbacksAndResetToNativeDefaults() = runBlocking {
        withInstalledFixture { fixture ->
            val viewModel = fixture.viewModel
            customChanges().forEach { change ->
                applyChange(viewModel, change)
            }
            assertEquals(CUSTOM, fixture.settings.current().expert.core)
            assertEquals(CUSTOM, SettingsRepository(fixture.app).current().expert.core)
            assertTrue("disconnected tuning edits unexpectedly created a runtime", isStopped(fixture.app))

            withContext(Dispatchers.Main.immediate) { viewModel.onResetCoreTuning() }
            awaitSettingsWrites(viewModel)
            assertEquals(DEFAULTS, fixture.settings.current().expert.core)
            assertEquals(DEFAULTS, SettingsRepository(fixture.app).current().expert.core)
            assertTrue("reset unexpectedly created a runtime", isStopped(fixture.app))
        }
    }

    @Test
    fun coldTwoWorkerEngineServesRepeatedQaOnTheSameTun() = runBlocking {
        withInstalledFixture { fixture ->
            assertNotNull("cold worker control requires a separate-UID QA component", fixture.qaComponent)
            val expected = DEFAULTS.copy(workerThreads = 2)
            configureFirewallFixture(fixture, expected)
            fixture.startLocalGuard()
            val initial = awaitAppliedStage(fixture.app, expected)

            repeat(2) { probe ->
                reportStage("cold_workers_2_${probe + 1}", initial)
                verifyQaEgressIfRequested(fixture, initial)
                val current = awaitAppliedStage(fixture.app, expected)
                assertEquals("cold WORKERS=2 replaced the native engine", initial.engineGeneration, current.engineGeneration)
                assertEquals("cold WORKERS=2 changed the native policy", initial.policyRevision, current.policyRevision)
                assertEquals("cold WORKERS=2 replaced the master TUN", initial.masterTunFd, current.masterTunFd)
                assertEquals("cold WORKERS=2 replaced the VPN network", initial.vpnNetworkHandle, current.vpnNetworkHandle)
            }
        }
    }

    @Test
    fun runtimeFieldsReplaceTheEngineAndDnsFieldsReloadPolicyOnTheSameTun() = runBlocking {
        withInstalledFixture { fixture ->
            val app = fixture.app
            configureFirewallFixture(fixture, DEFAULTS)
            fixture.startLocalGuard()
            var expected = DEFAULTS
            var stage = awaitAppliedStage(app, expected)
            reportStage("default", stage)
            verifyQaEgressIfRequested(fixture, stage)
            reportStage("default_repeat_control", stage)
            verifyQaEgressIfRequested(fixture, stage)

            customChanges().take(RUNTIME_FIELD_COUNT).forEach { change ->
                val previous = stage
                expected = change.expected(expected)
                applyChange(fixture.viewModel, change)
                stage = awaitAppliedStage(app, expected)
                assertTrue("${change.parameter} did not replace the native engine", stage.engineGeneration > previous.engineGeneration)
                assertEquals("${change.parameter} unnecessarily replaced the master TUN", previous.masterTunFd, stage.masterTunFd)
                assertEquals("${change.parameter} unnecessarily replaced the VPN network", previous.vpnNetworkHandle, stage.vpnNetworkHandle)
                reportStage(change.parameter.name, stage)
                verifyQaEgressIfRequested(fixture, stage)
            }

            customChanges().drop(RUNTIME_FIELD_COUNT).forEach { change ->
                val previous = stage
                expected = change.expected(expected)
                applyChange(fixture.viewModel, change)
                stage = awaitAppliedStage(app, expected)
                assertEquals("${change.parameter} restarted the native engine", previous.engineGeneration, stage.engineGeneration)
                assertTrue("${change.parameter} did not apply a native policy", stage.policyRevision > previous.policyRevision)
                assertEquals("${change.parameter} replaced the master TUN", previous.masterTunFd, stage.masterTunFd)
                assertEquals("${change.parameter} replaced the VPN network", previous.vpnNetworkHandle, stage.vpnNetworkHandle)
                reportStage(change.parameter.name, stage)
                verifyQaEgressIfRequested(fixture, stage)
            }
            assertEquals(CUSTOM, fixture.settings.current().expert.core)
            assertEquals(CUSTOM, SettingsRepository(app).current().expert.core)
            assertEquals("DNS tuning caused a late engine restart", stage.engineGeneration, awaitAppliedStage(app, expected).engineGeneration)
        }
    }

    private suspend fun configureFirewallFixture(fixture: Fixture, tuning: CoreTuningSettings) {
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        val baseline = fixture.settings.current()
        fixture.settings.restoreExactCheckpoint(
            baseline.copy(
                connection = baseline.connection.copy(safeModeEnabled = false),
                traffic = TrafficSettings(),
                dns = DnsSettings(),
                privacyRoute = PrivacyRouteSettings(),
                i2p = I2pSettings(),
                expert = baseline.expert.copy(
                    core = tuning,
                    firewallEnabled = true,
                    killSwitchEnabled = false,
                    newAppQuarantineEnabled = false,
                    systemDnsProtectionEnabled = false,
                    sniff = false,
                    strictRoute = true,
                    perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
                    appAssignments = mapOf(testPackage to AppTunnelLane.BLOCK),
                    blockedPackagesEnabled = true,
                    blockAppsAlways = true,
                    networkActivityLogging = false,
                ),
                statistics = baseline.statistics.copy(enabled = false),
                appTrafficStatsEnabled = false,
            ),
        )
    }

    private suspend fun withInstalledFixture(block: suspend (Fixture) -> Unit) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("pass foxhole.liveCoreTuning=1 to run installed Expert integration", arguments.getString("foxhole.liveCoreTuning") == "1")
        val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
        assertTrue("installed Expert gate requires a disposable debug emulator", BuildConfig.DEBUG && app.packageName.endsWith(".debug"))
        assertTrue("installed Expert gate refused a physical device", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals("installed Expert gate requires the Android 35 16 KiB VM", 35, Build.VERSION.SDK_INT)
        assertEquals(16_384L, Os.sysconf(OsConstants._SC_PAGESIZE))
        assertTrue("VPN consent must be granted before this gate", VpnService.prepare(app) == null)
        assertEquals("unlock the VM before the gate; it never requests authentication", LockState.UNLOCKED, app.appGraph.appLockManager.currentLockState())
        assertTrue("installed Expert gate requires an idle runtime", isStopped(app))
        val settings = app.container.settingsRepository
        val checkpoint = settings.current()
        assumeTrue("installed Expert gate requires no desired standalone guard", checkpoint.localGuardModeOrNull() == null)
        val profiles = app.container.profileDatabase.profileDao().getAllProfiles()
        assumeTrue("profile invariant must already be valid; the gate never repairs profiles", profiles.isEmpty() || profiles.count { it.isActive } == 1)
        val qaComponent = arguments.getString("foxhole.coreTuningQaComponent")?.let { validateQaComponent(app, it) }
        val resumePreferences = app.getSharedPreferences("foxhole_runtime_resume", Context.MODE_PRIVATE)
        val resumeCheckpoint = resumePreferences.all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        val store = ViewModelStore()
        var fixture: Fixture? = null
        var viewModel: HomeViewModel? = null
        var primaryFailure: Throwable? = null
        try {
            settings.restoreExactCheckpoint(
                checkpoint.copy(
                    connection = checkpoint.connection.copy(
                        autoReconnect = false,
                        autoRefreshSubscriptions = false,
                        componentUpdateCheckEnabled = false,
                        tlsFingerprintAutoUpdate = false,
                    ),
                    expert = checkpoint.expert.copy(core = DEFAULTS),
                ),
            )
            viewModel = withContext(Dispatchers.Main.immediate) {
                val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
                ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[HomeViewModel::class.java]
            }
            fixture = Fixture(app, settings, requireNotNull(viewModel), qaComponent)
            block(requireNotNull(fixture))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            val failures = withContext(NonCancellable) {
                val failures = mutableListOf<Throwable>()
                suspend fun capture(action: suspend () -> Unit) {
                    try { action() } catch (failure: Throwable) { failures += failure }
                }
                capture { viewModel?.let { awaitSettingsWrites(it) } }
                capture { withContext(Dispatchers.Main.immediate) { store.clear() } }
                capture { fixture?.let { stopFixtureRuntime(it) } }
                capture { assertTrue("Expert gate did not release its TUN/native resources", awaitStopped(app)) }
                capture { settings.restoreExactCheckpoint(checkpoint) }
                capture { app.applyGuardReconcileSchedule(enabled = false) }
                capture { restoreResumeCheckpoint(resumePreferences, resumeCheckpoint) }
                capture { assertTrue("Expert gate changed existing profile rows", app.container.profileDatabase.profileDao().getAllProfiles() == profiles) }
                capture { assertTrue("Expert gate did not restore settings", settings.current() == checkpoint) }
                capture { assertTrue("Expert gate did not restore encrypted settings", SettingsRepository(app).current() == checkpoint) }
                capture { assertTrue("Expert gate did not restore runtime resume preferences", resumePreferences.all == resumeCheckpoint) }
                capture { assertTrue("Expert gate left a VPN/TUN after restoration", awaitStopped(app)) }
                failures
            }
            if (primaryFailure != null) failures.forEach { primaryFailure.addSuppressed(it) }
            else if (failures.isNotEmpty()) {
                val failure = failures.first()
                failures.drop(1).forEach(failure::addSuppressed)
                throw failure
            }
        }
    }

    private suspend fun applyChange(viewModel: HomeViewModel, change: Change) {
        withContext(Dispatchers.Main.immediate) { viewModel.onCoreTuningChanged(change.parameter, change.value) }
        awaitSettingsWrites(viewModel)
    }

    private suspend fun awaitSettingsWrites(viewModel: HomeViewModel) {
        withTimeout(SETTINGS_BUDGET_MS) { viewModel.runtimeSettingUpdates.awaitPending() }
    }

    private suspend fun awaitAppliedStage(app: FoxholeApplication, expected: CoreTuningSettings): Stage {
        val result = withTimeoutOrNull(APPLY_BUDGET_MS) {
            while (true) {
                val snapshot = app.container.connectionController.snapshot.value
                val ownership = app.container.runtimeSupervisor.ownership.value
                val native = app.container.runtimeInstanceStore.nativeSnapshot()
                val stats = app.container.runtimeInstanceStore.current()?.runtimeStatsJson()?.let { runCatching { JSONObject(it) }.getOrNull() }
                val fd = native.masterTunFd
                val network = ownership.activeVpnNetworkHandle
                if (snapshot.state == ConnectionState.CONNECTED && snapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
                    ownership.activeLocalGuardMode == LocalGuardMode.FIREWALL && ownership.activeSession == null &&
                    app.container.settingsRepository.current().expert.core == expected &&
                    native.nativeState == RuntimeState.RUNNING && native.hasEngineHandle && native.hasTunFileDescriptor &&
                    fd != null && network != null && network > 0 && app.container.connectionController.isConnectedRuntimeCurrent() &&
                    app.container.runtimeSupervisor.queueSnapshot().commandQueueDepth == 0 && stats != null &&
                    stats.optLong("generation", 0) > 0 && stats.optLong("policy_revision", 0) > 0
                ) {
                    return@withTimeoutOrNull Stage(stats.getLong("generation"), stats.getLong("policy_revision"), fd, network)
                }
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
        val state = app.container.connectionController.snapshot.value.state
        val native = app.container.runtimeInstanceStore.nativeSnapshot()
        val statsKeys = app.container.runtimeInstanceStore.current()?.runtimeStatsJson()
            ?.let { runCatching { JSONObject(it).keys().asSequence().toList() }.getOrNull() }
        assertNotNull("Expert change never applied: state=$state native=${native.nativeState} fd=${native.masterTunFd} statsKeys=$statsKeys", result)
        return requireNotNull(result)
    }

    private suspend fun stopFixtureRuntime(fixture: Fixture) {
        if (!fixture.runtimeRequested) return
        val app = fixture.app
        val previousSequence = app.container.runtimeSupervisor.queueSnapshot().lastSequence
        val started = FoxholeConnectionServiceContract.startForegroundService(
            app, TrafficMode.TUNNEL, FoxholeConnectionServiceContract.ACTION_DISCONNECT,
            suppressLocalGuard = true,
        )
        assertTrue("Expert gate stop command was refused", started is ForegroundRuntimeStartResult.Started)
        assertTrue("Expert gate stop command did not complete", awaitStopped(app, previousSequence))
    }

    private suspend fun restoreResumeCheckpoint(preferences: SharedPreferences, checkpoint: Map<String, *>) {
        withContext(Dispatchers.IO) {
            val editor = preferences.edit().clear()
            checkpoint.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.map { require(it is String); it }.toSet())
                    else -> error("Unsupported runtime resume preference type")
                }
            }
            assertTrue("Expert gate could not persist runtime resume checkpoint", editor.commit())
        }
    }

    private suspend fun awaitStopped(app: FoxholeApplication, disconnectAfterSequence: Long? = null): Boolean {
        val deadline = SystemClock.elapsedRealtime() + STOP_BUDGET_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (stopCompleted(app, disconnectAfterSequence)) return true
            delay(POLL_MS)
        }
        return stopCompleted(app, disconnectAfterSequence)
    }

    private fun stopCompleted(app: FoxholeApplication, disconnectAfterSequence: Long?): Boolean {
        if (!isStopped(app)) return false
        if (disconnectAfterSequence == null) return true
        // Android delivery is outside the supervisor queue until onStartCommand admits the stop.
        return app.container.diagnosticsLogger.entries.value.any { entry ->
            if (entry.tag != "runtime" || !entry.message.startsWith("runtime command completed: ")) return@any false
            val details = entry.message.removePrefix("runtime command completed: ").split(" • ")
            val sequence = details.firstOrNull { it.startsWith("sequence=") }?.removePrefix("sequence=")?.toLongOrNull()
            "priority=user_stop" in details && "reason=disconnect" in details && sequence != null && sequence > disconnectAfterSequence
        }
    }

    private fun isStopped(app: FoxholeApplication): Boolean {
        val ownership = app.container.runtimeSupervisor.ownership.value
        return app.container.connectionController.snapshot.value.state == ConnectionState.IDLE &&
            !app.container.connectionController.hasActiveVpnNetwork() && ownership.activeSession == null &&
            ownership.activeMode == null && ownership.activeLocalGuardMode == null && !ownership.validationActive &&
            !ownership.networkCallbackRegistered && !ownership.vpnNetworkCallbackRegistered && !ownership.defaultNetworkCallbackRegistered &&
            ownership.activeVpnNetworkHandle == null && !app.container.runtimeInstanceStore.nativeSnapshot().hasAttachedRuntimeResources() &&
            !app.container.runtimeInstanceStore.nativeSnapshot().cleanupDraining &&
            app.container.runtimeSupervisor.queueSnapshot().commandQueueDepth == 0 && tunDescriptors().isEmpty()
    }

    private fun tunDescriptors(): Set<String> {
        val descriptors = requireNotNull(File("/proc/self/fd").listFiles()) { "own descriptor table is unavailable" }
        return descriptors.mapNotNull { descriptor ->
            runCatching { Os.readlink(descriptor.path) }.getOrNull()?.takeIf {
                it.startsWith("/dev/tun") || it.startsWith("/dev/net/tun")
            }?.let { descriptor.name }
        }.toSet()
    }

    private fun validateQaComponent(app: FoxholeApplication, value: String): ComponentName {
        val component = requireNotNull(ComponentName.unflattenFromString(value)) { "QA component is malformed" }
        val safeName = Regex("[A-Za-z0-9_.]+")
        require(component.packageName.matches(safeName) && component.className.matches(safeName)) { "QA component name is unsafe" }
        val info = app.packageManager.getInstrumentationInfo(component, 0)
        require(info.targetPackage == component.packageName) { "QA instrumentation must target its own package" }
        require(app.packageManager.getApplicationInfo(component.packageName, 0).uid != app.applicationInfo.uid) { "QA probe must use a separate UID" }
        return component
    }

    private suspend fun verifyQaEgressIfRequested(fixture: Fixture, expected: Stage) {
        val component = fixture.qaComponent ?: return
        reportProbeCounters(fixture.app, "before")
        val result = withContext(Dispatchers.IO) {
            // UiAutomation splits arguments directly; shell quotes become literal component bytes.
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am instrument -w -r ${component.flattenToString()}")
            val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "CoreTuningQaReceipt").apply { isDaemon = true } }
            try {
                executor.submit<String> {
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(bytes.size() + count <= MAX_QA_RECEIPT_BYTES) { "QA receipt exceeded its bound" }
                            bytes.write(buffer, 0, count)
                        }
                        bytes.toString(Charsets.UTF_8.name())
                    }
                }.get(QA_BUDGET_SECONDS, TimeUnit.SECONDS)
            } finally {
                descriptor.close()
                executor.shutdownNow()
            }
        }
        reportProbeCounters(fixture.app, "after")
        val fields = result.lineSequence().filter { it.startsWith("INSTRUMENTATION_RESULT: ") }
            .map { it.removePrefix("INSTRUMENTATION_RESULT: ").split('=', limit = 2) }
            .filter { it.size == 2 }.associate { it[0] to it[1].trim() }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            listOf("pass", "reason", "http_status", "caps", "net_handle", "exception_class", "cause_types", "cause_flags", "qa_foreground_resumed", "qa_initial_blocked", "qa_network_ready", "qa_network_wait_ms").forEach { key ->
                putString("core_tuning_qa_$key", fields[key].orEmpty().take(256))
            }
            putBoolean("core_tuning_qa_instrumentation_failed", result.contains("INSTRUMENTATION_FAILED"))
            putBoolean("core_tuning_qa_permission_denied", result.contains("Permission Denial"))
            putString("core_tuning_qa_framework_failure", result.lineSequence().filter { it.contains("INSTRUMENTATION_FAILED") || it.startsWith("Error:") }.joinToString("; ").take(512))
        })
        assertTrue("separate-UID VPN-bound HTTPS probe failed: reason=${fields["reason"]} status=${fields["http_status"]}", fields["pass"] == "true" && fields["http_status"] == "200" && fields["reason"] == "vpn_bound_https_ok")
        assertTrue("QA probe was not VPN-bound", fields["caps"].orEmpty().split(',').contains("vpn=true"))
        assertEquals("QA probe used another VPN network", expected.vpnNetworkHandle, fields["net_handle"]?.toLongOrNull())
        assertTrue("QA instrumentation did not finish successfully", result.lineSequence().any { it.trim() == "INSTRUMENTATION_CODE: -1" })
    }

    private fun reportProbeCounters(app: FoxholeApplication, phase: String) {
        val stats = app.container.runtimeInstanceStore.current()?.runtimeStatsJson()
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val flow = stats.optJSONObject("flow") ?: stats
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("core_tuning_probe_phase", phase)
            putBoolean("core_tuning_native_connected", flow.optBoolean("connected"))
            putBoolean("core_tuning_native_error", stats.has("last_error") && !stats.isNull("last_error"))
            listOf(
                "tcp_flows_opened", "blocked_flows", "attribution_errors", "dial_errors", "flow_errors",
                "bytes_up", "bytes_down", "flows_closed", "flows_revoked", "dns_queries", "dns_blocked",
            ).forEach { key -> putLong("core_tuning_counter_$key", flow.optLong(key, -1)) }
        })
    }

    private fun reportStage(label: String, stage: Stage) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("core_tuning_stage", label)
            putLong("engine_generation", stage.engineGeneration)
            putLong("policy_revision", stage.policyRevision)
            putInt("master_tun_fd", stage.masterTunFd)
            putLong("vpn_network_handle", stage.vpnNetworkHandle)
        })
    }

    private data class Fixture(val app: FoxholeApplication, val settings: SettingsRepository, val viewModel: HomeViewModel, val qaComponent: ComponentName?) {
        var runtimeRequested = false
            private set

        suspend fun startLocalGuard() {
            runtimeRequested = true
            app.container.connectionController.syncLocalGuard()
        }
    }
    private data class Stage(val engineGeneration: Long, val policyRevision: Long, val masterTunFd: Int, val vpnNetworkHandle: Long)
    private data class Change(val parameter: CoreTuningParameter, val value: Int, val expected: (CoreTuningSettings) -> CoreTuningSettings)

    private fun customChanges(): List<Change> = listOf(
        Change(CoreTuningParameter.WORKERS, 2) { it.copy(workerThreads = 2) },
        Change(CoreTuningParameter.CONNECT_TIMEOUT, 7_000) { it.copy(connectTimeoutMs = 7_000) },
        Change(CoreTuningParameter.HANDSHAKE_TIMEOUT, 9_000) { it.copy(handshakeTimeoutMs = 9_000) },
        Change(CoreTuningParameter.RELAY_BUFFER, 8_192) { it.copy(relayBufferBytes = 8_192) },
        Change(CoreTuningParameter.TCP_FLOWS, 512) { it.copy(maxTcpFlows = 512) },
        Change(CoreTuningParameter.UDP_FLOWS, 256) { it.copy(maxUdpFlows = 256) },
        Change(CoreTuningParameter.DNS_TIMEOUT, 3_000) { it.copy(dnsTimeoutMs = 3_000) },
        Change(CoreTuningParameter.DNS_CACHE, 2_048) { it.copy(dnsCacheSize = 2_048) },
    )

    private companion object {
        const val RUNTIME_FIELD_COUNT = 6
        const val SETTINGS_BUDGET_MS = 10_000L
        const val APPLY_BUDGET_MS = 45_000L
        const val STOP_BUDGET_MS = 25_000L
        const val POLL_MS = 100L
        const val QA_BUDGET_SECONDS = 75L
        const val MAX_QA_RECEIPT_BYTES = 8_192
        val DEFAULTS = CoreTuningSettings(4, 10_000, 15_000, 16_384, 1_024, 512, 5_000, 4_096)
        val CUSTOM = CoreTuningSettings(2, 7_000, 9_000, 8_192, 512, 256, 3_000, 2_048)
    }
}
