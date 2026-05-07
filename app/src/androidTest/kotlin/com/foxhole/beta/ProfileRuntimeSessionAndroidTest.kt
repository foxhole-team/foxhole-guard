package com.foxhole.beta

import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TunStack
import com.foxhole.beta.vpn.TunnelValidationEvidenceClassifier
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileRuntimeSessionAndroidTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun logsCurrentActiveProfileRuntimeSummary() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val profiles = app.container.profileDatabase.profileDao().observeProfiles().first()
            Log.d(
                TEST_TAG,
                "profiles=${profiles.joinToString { "${it.id}:${it.name}:${it.isActive}" }}",
            )

            val active = app.container.profileRepository.getActiveProfile()
            if (active == null) {
                Log.d(TEST_TAG, "active profile is missing")
                return@runBlocking
            }

            val session = app.container.profileRepository.getSession(active.id)
            val root = json.parseToJsonElement(session.configJson).jsonObject
            val primary = root["outbounds"]!!.jsonArray.first().jsonObject
            val serverPort = primary["server_port"]!!.jsonPrimitive.content
            val outboundType = primary["type"]!!.jsonPrimitive.content

            Log.d(
                TEST_TAG,
                "activeProfileId=${active.id} name=${active.name} outboundType=$outboundType serverPort=$serverPort",
            )
            assertTrue(serverPort.isNotBlank())
        }

    @Test
    fun freshExactImportPreservesUserServerPort() {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            resetRelevantSettings(app)
            clearProfiles(app)

            val imported = app.container.profileRepository.importProfile(EXACT_USER_XRAY_CONFIG)
            app.container.connectionController.setActiveProfile(imported.id)

            val session = app.container.profileRepository.getSession(imported.id)
            val root = json.parseToJsonElement(session.configJson).jsonObject
            val primary = root["outbounds"]!!.jsonArray.first().jsonObject
            val routeRules = root["route"]!!.jsonObject["rules"]!!.jsonArray

            assertEquals("43000", primary["server_port"]!!.jsonPrimitive.content)
            routeRules
                .mapNotNull { rule -> rule.jsonObject["port"]?.jsonPrimitive }
                .forEach { port -> assertEquals(false, port.isString) }

            Log.d(
                TEST_TAG,
                "freshImport profileId=${imported.id} serverPort=${primary["server_port"]!!.jsonPrimitive.content}",
            )
        }
    }

    @Test
    fun freshDirectShareImportsPreserveProvidedRuntimeFields() {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            resetRelevantSettings(app)

            clearProfiles(app)
            val importedVless = app.container.profileRepository.importProfile(DIRECT_VLESS_REALITY_URI)
            val vlessSession = app.container.profileRepository.getSession(importedVless.id)
            val vlessRoot = json.parseToJsonElement(vlessSession.configJson).jsonObject
            val vlessOutbound = vlessRoot["outbounds"]!!.jsonArray.first().jsonObject
            val vlessTls = vlessOutbound["tls"]!!.jsonObject
            val vlessReality = vlessTls["reality"]!!.jsonObject
            val vlessUtls = vlessTls["utls"]!!.jsonObject
            assertEquals("axn666.nl", vlessOutbound["server"]!!.jsonPrimitive.content)
            assertEquals("8447", vlessOutbound["server_port"]!!.jsonPrimitive.content)
            assertEquals("www.microsoft.com", vlessTls["server_name"]!!.jsonPrimitive.content)
            assertEquals("chrome", vlessUtls["fingerprint"]!!.jsonPrimitive.content)
            assertEquals(
                "WG2E71GvJTVvpUigKJ7UgC0-XyarAVTkvPQMbH8h2iM",
                vlessReality["public_key"]!!.jsonPrimitive.content,
            )
            assertEquals("03d0b309d56c352b", vlessReality["short_id"]!!.jsonPrimitive.content)

            clearProfiles(app)
            val importedHysteria2 = app.container.profileRepository.importProfile(DIRECT_HYSTERIA2_URI)
            val hysteria2Session = app.container.profileRepository.getSession(importedHysteria2.id)
            val hysteria2Root = json.parseToJsonElement(hysteria2Session.configJson).jsonObject
            val hysteria2Outbound = hysteria2Root["outbounds"]!!.jsonArray.first().jsonObject
            val hysteria2Tls = hysteria2Outbound["tls"]!!.jsonObject

            assertEquals("axn666.nl", hysteria2Outbound["server"]!!.jsonPrimitive.content)
            assertEquals("8443", hysteria2Outbound["server_port"]!!.jsonPrimitive.content)
            assertEquals("axn666.nl", hysteria2Tls["server_name"]!!.jsonPrimitive.content)
            assertEquals(
                "RLS3MLv81RhMPNr5xHRnqGmEPUkIFxxI1fTbAqzmZ+s=",
                hysteria2Outbound["password"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun manualFreshImportConnectsWhenRequested() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveConnect") != "1") {
            Log.d(TEST_TAG, "manual live connect skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            if (VpnService.prepare(app) != null) {
                Log.d(TEST_TAG, "manual live connect skipped: vpn permission missing")
                return@runBlocking
            }
            resetRelevantSettings(app)
            clearProfiles(app)

            val imported = app.container.profileRepository.importProfile(EXACT_USER_XRAY_CONFIG)
            app.container.connectionController.setActiveProfile(imported.id)
            Log.d(TEST_TAG, "manual live connect imported profileId=${imported.id}")

            app.container.connectionController.connect(imported.id)
            delay(20_000)
            app.container.connectionController.disconnect()
            delay(3_000)
        }
    }

    @Test
    fun manualDirectShareLinksLogTerminalStateAndDiagnostics() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveDirectLinks") != "1") {
            Log.d(TEST_TAG, "manual direct-link live connect skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            assumeTrue("manual direct-link live connect requires pre-granted VPN permission", VpnService.prepare(app) == null)
            resetRelevantSettings(app)
            val requestedLabel = InstrumentationRegistry.getArguments().getString("foxhole.liveDirectLabel")
            val linkCases =
                DIRECT_LINK_CASES.filter { linkCase ->
                    requestedLabel.isNullOrBlank() || linkCase.label == requestedLabel
                }
            assumeTrue("manual direct-link live connect has no case for label=$requestedLabel", linkCases.isNotEmpty())

            linkCases.forEach { linkCase ->
                clearProfiles(app)
                val imported = app.container.profileRepository.importProfile(linkCase.rawLink)
                app.container.connectionController.setActiveProfile(imported.id)
                val startedAt = System.currentTimeMillis()
                Log.d(TEST_TAG, "liveDirectLink start label=${linkCase.label} profileId=${imported.id}")

                app.container.connectionController.connect(imported.id)
                val terminalState: ConnectionState? =
                    withTimeoutOrNull(LIVE_DIRECT_LINK_TERMINAL_TIMEOUT_MS) {
                        waitForActiveConnectionAttempt(app)
                        waitForTerminalState(app)
                    }
                delay(2_000)
                val evidence =
                    TunnelValidationEvidenceClassifier.classify(
                        entries = app.container.diagnosticsLogger.entries.value,
                        sinceMs = startedAt,
                    )
                val terminalMessage = app.container.connectionController.snapshot.value.message
                val terminalStateLabel = terminalState?.name ?: "TIMEOUT"
                Log.d(
                    TEST_TAG,
                    "liveDirectLink result label=${linkCase.label} terminalState=$terminalStateLabel message=${terminalMessage.orEmpty()} fatal=${evidence.fatalRuntimeMessage.orEmpty()} successTraffic=${evidence.hasSuccessfulTunnelActivity}",
                )
                app.container.connectionController.disconnect()
                delay(3_000)
            }
        }
    }

    @Test
    fun manualDirectShareHysteriaWarmupThenTcpRuntime() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveDirectWarmup") != "1") {
            Log.d(TEST_TAG, "manual direct-link warmup live connect skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            assumeTrue("manual direct-link warmup requires pre-granted VPN permission", VpnService.prepare(app) == null)
            resetRelevantSettings(app)
            clearProfiles(app)

            val importedVless = app.container.profileRepository.importProfile(DIRECT_VLESS_REALITY_URI, preferredName = "Warmup VLESS")
            val importedHysteria = app.container.profileRepository.importProfile(DIRECT_HYSTERIA2_URI, preferredName = "Warmup Hysteria2")

            runWarmupProbe(app, label = "vless-before-hysteria", profileId = importedVless.id)
            runWarmupProbe(app, label = "hysteria2-warmup", profileId = importedHysteria.id)
            runWarmupProbe(app, label = "vless-after-hysteria", profileId = importedVless.id)

            app.container.connectionController.disconnect()
            delay(3_000)
        }
    }

    @Test
    fun manualSmartSubscriptionLogsImportAndTargetProtocolRuntime() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveSmartSubscription") != "1") {
            Log.d(TEST_TAG, "manual smart subscription live connect skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val subscriptionUrl =
                InstrumentationRegistry
                    .getArguments()
                    .getString("foxhole.smartSubscriptionUrl")
                    ?.trim()
                    ?.takeIf { it.startsWith("https://", ignoreCase = true) }
            if (subscriptionUrl == null) {
                Log.d(TEST_TAG, "manual smart subscription skipped: https url arg missing")
                return@runBlocking
            }
            if (!ensureVpnPermission(app)) {
                Log.d(TEST_TAG, "manual smart subscription skipped: vpn permission missing")
                return@runBlocking
            }
            val targetProtocols = requestedSmartProbeProtocols()
            resetRelevantSettings(app)
            clearProfiles(app)

            val imported =
                app.container.profileRepository.importProfile(
                    rawInput = subscriptionUrl,
                    preferredName = "Live Smart",
                    allowInsecureTlsForProfile =
                        InstrumentationRegistry
                            .getArguments()
                            .getString("foxhole.allowInsecureTlsForLiveSubscription") == "1",
                )
            app.container.connectionController.setActiveProfile(imported.id)
            val profiles = app.container.profileRepository.profiles.first()
            val targetProtocolSummary = targetProtocols.joinToString { it.name.lowercase() }
            val profileProtocolSummary =
                profiles.joinToString { profile ->
                    val protocolHints = profile.runtimeProbeTargets().map { it.protocolHint }.distinct()
                    protocolHints.joinToString("|") { it.name.lowercase() }
                }
            Log.d(
                TEST_TAG,
                "liveSmart import profiles=${profiles.size} targets=$targetProtocolSummary protocolOptions=$profileProtocolSummary",
            )

            profiles.forEach { profile ->
                profile
                    .runtimeProbeTargets()
                    .filter { it.protocolHint in targetProtocols }
                    .forEach { target ->
                        disconnectAndWaitForIdle(app)
                        baselineRuntimeSettings(app)
                        app.container.connectionController.setActiveProfile(profile.id)
                        val startedAt = System.currentTimeMillis()
                        Log.d(
                            TEST_TAG,
                            "liveSmartStart profileId=${profile.id} protocol=${target.protocolHint.name.lowercase()} optionId=${target.optionId.orEmpty()}",
                        )
                        app.container.connectionController.connect(profile.id, protocolOptionId = target.optionId)
                        val terminalState =
                            withTimeoutOrNull(liveSmartTerminalTimeoutMs(target.protocolHint)) {
                                waitForActiveConnectionAttempt(app)
                                waitForTerminalState(app)
                            }
                        delay(2_000)
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
                        val evidence =
                            TunnelValidationEvidenceClassifier.classify(
                                entries = app.container.diagnosticsLogger.entries.value,
                                sinceMs = startedAt,
                            )
                        val snapshot = app.container.connectionController.snapshot.value
                        val terminalStateLabel = terminalState?.name ?: "TIMEOUT"
                        val snapshotMessage = snapshot.message.orEmpty().take(160)
                        val fatalMessage = evidence.fatalRuntimeMessage.orEmpty().take(200)
                        Log.d(
                            TEST_TAG,
                            "liveSmart result profileId=${profile.id} protocol=${target.protocolHint.name.lowercase()} terminalState=$terminalStateLabel ipRefresh=$ipRefreshResult message=$snapshotMessage fatal=$fatalMessage successTraffic=${evidence.hasSuccessfulTunnelActivity}",
                        )
                        if (requireLiveSmartSuccess()) {
                            assertEquals(ConnectionState.CONNECTED.name, terminalStateLabel)
                            assertTrue(evidence.hasSuccessfulTunnelActivity)
                            assertTrue(fatalMessage.isEmpty())
                        }
                    }
            }
            disconnectAndWaitForIdle(app)
        }
    }

    @Test
    fun manualSmartSubscriptionVlessTcpBackgroundHold() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveSmartVlessTcpBackground") != "1") {
            Log.d(TEST_TAG, "manual smart VLESS TCP background hold skipped")
            return
        }
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
    fun restoreBaselineRuntimeSettingsWhenRequested() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.restoreRuntimeBaseline") != "1") {
            Log.d(TEST_TAG, "restore runtime baseline skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            baselineRuntimeSettings(app)
            Log.d(TEST_TAG, "restore runtime baseline applied")
        }
    }

    @Test
    fun liveOptionProbeMatrixLogsVpnBoundIpResults() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveOptionProbe") != "1") {
            Log.d(TEST_TAG, "live option probe skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val active = app.container.profileRepository.getActiveProfile()
            if (active == null) {
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
                val targetOptions =
                    active.protocolOptions.filter { option ->
                        option.displayName.contains("VLESS", ignoreCase = true) ||
                            option.displayName.contains("TROJAN", ignoreCase = true) ||
                            option.displayName.contains("SHADOWSOCKS", ignoreCase = true)
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
                        app.container.connectionController.connect(active.id, protocolOptionId = option.id)
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
                                    append("ok:")
                                    append(ipResult.getOrThrow().ipv4 ?: ipResult.getOrThrow().ipv6 ?: "unknown")
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

    private suspend fun resetRelevantSettings(app: FoxholeApplication) {
        with(app.container.settingsRepository) {
            updateAutoReconnect(false)
            updateTrafficMode(TrafficMode.TUNNEL)
            updatePerAppRoutingMode(PerAppRoutingMode.FULL_TUNNEL)
            updateSelectedPackages(emptyList())
            updateSniff(false)
            updateRouteOnly(false)
            updateBypassLan(false)
            updateAllowPrivateOutboundHosts(false)
            updateStrictRoute(true)
        }
    }

    private suspend fun baselineRuntimeSettings(app: FoxholeApplication) {
        with(app.container.settingsRepository) {
            updateAutoReconnect(false)
            updateTrafficMode(TrafficMode.TUNNEL)
            updateTunStack(TunStack.SYSTEM)
            updatePerAppRoutingMode(PerAppRoutingMode.FULL_TUNNEL)
            updateSelectedPackages(emptyList())
            updateSniff(false)
            updateRouteOnly(false)
            updateBypassLan(false)
            updateAllowPrivateOutboundHosts(false)
            updateStrictRoute(true)
            updatePreferIpv6(false)
        }
    }

    private suspend fun applyProbeVariant(
        app: FoxholeApplication,
        variant: ProbeVariant,
    ) {
        with(app.container.settingsRepository) {
            updateTunStack(variant.tunStack)
            updateStrictRoute(variant.strictRoute)
            updateSniff(false)
            updateRouteOnly(false)
            updateBypassLan(false)
            updatePreferIpv6(false)
        }
    }

    private suspend fun restoreSettings(
        repository: com.foxhole.beta.core.settings.SettingsRepository,
        settings: Settings,
    ) {
        repository.replaceForTests(settings)
    }

    private suspend fun waitForTerminalState(app: FoxholeApplication): ConnectionState {
        while (true) {
            val state = app.container.connectionController.snapshot.value.state
            if (state == ConnectionState.CONNECTED || state == ConnectionState.ERROR || state == ConnectionState.IDLE) {
                return state
            }
            delay(250)
        }
    }

    private suspend fun waitForActiveConnectionAttempt(app: FoxholeApplication) {
        while (true) {
            val state = app.container.connectionController.snapshot.value.state
            if (state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING || state == ConnectionState.CONNECTED) {
                return
            }
            delay(100)
        }
    }

    private suspend fun disconnectAndWaitForIdle(app: FoxholeApplication) {
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

    private suspend fun runWarmupProbe(
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

    private fun clearProfiles(app: FoxholeApplication) {
        app.container.profileDatabase.clearAllTables()
        deleteChildren(File(app.filesDir, "profile-secrets"))
    }

    private fun shell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
                return reader.readText()
            }
        }
    }

    private fun deleteChildren(dir: File) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                deleteChildren(child)
            }
            child.delete()
        }
    }

    private fun requestedSmartProbeProtocols(): Set<ProtocolHint> {
        val requested =
            InstrumentationRegistry
                .getArguments()
                .getString("foxhole.smartProtocols")
                ?.split(',')
                ?.mapNotNull { raw ->
                    runCatching {
                        ProtocolHint.valueOf(raw.trim().uppercase())
                    }.getOrNull()
                }
                ?.toSet()
                .orEmpty()
        return requested.ifEmpty { setOf(ProtocolHint.TROJAN, ProtocolHint.WIREGUARD) }
    }

    private fun requireLiveSmartSuccess(): Boolean =
        InstrumentationRegistry.getArguments().getString("foxhole.requireLiveSmartSuccess") == "1"

    private fun longArgument(name: String, defaultValue: Long): Long =
        InstrumentationRegistry
            .getArguments()
            .getString(name)
            ?.toLongOrNull()
            ?: defaultValue

    private suspend fun runVpnBoundIpRefresh(app: FoxholeApplication): String =
        runCatching {
            withTimeoutOrNull(20_000L) {
                app.container.connectionController.refreshIpInfo()
            } ?: error("timeout")
        }.fold(
            onSuccess = { ipInfo -> "ok:${ipInfo.ipv4 ?: ipInfo.ipv6 ?: "unknown"}" },
            onFailure = { error -> "fail:${error.javaClass.simpleName}:${error.message.orEmpty().take(80)}" },
        )

    private data class RuntimeProbeTarget(
        val protocolHint: ProtocolHint,
        val optionId: String?,
    )

    private fun com.foxhole.beta.core.model.Profile.runtimeProbeTargets(): List<RuntimeProbeTarget> =
        protocolOptions
            .map { option -> RuntimeProbeTarget(protocolHint = option.protocolHint, optionId = option.id) }
            .ifEmpty { listOf(RuntimeProbeTarget(protocolHint = protocolHint, optionId = null)) }

    private fun liveSmartTerminalTimeoutMs(protocolHint: ProtocolHint): Long =
        when (protocolHint) {
            ProtocolHint.WIREGUARD,
            ProtocolHint.HYSTERIA2,
            -> 120_000L
            else -> 90_000L
        }

    private suspend fun ensureVpnPermission(app: FoxholeApplication): Boolean {
        VpnService.prepare(app)?.let { prepareIntent ->
            if (InstrumentationRegistry.getArguments().getString("foxhole.requestVpnPermission") != "1") {
                return false
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val activity =
                instrumentation.startActivitySync(
                    Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            instrumentation.runOnMainSync {
                activity.startActivityForResult(prepareIntent, VPN_PERMISSION_REQUEST_CODE)
            }
            Log.d(TEST_TAG, "waiting for vpn permission dialog approval")
            val granted =
                withTimeoutOrNull(45_000) {
                    while (VpnService.prepare(app) != null) {
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

    companion object {
        private const val TEST_TAG = "FoxholeSessionTest"
        private const val VPN_PERMISSION_REQUEST_CODE = 7301
        private const val LIVE_DIRECT_LINK_TERMINAL_TIMEOUT_MS = 150_000L
        private data class DirectLinkCase(
            val label: String,
            val rawLink: String,
        )

        private val DIRECT_LINK_CASES =
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

        private const val DIRECT_VLESS_REALITY_URI =
            "vless://6e9f4de4c1423d55cff3968370533d73@axn666.nl:8447?type=tcp&encryption=none&security=reality&sni=www.microsoft.com&pbk=WG2E71GvJTVvpUigKJ7UgC0-XyarAVTkvPQMbH8h2iM&sid=03d0b309d56c352b&fp=chrome&spx=/#axnet_secure_core_direct_37792e"

        private const val DIRECT_HYSTERIA2_URI =
            "hysteria2://RLS3MLv81RhMPNr5xHRnqGmEPUkIFxxI1fTbAqzmZ+s=@axn666.nl:8443?sni=axn666.nl"

        private const val EXACT_USER_XRAY_CONFIG =
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
                        "address": "37.139.40.59",
                        "port": 43000,
                        "users": [
                          {
                            "encryption": "none",
                            "flow": "xtls-rprx-vision",
                            "id": "1ab729d2-fd63-493d-bf9c-2ee83c01ee3b",
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
                      "publicKey": "IiUOQtR3zqUg32FfqorRXwUVSz9e1CSPFsJnafcFVmE",
                      "serverName": "37.139.40.59",
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

    private data class ProbeVariant(
        val name: String,
        val tunStack: TunStack,
        val strictRoute: Boolean,
    )
}
