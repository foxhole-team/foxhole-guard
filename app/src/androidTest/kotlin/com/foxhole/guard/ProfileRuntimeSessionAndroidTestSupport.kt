package com.foxhole.guard

import android.content.Intent
import android.net.VpnService
import android.os.Debug
import android.util.Base64
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.storedProtocolHintOrNull
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsMetric
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TunStack
import com.foxhole.core.runtime.RuntimeIpPanelState
import com.foxhole.core.runtime.RuntimePhase
import com.foxhole.core.runtime.RuntimeTorUiState
import com.foxhole.core.runtime.TunnelValidationEvidenceClassifier
import com.foxhole.guard.core.settings.updateAllowPrivateOutboundHosts
import com.foxhole.guard.core.settings.updateAnalyzeDestinationCountries
import com.foxhole.guard.core.settings.updateAnomalyEnabled
import com.foxhole.guard.core.settings.updateBlockAppsAlways
import com.foxhole.guard.core.settings.updateBlockedPackages
import com.foxhole.guard.core.settings.updateBlockedPackagesEnabled
import com.foxhole.guard.core.settings.updateBypassLan
import com.foxhole.guard.core.settings.updateDnsReplaceSystemDns
import com.foxhole.guard.core.settings.updateFirewallEnabled
import com.foxhole.guard.core.settings.updateNetworkActivityLogging
import com.foxhole.guard.core.settings.updatePerAppRoutingMode
import com.foxhole.guard.core.settings.updatePreferIpv6
import com.foxhole.guard.core.settings.updateSelectedPackages
import com.foxhole.guard.core.settings.updateSniff
import com.foxhole.guard.core.settings.updateStatisticsEnabled
import com.foxhole.guard.core.settings.updateStatisticsMetricEnabled
import com.foxhole.guard.core.settings.updateStrictRoute
import com.foxhole.guard.core.settings.updateTrafficMapEnabled
import com.foxhole.guard.core.settings.updateTrafficMode
import com.foxhole.guard.core.settings.updateTunStack
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.ui.cli.CliMainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileInputStream
import java.util.regex.Pattern

// Shared fixture of the runtime-session suites: settings baselines, waiters and probes.
// Split from ProfileRuntimeSessionAndroidTest.kt.
internal open class ProfileRuntimeSessionAndroidTestSupport {
    protected val json = Json { ignoreUnknownKeys = true }

    protected suspend fun resetRelevantSettings(app: FoxholeApplication) {
        with(app.container.settingsRepository) {
            updateSafeModeEnabled(false)
            updateAutoReconnect(false)
            updateTrafficMode(TrafficMode.TUNNEL)
            updatePerAppRoutingMode(PerAppRoutingMode.FULL_TUNNEL)
            updateSelectedPackages(emptyList())
            updateSniff(false)
            updateBypassLan(false)
            updateAllowPrivateOutboundHosts(false)
            updateStrictRoute(true)
        }
        disableLocalGuardSettings(app)
    }

    protected suspend fun baselineRuntimeSettings(app: FoxholeApplication) {
        with(app.container.settingsRepository) {
            updateSafeModeEnabled(false)
            updateAutoReconnect(false)
            updateTrafficMode(TrafficMode.TUNNEL)
            updateTunStack(TunStack.SYSTEM)
            updatePerAppRoutingMode(PerAppRoutingMode.FULL_TUNNEL)
            updateSelectedPackages(emptyList())
            updateSniff(false)
            updateBypassLan(false)
            updateAllowPrivateOutboundHosts(false)
            updateStrictRoute(true)
            updatePreferIpv6(false)
        }
        disableLocalGuardSettings(app)
    }

    protected suspend fun disableLocalGuardSettings(app: FoxholeApplication) {
        with(app.container.settingsRepository) {
            updateFirewallEnabled(false)
            updateTrafficMapEnabled(false)
            updateStatisticsEnabled(false)
            updateStatisticsMetricEnabled(StatisticsMetric.COUNTRY_TRAFFIC, false)
            updateStatisticsMetricEnabled(StatisticsMetric.ANOMALIES, false)
            updateAnomalyEnabled(false)
            updateAnalyzeDestinationCountries(false)
            updateNetworkActivityLogging(false)
            updateDnsReplaceSystemDns(false)
            updateBlockedPackages(emptyList())
            updateBlockedPackagesEnabled(false)
            updateBlockAppsAlways(false)
        }
    }

