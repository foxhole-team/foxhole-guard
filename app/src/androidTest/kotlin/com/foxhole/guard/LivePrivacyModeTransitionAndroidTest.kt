package com.foxhole.guard

import android.net.Network
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.runtime.RuntimeTorUiState
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.core.runtime.network.TOR_CHECK_IP_INFO_ENDPOINT
import com.foxhole.guard.core.settings.applyRoutingModePreset
import com.foxhole.guard.core.settings.updatePrivacyRouteBlockAppsWhenTorUnavailable
import com.foxhole.guard.core.settings.updatePrivacyRouteMode
import com.foxhole.guard.core.settings.updatePrivacyRoutePermitted
import com.foxhole.guard.core.settings.updatePrivacyRouteScope
import com.foxhole.guard.core.settings.updateUnifiedAppAssignments
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.ui.ConnectModeSwitchRequestResult
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.LiveModeSwitchKind
import com.foxhole.guard.ui.TorTransitionPrompt
import com.foxhole.guard.ui.VpnRoutingScenario
import com.foxhole.guard.ui.confirmLiveModeSwitch
import com.foxhole.guard.ui.onConnectModeSwitchRequested
import com.foxhole.guard.ui.onVpnRoutingScenarioSelected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

@RunWith(AndroidJUnit4::class)
internal class LivePrivacyModeTransitionAndroidTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun vpnTorHotTransitionsAndTorOnlyHandoffStayFailClosed() {
        assumeTrue(
            "live privacy transitions skipped: pass -e foxhole.livePrivacyTransitions 1 to run them",
            InstrumentationRegistry.getArguments().getString("foxhole.livePrivacyTransitions") == "1",
        )
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            assertDisposableDebugTarget(app)
            val subscription = smartSubscriptionInput()
            assertNotNull("subscription input missing for the live privacy transition gate", subscription)
            val settings = app.container.settingsRepository
            val previousSettings = settings.current()
            val profilesBefore = currentProfileIds(app)
            val activeProfileBefore = app.container.profileDatabase.profileDao().getActiveProfile()?.id
            assertTrue(
                "privacy transition gate requires an empty disposable debug profile database",
                profilesBefore.isEmpty(),
            )
            assertTrue("VPN permission missing for the live privacy transition gate", ensureVpnPermission(app))

