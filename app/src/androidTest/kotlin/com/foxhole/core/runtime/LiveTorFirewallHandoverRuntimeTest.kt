package com.foxhole.core.runtime

import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.runtime.ConnectivityNetworkRegistry
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.ProfileRuntimeSessionAndroidTestSupport
import com.foxhole.guard.core.settings.updateFirewallEnabled
import com.foxhole.guard.core.settings.updatePrivacyRouteBlockAppsWhenTorUnavailable
import com.foxhole.guard.core.settings.updatePrivacyRouteBridgesEnabled
import com.foxhole.guard.core.settings.updatePrivacyRouteBypassVpnTunnel
import com.foxhole.guard.core.settings.updatePrivacyRouteMode
import com.foxhole.guard.core.settings.updatePrivacyRoutePermitted
import com.foxhole.guard.core.settings.updatePrivacyRouteScope
import com.foxhole.guard.core.settings.updateVpnRoutingScenario
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.runtime.currentVpnNetworkHandle
import com.foxhole.guard.runtime.runtimeValidationProbeEndpoints
import com.foxhole.guard.runtime.vpnNetworkIdentityDiffersFrom
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class LiveTorFirewallHandoverRuntimeTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun torVpnScenariosHandOverToFirewallWithNetworkProof() =
        runBlocking {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(
                "live Tor/firewall handover skipped: pass -e $ARG_ENABLED 1 to run it",
                args.getString(ARG_ENABLED) == "1",
            )
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            assertTrue("VPN permission missing for live Tor/firewall handover", ensureVpnPermission(app))
            val subscription = smartSubscriptionInput()
            assertNotNull("subscription input missing for live Tor/firewall handover", subscription)
            val settings = app.container.settingsRepository
            val previous = settings.current()
            var primaryFailure: Throwable? = null

            try {
                resetRelevantSettings(app)
                clearProfiles(app)
                settings.update { current ->
                    current.copy(
                        webApps = current.webApps.copy(enabled = true, pushServiceEnabled = false),
                    )
                }
                val profile =
                    app.container.profileRepository.importProfile(
                        rawInput = requireNotNull(subscription),
                        preferredName = "Live Tor Firewall",
                        allowInsecureTlsForProfile =
                            args.getString("foxhole.allowInsecureTlsForLiveSubscription") == "1",
                    )
                val target =
                    probeTargetFor(app, profile.id, setOf(ProtocolHint.VLESS, ProtocolHint.TROJAN))
                assertNotNull("subscription exposed no Tor-compatible VPN option", target)

                runScenario(
                    app = app,
                    profileId = profile.id,
                    protocolOptionId = requireNotNull(target).optionId,
                    localHttpProxyEnabled = false,
                    overlapTorRemovalWithStop = false,
                )
                stopGuardAndWait(app)
                runScenario(
                    app = app,
                    profileId = profile.id,
                    protocolOptionId = target.optionId,
                    localHttpProxyEnabled = true,
                    overlapTorRemovalWithStop = true,
                )
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                val cleanupFailures =
                    withContext(NonCancellable) {
                        finalizeTestIndependently(app, previous)
                    }
                propagateCleanupFailures(primaryFailure, cleanupFailures)
            }
        }

    private suspend fun runScenario(
        app: FoxholeApplication,
        profileId: Long,
        protocolOptionId: String?,
        localHttpProxyEnabled: Boolean,
        overlapTorRemovalWithStop: Boolean,
    ) {
        val controller = app.container.connectionController
        val settings = app.container.settingsRepository
        baselineRuntimeSettings(app)
        settings.updateVpnRoutingScenario(
            perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
            localHttpProxyEnabled = localHttpProxyEnabled,
        )
        settings.updatePrivacyRoutePermitted(true)
        settings.updatePrivacyRouteBridgesEnabled(false)
        settings.updatePrivacyRouteScope(PrivacyRouteScope.ALL_APPS)
        settings.updatePrivacyRouteBypassVpnTunnel(false)
        settings.updatePrivacyRouteBlockAppsWhenTorUnavailable(true)
        settings.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
        controller.setActiveProfile(profileId)

        val scenario = if (localHttpProxyEnabled) "proxy" else "whole-device"
        Log.d(TEST_TAG, "handover scenario=$scenario connect")
        controller.connect(profileId, protocolOptionId = protocolOptionId)
        val terminalState =
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                waitForActiveConnectionAttempt(app)
                waitForTerminalState(app)
            }
        assertEquals("$scenario VPN did not connect", ConnectionState.CONNECTED, terminalState)
        assertTrue(
            "$scenario Tor never reached CONNECTED",
            waitUntil(TOR_TIMEOUT_MS) {
                controller.torPhase.value.phase == TorNetworkPhase.CONNECTED &&
                    controller.snapshot.value.torActive &&
                    controller.snapshot.value.state == ConnectionState.CONNECTED
            },
        )

        val oldHandle = controller.currentVpnNetworkHandle()
        assertNotNull("$scenario VPN network handle missing", oldHandle)
        val oldNetwork = networkByHandle(app, requireNotNull(oldHandle))
        assertNotNull("$scenario VPN network disappeared before proof", oldNetwork)
        val oldInterfaceName = networkInterfaceName(app, requireNotNull(oldNetwork))
        assertNotNull("$scenario VPN interface name missing", oldInterfaceName)
        assertBoundInternet(oldNetwork, "$scenario VPN+Tor")

        val handoverStartedAt = System.currentTimeMillis()
        settings.updateFirewallEnabled(true)
        if (overlapTorRemovalWithStop) {
            settings.updatePrivacyRouteMode(PrivacyRouteMode.OFF)
            assertTrue("$scenario Tor-removal reload was not dispatched", controller.reload(profileId))
        }
        controller.disconnect(suppressLocalGuard = false)

        assertFirewallGuardPublished(
            app = app,
            scenario = scenario,
            oldHandle = requireNotNull(oldHandle),
            oldInterfaceName = requireNotNull(oldInterfaceName),
            handoverStartedAt = handoverStartedAt,
        )
        val newHandle = controller.currentVpnNetworkHandle()
        assertNotNull("$scenario replacement firewall handle missing", newHandle)
        val guardNetwork = networkByHandle(app, requireNotNull(newHandle))
        assertNotNull("$scenario firewall Network disappeared", guardNetwork)
        val newInterfaceName = networkInterfaceName(app, requireNotNull(guardNetwork))
        assertTrue(
            "$scenario firewall did not replace the retired VPN interface",
            vpnNetworkIdentityDiffersFrom(
                currentHandle = newHandle,
                currentInterfaceName = newInterfaceName,
                previousHandle = oldHandle,
                previousInterfaceName = oldInterfaceName,
            ),
        )
        if (newHandle != oldHandle) {
            assertTrue(
                "$scenario retired VPN Network remained registered",
                waitUntil(OLD_HANDLE_TIMEOUT_MS) { networkByHandle(app, oldHandle) == null },
            )
        } else {
            assertTrue(
                "$scenario retired VPN interface remained registered behind the reused Network handle",
                waitUntil(OLD_HANDLE_TIMEOUT_MS) { networkByInterfaceName(app, oldInterfaceName) == null },
            )
        }
        assertBoundInternet(guardNetwork, "$scenario firewall guard")
        assertTrue(
            "$scenario left Tor running after firewall handover",
            waitUntil(TOR_STOP_TIMEOUT_MS) { controller.torPhase.value.phase == TorNetworkPhase.OFFLINE },
        )
        assertFalse("$scenario guard snapshot still claims Tor", controller.snapshot.value.torActive)
        Log.d(TEST_TAG, "handover scenario=$scenario old=$oldHandle new=$newHandle PASS")
    }

    private suspend fun assertFirewallGuardPublished(
        app: FoxholeApplication,
        scenario: String,
        oldHandle: Long,
        oldInterfaceName: String,
        handoverStartedAt: Long,
    ) {
        val controller = app.container.connectionController
        val published =
            waitUntil(GUARD_TIMEOUT_MS) {
                controller.snapshot.value.state == ConnectionState.CONNECTED &&
                    controller.snapshot.value.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
                    controller.currentVpnNetworkHandle()?.let { handle ->
                        val network = networkByHandle(app, handle)
                        vpnNetworkIdentityDiffersFrom(
                            currentHandle = handle,
                            currentInterfaceName = network?.let { networkInterfaceName(app, it) },
                            previousHandle = oldHandle,
                            previousInterfaceName = oldInterfaceName,
                        )
                    } == true
            }
        if (published) {
            return
        }

        val snapshot = controller.snapshot.value
        val queue = app.container.runtimeSupervisor.queueSnapshot()
        val ownership = app.container.runtimeSupervisor.ownership.value
        val native = app.container.runtimeInstanceStore.nativeSnapshot()
        val firewallEnabled = app.container.settingsRepository.current().expert.firewallEnabled
        val currentHandle = controller.currentVpnNetworkHandle()
        val markers = safeHandoverMarkers(app, handoverStartedAt)
        assertTrue(
            buildString {
                append("$scenario did not publish replacement firewall guard")
                append("; state=${snapshot.state.name.lowercase()}")
                append(" profile=${snapshot.profileId}")
                append(" teardown=${snapshot.teardownPhase?.name?.lowercase() ?: "none"}")
                append(" tor=${snapshot.torActive}")
                append(" firewall=$firewallEnabled")
                append(" handle=${handleRelation(currentHandle, oldHandle)}")
                append(" controller_vpn=${controller.hasActiveVpnNetwork()}")
                append(" queue=${queue.runningPriority ?: "idle"}/${safeCommandReason(queue.runningReason)}")
                append(" depth=${queue.commandQueueDepth}")
                append(" closed=${queue.closed}")
                append(" owner_mode=${ownership.activeMode?.name?.lowercase() ?: "none"}")
                append(" owner_session=${ownership.activeSession != null}")
                append(" owner_guard=${ownership.activeLocalGuardMode?.name?.lowercase() ?: "none"}")
                append(" validating=${ownership.validationActive}")
                append(" native=${native.nativeState.name.lowercase()}")
                append(" draining=${native.cleanupDraining}")
                append(" engine=${native.hasEngineHandle}")
                append(" tun=${native.hasTunFileDescriptor}")
                append(" host=${native.hasHost}")
                append(" config=${native.hasConfig}")
                append(" last_stop=${safeStopReason(native.lastStopReason)}")
                append(" markers=$markers")
            },
            published,
        )
    }

    private fun safeHandoverMarkers(
        app: FoxholeApplication,
        handoverStartedAt: Long,
    ): List<String> =
        app.container.diagnosticsLogger.entries.value
            .asSequence()
            .filter { entry -> entry.timestamp >= handoverStartedAt }
            .mapNotNull { entry -> safeHandoverMarker(entry.message) }
            .toList()
            .takeLast(MAX_HANDOVER_MARKERS)

    @Suppress("CyclomaticComplexMethod")
    private fun safeHandoverMarker(message: String): String? =
        when {
            message.contains("disconnect requested") -> "disconnect_requested"
            message.contains("runtime priority command queued") -> "priority_queued"
            message.contains("runtime priority command coalesced") -> "priority_coalesced"
            message.contains("runtime command queued") -> "command_queued"
            message.contains("runtime command preempted") -> "command_preempted"
            message.contains("runtime command force-killed") -> "command_force_killed"
            message.contains("runtime command started") -> "command_started"
            message.contains("runtime command completed") -> "command_completed"
            message.contains("runtime command cancelled") -> "command_cancelled"
            message.contains("runtime command failed") -> "command_failed"
            message.contains("runtime command rejected") -> "command_rejected"
            message.contains("runtime command skipped for closed owner") -> "closed_owner"
            message.contains("session ended") -> "session_ended"
            message.contains("tunnel handoff to local guard") -> "tunnel_to_guard"
            message.contains("native runtime quiesced for interface handover") -> "native_quiesced"
            message.contains("vpn runtime quiesced and retired for interface handover") -> "runtime_retired"
            message.contains("local guard start deferred") -> "guard_deferred"
            message.contains("local guard session build failed") -> "guard_build_failed"
            message.contains("local guard start refused") -> "guard_refused"
            message.contains("local guard start failed") -> "guard_start_failed"
            message.contains("local guard vpn network wait timeout") -> "guard_network_wait_timeout"
            message.contains("local guard vpn network validation timeout") -> "guard_validation_timeout"
            message.contains("local guard reachability fallback passed") -> "guard_fallback_passed"
            message.contains("local guard reachability fallback failed") -> "guard_fallback_failed"
            message.contains("local guard started") -> "guard_started"
            message.contains("previous service destroy cleanup completed") -> "owner_drain_completed"
            message.contains("runtime command refused while previous service destroy") -> "owner_drain_refused"
            message.contains("bridge write rejected") -> "bridge_write_rejected"
            else -> null
        }

    private fun handleRelation(
        currentHandle: Long?,
        oldHandle: Long,
    ): String =
        when (currentHandle) {
            null -> "none"
            oldHandle -> "old"
            else -> "replacement"
        }

    private fun safeCommandReason(reason: String?): String =
        when {
            reason == null -> "none"
            reason == "disconnect" -> "disconnect"
            reason.startsWith("reload:") -> "reload"
            reason.startsWith("connect:") -> "connect"
            reason.startsWith("local_guard:") -> "local_guard"
            else -> "other"
        }

    private fun safeStopReason(reason: String?): String =
        when {
            reason == null -> "none"
            reason.startsWith("reload_recovery") -> "reload_recovery"
            reason.startsWith("local_guard_handoff") -> "local_guard_handoff"
            reason.startsWith("disconnect") -> "disconnect"
            reason.startsWith("priority_command_preempt") -> "priority_preempt"
            else -> "other"
        }

    private suspend fun assertBoundInternet(
        network: Network,
        label: String,
    ) = withContext(Dispatchers.IO) {
        val dnsProof =
            retryBoundNetworkProof(BOUND_DNS_PROBE_HOSTS) { host ->
                network.getAllByName(host).isNotEmpty()
            }
        assertTrue(
            "$label DNS proof failed after bounded retries; failures=${dnsProof.failures.joinToString()}",
            dnsProof.passed,
        )
        val httpProof =
            retryBoundNetworkProof(runtimeValidationProbeEndpoints(emptyList())) { endpoint ->
                boundHttpEndpointReachable(network, endpoint)
            }
        assertTrue(
            "$label HTTP proof failed after bounded retries; failures=${httpProof.failures.joinToString()}",
            httpProof.passed,
        )
    }

    private suspend fun retryBoundNetworkProof(
        candidates: List<String>,
        probe: (String) -> Boolean,
    ): BoundNetworkProof {
        val failures = linkedSetOf<String>()
        repeat(BOUND_PROOF_ATTEMPTS) { attempt ->
            candidates.forEach { candidate ->
                try {
                    if (probe(candidate)) {
                        return BoundNetworkProof(passed = true, failures = failures.toList())
                    }
                    failures += "response_rejected"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures += error.javaClass.simpleName
                }
            }
            if (attempt + 1 < BOUND_PROOF_ATTEMPTS) {
                delay(BOUND_PROOF_RETRY_DELAY_MS)
            }
        }
        return BoundNetworkProof(passed = false, failures = failures.toList())
    }

    private fun boundHttpEndpointReachable(
        network: Network,
        endpoint: String,
    ): Boolean {
        val connection = network.openConnection(URL(endpoint)) as HttpURLConnection
        try {
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            return connection.responseCode in 200..399
        } finally {
            connection.disconnect()
        }
    }

    private data class BoundNetworkProof(
        val passed: Boolean,
        val failures: List<String>,
    )

    private fun networkByHandle(
        app: FoxholeApplication,
        handle: Long,
    ): Network? =
        ConnectivityNetworkRegistry
            .snapshot(app)
            .firstOrNull { network -> network.networkHandle == handle }

    private fun networkByInterfaceName(
        app: FoxholeApplication,
        interfaceName: String,
    ): Network? =
        ConnectivityNetworkRegistry
            .snapshot(app)
            .firstOrNull { network -> networkInterfaceName(app, network) == interfaceName }

    private fun networkInterfaceName(
        app: FoxholeApplication,
        network: Network,
    ): String? =
        app.getSystemService(ConnectivityManager::class.java)
            .getLinkProperties(network)
            ?.interfaceName

    private suspend fun stopGuardAndWait(app: FoxholeApplication) {
        val controller = app.container.connectionController
        val failures = mutableListOf<Throwable>()
        failures.captureCleanupFailure {
            app.container.settingsRepository.updateFirewallEnabled(false)
        }
        failures.captureCleanupFailure {
            controller.disconnect(suppressLocalGuard = true)
        }
        val stoppedGracefully =
            waitUntil(GRACEFUL_CLEANUP_TIMEOUT_MS) {
                runtimeFullyStopped(app)
            }
        if (!stoppedGracefully) {
            failures.captureCleanupFailure {
                FoxholeConnectionServiceContract.stopAllServices(app)
            }
        }
        val stopped =
            stoppedGracefully ||
                waitUntil(FALLBACK_CLEANUP_TIMEOUT_MS) {
                    runtimeFullyStopped(app)
                }
        if (!stopped) {
            failures += AssertionError("FoxHole service or VPN remained after test cleanup")
        }
        delay(500L)
        val primary = failures.firstOrNull() ?: return
        failures.drop(1).forEach(primary::addSuppressed)
        throw primary
    }

    private fun runtimeFullyStopped(app: FoxholeApplication): Boolean {
        val controller = app.container.connectionController
        val snapshot = controller.snapshot.value
        return snapshot.state == ConnectionState.IDLE &&
            !snapshot.torActive &&
            snapshot.appliedTorRoute == null &&
            controller.torPhase.value.phase == TorNetworkPhase.OFFLINE &&
            !controller.hasActiveVpnNetwork() &&
            !hasFoxholeRuntimeServices(app) &&
            !hasActiveFoxholeVpnNetwork(app)
    }

    private suspend fun finalizeTestIndependently(
        app: FoxholeApplication,
        previous: com.foxhole.core.model.Settings,
    ): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        failures.captureCleanupFailure {
            app.container.settingsRepository.updatePrivacyRouteMode(PrivacyRouteMode.OFF)
        }
        failures.captureCleanupFailure {
            app.container.settingsRepository.updateFirewallEnabled(false)
        }
        failures.captureCleanupFailure {
            stopGuardAndWait(app)
        }
        failures.captureCleanupFailure {
            restoreSettings(app.container.settingsRepository, previous)
        }
        failures.captureCleanupFailure {
            assertTrue("Tor/firewall handover gate left a service or VPN active", runtimeFullyStopped(app))
        }
        return failures
    }

    private suspend fun MutableList<Throwable>.captureCleanupFailure(action: suspend () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            add(failure)
        }
    }

    private fun propagateCleanupFailures(
        primaryFailure: Throwable?,
        cleanupFailures: List<Throwable>,
    ) {
        if (primaryFailure != null) {
            cleanupFailures.filterNot { cleanupFailure -> cleanupFailure === primaryFailure }
                .forEach(primaryFailure::addSuppressed)
            return
        }

        val cleanupPrimary = cleanupFailures.firstOrNull() ?: return
        cleanupFailures.drop(1)
            .filterNot { cleanupFailure -> cleanupFailure === cleanupPrimary }
            .forEach(cleanupPrimary::addSuppressed)
        throw cleanupPrimary
    }

    private companion object {
        const val ARG_ENABLED = "foxhole.liveTorFirewallHandover"
        const val CONNECT_TIMEOUT_MS = 120_000L
        const val TOR_TIMEOUT_MS = 480_000L
        const val GUARD_TIMEOUT_MS = 90_000L
        const val OLD_HANDLE_TIMEOUT_MS = 30_000L
        const val TOR_STOP_TIMEOUT_MS = 30_000L
        const val GRACEFUL_CLEANUP_TIMEOUT_MS = 30_000L
        const val FALLBACK_CLEANUP_TIMEOUT_MS = 30_000L
        const val HTTP_TIMEOUT_MS = 10_000
        const val MAX_HANDOVER_MARKERS = 40
        const val BOUND_PROOF_ATTEMPTS = 2
        const val BOUND_PROOF_RETRY_DELAY_MS = 750L
        val BOUND_DNS_PROBE_HOSTS = listOf("example.com", "cloudflare.com", "www.gstatic.com")
    }
}