    protected suspend fun applyProbeVariant(
        app: FoxholeApplication,
        variant: ProbeVariant,
    ) {
        with(app.container.settingsRepository) {
            updateTunStack(variant.tunStack)
            updateStrictRoute(variant.strictRoute)
            updateSniff(false)
            updateBypassLan(false)
            updatePreferIpv6(false)
        }
    }

    protected suspend fun restoreSettings(
        repository: com.foxhole.guard.core.settings.SettingsRepository,
        settings: Settings,
    ) {
        repository.replaceForTests(settings)
    }

    protected suspend fun waitForTerminalState(app: FoxholeApplication): ConnectionState {
        while (true) {
            val state = app.container.connectionController.snapshot.value.state
            if (state == ConnectionState.CONNECTED || state == ConnectionState.ERROR || state == ConnectionState.IDLE) {
                return state
            }
            delay(250)
        }
    }

    protected suspend fun waitForActiveConnectionAttempt(app: FoxholeApplication) {
        while (true) {
            val state = app.container.connectionController.snapshot.value.state
            if (state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING || state == ConnectionState.CONNECTED) {
                return
            }
            delay(100)
        }
    }

    protected suspend fun disconnectAndWaitForIdle(app: FoxholeApplication) {
        app.container.connectionController.disconnect()
        withTimeoutOrNull(20_000) {
            while (true) {
                val state = app.container.connectionController.snapshot.value.state
                if (state == ConnectionState.IDLE || state == ConnectionState.ERROR) {
                    return@withTimeoutOrNull
                }
                delay(250)
            }
        }
        delay(2_000)
    }

