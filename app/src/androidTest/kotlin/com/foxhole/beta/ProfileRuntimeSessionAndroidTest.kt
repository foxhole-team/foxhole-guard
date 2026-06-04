package com.foxhole.beta

import android.content.Intent
import android.net.VpnService
import android.os.Debug
import android.util.Base64
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.StatisticsMetric
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TunStack
import com.foxhole.beta.vpn.FoxholeConnectionServiceContract
import com.foxhole.beta.vpn.FoxholeVpnService
import com.foxhole.beta.vpn.LocalGuardMode
import com.foxhole.beta.vpn.RuntimeIpPanelState
import com.foxhole.beta.vpn.RuntimePhase
import com.foxhole.beta.vpn.RuntimeTorUiState
import com.foxhole.beta.vpn.TunnelValidationEvidenceClassifier
import com.foxhole.beta.vpn.localGuardModeOrNull
import java.io.File
import java.io.FileInputStream
import java.util.regex.Pattern
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            val serverPort = primary["server_port"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val outboundType = primary["type"]!!.jsonPrimitive.content

            Log.d(
                TEST_TAG,
                "activeProfileId=${active.id} name=${active.name} outboundType=$outboundType serverPort=$serverPort",
            )
            assertTrue(outboundType.isNotBlank())
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
            app.container.settingsRepository.updateAllowPrivateOutboundHosts(true)

            clearProfiles(app)
            val importedVless = app.container.profileRepository.importProfile(DIRECT_VLESS_REALITY_PRESERVE_URI)
            val vlessSession = app.container.profileRepository.getSession(importedVless.id)
            val vlessRoot = json.parseToJsonElement(vlessSession.configJson).jsonObject
            val vlessOutbound = vlessRoot["outbounds"]!!.jsonArray.first().jsonObject
            val vlessTls = vlessOutbound["tls"]!!.jsonObject
            val vlessReality = vlessTls["reality"]!!.jsonObject
            val vlessUtls = vlessTls["utls"]!!.jsonObject
            assertEquals(PRESERVE_FIXTURE_HOST, vlessOutbound["server"]!!.jsonPrimitive.content)
            assertEquals("8447", vlessOutbound["server_port"]!!.jsonPrimitive.content)
            assertEquals("www.microsoft.com", vlessTls["server_name"]!!.jsonPrimitive.content)
            assertEquals("chrome", vlessUtls["fingerprint"]!!.jsonPrimitive.content)
            assertEquals(
                "WG2E71GvJTVvpUigKJ7UgC0-XyarAVTkvPQMbH8h2iM",
                vlessReality["public_key"]!!.jsonPrimitive.content,
            )
            assertEquals("03d0b309d56c352b", vlessReality["short_id"]!!.jsonPrimitive.content)

            clearProfiles(app)
            val importedHysteria2 = app.container.profileRepository.importProfile(DIRECT_HYSTERIA2_PRESERVE_URI)
            val hysteria2Session = app.container.profileRepository.getSession(importedHysteria2.id)
            val hysteria2Root = json.parseToJsonElement(hysteria2Session.configJson).jsonObject
            val hysteria2Outbound = hysteria2Root["outbounds"]!!.jsonArray.first().jsonObject
            val hysteria2Tls = hysteria2Outbound["tls"]!!.jsonObject

            assertEquals(PRESERVE_FIXTURE_HOST, hysteria2Outbound["server"]!!.jsonPrimitive.content)
            assertEquals("8443", hysteria2Outbound["server_port"]!!.jsonPrimitive.content)
            assertEquals(PRESERVE_FIXTURE_HOST, hysteria2Tls["server_name"]!!.jsonPrimitive.content)
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
            val requireSuccess = requireLiveSmartSuccess()
            val subscriptionInput = smartSubscriptionInput()
            if (subscriptionInput == null) {
                Log.d(TEST_TAG, "manual smart subscription skipped: subscription input missing")
                if (requireSuccess) {
                    assertTrue("smart subscription input missing for required live test", false)
                }
                return@runBlocking
            }
            if (!ensureVpnPermission(app)) {
                Log.d(TEST_TAG, "manual smart subscription skipped: vpn permission missing")
                if (requireSuccess) {
                    assertTrue("vpn permission missing for required live smart test", false)
                }
                return@runBlocking
            }
            val targetProtocols = requestedSmartProbeProtocols()
            resetRelevantSettings(app)
            clearProfiles(app)

            val imported =
                app.container.profileRepository.importProfile(
                    rawInput = subscriptionInput,
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
                        if (requireSuccess) {
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
    fun manualSmartSubscriptionTcpTorRuntime() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveSmartTcpTor") != "1") {
            Log.d(TEST_TAG, "manual smart TCP TOR live connect skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val subscriptionInput = smartSubscriptionInput()
            if (subscriptionInput == null) {
                Log.d(TEST_TAG, "manual smart TCP TOR skipped: subscription input missing")
                return@runBlocking
            }
            val requireSuccess = requireLiveSmartSuccess()
            if (!ensureVpnPermission(app)) {
                Log.d(TEST_TAG, "manual smart TCP TOR skipped: vpn permission missing")
                if (requireSuccess) {
                    assertTrue("vpn permission missing for required live TCP TOR test", false)
                }
                return@runBlocking
            }
            val targetProtocols =
                requestedSmartProbeProtocols(
                    defaultProtocols = setOf(ProtocolHint.VLESS, ProtocolHint.TROJAN, ProtocolHint.SHADOWSOCKS, ProtocolHint.OUTLINE),
                )
            resetRelevantSettings(app)
            clearProfiles(app)

            val imported =
                app.container.profileRepository.importProfile(
                    rawInput = subscriptionInput,
                    preferredName = "Live Smart TOR",
                    allowInsecureTlsForProfile =
                        InstrumentationRegistry
                            .getArguments()
                            .getString("foxhole.allowInsecureTlsForLiveSubscription") == "1",
                )
            app.container.connectionController.setActiveProfile(imported.id)
            val profiles = app.container.profileRepository.profiles.first()
            val selectedTarget =
                profiles
                    .flatMap { profile ->
                        profile
                            .runtimeProbeTargets()
                            .filter { target -> target.protocolHint in targetProtocols }
                            .map { target -> profile to target }
                    }.firstOrNull()
            if (selectedTarget == null) {
                Log.d(TEST_TAG, "manual smart TCP TOR skipped: no TCP target for ${targetProtocols.joinToString { it.name }}")
                if (requireSuccess) {
                    assertTrue("smart subscription did not expose requested TCP target", false)
                }
                return@runBlocking
            }
            val (profile, target) = selectedTarget

            try {
                disconnectAndWaitForIdle(app)
                baselineRuntimeSettings(app)
                app.container.settingsRepository.updatePrivacyRouteScope(PrivacyRouteScope.ALL_APPS)
                app.container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
                app.container.connectionController.setActiveProfile(profile.id)
                val torSession = app.container.profileRepository.getSession(profile.id, target.optionId)
                val torSettings = app.container.settingsRepository.current()
                val outbounds =
                    json.parseToJsonElement(torSession.configJson)
                        .jsonObject["outbounds"]
                        ?.jsonArray
                        .orEmpty()
                        .map { it.jsonObject }
                val torConfigActive =
                    outbounds.any { outbound ->
                        outbound["tag"]?.jsonPrimitive?.content == "tor-over-vpn" &&
                            outbound["type"]?.jsonPrimitive?.content == "tor"
                    }
                val outboundSummary =
                    outbounds.joinToString { outbound ->
                        "${outbound["tag"]?.jsonPrimitive?.content.orEmpty()}:${outbound["type"]?.jsonPrimitive?.content.orEmpty()}"
                    }
                Log.d(
                    TEST_TAG,
                    "liveSmartTcpTor config profileId=${profile.id} targetProtocol=${target.protocolHint.name.lowercase()} sessionProtocol=${torSession.protocolHint.name.lowercase()} optionId=${target.optionId.orEmpty()} privacyMode=${torSettings.privacyRoute.mode.name} privacyScope=${torSettings.privacyRoute.scope.name} trafficMode=${torSettings.traffic.mode.name} torConfigActive=$torConfigActive outbounds=$outboundSummary",
                )
                assertTrue("assembled TCP TOR config is missing tor-over-vpn outbound", torConfigActive)

                val startedAt = System.currentTimeMillis()
                app.container.connectionController.connect(profile.id, protocolOptionId = target.optionId)
                val terminalState =
                    withTimeoutOrNull(longArgument("foxhole.torTerminalTimeoutMs", 240_000L)) {
                        waitForActiveConnectionAttempt(app)
                        waitForTerminalState(app)
                    }
                delay(2_000)
                val evidence =
                    TunnelValidationEvidenceClassifier.classify(
                        entries = app.container.diagnosticsLogger.entries.value,
                        sinceMs = startedAt,
                    )
                val snapshot = app.container.connectionController.snapshot.value
                val terminalStateLabel = terminalState?.name ?: "TIMEOUT"
                Log.d(
                    TEST_TAG,
                    "liveSmartTcpTor result profileId=${profile.id} protocol=${target.protocolHint.name.lowercase()} terminalState=$terminalStateLabel message=${snapshot.message.orEmpty().take(160)} fatal=${evidence.fatalRuntimeMessage.orEmpty().take(200)} successTraffic=${evidence.hasSuccessfulTunnelActivity}",
                )
                if (requireSuccess) {
                    assertEquals(ConnectionState.CONNECTED.name, terminalStateLabel)
                    assertTrue(evidence.hasSuccessfulTunnelActivity)
                    assertTrue(evidence.fatalRuntimeMessage.orEmpty().isEmpty())
                }
            } finally {
                app.container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.OFF)
                disconnectAndWaitForIdle(app)
            }
        }
    }

    @Test
    fun manualSmartHysteriaLocalGuardSwitchingRuntime() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveSmartLocalGuardSwitch") != "1") {
            Log.d(TEST_TAG, "manual smart local guard switching skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val subscriptionInput = smartSubscriptionInput()
            if (subscriptionInput == null) {
                Log.d(TEST_TAG, "manual smart local guard switching skipped: subscription input missing")
                return@runBlocking
            }
            if (!ensureVpnPermission(app)) {
                Log.d(TEST_TAG, "manual smart local guard switching skipped: vpn permission missing")
                return@runBlocking
            }
            resetRelevantSettings(app)
            app.container.settingsRepository.updateFirewallEnabled(false)
            app.container.settingsRepository.updateNetworkActivityLogging(false)
            app.container.settingsRepository.updateNetworkActivityPersistentLogging(false)
            app.container.settingsRepository.updateBlockAppsAlways(false)
            app.container.settingsRepository.updateBlockedPackages(emptyList())
            FoxholeConnectionServiceContract.stopAllServices(app)
            disconnectAndWaitForIdle(app)
            clearProfiles(app)

            val imported =
                app.container.profileRepository.importProfile(
                    rawInput = subscriptionInput,
                    preferredName = "Live Smart Switch",
                    allowInsecureTlsForProfile =
                        InstrumentationRegistry
                            .getArguments()
                            .getString("foxhole.allowInsecureTlsForLiveSubscription") == "1",
                )
            val profiles = app.container.profileRepository.profiles.first()
            val selectedTarget =
                profiles
                    .flatMap { profile ->
                        profile
                            .runtimeProbeTargets()
                            .filter { target -> target.protocolHint == ProtocolHint.HYSTERIA2 }
                            .map { target -> profile to target }
                    }.firstOrNull()
            if (selectedTarget == null) {
                Log.d(TEST_TAG, "manual smart local guard switching skipped: no Hysteria2 target")
                assertTrue("smart subscription did not expose Hysteria2 target", false)
                return@runBlocking
            }
            val (profile, target) = selectedTarget
            assertEquals(imported.id, profile.id)
            val blockedPackage = firstInstalledPackageExcept(app.packageName)
            val args = InstrumentationRegistry.getArguments()
            val loggingEnabled = args.getString("foxhole.localGuardSwitchLogging") != "0"
            val blockAppsAlways = args.getString("foxhole.localGuardSwitchBlockAppsAlways") != "0"

            try {
                app.container.settingsRepository.updateNetworkActivityLogging(loggingEnabled)
                app.container.settingsRepository.updateNetworkActivityPersistentLogging(loggingEnabled)
                app.container.settingsRepository.updateBlockedPackages(listOf(blockedPackage))
                app.container.settingsRepository.updateBlockAppsAlways(blockAppsAlways)
                app.container.settingsRepository.updateFirewallEnabled(true)
                Log.d(
                    TEST_TAG,
                    "liveSmartLocalGuardSwitch settings logging=$loggingEnabled blockAppsAlways=$blockAppsAlways blockedPackage=$blockedPackage",
                )
                app.container.connectionController.syncLocalGuard()
                assertTrue(
                    "local guard did not start before VPN switch",
                    waitUntil(timeoutMs = 20_000L) {
                        val snapshot = app.container.connectionController.snapshot.value
                        snapshot.state == ConnectionState.CONNECTED &&
                            snapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
                    },
                )
                assertEquals(LocalGuardMode.FIREWALL, app.container.settingsRepository.current().localGuardModeOrNull())

                app.container.connectionController.connect(profile.id, protocolOptionId = target.optionId)
                val terminalState =
                    withTimeoutOrNull(liveSmartTerminalTimeoutMs(ProtocolHint.HYSTERIA2)) {
                        waitForActiveConnectionAttempt(app)
                        waitForTerminalState(app)
                    }
                val vpnSnapshot = app.container.connectionController.snapshot.value
                Log.d(
                    TEST_TAG,
                    "liveSmartLocalGuardSwitch vpn terminalState=${terminalState?.name ?: "TIMEOUT"} profileId=${vpnSnapshot.profileId} protocol=${vpnSnapshot.protocolHint?.name.orEmpty()}",
                )
                assertEquals(ConnectionState.CONNECTED, terminalState)
                assertEquals(profile.id, vpnSnapshot.profileId)
                assertEquals(ProtocolHint.HYSTERIA2, vpnSnapshot.protocolHint)

                app.container.connectionController.disconnect()
                assertTrue(
                    "local guard did not resume after VPN disconnect",
                    waitUntil(timeoutMs = 30_000L) {
                        if (app.container.connectionController.snapshot.value.state == ConnectionState.IDLE) {
                            app.container.connectionController.syncLocalGuard()
                        }
                        val snapshot = app.container.connectionController.snapshot.value
                        snapshot.state == ConnectionState.CONNECTED &&
                            snapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
                    },
                )
                Log.d(TEST_TAG, "liveSmartLocalGuardSwitch local guard resumed")
            } finally {
                FoxholeConnectionServiceContract.startForegroundService(
                    context = app,
                    mode = TrafficMode.TUNNEL,
                    action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
                    suppressLocalGuard = true,
                )
                app.container.settingsRepository.updateFirewallEnabled(false)
                app.container.settingsRepository.updateNetworkActivityLogging(false)
                app.container.settingsRepository.updateNetworkActivityPersistentLogging(false)
                app.container.settingsRepository.updateBlockAppsAlways(false)
                app.container.settingsRepository.updateBlockedPackages(emptyList())
                waitUntil(timeoutMs = 20_000L) {
                    app.container.connectionController.snapshot.value.state in setOf(ConnectionState.IDLE, ConnectionState.ERROR)
                }
            }
        }
    }

    @Test
    fun manualTorOnlyRuntimeConnectsWhenRequested() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveTorOnly") != "1") {
            Log.d(TEST_TAG, "manual TOR-only live connect skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val requireSuccess =
                InstrumentationRegistry.getArguments().getString("foxhole.requireLiveTorOnlySuccess") == "1"
            if (!ensureVpnPermission(app)) {
                Log.d(TEST_TAG, "manual TOR-only skipped: vpn permission missing")
                if (requireSuccess) {
                    assertTrue("vpn permission missing for required TOR-only live test", false)
                }
                return@runBlocking
            }
            resetRelevantSettings(app)
            clearProfiles(app)
            disconnectAndWaitForIdle(app)

            app.container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
            app.container.settingsRepository.updatePrivacyRouteScope(PrivacyRouteScope.ALL_APPS)
            app.container.settingsRepository.updatePrivacyRouteSelectedPackages(emptyList())

            try {
                val startedAt = System.currentTimeMillis()
                app.container.connectionController.connectTorOnly(statusMessage = "Live TOR-only")
                val terminalState =
                    withTimeoutOrNull(longArgument("foxhole.torOnlyTerminalTimeoutMs", 480_000L)) {
                        waitForActiveConnectionAttempt(app)
                        waitForTerminalState(app)
                    }
                delay(2_000)
                val evidence =
                    TunnelValidationEvidenceClassifier.classify(
                        entries = app.container.diagnosticsLogger.entries.value,
                        sinceMs = startedAt,
                    )
                val snapshot = app.container.connectionController.snapshot.value
                val runtimeUi = app.container.connectionController.runtimeUiState.value
                Log.d(
                    TEST_TAG,
                    "liveTorOnly result terminalState=${terminalState?.name ?: "TIMEOUT"} profileId=${snapshot.profileId} message=${snapshot.message.orEmpty().take(160)} torState=${runtimeUi.tor.runtimeStressLabel()} fatal=${evidence.fatalRuntimeMessage.orEmpty().take(200)} successTraffic=${evidence.hasSuccessfulTunnelActivity}",
                )
                if (requireSuccess) {
                    assertEquals(ConnectionState.CONNECTED, terminalState)
                    assertEquals(FoxholeVpnService.TOR_ONLY_PROFILE_ID, snapshot.profileId)
                    assertTrue(evidence.fatalRuntimeMessage.orEmpty().isEmpty())
                }
            } finally {
                app.container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.OFF)
                disconnectAndWaitForIdle(app)
            }
        }
    }

    @Test
    fun manualSplitTunnelIncludeOneAppRuntime() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveSplitIncludeOneApp") != "1") {
            Log.d(TEST_TAG, "manual split include-one-app live runtime skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val requireSuccess =
                InstrumentationRegistry.getArguments().getString("foxhole.requireLiveSplitIncludeSuccess") == "1"
            if (!ensureVpnPermission(app)) {
                Log.d(TEST_TAG, "manual split include-one-app skipped: vpn permission missing")
                if (requireSuccess) {
                    assertTrue("vpn permission missing for required split include-one-app live test", false)
                }
                return@runBlocking
            }
            resetRelevantSettings(app)
            clearProfiles(app)
            disconnectAndWaitForIdle(app)

            val selectedPackage =
                InstrumentationRegistry.getArguments()
                    .getString("foxhole.splitSelectedPackage")
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: firstInstalledPackageExcept(app.packageName)
            val imported =
                app.container.profileRepository.importProfile(
                    rawInput = LIVE_SPLIT_DIRECT_PROFILE,
                    preferredName = "Live Split Direct",
                )
            app.container.connectionController.setActiveProfile(imported.id)
            app.container.settingsRepository.updateSelectedPackages(listOf(selectedPackage))
            app.container.settingsRepository.updatePerAppRoutingMode(PerAppRoutingMode.INCLUDE_SELECTED_APPS)
            val session = app.container.profileRepository.getSession(imported.id)
            val config = json.parseToJsonElement(session.configJson).jsonObject
            val tunInbound = config["inbounds"]!!.jsonArray.first().jsonObject
            val includePackages =
                tunInbound["include_package"]
                    ?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    .orEmpty()
            assertEquals(listOf(selectedPackage), includePackages)

            try {
                val startedAt = System.currentTimeMillis()
                Log.d(TEST_TAG, "liveSplitInclude start profileId=${imported.id} selectedPackage=$selectedPackage")
                app.container.connectionController.connect(imported.id)
                val terminalState =
                    withTimeoutOrNull(longArgument("foxhole.splitIncludeTerminalTimeoutMs", 90_000L)) {
                        waitForActiveConnectionAttempt(app)
                        waitForTerminalState(app)
                    }
                delay(2_000)
                val messages = diagnosticMessagesSince(app, startedAt)
                val splitApplied =
                    messages.any { message ->
                        message.contains("VPN app split applied") &&
                            message.contains("mode=include") &&
                            message.contains("include_count=1")
                    }
                val evidence =
                    TunnelValidationEvidenceClassifier.classify(
                        entries = app.container.diagnosticsLogger.entries.value,
                        sinceMs = startedAt,
                    )
                val snapshot = app.container.connectionController.snapshot.value
                Log.d(
                    TEST_TAG,
                    "liveSplitInclude result terminalState=${terminalState?.name ?: "TIMEOUT"} profileId=${snapshot.profileId} selectedPackage=$selectedPackage splitApplied=$splitApplied message=${snapshot.message.orEmpty().take(160)} fatal=${evidence.fatalRuntimeMessage.orEmpty().take(200)} successTraffic=${evidence.hasSuccessfulTunnelActivity}",
                )
                if (requireSuccess) {
                    assertEquals(ConnectionState.CONNECTED, terminalState)
                    assertEquals(imported.id, snapshot.profileId)
                    assertTrue("Android VPN package include split was not applied", splitApplied)
                    assertTrue(evidence.fatalRuntimeMessage.orEmpty().isEmpty())
                }
            } finally {
                app.container.settingsRepository.updatePerAppRoutingMode(PerAppRoutingMode.FULL_TUNNEL)
                app.container.settingsRepository.updateSelectedPackages(emptyList())
                disconnectAndWaitForIdle(app)
            }
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
    fun manualSmartSubscriptionVlessNetworkSwitchRefresh() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveSmartVlessNetworkSwitch") != "1") {
            Log.d(TEST_TAG, "manual smart VLESS network switch refresh skipped")
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

            shell("am start -n ${app.packageName}/com.foxhole.beta.MainActivity")
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
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveRuntimeStress") != "1") {
            Log.d(TEST_TAG, "manual runtime stress skipped")
            return
        }
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
                        "liveRuntimeStress cycleStopped=$cycle connectionState=${sample.connectionState} runtimePhase=${sample.runtimePhase} runtimeGeneration=${sample.runtimeGeneration} nativeGeneration=${sample.nativeGeneration ?: "unknown"} commandQueueDepth=${sample.commandQueueDepth ?: "unknown"} rssKb=${sample.rssKb ?: "unknown"} pssKb=${sample.pssKb ?: "unknown"} nativeHeapKb=${sample.nativeHeapKb ?: "unknown"} javaHeapKb=${sample.javaHeapKb} threads=${sample.threadCount} event=${sample.latestRuntimeEvent.orEmpty()} nativeServer=${sample.hasNativeServer} tunFd=${sample.hasTunFileDescriptor} callbacks=${sample.networkCallbacks} cleanupUnresolved=${sample.cleanupUnresolved} ipDevice=${sample.ipDeviceState} ipTunnel=${sample.ipTunnelState} ipTor=${sample.ipTorState} torState=${sample.torState}",
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
            updateSafeModeEnabled(false)
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
        disableLocalGuardSettings(app)
    }

    private suspend fun baselineRuntimeSettings(app: FoxholeApplication) {
        with(app.container.settingsRepository) {
            updateSafeModeEnabled(false)
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
        disableLocalGuardSettings(app)
    }

    private suspend fun disableLocalGuardSettings(app: FoxholeApplication) {
        with(app.container.settingsRepository) {
            updateFirewallEnabled(false)
            updateTrafficMapEnabled(false)
            updateStatisticsEnabled(false)
            updateStatisticsMetricEnabled(StatisticsMetric.COUNTRY_TRAFFIC, false)
            updateStatisticsMetricEnabled(StatisticsMetric.ANOMALIES, false)
            updateAnomalyEnabled(false)
            updateAnalyzeDestinationCountries(false)
            updateNetworkActivityLogging(false)
            updateNetworkActivityPersistentLogging(false)
            updateSystemDnsProtectionEnabled(false)
            updateBlockedPackages(emptyList())
            updateBlockedPackagesEnabled(false)
            updateBlockAppsAlways(false)
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

    private suspend fun waitUntil(
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

    private suspend fun waitForDiagnosticMessage(
        app: FoxholeApplication,
        sinceMs: Long,
        timeoutMs: Long,
        predicate: (String) -> Boolean,
    ): Boolean =
        waitUntil(timeoutMs = timeoutMs) {
            diagnosticMessagesSince(app, sinceMs).any(predicate)
        }

    private fun diagnosticMessagesSince(
        app: FoxholeApplication,
        sinceMs: Long,
    ): List<String> =
        app.container.diagnosticsLogger.entries.value
            .asSequence()
            .filter { entry -> entry.timestamp >= sinceMs }
            .map { entry -> entry.message }
            .toList()

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

    private fun firstInstalledPackageExcept(packageName: String): String =
        shell("cmd package list packages")
            .lineSequence()
            .map { line -> line.removePrefix("package:").trim() }
            .firstOrNull { candidate -> candidate.isNotBlank() && candidate != packageName }
            ?: "com.android.settings"

    private fun shell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { reader ->
                return reader.readText()
            }
        }
    }

    private fun smartSubscriptionInput(): String? {
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

    private fun readDeviceTextFile(path: String): String? =
        runCatching { File(path).takeIf { it.isFile && it.canRead() }?.readText() }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: shell("cat ${path.shellSingleQuoted()} 2>/dev/null || true")
                .trim()
                .takeIf(String::isNotBlank)

    private fun String.shellSingleQuoted(): String = "'${replace("'", "'\\''")}'"

    private fun deleteChildren(dir: File) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                deleteChildren(child)
            }
            child.delete()
        }
    }

    private fun requestedSmartProbeProtocols(
        defaultProtocols: Set<ProtocolHint> = setOf(ProtocolHint.TROJAN, ProtocolHint.WIREGUARD),
    ): Set<ProtocolHint> {
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
        return requested.ifEmpty { defaultProtocols }
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

    private fun captureRuntimeStressResourceSample(
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
            commandQueueDepth = stopDetails["command_queue_depth"]?.toIntOrNull(),
            networkCallbacks = callbackDetails["network_callbacks"]?.toIntOrNull(),
            hasNativeServer = stopDetails["native_server"]?.toBooleanStrictOrNull(),
            hasTunFileDescriptor = stopDetails["tun_fd"]?.toBooleanStrictOrNull(),
            hasNativeHost = stopDetails["native_host"]?.toBooleanStrictOrNull(),
            hasNativeConfig = stopDetails["native_config"]?.toBooleanStrictOrNull(),
            nativeState = stopDetails["native_state"],
            cleanupUnresolved = stopDetails["cleanup_unresolved"]?.toBooleanStrictOrNull(),
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

    private fun assertRuntimeStoppedAfterCycle(
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
        assertFalse("cycle ${sample.cycle} left unresolved native cleanup event=${sample.latestRuntimeEvent}", sample.cleanupUnresolved == true)
        assertFalse("cycle ${sample.cycle} detached close after disconnect event=${sample.latestRuntimeEvent}", sample.lastCloseDetached == true)
        assertEquals(
            "cycle ${sample.cycle} callback cleanup snapshot event mismatch",
            "service_destroy_after_callbacks_unregistered",
            sample.callbackCleanupEvent,
        )
        assertEquals("cycle ${sample.cycle} leaked network callbacks event=${sample.callbackCleanupEvent}", 0, sample.networkCallbacks ?: 0)
    }

    private fun assertRuntimeStressMemoryStable(samples: List<RuntimeStressResourceSample>) {
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

    private suspend fun waitForRuntimeStopDetails(
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

    private suspend fun waitForRuntimeCallbackCleanupDetails(
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

    private fun latestRuntimeHealthDetails(
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

    private fun parseRuntimeHealthDetails(message: String): Map<String, String> =
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

    private fun readCurrentPssKb(): Long? =
        runCatching {
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            memoryInfo.totalPss.takeIf { it >= 0 }?.toLong()
        }.getOrNull()

    private fun readProcStatusKb(label: String): Long? =
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

    private fun hasFoxholeRuntimeServices(app: FoxholeApplication): Boolean {
        val services = shell("dumpsys activity services ${app.packageName}")
        return services.contains("FoxholeVpnService") || services.contains("FoxholeProxyService")
    }

    private fun hasActiveFoxholeVpnNetwork(app: FoxholeApplication): Boolean =
        shell("dumpsys connectivity").contains("VPN CONNECTED extra: VPN:${app.packageName}")

    private data class RuntimeStressResourceSample(
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
        val cleanupUnresolved: Boolean?,
        val lastCloseDetached: Boolean?,
        val nativeGeneration: Long?,
        val ipDeviceState: String,
        val ipTunnelState: String,
        val ipTorState: String,
        val torState: String,
        val hasRuntimeService: Boolean,
        val hasActiveVpnNetwork: Boolean,
    )

    private fun RuntimePhase.runtimeStressLabel(): String =
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

    private fun RuntimeIpPanelState.runtimeStressLabel(): String =
        when (this) {
            RuntimeIpPanelState.Hidden -> "hidden"
            is RuntimeIpPanelState.Loading -> "loading:${target.name.lowercase()}:previous=${previous != null}"
            is RuntimeIpPanelState.Ready -> "ready:${target.name.lowercase()}:stale=$stale"
            is RuntimeIpPanelState.Failed -> "failed:${target.name.lowercase()}:previous=${previous != null}"
        }

    private fun RuntimeTorUiState.runtimeStressLabel(): String =
        when (this) {
            RuntimeTorUiState.Off -> "off"
            is RuntimeTorUiState.Starting -> "starting"
            is RuntimeTorUiState.Bootstrapping -> "bootstrapping:${progress ?: -1}:previous=${previousExit != null}"
            is RuntimeTorUiState.Ready -> "ready:exit=${exit.ip}"
            is RuntimeTorUiState.Rotating -> "rotating:previous=${previousExit.ip}"
            is RuntimeTorUiState.Failed -> "failed:previous=${previousExit != null}"
        }

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
            .onSuccess {
                if (approveButton != null) {
                    Log.d(TEST_TAG, "vpn permission dialog approve clicked")
                }
            }
    }

    companion object {
        private const val TEST_TAG = "FoxholeSessionTest"
        private const val DEFAULT_SMART_SUBSCRIPTION_DEVICE_PATH = "/data/local/tmp/foxhole-subscription.raw"
        private const val VPN_PERMISSION_REQUEST_CODE = 7301
        private const val VPN_PERMISSION_DIALOG_WAIT_MS = 1_000L
        private const val RUNTIME_STOP_DISCONNECT_EVENT = "stop_success:disconnect"
        private const val RUNTIME_SERVICE_DESTROY_CALLBACKS_UNREGISTERED_EVENT = "service_destroy_after_callbacks_unregistered"
        private const val LIVE_DIRECT_LINK_TERMINAL_TIMEOUT_MS = 150_000L
        private const val BYTES_PER_KB = 1024L
        private const val RUNTIME_STRESS_RSS_DELTA_LIMIT_KB = 250L * 1024L
        private val LIVE_SPLIT_DIRECT_PROFILE =
            """
            {
              "outbounds": [
                { "type": "direct", "tag": "direct-upstream" }
              ]
            }
            """.trimIndent()

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

        private const val PRESERVE_FIXTURE_HOST = "example.com"
        private val DIRECT_VLESS_REALITY_PRESERVE_URI =
            DIRECT_VLESS_REALITY_URI.replace("axn666.nl", PRESERVE_FIXTURE_HOST)
        private val DIRECT_HYSTERIA2_PRESERVE_URI =
            DIRECT_HYSTERIA2_URI.replace("axn666.nl", PRESERVE_FIXTURE_HOST)

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
