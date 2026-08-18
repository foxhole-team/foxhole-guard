package com.foxhole.guard

import android.net.VpnService
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.LatencyProbeMethod
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.TunStack
import com.foxhole.core.profile.MultiProtocolProfileSupport
import com.foxhole.core.runtime.TunnelValidationEvidenceClassifier
import com.foxhole.guard.core.data.refreshProfile
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

internal class ProfileRuntimeGuardSessionAndroidTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun manualSmartSubscriptionVlessTcpBackgroundHold() {
        assumeTrue(
            "manual smart VLESS TCP background hold skipped: pass -e foxhole.liveSmartVlessTcpBackground 1 to run it",
            InstrumentationRegistry.getArguments().getString("foxhole.liveSmartVlessTcpBackground") == "1",
        )
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val subscriptionUrl =
                InstrumentationRegistry
                    .getArguments()
                    .getString("foxhole.smartSubscriptionUrl")
                    ?.trim()
                    ?.takeIf { it.startsWith("https://", ignoreCase = true) }
            if (subscriptionUrl == null) {
                Log.d(TEST_TAG, "manual smart VLESS TCP background hold skipped: https url arg missing")
                return@runBlocking
            }
            if (!ensureVpnPermission(app)) {
                Log.d(TEST_TAG, "manual smart VLESS TCP background hold skipped: vpn permission missing")
                return@runBlocking
            }
            val holdDurationMs = longArgument("foxhole.backgroundHoldMs", 15 * 60 * 1_000L)
            val probeIntervalMs = longArgument("foxhole.backgroundProbeIntervalMs", 60_000L)
            val requireSuccess = requireLiveSmartSuccess()
            resetRelevantSettings(app)
            clearProfiles(app)

            val imported =
                app.container.profileRepository.importProfile(
                    rawInput = subscriptionUrl,
                    preferredName = "Live Smart Background",
                    allowInsecureTlsForProfile =
                        InstrumentationRegistry
                            .getArguments()
                            .getString("foxhole.allowInsecureTlsForLiveSubscription") == "1",
                )
            app.container.connectionController.setActiveProfile(imported.id)
            val profiles = app.container.profileRepository.profiles.first()
            val vlessTargets =
                profiles.flatMap { profile ->
                    profile
                        .runtimeProbeTargets()
                        .filter { target -> target.protocolHint == ProtocolHint.VLESS }
                        .map { target -> profile to target }
                }
            Log.d(
                TEST_TAG,
                "liveSmartBackground import profiles=${profiles.size} vlessTargets=${vlessTargets.size}",
            )
            val selectedTarget = vlessTargets.firstOrNull()
            if (selectedTarget == null) {
                if (requireSuccess) {
                    assertTrue("smart subscription did not expose a VLESS target", false)
                }
                return@runBlocking
            }
            val (profile, target) = selectedTarget

            disconnectAndWaitForIdle(app)
            baselineRuntimeSettings(app)
            app.container.settingsRepository.updateLatencyProbeMethod(LatencyProbeMethod.TCP)
            app.container.connectionController.setActiveProfile(profile.id)
            val startedAt = System.currentTimeMillis()
            Log.d(
                TEST_TAG,
                "liveSmartBackground start profileId=${profile.id} protocol=vless optionId=${target.optionId.orEmpty()} durationMs=$holdDurationMs intervalMs=$probeIntervalMs",
            )
            app.container.connectionController.connect(profile.id, protocolOptionId = target.optionId)
            val terminalState =
                withTimeoutOrNull(liveSmartTerminalTimeoutMs(ProtocolHint.VLESS)) {
                    waitForActiveConnectionAttempt(app)
                    waitForTerminalState(app)
                }
            if (terminalState != ConnectionState.CONNECTED) {
                val snapshot = app.container.connectionController.snapshot.value
                val evidence =
                    TunnelValidationEvidenceClassifier.classify(
                        entries = app.container.diagnosticsLogger.entries.value,
                        sinceMs = startedAt,
                    )
                Log.d(
                    TEST_TAG,
                    "liveSmartBackground connectFailed terminalState=${terminalState?.name ?: "TIMEOUT"} message=${snapshot.message.orEmpty().take(160)} fatal=${evidence.fatalRuntimeMessage.orEmpty().take(200)} successTraffic=${evidence.hasSuccessfulTunnelActivity}",
                )
                if (requireSuccess) {
                    assertEquals(ConnectionState.CONNECTED, terminalState)
                }
                disconnectAndWaitForIdle(app)
                return@runBlocking
            }