    protected suspend fun waitUntil(
        timeoutMs: Long,
        predicate: suspend () -> Boolean,
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

    protected suspend fun waitForDiagnosticMessage(
        app: FoxholeApplication,
        sinceMs: Long,
        timeoutMs: Long,
        predicate: (String) -> Boolean,
    ): Boolean =
        waitUntil(timeoutMs = timeoutMs) {
            diagnosticMessagesSince(app, sinceMs).any(predicate)
        }

    protected fun diagnosticMessagesSince(
        app: FoxholeApplication,
        sinceMs: Long,
    ): List<String> =
        app.container.diagnosticsLogger.entries.value
            .asSequence()
            .filter { entry -> entry.timestamp >= sinceMs }
            .map { entry -> entry.message }
            .toList()

    protected suspend fun runWarmupProbe(
        app: FoxholeApplication,
        label: String,
        profileId: Long,
    ) {
        app.container.connectionController.disconnect()
        delay(3_000)
        baselineRuntimeSettings(app)
        app.container.connectionController.setActiveProfile(profileId)
        val startedAt = System.currentTimeMillis()
        Log.d(TEST_TAG, "liveDirectWarmup start label=$label profileId=$profileId")
        app.container.connectionController.connect(profileId)
        val terminalState =
            withTimeoutOrNull(LIVE_DIRECT_LINK_TERMINAL_TIMEOUT_MS) {
                waitForActiveConnectionAttempt(app)
                waitForTerminalState(app)
            }
        val ipRefreshResult =
            if (terminalState == ConnectionState.CONNECTED) {
                runCatching {
                    withTimeoutOrNull(20_000) {
                        app.container.connectionController.refreshIpInfo()
                    } ?: error("timeout")
                }.fold(
                    onSuccess = { "ok" },
                    onFailure = { "fail:${it.javaClass.simpleName}" },
                )
            } else {
                "skipped"
            }
        delay(2_000)
        val evidence =
            TunnelValidationEvidenceClassifier.classify(
                entries = app.container.diagnosticsLogger.entries.value,
                sinceMs = startedAt,
            )
        val snapshot = app.container.connectionController.snapshot.value
        Log.d(
            TEST_TAG,
            "liveDirectWarmup result label=$label terminalState=${terminalState?.name ?: "TIMEOUT"} ipRefresh=$ipRefreshResult message=${snapshot.message.orEmpty().take(160)} fatal=${evidence.fatalRuntimeMessage.orEmpty().take(200)} successTraffic=${evidence.hasSuccessfulTunnelActivity}",
        )
    }

    protected fun clearProfiles(app: FoxholeApplication) {
        app.container.profileDatabase.clearAllTables()
        deleteChildren(File(app.filesDir, "profile-secrets"))
    }

    protected fun firstInstalledPackageExcept(packageName: String): String =
        shell("cmd package list packages")
            .lineSequence()
            .map { line -> line.removePrefix("package:").trim() }
            .firstOrNull { candidate -> candidate.isNotBlank() && candidate != packageName }
            ?: "com.android.settings"

    protected fun shell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
                return reader.readText()
            }
        }
    }

    protected fun smartSubscriptionInput(): String? {
        val args = InstrumentationRegistry.getArguments()
        args
            .getString("foxhole.smartSubscriptionBase64")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { encoded ->
                return String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                    .trim()
                    .takeIf(String::isNotBlank)
            }
        val rawFile =
            args
                .getString("foxhole.smartSubscriptionRawFile")
                ?.trim()
                ?.takeIf(String::isNotBlank)
        if (rawFile != null) {
            return readDeviceTextFile(rawFile)
        }
        val configuredInput =
            args
                .getString("foxhole.smartSubscriptionUrl")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: return readDeviceTextFile(DEFAULT_SMART_SUBSCRIPTION_DEVICE_PATH)
                    .also { fallback ->
                        Log.d(
                            TEST_TAG,
                            "smartSubscription fallback path=$DEFAULT_SMART_SUBSCRIPTION_DEVICE_PATH bytes=${fallback?.length ?: 0} args=${args.keySet().joinToString()}",
                        )
                    }
        if (!configuredInput.startsWith("https://", ignoreCase = true)) {
            return readDeviceTextFile(configuredInput)
        }
        return configuredInput
    }

    protected fun readDeviceTextFile(path: String): String? =
        runCatching { File(path).takeIf { it.isFile && it.canRead() }?.readText() }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: shell("cat ${path.shellSingleQuoted()} 2>/dev/null || true")
                .trim()
                .takeIf(String::isNotBlank)

    protected fun String.shellSingleQuoted(): String = "'${replace("'", "'\\''")}'"

    protected fun deleteChildren(dir: File) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                deleteChildren(child)
            }
            child.delete()
        }
    }

    protected fun requestedSmartProbeProtocols(
        defaultProtocols: Set<ProtocolHint> = setOf(ProtocolHint.TROJAN, ProtocolHint.WIREGUARD),
    ): Set<ProtocolHint> {
        val requested =
            InstrumentationRegistry
                .getArguments()
                .getString("foxhole.smartProtocols")
                ?.split(',')
                ?.mapNotNull { raw ->
                    runCatching {
                        storedProtocolHintOrNull(raw.trim().uppercase())
                            ?: error("unsupported protocol hint: $raw")
                    }.getOrNull()
                }
                ?.toSet()
                .orEmpty()
        return requested.ifEmpty { defaultProtocols }
    }

    protected fun requireLiveSmartSuccess(): Boolean =
        InstrumentationRegistry.getArguments().getString("foxhole.requireLiveSmartSuccess") == "1"

    protected fun longArgument(name: String, defaultValue: Long): Long =
        InstrumentationRegistry
            .getArguments()
            .getString(name)
            ?.toLongOrNull()
            ?: defaultValue

    protected suspend fun runVpnBoundIpRefresh(app: FoxholeApplication): String =
        runCatching {
            withTimeoutOrNull(20_000L) {
                app.container.connectionController.refreshIpInfo()
            } ?: error("timeout")
        }.fold(
            onSuccess = { ipInfo -> "ok:${ipInfo.ipv4 ?: ipInfo.ipv6 ?: "unknown"}" },
            onFailure = { error -> "fail:${error.javaClass.simpleName}:${error.message.orEmpty().take(80)}" },
        )

    protected fun captureRuntimeStressResourceSample(
        app: FoxholeApplication,
        cycle: Int,
        stopDetails: Map<String, String>,
        callbackDetails: Map<String, String>,
    ): RuntimeStressResourceSample {
        val runtime = Runtime.getRuntime()
        val connectionSnapshot = app.container.connectionController.snapshot.value
        val runtimeUiState = app.container.connectionController.runtimeUiState.value
        return RuntimeStressResourceSample(
            cycle = cycle,
            connectionState = connectionSnapshot.state.name,
            runtimePhase = runtimeUiState.phase.runtimeStressLabel(),
            runtimeGeneration = runtimeUiState.generation,
            rssKb = readProcStatusKb("VmRSS"),
            pssKb = readCurrentPssKb(),
            nativeHeapKb = runCatching { Debug.getNativeHeapAllocatedSize() / BYTES_PER_KB }.getOrNull(),
            javaHeapKb = ((runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_KB).coerceAtLeast(0L),
            threadCount = runCatching { Thread.getAllStackTraces().size }.getOrDefault(-1),
            owner = stopDetails["owner"],
            latestRuntimeEvent = stopDetails["event"],
            callbackCleanupEvent = callbackDetails["event"],
            commandQueueDepth =
                callbackDetails["command_queue_depth"]?.toIntOrNull()
                    ?: stopDetails["command_queue_depth"]?.toIntOrNull(),
            networkCallbacks = callbackDetails["network_callbacks"]?.toIntOrNull(),
            hasNativeServer = stopDetails["native_server"]?.toBooleanStrictOrNull(),
            hasTunFileDescriptor = stopDetails["tun_fd"]?.toBooleanStrictOrNull(),
            hasNativeHost = stopDetails["native_host"]?.toBooleanStrictOrNull(),
            hasNativeConfig = stopDetails["native_config"]?.toBooleanStrictOrNull(),
            nativeState = stopDetails["native_state"],
            cleanupDraining = stopDetails["cleanup_draining"]?.toBooleanStrictOrNull(),
            lastCloseDetached = stopDetails["last_close_detached"]?.toBooleanStrictOrNull(),
            nativeGeneration = stopDetails["native_generation"]?.toLongOrNull(),
            ipDeviceState = runtimeUiState.ip.device.runtimeStressLabel(),
            ipTunnelState = runtimeUiState.ip.tunnel.runtimeStressLabel(),
            ipTorState = runtimeUiState.ip.tor.runtimeStressLabel(),
            torState = runtimeUiState.tor.runtimeStressLabel(),
            hasRuntimeService = hasFoxholeRuntimeServices(app),
            hasActiveVpnNetwork = hasActiveFoxholeVpnNetwork(app),
        )
    }

    protected fun assertRuntimeStoppedAfterCycle(
        sample: RuntimeStressResourceSample,
        expectedStopEvent: String,
    ) {
        assertNotNull("cycle ${sample.cycle} missing runtime health snapshot", sample.latestRuntimeEvent)
        assertEquals("cycle ${sample.cycle} connection state was not idle", ConnectionState.IDLE.name, sample.connectionState)
        assertEquals("cycle ${sample.cycle} runtime phase was not idle", "idle", sample.runtimePhase)
        assertFalse("cycle ${sample.cycle} left a FoxHole runtime service active", sample.hasRuntimeService)
        assertFalse("cycle ${sample.cycle} left a FoxHole VPN network active", sample.hasActiveVpnNetwork)
        assertEquals("cycle ${sample.cycle} stop snapshot owner mismatch", "vpn", sample.owner)
        assertEquals("cycle ${sample.cycle} stop snapshot event mismatch", expectedStopEvent, sample.latestRuntimeEvent)
        assertEquals("cycle ${sample.cycle} command queue was not empty", 0, sample.commandQueueDepth ?: 0)
        assertFalse("cycle ${sample.cycle} left native server attached event=${sample.latestRuntimeEvent}", sample.hasNativeServer == true)
        assertFalse("cycle ${sample.cycle} left TUN fd attached event=${sample.latestRuntimeEvent}", sample.hasTunFileDescriptor == true)
        assertFalse("cycle ${sample.cycle} left native host attached event=${sample.latestRuntimeEvent}", sample.hasNativeHost == true)
        assertFalse("cycle ${sample.cycle} left native config attached event=${sample.latestRuntimeEvent}", sample.hasNativeConfig == true)
        assertEquals("cycle ${sample.cycle} native state was not idle", "idle", sample.nativeState)
        assertFalse("cycle ${sample.cycle} left draining native cleanup event=${sample.latestRuntimeEvent}", sample.cleanupDraining == true)
        assertFalse("cycle ${sample.cycle} detached close after disconnect event=${sample.latestRuntimeEvent}", sample.lastCloseDetached == true)
        assertEquals(
            "cycle ${sample.cycle} callback cleanup snapshot event mismatch",
            "service_destroy_after_callbacks_unregistered",
            sample.callbackCleanupEvent,
        )
        assertEquals("cycle ${sample.cycle} leaked network callbacks event=${sample.callbackCleanupEvent}", 0, sample.networkCallbacks ?: 0)
    }

    protected fun assertRuntimeStressMemoryStable(samples: List<RuntimeStressResourceSample>) {
        val rssSamples = samples.mapNotNull { it.rssKb }
        if (rssSamples.size < 3) {
            return
        }
        val strictlyMonotonicGrowth = rssSamples.zipWithNext().all { (before, after) -> after > before }
        assertFalse("runtime stress RSS grew monotonically: $rssSamples", strictlyMonotonicGrowth)
        val first = rssSamples.first()
        val last = rssSamples.last()
        val maxDeltaKb = maxOf(RUNTIME_STRESS_RSS_DELTA_LIMIT_KB, first / 2)
        assertTrue(
            "runtime stress RSS delta too high firstKb=$first lastKb=$last samples=$rssSamples",
            last <= first + maxDeltaKb,
        )
    }

    protected suspend fun waitForRuntimeStopDetails(
        app: FoxholeApplication,
        sinceMs: Long,
        requiredEvent: String,
    ): Map<String, String> {
        var details: Map<String, String>? = null
        waitUntil(timeoutMs = 20_000L) {
            details = latestRuntimeHealthDetails(app, sinceMs, requiredEvent = requiredEvent, requiredOwner = "vpn")
            details != null
        }
        return details.orEmpty()
    }

    protected suspend fun waitForRuntimeCallbackCleanupDetails(
        app: FoxholeApplication,
        sinceMs: Long,
    ): Map<String, String> {
        var details: Map<String, String>? = null
        waitUntil(timeoutMs = 20_000L) {
            details =
                latestRuntimeHealthDetails(
                    app = app,
                    sinceMs = sinceMs,
                    requiredEvent = RUNTIME_SERVICE_DESTROY_CALLBACKS_UNREGISTERED_EVENT,
                    requiredOwner = "vpn",
                )
            details != null
        }
        return details.orEmpty()
    }

    protected fun latestRuntimeHealthDetails(
        app: FoxholeApplication,
        sinceMs: Long,
        requiredEvent: String,
        requiredOwner: String? = null,
    ): Map<String, String>? =
        app.container.diagnosticsLogger.entries.value
            .asReversed()
            .firstNotNullOfOrNull { entry ->
                if (entry.timestamp < sinceMs || entry.tag != "runtime-health" || !entry.message.startsWith("runtime resource snapshot")) {
                    return@firstNotNullOfOrNull null
                }
                parseRuntimeHealthDetails(entry.message)
                    .takeIf { details ->
                        details["event"] == requiredEvent &&
                            (requiredOwner == null || details["owner"] == requiredOwner)
                    }
            }

    protected fun parseRuntimeHealthDetails(message: String): Map<String, String> =
        message
            .substringAfter(": ", missingDelimiterValue = "")
            .split(" • ")
            .mapNotNull { raw ->
                val key = raw.substringBefore("=", missingDelimiterValue = "").trim()
                val value = raw.substringAfter("=", missingDelimiterValue = "").trim()
                if (key.isBlank() || value.isBlank()) {
                    null
                } else {
                    key to value
                }
            }.toMap()

    protected fun readCurrentPssKb(): Long? =
        runCatching {
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            memoryInfo.totalPss.takeIf { it >= 0 }?.toLong()
        }.getOrNull()

    protected fun readProcStatusKb(label: String): Long? =
        runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstNotNullOfOrNull { line ->
                    if (line.startsWith(label)) {
                        line
                            .substringAfter(':')
                            .trim()
                            .substringBefore(' ')
                            .toLongOrNull()
                    } else {
                        null
                    }
                }
            }
        }.getOrNull()

    protected fun hasFoxholeRuntimeServices(app: FoxholeApplication): Boolean {
        val services = shell("dumpsys activity services ${app.packageName}")
        return services.contains("FoxholeVpnService")
    }

    protected fun hasActiveFoxholeVpnNetwork(app: FoxholeApplication): Boolean =
        shell("dumpsys connectivity").contains("VPN CONNECTED extra: VPN:${app.packageName}")

    protected data class RuntimeStressResourceSample(
        val cycle: Int,
        val connectionState: String,
        val runtimePhase: String,
        val runtimeGeneration: Long,
        val rssKb: Long?,
        val pssKb: Long?,
        val nativeHeapKb: Long?,
        val javaHeapKb: Long,
        val threadCount: Int,
        val owner: String?,
        val latestRuntimeEvent: String?,
        val callbackCleanupEvent: String?,
        val commandQueueDepth: Int?,
        val networkCallbacks: Int?,
        val hasNativeServer: Boolean?,
        val hasTunFileDescriptor: Boolean?,
        val hasNativeHost: Boolean?,
        val hasNativeConfig: Boolean?,
        val nativeState: String?,
        val cleanupDraining: Boolean?,
        val lastCloseDetached: Boolean?,
        val nativeGeneration: Long?,
        val ipDeviceState: String,
        val ipTunnelState: String,
        val ipTorState: String,
        val torState: String,
        val hasRuntimeService: Boolean,
        val hasActiveVpnNetwork: Boolean,
    )

    protected fun RuntimePhase.runtimeStressLabel(): String =
        when (this) {
            RuntimePhase.Idle -> "idle"
            RuntimePhase.Preparing -> "preparing"
            RuntimePhase.StartingNative -> "starting_native"
            RuntimePhase.WaitingVpnNetwork -> "waiting_vpn_network"
            RuntimePhase.ValidatingTunnel -> "validating_tunnel"
            RuntimePhase.Connected -> "connected"
            RuntimePhase.Reconnecting -> "reconnecting"
            RuntimePhase.Reloading -> "reloading"
            RuntimePhase.Stopping -> "stopping"
            RuntimePhase.Killing -> "killing"
            is RuntimePhase.Error -> "error"
        }

    protected fun RuntimeIpPanelState.runtimeStressLabel(): String =
        when (this) {
            RuntimeIpPanelState.Hidden -> "hidden"
            is RuntimeIpPanelState.Loading -> "loading:${target.name.lowercase()}:previous=${previous != null}"
            is RuntimeIpPanelState.Ready -> "ready:${target.name.lowercase()}:stale=$stale"
            is RuntimeIpPanelState.Failed -> "failed:${target.name.lowercase()}:previous=${previous != null}"
        }

    protected fun RuntimeTorUiState.runtimeStressLabel(): String =
        when (this) {
            RuntimeTorUiState.Off -> "off"
            is RuntimeTorUiState.Starting -> "starting"
            is RuntimeTorUiState.Bootstrapping -> "bootstrapping:${progress ?: -1}:previous=${previousExit != null}"
            is RuntimeTorUiState.Ready -> "ready:exit=${exit.ip}"
        }

    protected data class RuntimeProbeTarget(
        val protocolHint: ProtocolHint,
        val optionId: String?,
    )

    protected fun com.foxhole.core.model.Profile.runtimeProbeTargets(): List<RuntimeProbeTarget> =
        protocolOptions
            .map { option -> RuntimeProbeTarget(protocolHint = option.protocolHint, optionId = option.id) }
            .ifEmpty { listOf(RuntimeProbeTarget(protocolHint = protocolHint, optionId = null)) }

    /**
     * The first option in the imported profile that speaks one of [protocols].
     *
     * Connecting to a profile without naming an option takes whatever the
     * subscription happens to list first, which on this feed is not always a
     * profile that comes up — the live legs below would then fail on the setup
     * rather than on what they are testing.
     */
    protected suspend fun probeTargetFor(
        app: FoxholeApplication,
        profileId: Long,
        protocols: Set<ProtocolHint>,
    ): RuntimeProbeTarget? =
        app.container.profileRepository.profiles
            .first()
            .firstOrNull { profile -> profile.id == profileId }
            ?.runtimeProbeTargets()
            ?.firstOrNull { target -> target.protocolHint in protocols }

    protected fun liveSmartTerminalTimeoutMs(protocolHint: ProtocolHint): Long =
        when (protocolHint) {
            ProtocolHint.WIREGUARD,
            ProtocolHint.HYSTERIA2,
            -> 120_000L
            else -> 90_000L
        }

    protected suspend fun ensureVpnPermission(app: FoxholeApplication): Boolean {
        VpnService.prepare(app)?.let { prepareIntent ->
            if (InstrumentationRegistry.getArguments().getString("foxhole.requestVpnPermission") != "1") {
                return false
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val activity =
                instrumentation.startActivitySync(
                    Intent(app, CliMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            instrumentation.runOnMainSync {
                activity.startActivityForResult(prepareIntent, VPN_PERMISSION_REQUEST_CODE)
            }
            Log.d(TEST_TAG, "waiting for vpn permission dialog approval")
            val granted =
                withTimeoutOrNull(45_000) {
                    while (VpnService.prepare(app) != null) {
                        approveVpnPermissionDialogIfPresent()
                        delay(500)
                    }
                    true
                } ?: false
            if (granted) {
                Log.d(TEST_TAG, "vpn permission approved")
            }
            return granted
        }
        return true
    }

    protected fun approveVpnPermissionDialogIfPresent() {
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
            .onSuccess {
                if (approveButton != null) {
                    Log.d(TEST_TAG, "vpn permission dialog approve clicked")
                }
            }
    }

    companion object {
        internal const val TEST_TAG = "FoxholeSessionTest"
        internal const val DEFAULT_SMART_SUBSCRIPTION_DEVICE_PATH = "/data/local/tmp/foxhole-subscription.raw"
        internal const val VPN_PERMISSION_REQUEST_CODE = 7301
        internal const val VPN_PERMISSION_DIALOG_WAIT_MS = 1_000L
        internal const val RUNTIME_STOP_DISCONNECT_EVENT = "stop_success:disconnect"
        internal const val RUNTIME_SERVICE_DESTROY_CALLBACKS_UNREGISTERED_EVENT = "service_destroy_after_callbacks_unregistered"
        internal const val LIVE_DIRECT_LINK_TERMINAL_TIMEOUT_MS = 150_000L
        internal const val BYTES_PER_KB = 1024L
        internal const val RUNTIME_STRESS_RSS_DELTA_LIMIT_KB = 250L * 1024L
        internal val LIVE_SPLIT_DIRECT_PROFILE =
            """
            {
              "outbounds": [
                { "type": "direct", "tag": "direct-upstream" }
              ]
            }
            """.trimIndent()

        internal data class DirectLinkCase(
            val label: String,
            val rawLink: String,
        )

        internal val DIRECT_LINK_CASES =
            listOf(
                DirectLinkCase(
                    label = "vless-reality",
                    rawLink = DIRECT_VLESS_REALITY_URI,
                ),
                DirectLinkCase(
                    label = "hysteria2",
                    rawLink = DIRECT_HYSTERIA2_URI,
                ),
            )

        internal const val DIRECT_VLESS_REALITY_URI =
            "vless://11111111111111111111111111111111@edge.example.net:8447?type=tcp&encryption=none&security=reality&sni=www.microsoft.com&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0000000000000000&fp=chrome&spx=/#example_secure_core_direct_37792e"

        internal const val DIRECT_HYSTERIA2_URI =
            "hysteria2://cGFzc3dvcmQtcGxhY2Vob2xkZXItZm9yLWRvY3M9@edge.example.net:8443?sni=edge.example.net"

        internal const val PRESERVE_FIXTURE_HOST = "example.com"
        internal val DIRECT_VLESS_REALITY_PRESERVE_URI =
            DIRECT_VLESS_REALITY_URI.replace("edge.example.net", PRESERVE_FIXTURE_HOST)
        internal val DIRECT_HYSTERIA2_PRESERVE_URI =
            DIRECT_HYSTERIA2_URI.replace("edge.example.net", PRESERVE_FIXTURE_HOST)

        internal const val EXACT_USER_XRAY_CONFIG =
            """
            {
              "dns": {
                "hosts": {
                  "domain:googleapis.cn": "googleapis.com",
                  "dot.pub": [
                    "1.12.12.12",
                    "120.53.53.53"
                  ],
                  "dns.alidns.com": [
                    "223.5.5.5",
                    "223.6.6.6",
                    "2400:3200::1",
                    "2400:3200:baba::1"
                  ],
                  "one.one.one.one": [
                    "1.1.1.1",
                    "1.0.0.1",
                    "2606:4700:4700::1111",
                    "2606:4700:4700::1001"
                  ],
                  "dns.cloudflare.com": [
                    "104.16.132.229",
                    "104.16.133.229",
                    "2606:4700::6810:84e5",
                    "2606:4700::6810:85e5"
                  ],
                  "cloudflare-dns.com": [
                    "104.16.248.249",
                    "104.16.249.249",
                    "2606:4700::6810:f8f9",
                    "2606:4700::6810:f9f9"
                  ],
                  "dns.google": [
                    "8.8.8.8",
                    "8.8.4.4",
                    "2001:4860:4860::8888",
                    "2001:4860:4860::8844"
                  ],
                  "dns.quad9.net": [
                    "9.9.9.9",
                    "149.112.112.112",
                    "2620:fe::fe",
                    "2620:fe::9"
                  ],
                  "common.dot.dns.yandex.net": [
                    "77.88.8.8",
                    "77.88.8.1",
                    "2a02:6b8::feed:0ff",
                    "2a02:6b8:0:1::feed:0ff"
                  ]
                },
                "servers": [
                  "8.8.8.8"
                ]
              },
              "inbounds": [
                {
                  "listen": "127.0.0.1",
                  "port": 10808,
                  "protocol": "socks",
                  "settings": {
                    "auth": "noauth",
                    "udp": true,
                    "userLevel": 8
                  },
                  "sniffing": {
                    "destOverride": [
                      "http",
                      "tls"
                    ],
                    "enabled": true,
                    "routeOnly": false
                  },
                  "tag": "socks"
                },
                {
                  "listen": "127.0.0.1",
                  "port": 10809,
                  "protocol": "http",
                  "settings": {
                    "userLevel": 8
                  },
                  "tag": "http"
                }
              ],
              "log": {
                "loglevel": "warning"
              },
              "outbounds": [
                {
                  "mux": {
                    "concurrency": -1,
                    "enabled": false
                  },
                  "protocol": "vless",
                  "settings": {
                    "vnext": [
                      {
                        "address": "203.0.113.59",
                        "port": 43000,
                        "users": [
                          {
                            "encryption": "none",
                            "flow": "xtls-rprx-vision",
                            "id": "00000000-0000-4000-8000-000000000011",
                            "level": 8
                          }
                        ]
                      }
                    ]
                  },
                  "streamSettings": {
                    "network": "tcp",
                    "realitySettings": {
                      "allowInsecure": false,
                      "fingerprint": "chrome",
                      "publicKey": "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                      "serverName": "203.0.113.59",
                      "shortId": "736acf61",
                      "show": false,
                      "spiderX": "/"
                    },
                    "security": "reality",
                    "tcpSettings": {
                      "header": {
                        "type": "none"
                      }
                    }
                  },
                  "tag": "proxy"
                },
                {
                  "protocol": "freedom",
                  "settings": {
                    "domainStrategy": "UseIP"
                  },
                  "tag": "direct"
                },
                {
                  "protocol": "blackhole",
                  "settings": {
                    "response": {
                      "type": "http"
                    }
                  },
                  "tag": "block"
                }
              ],
              "policy": {
                "levels": {
                  "8": {
                    "connIdle": 300,
                    "downlinkOnly": 1,
                    "handshake": 4,
                    "uplinkOnly": 1
                  }
                },
                "system": {
                  "statsOutboundUplink": true,
                  "statsOutboundDownlink": true
                }
              },
              "remarks": "💫 Игровой белый интернет",
              "routing": {
                "domainStrategy": "AsIs",
                "rules": [
                  {
                    "ip": [
                      "8.8.8.8"
                    ],
                    "outboundTag": "proxy",
                    "port": "53",
                    "type": "field"
                  },
                  {
                    "ip": [
                      "223.5.5.5"
                    ],
                    "outboundTag": "direct",
                    "port": "53",
                    "type": "field"
                  }
                ]
              },
              "stats": {}
            }
            """
    }

    protected data class ProbeVariant(
        val name: String,
        val tunStack: TunStack,
        val strictRoute: Boolean,
    )
}