            var viewModelStore: ViewModelStore? = null
            var primaryFailure: Throwable? = null
            try {
                stopAllAndAssertIdle(app)
                baselineRuntimeSettings(app)
                app.applyGuardReconcileSchedule(enabled = false)
                settings.updateAtomicConnection(true)
                settings.updatePrivacyRoutePermitted(true)
                settings.updatePrivacyRouteMode(PrivacyRouteMode.OFF)
                settings.updatePrivacyRouteScope(PrivacyRouteScope.ALL_APPS)
                settings.updatePrivacyRouteBlockAppsWhenTorUnavailable(true)
                settings.applyRoutingModePreset(RoutingModePreset.VPN, PrivacyRouteScope.ALL_APPS)
                settings.updateUnifiedAppAssignments(
                    selectedPackages = listOf(firstInstalledPackageExcept(app.packageName)),
                    blockedPackages = emptyList(),
                )

                val imported = importPrivateProfile(app, requireNotNull(subscription))
                val target =
                    probeTargetFor(
                        app = app,
                        profileId = imported.id,
                        protocols = setOf(ProtocolHint.VLESS, ProtocolHint.TROJAN),
                    )
                assertNotNull("the subscription exposed no TCP target for the privacy transition gate", target)
                val profileId = imported.id
                val requiredTarget = requireNotNull(target)
                val optionId = requiredTarget.optionId
                val protocolHint = requiredTarget.protocolHint
                app.container.connectionController.setActiveProfile(profileId)
                val store = ViewModelStore()
                viewModelStore = store
                val viewModel =
                    ViewModelProvider(
                        TestViewModelStoreOwner(store),
                        HomeViewModel.factory(app),
                    )[HomeViewModel::class.java]

                app.container.connectionController.connect(profileId, protocolOptionId = optionId)
                val plainVpn =
                    awaitStage(app, "plain VPN", CONNECT_BUDGET_MS) { snapshot, tor ->
                        snapshot.state == ConnectionState.CONNECTED &&
                            snapshot.profileId == profileId &&
                            snapshot.protocolHint == protocolHint &&
                            snapshot.protocolOptionId == optionId &&
                            !snapshot.torActive &&
                            snapshot.appliedTorRoute == null &&
                            tor == RuntimeTorUiState.Off
                }
                assertRuntimeCarrier(app, "plain VPN")
                val plainVpnExit = requireVpnExitNotTor(app, "plain VPN")
                val plainVpnNetwork = requireCurrentVpnNetwork(app, "plain VPN")
                recordStage("plain_vpn", plainVpn.generation)

                val attachPrompt =
                    requestLivePrompt(
                        viewModel = viewModel,
                        target = RoutingModePreset.VPN_TOR,
                        expectedKind = LiveModeSwitchKind.ATTACH_TOR,
                    )
                viewModel.confirmLiveModeSwitch(attachPrompt)
                val vpnTor =
                    awaitStage(app, "VPN plus Tor", TOR_APPLY_BUDGET_MS) { snapshot, tor ->
                        val applied = snapshot.appliedTorRoute
                        snapshot.state == ConnectionState.CONNECTED &&
                            snapshot.profileId == profileId &&
                            snapshot.protocolHint == protocolHint &&
                            snapshot.protocolOptionId == optionId &&
                            snapshot.torActive &&
                            applied?.scope == PrivacyRouteScope.ALL_APPS &&
                            applied.bypassVpnTunnel.not() &&
                            tor is RuntimeTorUiState.Ready
                }
                assertRuntimeCarrier(app, "VPN plus Tor")
                assertVpnNetworkUnchanged(app, "VPN plus Tor", plainVpnNetwork)
                val vpnTorExit = requireAuthenticatedTorExit(app, "VPN plus Tor")
                val vpnTorRoutedExit = requireVpnBoundTorExit(app, "VPN plus Tor")
                assertTrue(
                    "VPN plus Tor did not verify a distinct Tor exit",
                    plainVpnExit != vpnTorExit && plainVpnExit != vpnTorRoutedExit,
                )
                recordStage("vpn_tor", vpnTor.generation)

                val detachPrompt =
                    requestLivePrompt(
                        viewModel = viewModel,
                        target = RoutingModePreset.VPN,
                        expectedKind = LiveModeSwitchKind.DETACH_TOR,
                    )
                viewModel.confirmLiveModeSwitch(detachPrompt)
                val detachedVpn =
                    awaitStage(app, "VPN after Tor detach", CONNECT_BUDGET_MS) { snapshot, tor ->
                        snapshot.state == ConnectionState.CONNECTED &&
                            snapshot.profileId == profileId &&
                            snapshot.protocolHint == protocolHint &&
                            snapshot.protocolOptionId == optionId &&
                            !snapshot.torActive &&
                            snapshot.appliedTorRoute == null &&
                            tor == RuntimeTorUiState.Off &&
                            app.container.connectionController.torRouteIpInfo.value == null
                }
                assertRuntimeCarrier(app, "VPN after Tor detach")
                assertVpnNetworkUnchanged(app, "VPN after Tor detach", plainVpnNetwork)
                assertNull(
                    "VPN after Tor detach retained the previous Tor exit proof",
                    app.container.connectionController.torRouteIpInfo.value,
                )
                val detachedVpnExit = requireVpnExitNotTor(app, "VPN after Tor detach")
                recordStage("vpn_detached", detachedVpn.generation)

                val torOnlyPrompt =
                    requestLivePrompt(
                        viewModel = viewModel,
                        target = RoutingModePreset.TOR,
                        expectedKind = LiveModeSwitchKind.TOR_STOPS_VPN,
                    )
                viewModel.confirmLiveModeSwitch(torOnlyPrompt)
                val torOnly =
                    awaitStage(app, "Tor-only handoff", TOR_HANDOFF_BUDGET_MS) { snapshot, tor ->
                        val applied = snapshot.appliedTorRoute
                        snapshot.state == ConnectionState.CONNECTED &&
                            snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
                            snapshot.torActive &&
                            applied?.scope == PrivacyRouteScope.ALL_APPS &&
                            applied.bypassVpnTunnel &&
                            tor is RuntimeTorUiState.Ready
                }
                assertRuntimeCarrier(app, "Tor-only handoff")
                assertNotNull(
                    "Tor-only handoff reached Ready without a fresh Tor exit proof",
                    app.container.connectionController.torRouteIpInfo.value,
                )
                val torOnlyExit = requireAuthenticatedTorExit(app, "Tor-only handoff")
                assertTrue(
                    "Tor-only handoff did not verify a distinct Tor exit",
                    detachedVpnExit != torOnlyExit,
                )
                recordStage("tor_only", torOnly.generation)

                assertEquals(
                    "Tor-only to VPN handoff was not applied atomically",
                    ConnectModeSwitchRequestResult.APPLIED,
                    viewModel.onConnectModeSwitchRequested(
                        RoutingModePreset.VPN,
                        PrivacyRouteScope.ALL_APPS,
                    ),
                )
                val restoredVpn =
                    awaitStage(app, "VPN after Tor-only", TOR_HANDOFF_BUDGET_MS) { snapshot, tor ->
                        snapshot.state == ConnectionState.CONNECTED &&
                            snapshot.profileId == profileId &&
                            snapshot.protocolHint == protocolHint &&
                            snapshot.protocolOptionId == optionId &&
                            !snapshot.torActive &&
                            snapshot.appliedTorRoute == null &&
                            tor == RuntimeTorUiState.Off
                }
                assertRuntimeCarrier(app, "VPN after Tor-only")
                requireVpnExitNotTor(app, "VPN after Tor-only")
                val restoredVpnNetwork = requireCurrentVpnNetwork(app, "VPN after Tor-only")
                recordStage("vpn_restored", restoredVpn.generation)

                val fullTunnelSignature =
                    requireNotNull(app.container.connectionController.appliedRuntimeSignature.value) {
                        "restored VPN did not acknowledge its runtime fingerprint"
                    }
                viewModel.onVpnRoutingScenarioSelected(VpnRoutingScenario.SELECTED_EXCLUDE)
                assertTrue(
                    "atomic VPN scenario switch unexpectedly requested a second confirmation",
                    viewModel.pendingRoutingScenarioConfirmationMutable.value == null,
                )
                val excludedScenario =
                    awaitStage(app, "exclude scenario", CONNECT_BUDGET_MS) { snapshot, tor ->
                        val controller = app.container.connectionController
                        snapshot.state == ConnectionState.CONNECTED &&
                            snapshot.profileId == profileId &&
                            snapshot.protocolHint == protocolHint &&
                            snapshot.protocolOptionId == optionId &&
                            !snapshot.torActive &&
                            snapshot.appliedTorRoute == null &&
                            tor == RuntimeTorUiState.Off &&
                            settings.settings.value.expert.perAppRoutingMode ==
                            PerAppRoutingMode.EXCLUDE_SELECTED_APPS &&
                            controller.appliedRuntimeSignature.value != null &&
                            controller.appliedRuntimeSignature.value != fullTunnelSignature
                }
                assertRuntimeCarrier(app, "exclude scenario")
                assertVpnNetworkUnchanged(app, "exclude scenario", restoredVpnNetwork)
                requireVpnExitNotTor(app, "exclude scenario")
                recordStage("scenario_exclude", excludedScenario.generation)

                val excludedSignature =
                    requireNotNull(app.container.connectionController.appliedRuntimeSignature.value) {
                        "exclude scenario did not acknowledge its runtime fingerprint"
                    }
                viewModel.onVpnRoutingScenarioSelected(VpnRoutingScenario.WHOLE_DEVICE)
                assertTrue(
                    "atomic whole-device scenario unexpectedly requested a second confirmation",
                    viewModel.pendingRoutingScenarioConfirmationMutable.value == null,
                )
                val wholeDeviceScenario =
                    awaitStage(app, "whole-device scenario", CONNECT_BUDGET_MS) { snapshot, tor ->
                        val controller = app.container.connectionController
                        snapshot.state == ConnectionState.CONNECTED &&
                            snapshot.profileId == profileId &&
                            snapshot.protocolHint == protocolHint &&
                            snapshot.protocolOptionId == optionId &&
                            !snapshot.torActive &&
                            snapshot.appliedTorRoute == null &&
                            tor == RuntimeTorUiState.Off &&
                            settings.settings.value.expert.perAppRoutingMode == PerAppRoutingMode.FULL_TUNNEL &&
                            controller.appliedRuntimeSignature.value != null &&
                            controller.appliedRuntimeSignature.value != excludedSignature
                }
                assertRuntimeCarrier(app, "whole-device scenario")
                assertVpnNetworkUnchanged(app, "whole-device scenario", restoredVpnNetwork)
                requireVpnExitNotTor(app, "whole-device scenario")
                recordStage("scenario_whole_device", wholeDeviceScenario.generation)
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                val finalizationFailures =
                    withContext(NonCancellable) {
                        finalizeTestIndependently(
                            app = app,
                            viewModelStore = viewModelStore,
                            previousSettings = previousSettings,
                            profileIdsBefore = profilesBefore,
                            activeProfileBefore = activeProfileBefore,
                        )
                    }
                val failure = primaryFailure
                if (failure != null) {
                    finalizationFailures.forEach(failure::addSuppressed)
                } else {
                    finalizationFailures.firstOrNull()?.let { finalizationFailure ->
                        finalizationFailures.drop(1).forEach(finalizationFailure::addSuppressed)
                        throw finalizationFailure
                    }
                }
            }
        }
    }

    private fun requestLivePrompt(
        viewModel: HomeViewModel,
        target: RoutingModePreset,
        expectedKind: LiveModeSwitchKind,
    ): TorTransitionPrompt.LiveModeSwitch {
        assertEquals(
            "live mode switch did not defer to its confirmation contract",
            ConnectModeSwitchRequestResult.DEFERRED,
            viewModel.onConnectModeSwitchRequested(target, PrivacyRouteScope.ALL_APPS),
        )
        val prompt = viewModel.torTransitionPromptMutable.value as? TorTransitionPrompt.LiveModeSwitch
        assertNotNull("live mode switch did not publish the expected confirmation prompt", prompt)
        assertEquals("live mode switch published the wrong transition kind", expectedKind, prompt?.kind)
        assertEquals("live mode switch prompt target drifted", target, prompt?.target)
        assertEquals("live mode switch prompt scope drifted", PrivacyRouteScope.ALL_APPS, prompt?.scope)
        return requireNotNull(prompt)
    }

    private suspend fun importPrivateProfile(
        app: FoxholeApplication,
        subscription: String,
    ): com.foxhole.core.model.Profile =
        try {
            app.container.profileRepository.importProfile(
                rawInput = subscription,
                preferredName = "Live privacy transitions",
                allowInsecureTlsForProfile =
                    InstrumentationRegistry.getArguments()
                        .getString("foxhole.allowInsecureTlsForLiveSubscription") == "1",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw AssertionError(
                "private live subscription import failed (${error.javaClass.simpleName})",
            )
        }

    private suspend fun awaitStage(
        app: FoxholeApplication,
        label: String,
        timeoutMs: Long,
        predicate: (ConnectionSnapshot, RuntimeTorUiState) -> Boolean,
    ): LiveStage {
        val stage =
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    val snapshot = app.container.connectionController.snapshot.value
                    val runtime = app.container.connectionController.runtimeUiState.value
                    if (snapshot.state == ConnectionState.ERROR) {
                        throw AssertionError("$label entered ERROR")
                    }
                    if (predicate(snapshot, runtime.tor)) {
                        return@withTimeoutOrNull LiveStage(runtime.generation)
                    }
                    delay(POLL_INTERVAL_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        assertNotNull("$label did not reach its verified applied state before timeout", stage)
        return requireNotNull(stage)
    }

    private fun assertRuntimeCarrier(app: FoxholeApplication, label: String) {
        assertTrue("$label lost the FoxHole runtime service", hasFoxholeRuntimeServices(app))
        assertTrue("$label lost the FoxHole VPN network", hasActiveFoxholeVpnNetwork(app))
    }

    private fun assertDisposableDebugTarget(app: FoxholeApplication) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue("profile-isolating live gate requires a debuggable target", BuildConfig.DEBUG)
        assertTrue("profile-isolating live gate refused a non-debug application ID", app.packageName.endsWith(".debug"))
        assertTrue(
            "profile-isolating live gate target package mismatch",
            instrumentation.targetContext.packageName == app.packageName,
        )
        assertTrue(
            "profile-isolating live gate instrumentation package mismatch",
            instrumentation.context.packageName == "${app.packageName}.test",
        )
    }

    private suspend fun stopAllAndAssertIdle(app: FoxholeApplication) {
        disconnectAndWaitForIdle(app)
        FoxholeConnectionServiceContract.stopAllServices(app)
        assertRuntimeFullyStopped(app)
    }

    private suspend fun assertRuntimeFullyStopped(app: FoxholeApplication) {
        val stopped =
            waitUntil(CLEANUP_BUDGET_MS) {
                val snapshot = app.container.connectionController.snapshot.value
                snapshot.state == ConnectionState.IDLE &&
                    !snapshot.torActive &&
                    snapshot.appliedTorRoute == null &&
                    app.container.connectionController.runtimeUiState.value.tor == RuntimeTorUiState.Off &&
                    !hasFoxholeRuntimeServices(app) &&
                    !hasActiveFoxholeVpnNetwork(app)
            }
        assertTrue("privacy transition gate did not finish fail-closed", stopped)
    }

    private suspend fun finalizeTestIndependently(
        app: FoxholeApplication,
        viewModelStore: ViewModelStore?,
        previousSettings: com.foxhole.core.model.Settings,
        profileIdsBefore: Set<Long>,
        activeProfileBefore: Long?,
    ): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        suspend fun capture(block: suspend () -> Unit) {
            runCatching { block() }.exceptionOrNull()?.let(failures::add)
        }

        capture { viewModelStore?.clear() }
        capture { disconnectAndWaitForIdle(app) }
        capture { FoxholeConnectionServiceContract.stopAllServices(app) }
        capture { assertRuntimeFullyStopped(app) }
        capture { cleanupCreatedProfiles(app, profileIdsBefore, activeProfileBefore) }
        capture { restoreSettings(app.container.settingsRepository, previousSettings) }
        capture {
            app.applyGuardReconcileSchedule(
                enabled = previousSettings.localGuardModeOrNull() != null,
            )
        }
        capture { assertRuntimeFullyStopped(app) }
        return failures
    }

    private suspend fun currentProfileIds(app: FoxholeApplication): Set<Long> =
        app.container.profileDatabase
            .profileDao()
            .getAllProfiles()
            .mapTo(linkedSetOf()) { profile -> profile.id }

    private suspend fun cleanupCreatedProfiles(
        app: FoxholeApplication,
        profileIdsBefore: Set<Long>,
        activeProfileBefore: Long?,
    ) {
        val repository = app.container.profileRepository
        (currentProfileIds(app) - profileIdsBefore).forEach { profileId ->
            repository.deleteProfile(profileId)
        }
        if (
            activeProfileBefore != null &&
            app.container.profileDatabase.profileDao().getById(activeProfileBefore) != null
        ) {
            repository.setActiveProfile(activeProfileBefore)
        }
        assertTrue(
            "privacy transition gate changed the pre-existing profile set",
            currentProfileIds(app) == profileIdsBefore,
        )
        assertTrue(
            "privacy transition gate did not restore the active profile",
            app.container.profileDatabase.profileDao().getActiveProfile()?.id == activeProfileBefore,
        )
    }

    private suspend fun requireAuthenticatedTorExit(app: FoxholeApplication, label: String): String {
        val exit =
            withTimeoutOrNull(TOR_PROOF_BUDGET_MS) {
                while (true) {
                    val info =
                        runCatching {
                            app.container.connectionController.refreshTorRouteIpInfo(IpInfoFetchMode.ENTRY_QUICK)
                        }.getOrNull()
                    val candidate = info?.ipv4 ?: info?.ipv6 ?: info?.ip
                    candidate?.normalizedNumericIpOrNull()?.let { return@withTimeoutOrNull it }
                    delay(ROUTE_PROOF_RETRY_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        assertNotNull("$label did not return an authenticated Tor exit proof", exit)
        return requireNotNull(exit)
    }

    private suspend fun requireVpnExitNotTor(app: FoxholeApplication, label: String): String {
        val proof =
            withTimeoutOrNull(VPN_PROOF_BUDGET_MS) {
                while (true) {
                    fetchVpnBoundTorCheck(app)?.let { return@withTimeoutOrNull it }
                    delay(ROUTE_PROOF_RETRY_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        assertNotNull("$label did not return a VPN-bound Tor-check proof", proof)
        val verified = requireNotNull(proof)
        assertFalse("$label unexpectedly egressed through Tor", verified.isTor)
        val directExit = requireDirectDeviceExit(app, label)
        assertTrue("$label matched the physical upstream exit", verified.ip != directExit)
        return verified.ip
    }

    private suspend fun requireVpnBoundTorExit(app: FoxholeApplication, label: String): String {
        val proof =
            withTimeoutOrNull(VPN_PROOF_BUDGET_MS) {
                while (true) {
                    fetchVpnBoundTorCheck(app)?.let { return@withTimeoutOrNull it }
                    delay(ROUTE_PROOF_RETRY_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        assertNotNull("$label did not return a VPN-bound Tor-check proof", proof)
        val verified = requireNotNull(proof)
        assertTrue("$label VPN-bound route did not egress through Tor", verified.isTor)
        return verified.ip
    }

    private suspend fun requireDirectDeviceExit(app: FoxholeApplication, label: String): String {
        val exit =
            withTimeoutOrNull(VPN_PROOF_BUDGET_MS) {
                while (true) {
                    val info =
                        runCatching {
                            app.container.connectionController.refreshDeviceIpInfo(IpInfoFetchMode.ENTRY_QUICK)
                        }.getOrNull()
                    val candidate = info?.ipv4 ?: info?.ipv6 ?: info?.ip
                    candidate?.normalizedNumericIpOrNull()?.let { return@withTimeoutOrNull it }
                    delay(ROUTE_PROOF_RETRY_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        assertNotNull("$label did not return a physical-upstream exit proof", exit)
        return requireNotNull(exit)
    }

    private fun requireCurrentVpnNetwork(app: FoxholeApplication, label: String): Network {
        val network = app.container.connectionController.currentVpnNetwork()
        assertNotNull("$label did not expose its VPN network handle", network)
        return requireNotNull(network)
    }

    private fun assertVpnNetworkUnchanged(
        app: FoxholeApplication,
        label: String,
        expected: Network,
    ) {
        assertEquals(
            "$label replaced the VPN network instead of applying in place",
            expected,
            app.container.connectionController.currentVpnNetwork(),
        )
    }

    private suspend fun fetchVpnBoundTorCheck(app: FoxholeApplication): RouteProof? {
        val network = app.container.connectionController.currentVpnNetwork() ?: return null
        return withContext(Dispatchers.IO) { fetchTorCheck(network) }
    }

    private fun fetchTorCheck(network: Network): RouteProof? {
        val connection =
            try {
                network.openConnection(URL(TOR_CHECK_IP_INFO_ENDPOINT)) as? HttpURLConnection
            } catch (_: Exception) {
                null
            } ?: return null
        return try {
            connection.connectTimeout = ROUTE_PROOF_CALL_TIMEOUT_MS
            connection.readTimeout = ROUTE_PROOF_CALL_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val payload = connection.inputStream.bufferedReader().use { reader -> JSONObject(reader.readText()) }
            if (!payload.has("IsTor") || !payload.has("IP")) return null
            val normalizedIp = payload.optString("IP").normalizedNumericIpOrNull() ?: return null
            RouteProof(isTor = payload.getBoolean("IsTor"), ip = normalizedIp)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun String.normalizedNumericIpOrNull(): String? {
        val candidate = this
        if (candidate.isBlank() || candidate != candidate.trim() || !candidate.isNumericIpText()) return null
        return runCatching { InetAddress.getByName(candidate).hostAddress }.getOrNull()
    }

    private fun String.isNumericIpText(): Boolean =
        if (contains(':')) {
            all { character ->
                character == ':' ||
                    character == '.' ||
                    character in '0'..'9' ||
                    character.lowercaseChar() in 'a'..'f'
            }
        } else {
            val octets = split('.')
            octets.size == 4 &&
                octets.all { octet ->
                    octet.isNotEmpty() &&
                        octet.length <= 3 &&
                        octet.all { character -> character in '0'..'9' } &&
                        octet.toInt() in 0..255
                }
        }

    private fun recordStage(label: String, generation: Long) {
        Log.d(TEST_TAG, "livePrivacy stage=$label generation=$generation verified=true")
    }

    private data class LiveStage(val generation: Long)

    private data class RouteProof(
        val isTor: Boolean,
        val ip: String,
    )

    private class TestViewModelStoreOwner(
        override val viewModelStore: ViewModelStore,
    ) : ViewModelStoreOwner

    private companion object {
        const val POLL_INTERVAL_MS = 250L
        const val ROUTE_PROOF_RETRY_MS = 1_000L
        const val ROUTE_PROOF_CALL_TIMEOUT_MS = 30_000
        const val VPN_PROOF_BUDGET_MS = 90_000L
        const val TOR_PROOF_BUDGET_MS = 120_000L
        const val CONNECT_BUDGET_MS = 150_000L
        const val TOR_APPLY_BUDGET_MS = 240_000L
        const val TOR_HANDOFF_BUDGET_MS = 240_000L
        const val CLEANUP_BUDGET_MS = 30_000L
    }
}