            val initialIpRefresh = runVpnBoundIpRefresh(app)
            Log.d(TEST_TAG, "liveSmartBackground initialIpRefresh=$initialIpRefresh")
            shell("input keyevent KEYCODE_HOME")

            val failures = mutableListOf<String>()
            var probeCount = 0
            val holdStartedAt = System.currentTimeMillis()
            val deadline = holdStartedAt + holdDurationMs.coerceAtLeast(1L)
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                delay(probeIntervalMs.coerceAtLeast(1L).coerceAtMost(deadline - now))
                probeCount += 1
                val elapsedSec = (System.currentTimeMillis() - holdStartedAt) / 1_000L
                val snapshot = app.container.connectionController.snapshot.value
                val traffic = app.container.connectionController.traffic.value
                val latencyResult =
                    runCatching {
                        app.container.connectionController.measureCurrentConnectionLatency(timeoutMs = 8_000L)
                    }.fold(
                        onSuccess = { "ok:$it" },
                        onFailure = { error -> "fail:${error.javaClass.simpleName}:${error.message.orEmpty().take(80)}" },
                    )
                val ipRefreshResult = runVpnBoundIpRefresh(app)
                if (snapshot.state != ConnectionState.CONNECTED) {
                    failures += "state@$elapsedSec=${snapshot.state.name}:${snapshot.message.orEmpty().take(80)}"
                }
                if (!latencyResult.startsWith("ok:")) {
                    failures += "tcpLatency@$elapsedSec=$latencyResult"
                }
                if (!ipRefreshResult.startsWith("ok:")) {
                    failures += "ipRefresh@$elapsedSec=$ipRefreshResult"
                }
                Log.d(
                    TEST_TAG,
                    "liveSmartBackground tick elapsedSec=$elapsedSec state=${snapshot.state.name} latencyTcp=$latencyResult ipRefresh=$ipRefreshResult rxTotal=${traffic.rxTotalBytes} txTotal=${traffic.txTotalBytes}",
                )
            }

            val evidence =
                TunnelValidationEvidenceClassifier.classify(
                    entries = app.container.diagnosticsLogger.entries.value,
                    sinceMs = startedAt,
                )
            val finalSnapshot = app.container.connectionController.snapshot.value
            Log.d(
                TEST_TAG,
                "liveSmartBackground result probes=$probeCount finalState=${finalSnapshot.state.name} failures=${failures.size} fatal=${evidence.fatalRuntimeMessage.orEmpty().take(200)} successTraffic=${evidence.hasSuccessfulTunnelActivity}",
            )
            if (requireSuccess) {
                assertEquals(ConnectionState.CONNECTED, finalSnapshot.state)
                assertTrue("background failures: ${failures.joinToString(" | ")}", failures.isEmpty())
                assertTrue("fatal runtime evidence: ${evidence.fatalRuntimeMessage.orEmpty()}", evidence.fatalRuntimeMessage == null)
                assertTrue("tunnel traffic evidence missing", evidence.hasSuccessfulTunnelActivity)
            }
            disconnectAndWaitForIdle(app)
        }
    }

    @Test
    fun manualSmartSubscriptionVlessNetworkSwitchRefresh() {
        assumeTrue(
            "manual smart VLESS network switch refresh skipped: pass -e foxhole.liveSmartVlessNetworkSwitch 1 to run it",
            InstrumentationRegistry.getArguments().getString("foxhole.liveSmartVlessNetworkSwitch") == "1",
        )
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val subscriptionUrl =
                InstrumentationRegistry
                    .getArguments()
                    .getString("foxhole.smartSubscriptionUrl")
                    ?.trim()
                    ?.takeIf { it.startsWith("https://", ignoreCase = true) }
            if (subscriptionUrl == null) {
                Log.d(TEST_TAG, "manual smart VLESS network switch refresh skipped: https url arg missing")
                return@runBlocking
            }
            if (!ensureVpnPermission(app)) {
                Log.d(TEST_TAG, "manual smart VLESS network switch refresh skipped: vpn permission missing")
                return@runBlocking
            }

            resetRelevantSettings(app)
            clearProfiles(app)
            val imported =
                app.container.profileRepository.importProfile(
                    rawInput = subscriptionUrl,
                    preferredName = "Live Smart Network Switch",
                    allowInsecureTlsForProfile =
                        InstrumentationRegistry
                            .getArguments()
                            .getString("foxhole.allowInsecureTlsForLiveSubscription") == "1",
                )
            app.container.connectionController.setActiveProfile(imported.id)
            val selectedTarget =
                app.container.profileRepository.profiles
                    .first()
                    .flatMap { profile ->
                        profile
                            .runtimeProbeTargets()
                            .filter { target -> target.protocolHint == ProtocolHint.VLESS }
                            .map { target -> profile to target }
                    }
                    .firstOrNull()
            if (selectedTarget == null) {
                assertTrue("smart subscription did not expose a VLESS target", false)
                return@runBlocking
            }

            shell("am start -n ${app.packageName}/com.foxhole.guard.ui.cli.CliMainActivity")
            delay(1_000)

            val (profile, target) = selectedTarget
            disconnectAndWaitForIdle(app)
            baselineRuntimeSettings(app)
            app.container.connectionController.setActiveProfile(profile.id)
            val startedAt = System.currentTimeMillis()
            Log.d(
                TEST_TAG,
                "liveSmartNetworkSwitch start profileId=${profile.id} protocol=vless optionId=${target.optionId.orEmpty()}",
            )
            app.container.connectionController.connect(profile.id, protocolOptionId = target.optionId)
            val terminalState =
                withTimeoutOrNull(liveSmartTerminalTimeoutMs(ProtocolHint.VLESS)) {
                    waitForActiveConnectionAttempt(app)
                    waitForTerminalState(app)
                }
            assertEquals(ConnectionState.CONNECTED, terminalState)
            val initialIpRefresh = runVpnBoundIpRefresh(app)
            Log.d(TEST_TAG, "liveSmartNetworkSwitch initialIpRefresh=$initialIpRefresh")
            assertTrue("initial vpn-bound ip refresh failed: $initialIpRefresh", initialIpRefresh.startsWith("ok:"))

            val switchStartedAt = System.currentTimeMillis()
            var wifiEnabled = false
            try {
                Log.d(TEST_TAG, "liveSmartNetworkSwitch disabling wifi")
                shell("svc wifi disable")
                val serviceSignal =
                    waitForDiagnosticMessage(
                        app = app,
                        sinceMs = switchStartedAt,
                        timeoutMs = 45_000L,
                    ) { message ->
                        message.contains("upstream network refresh signal") &&
                            (message.contains("reason=switched") || message.contains("reason=available"))
                    }
                val dashboardRefresh =
                    waitForDiagnosticMessage(
                        app = app,
                        sinceMs = switchStartedAt,
                        timeoutMs = 30_000L,
                    ) { message ->
                        message.contains("dashboard refresh started") &&
                            message.contains("reason=network_change") &&
                            message.contains("target=vpn_bound") &&
                            message.contains("showLoading=true")
                    }
                val cellularDashboardResult =
                    waitForDiagnosticMessage(
                        app = app,
                        sinceMs = switchStartedAt,
                        timeoutMs = 8_000L,
                    ) { message ->
                        message.contains("geo refreshed") &&
                            message.contains("reason=network_change") &&
                            message.contains("target=vpn_bound")
                    }
                Log.d(
                    TEST_TAG,
                    "liveSmartNetworkSwitch cellularResult serviceSignal=$serviceSignal dashboardRefresh=$dashboardRefresh dashboardResult=$cellularDashboardResult",
                )
                assertTrue(
                    diagnosticMessagesSince(app, switchStartedAt).joinToString(separator = "\n") { it.take(240) },
                    serviceSignal && dashboardRefresh,
                )

                val wifiReturnStartedAt = System.currentTimeMillis()
                Log.d(TEST_TAG, "liveSmartNetworkSwitch enabling wifi")
                shell("svc wifi enable")
                wifiEnabled = true
                val wifiServiceSignal =
                    waitForDiagnosticMessage(
                        app = app,
                        sinceMs = wifiReturnStartedAt,
                        timeoutMs = 45_000L,
                    ) { message ->
                        message.contains("upstream network refresh signal") &&
                            message.contains("reason=available")
                    }
                val wifiDashboardRefresh =
                    waitForDiagnosticMessage(
                        app = app,
                        sinceMs = wifiReturnStartedAt,
                        timeoutMs = 30_000L,
                    ) { message ->
                        message.contains("dashboard refresh started") &&
                            message.contains("reason=network_change") &&
                            message.contains("target=vpn_bound") &&
                            message.contains("showLoading=true")
                    }
                val wifiDashboardResult =
                    waitForDiagnosticMessage(
                        app = app,
                        sinceMs = wifiReturnStartedAt,
                        timeoutMs = 45_000L,
                    ) { message ->
                        message.contains("geo refreshed") &&
                            message.contains("reason=network_change") &&
                            message.contains("target=vpn_bound")
                    }
                Log.d(
                    TEST_TAG,
                    "liveSmartNetworkSwitch wifiResult serviceSignal=$wifiServiceSignal dashboardRefresh=$wifiDashboardRefresh dashboardResult=$wifiDashboardResult",
                )
                assertTrue(
                    diagnosticMessagesSince(app, wifiReturnStartedAt).joinToString(separator = "\n") { it.take(240) },
                    wifiServiceSignal && wifiDashboardRefresh && wifiDashboardResult,
                )
            } finally {
                if (!wifiEnabled) {
                    Log.d(TEST_TAG, "liveSmartNetworkSwitch restoring wifi")
                    shell("svc wifi enable")
                }
                delay(3_000)
                disconnectAndWaitForIdle(app)
            }
            val evidence =
                TunnelValidationEvidenceClassifier.classify(
                    entries = app.container.diagnosticsLogger.entries.value,
                    sinceMs = startedAt,
                )
            assertTrue("fatal runtime evidence: ${evidence.fatalRuntimeMessage.orEmpty()}", evidence.fatalRuntimeMessage == null)
        }
    }

    @Test
    fun manualSmartSubscriptionRuntimeStressCycles() {
        assumeTrue(
            "manual runtime stress skipped: pass -e foxhole.liveRuntimeStress 1 to run it",
            InstrumentationRegistry.getArguments().getString("foxhole.liveRuntimeStress") == "1",
        )
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val requireSuccess = requireLiveSmartSuccess()
            val subscriptionInput = smartSubscriptionInput()
            if (subscriptionInput == null) {
                Log.d(TEST_TAG, "manual runtime stress skipped: subscription input missing")
                if (requireSuccess) {
                    assertTrue("smart subscription input missing for required runtime stress", false)
                }
                return@runBlocking
            }
            if (!ensureVpnPermission(app)) {
                Log.d(TEST_TAG, "manual runtime stress skipped: vpn permission missing")
                if (requireSuccess) {
                    assertTrue("vpn permission missing for required runtime stress", false)
                }
                return@runBlocking
            }
            val cycleCount = longArgument("foxhole.runtimeStressCycles", 50L).toInt().coerceIn(1, 200)
            val targetProtocols = requestedSmartProbeProtocols(defaultProtocols = setOf(ProtocolHint.VLESS))
            val samples = mutableListOf<RuntimeStressResourceSample>()

            resetRelevantSettings(app)
            clearProfiles(app)
            val imported =
                app.container.profileRepository.importProfile(
                    rawInput = subscriptionInput,
                    preferredName = "Live Runtime Stress",
                    allowInsecureTlsForProfile =
                        InstrumentationRegistry
                            .getArguments()
                            .getString("foxhole.allowInsecureTlsForLiveSubscription") == "1",
                )
            app.container.connectionController.setActiveProfile(imported.id)
            val selectedTarget =
                app.container.profileRepository.profiles
                    .first()
                    .flatMap { profile ->
                        profile
                            .runtimeProbeTargets()
                            .filter { target -> target.protocolHint in targetProtocols }
                            .map { target -> profile to target }
                    }.firstOrNull()
            if (selectedTarget == null) {
                if (requireSuccess) {
                    assertTrue("smart subscription did not expose requested stress target", false)
                }
                return@runBlocking
            }
            val (profile, target) = selectedTarget

            try {
                disconnectAndWaitForIdle(app)
                baselineRuntimeSettings(app)
                repeat(cycleCount) { index ->
                    val cycle = index + 1
                    app.container.connectionController.setActiveProfile(profile.id)
                    val startedAt = System.currentTimeMillis()
                    Log.d(
                        TEST_TAG,
                        "liveRuntimeStress cycleStart=$cycle/$cycleCount profileId=${profile.id} protocol=${target.protocolHint.name.lowercase()} optionId=${target.optionId.orEmpty()}",
                    )
                    app.container.connectionController.connect(profile.id, protocolOptionId = target.optionId)
                    val terminalState =
                        withTimeoutOrNull(liveSmartTerminalTimeoutMs(target.protocolHint)) {
                            waitForActiveConnectionAttempt(app)
                            waitForTerminalState(app)
                        }
                    val ipRefreshResult =
                        if (terminalState == ConnectionState.CONNECTED) {
                            runVpnBoundIpRefresh(app)
                        } else {
                            "skipped"
                        }
                    val evidence =
                        TunnelValidationEvidenceClassifier.classify(
                            entries = app.container.diagnosticsLogger.entries.value,
                            sinceMs = startedAt,
                        )
                    Log.d(
                        TEST_TAG,
                        "liveRuntimeStress cycleConnected=$cycle terminalState=${terminalState?.name ?: "TIMEOUT"} ipRefresh=$ipRefreshResult fatal=${evidence.fatalRuntimeMessage.orEmpty().take(200)} successTraffic=${evidence.hasSuccessfulTunnelActivity}",
                    )
                    if (requireSuccess) {
                        assertEquals(ConnectionState.CONNECTED, terminalState)
                        assertTrue("cycle $cycle vpn-bound ip refresh failed: $ipRefreshResult", ipRefreshResult.startsWith("ok:"))
                        assertTrue("cycle $cycle fatal runtime evidence: ${evidence.fatalRuntimeMessage.orEmpty()}", evidence.fatalRuntimeMessage == null)
                        assertTrue("cycle $cycle tunnel traffic evidence missing", evidence.hasSuccessfulTunnelActivity)
                    }

                    disconnectAndWaitForIdle(app)
                    val expectedStopEvent =
                        if (terminalState == ConnectionState.CONNECTED) {
                            RUNTIME_STOP_DISCONNECT_EVENT
                        } else {
                            RUNTIME_SERVICE_DESTROY_CALLBACKS_UNREGISTERED_EVENT
                        }
                    val stopDetails =
                        waitForRuntimeStopDetails(
                            app = app,
                            sinceMs = startedAt,
                            requiredEvent = expectedStopEvent,
                        )
                    FoxholeConnectionServiceContract.stopAllServices(app)
                    waitUntil(timeoutMs = 20_000L) {
                        !hasFoxholeRuntimeServices(app) && !hasActiveFoxholeVpnNetwork(app)
                    }
                    val callbackDetails = waitForRuntimeCallbackCleanupDetails(app, sinceMs = startedAt)
                    val sample = captureRuntimeStressResourceSample(app, cycle, stopDetails, callbackDetails)
                    samples += sample
                    assertRuntimeStoppedAfterCycle(sample, expectedStopEvent)
                    Log.d(
                        TEST_TAG,
                        "liveRuntimeStress cycleStopped=$cycle connectionState=${sample.connectionState} runtimePhase=${sample.runtimePhase} runtimeGeneration=${sample.runtimeGeneration} nativeGeneration=${sample.nativeGeneration ?: "unknown"} commandQueueDepth=${sample.commandQueueDepth ?: "unknown"} rssKb=${sample.rssKb ?: "unknown"} pssKb=${sample.pssKb ?: "unknown"} nativeHeapKb=${sample.nativeHeapKb ?: "unknown"} javaHeapKb=${sample.javaHeapKb} threads=${sample.threadCount} event=${sample.latestRuntimeEvent.orEmpty()} nativeServer=${sample.hasNativeServer} tunFd=${sample.hasTunFileDescriptor} callbacks=${sample.networkCallbacks} cleanupDraining=${sample.cleanupDraining} ipDevice=${sample.ipDeviceState} ipTunnel=${sample.ipTunnelState} ipTor=${sample.ipTorState} torState=${sample.torState}",
                    )
                }
                assertRuntimeStressMemoryStable(samples)
            } finally {
                disconnectAndWaitForIdle(app)
                FoxholeConnectionServiceContract.stopAllServices(app)
            }
        }
    }

    @Test
    fun restoreBaselineRuntimeSettingsWhenRequested() {
        assumeTrue(
            "restore runtime baseline skipped: pass -e foxhole.restoreRuntimeBaseline 1 to run it",
            InstrumentationRegistry.getArguments().getString("foxhole.restoreRuntimeBaseline") == "1",
        )
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            baselineRuntimeSettings(app)
            Log.d(TEST_TAG, "restore runtime baseline applied")
        }
    }

    @Test
    fun liveOptionProbeMatrixLogsVpnBoundIpResults() {
        assumeTrue(
            "live option probe skipped: pass -e foxhole.liveOptionProbe 1 to run it",
            InstrumentationRegistry.getArguments().getString("foxhole.liveOptionProbe") == "1",
        )
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val storedActive = app.container.profileRepository.getActiveProfile()
            if (storedActive == null) {
                Log.d(TEST_TAG, "live option probe skipped: active profile is missing")
                return@runBlocking
            }
            if (VpnService.prepare(app) != null) {
                Log.d(TEST_TAG, "live option probe skipped: vpn permission missing")
                return@runBlocking
            }
            val settingsRepository = app.container.settingsRepository
            val previousSettings = settingsRepository.current()
            try {
                baselineRuntimeSettings(app)
                val active =
                    if (storedActive.sourceType == ProfileSourceType.SUBSCRIPTION_URL) {
                        runCatching {
                            app.container.profileRepository.refreshProfile(storedActive.id)
                        }.onFailure { error ->
                            Log.d(
                                TEST_TAG,
                                "live option probe pre-refresh failed: ${error.javaClass.simpleName}",
                            )
                        }.getOrDefault(storedActive)
                    } else {
                        storedActive
                    }
                val targetOptions =
                    MultiProtocolProfileSupport.supportedOptions(active).ifEmpty {
                        if (active.protocolHint == ProtocolHint.UNKNOWN || active.protocolHint == ProtocolHint.CUSTOM_CONFIG) {
                            emptyList()
                        } else {
                            listOf(
                                ProfileProtocolOption(
                                    id = "",
                                    displayName = active.protocolHint.name,
                                    protocolHint = active.protocolHint,
                                    isSelected = true,
                                ),
                            )
                        }
                    }
                if (targetOptions.isEmpty()) {
                    Log.d(TEST_TAG, "live option probe skipped: target options are missing")
                    return@runBlocking
                }
                val variants =
                    listOf(
                        ProbeVariant(name = "system-strict", tunStack = TunStack.SYSTEM, strictRoute = true),
                        ProbeVariant(name = "system-relaxed", tunStack = TunStack.SYSTEM, strictRoute = false),
                        ProbeVariant(name = "gvisor-strict", tunStack = TunStack.GVISOR, strictRoute = true),
                        ProbeVariant(name = "gvisor-relaxed", tunStack = TunStack.GVISOR, strictRoute = false),
                    )
                targetOptions.forEach { option ->
                    variants.forEach { variant ->
                        app.container.connectionController.disconnect()
                        delay(3_000)
                        applyProbeVariant(app, variant)
                        app.container.connectionController.connect(
                            active.id,
                            protocolOptionId = option.id.takeIf(String::isNotEmpty),
                        )
                        val terminalState =
                            withTimeoutOrNull(35_000) {
                                while (true) {
                                    val state = app.container.connectionController.snapshot.value.state
                                    if (state.name == "CONNECTED" || state.name == "ERROR" || state.name == "IDLE") {
                                        return@withTimeoutOrNull state.name
                                    }
                                    delay(250)
                                }
                            } ?: "TIMEOUT"
                        val ipResult =
                            runCatching {
                                app.container.connectionController.refreshIpInfo()
                            }
                        val message =
                            buildString {
                                append("probe option=")
                                append(option.displayName)
                                append(" variant=")
                                append(variant.name)
                                append(" terminalState=")
                                append(terminalState)
                                append(" ipResult=")
                                if (ipResult.isSuccess) {
                                    val info = ipResult.getOrThrow()
                                    append(
                                        when {
                                            info.ipv4 != null -> "ok:ipv4"
                                            info.ipv6 != null -> "ok:ipv6"
                                            else -> "ok:unknown"
                                        },
                                    )
                                } else {
                                    append("fail:")
                                    append(ipResult.exceptionOrNull()?.message.orEmpty())
                                }
                            }
                        Log.d(TEST_TAG, message)
                        app.container.connectionController.disconnect()
                        delay(3_000)
                    }
                }
            } finally {
                restoreSettings(settingsRepository, previousSettings)
            }
        }
    }

}
