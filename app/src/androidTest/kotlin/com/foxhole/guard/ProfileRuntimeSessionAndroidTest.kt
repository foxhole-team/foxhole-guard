package com.foxhole.guard

import android.net.VpnService
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.TunnelValidationEvidenceClassifier
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.guard.core.data.getSession
import com.foxhole.guard.core.settings.updateAllowPrivateOutboundHosts
import com.foxhole.guard.core.settings.updateBlockAppsAlways
import com.foxhole.guard.core.settings.updateBlockedPackages
import com.foxhole.guard.core.settings.updateFirewallEnabled
import com.foxhole.guard.core.settings.updateNetworkActivityLogging
import com.foxhole.guard.core.settings.updatePerAppRoutingMode
import com.foxhole.guard.core.settings.updatePrivacyRouteBridgesEnabled
import com.foxhole.guard.core.settings.updatePrivacyRouteBypassVpnTunnel
import com.foxhole.guard.core.settings.updatePrivacyRouteMode
import com.foxhole.guard.core.settings.updatePrivacyRouteScope
import com.foxhole.guard.core.settings.updatePrivacyRouteSelectedPackages
import com.foxhole.guard.core.settings.updateSelectedPackages
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.FoxholeVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

internal class ProfileRuntimeSessionAndroidTest : ProfileRuntimeSessionAndroidTestSupport() {
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
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                vlessReality["public_key"]!!.jsonPrimitive.content,
            )
            assertEquals("0000000000000000", vlessReality["short_id"]!!.jsonPrimitive.content)

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
                "cGFzc3dvcmQtcGxhY2Vob2xkZXItZm9yLWRvY3M9",
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

            // Bridges ship ON, and on a network that does not block Tor they are the slower path —
            // and a stale bundled list turns "Tor is reachable" into "Arti bootstrap timed out at
            // 8% (filtered)". The knob exists so a red run says WHICH of the two it was instead of
            // leaving the reader to guess: pass foxhole.torBridges=off to take them out.
            InstrumentationRegistry
                .getArguments()
                .getString("foxhole.torBridges")
                ?.let { requested ->
                    app.container.settingsRepository.updatePrivacyRouteBridgesEnabled(requested != "off")
                    Log.d(TEST_TAG, "liveTorOnly bridges=$requested")
                }
            app.container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
            // TOR alone is the route ENABLED plus bypassVpnTunnel — Tor dials out next to the
            // tunnel instead of through it. Without the bypass the settings still read «VPN + TOR»,
            // and the runtime correctly refuses to start that without a VPN profile, which this
            // test has just cleared.
            app.container.settingsRepository.updatePrivacyRouteBypassVpnTunnel(true)
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

}
