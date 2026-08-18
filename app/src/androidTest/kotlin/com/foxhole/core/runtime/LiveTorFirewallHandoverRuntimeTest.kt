package com.foxhole.core.runtime

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
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
                settings.updatePrivacyRouteMode(PrivacyRouteMode.OFF)
                settings.updateFirewallEnabled(false)
                runCatching { stopGuardAndWait(app) }
                    .onFailure { cleanupFailure ->
                        primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                    }
                restoreSettings(settings, previous)
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
        assertBoundInternet(requireNotNull(oldNetwork), "$scenario VPN+Tor")

        settings.updateFirewallEnabled(true)
        if (overlapTorRemovalWithStop) {
            settings.updatePrivacyRouteMode(PrivacyRouteMode.OFF)
            assertTrue("$scenario Tor-removal reload was not dispatched", controller.reload(profileId))
        }
        controller.disconnect(suppressLocalGuard = false)

        assertTrue(
            "$scenario did not publish replacement firewall guard",
            waitUntil(GUARD_TIMEOUT_MS) {
                controller.snapshot.value.state == ConnectionState.CONNECTED &&
                    controller.snapshot.value.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
                    controller.currentVpnNetworkHandle() != null &&
                    controller.currentVpnNetworkHandle() != oldHandle
            },
        )
        val newHandle = controller.currentVpnNetworkHandle()
        assertNotNull("$scenario replacement firewall handle missing", newHandle)
        assertNotEquals("$scenario reused the retired VPN Network handle", oldHandle, newHandle)
        assertTrue(
            "$scenario retired VPN Network remained registered",
            waitUntil(OLD_HANDLE_TIMEOUT_MS) { networkByHandle(app, requireNotNull(oldHandle)) == null },
        )
        val guardNetwork = networkByHandle(app, requireNotNull(newHandle))
        assertNotNull("$scenario firewall Network disappeared", guardNetwork)
        assertBoundInternet(requireNotNull(guardNetwork), "$scenario firewall guard")
        assertTrue(
            "$scenario left Tor running after firewall handover",
            waitUntil(TOR_STOP_TIMEOUT_MS) { controller.torPhase.value.phase == TorNetworkPhase.OFFLINE },
        )
        assertFalse("$scenario guard snapshot still claims Tor", controller.snapshot.value.torActive)
        Log.d(TEST_TAG, "handover scenario=$scenario old=$oldHandle new=$newHandle PASS")
    }

    private suspend fun assertBoundInternet(
        network: Network,
        label: String,
    ) = withContext(Dispatchers.IO) {
        assertTrue("$label DNS returned no addresses", network.getAllByName("example.com").isNotEmpty())
        val connection = network.openConnection(URL("https://example.com")) as HttpURLConnection
        try {
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            val responseCode = connection.responseCode
            assertTrue("$label HTTP failed with $responseCode", responseCode in 200..399)
        } finally {
            connection.disconnect()
        }
    }

    private fun networkByHandle(
        app: FoxholeApplication,
        handle: Long,
    ): Network? =
        ConnectivityNetworkRegistry
            .snapshot(app)
            .firstOrNull { network -> network.networkHandle == handle }

    private suspend fun stopGuardAndWait(app: FoxholeApplication) {
        val controller = app.container.connectionController
        app.container.settingsRepository.updateFirewallEnabled(false)
        controller.disconnect(suppressLocalGuard = true, userInitiated = false)
        FoxholeConnectionServiceContract.stopAllServices(app)
        assertTrue(
            "FoxHole VPN remained after test cleanup",
            waitUntil(CLEANUP_TIMEOUT_MS) {
                !controller.hasActiveVpnNetwork() && controller.snapshot.value.state != ConnectionState.DISCONNECTING
            },
        )
        delay(500L)
    }

    private companion object {
        const val ARG_ENABLED = "foxhole.liveTorFirewallHandover"
        const val CONNECT_TIMEOUT_MS = 120_000L
        const val TOR_TIMEOUT_MS = 480_000L
        const val GUARD_TIMEOUT_MS = 90_000L
        const val OLD_HANDLE_TIMEOUT_MS = 30_000L
        const val TOR_STOP_TIMEOUT_MS = 30_000L
        const val CLEANUP_TIMEOUT_MS = 60_000L
        const val HTTP_TIMEOUT_MS = 10_000
    }
}
