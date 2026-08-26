package com.foxhole.guard

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.runtime.RuntimeTorUiState
import com.foxhole.guard.core.settings.updatePrivacyRouteBlockAppsWhenTorUnavailable
import com.foxhole.guard.core.settings.updatePrivacyRouteBypassVpnTunnel
import com.foxhole.guard.core.settings.updatePrivacyRouteMode
import com.foxhole.guard.core.settings.updatePrivacyRoutePermitted
import com.foxhole.guard.core.settings.updatePrivacyRouteScope
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
internal class LiveTorRouteAndroidTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun manualTorOverVpnChangesTheExitAddressAndStopsCleanly() {
        assumeTrue(
            "live tor route skipped: pass -e foxhole.liveTor 1 to run it",
            InstrumentationRegistry.getArguments().getString("foxhole.liveTor") == "1",
        )
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val subscription = smartSubscriptionInput()
            if (subscription == null) {
                assertTrue("subscription input missing for the live Tor gate", false)
                return@runBlocking
            }
            if (!ensureVpnPermission(app)) {
                assertTrue("vpn permission missing for the live Tor gate", false)
                return@runBlocking
            }

            val settings = app.container.settingsRepository
            val previous = settings.current()
            var primaryFailure: Throwable? = null
            try {
                assertLiveTorStopped(app)
                resetRelevantSettings(app)
                clearProfiles(app)
                baselineRuntimeSettings(app)
                settings.updatePrivacyRouteMode(PrivacyRouteMode.OFF)
                settings.updatePrivacyRoutePermitted(false)
                val profile =
                    app.container.profileRepository.importProfile(
                        rawInput = subscription,
                        preferredName = "Live Tor",
                    )
                app.container.connectionController.setActiveProfile(profile.id)
                val target =
                    probeTargetFor(app, profile.id, setOf(ProtocolHint.VLESS, ProtocolHint.TROJAN))
                assertTrue("the subscription exposed no connectable option", target != null)
                val optionId = target?.optionId

                app.container.connectionController.connect(profile.id, protocolOptionId = optionId)
                val vpnState =
                    withTimeoutOrNull(LIVE_LEG_TERMINAL_BUDGET_MS) {
                        waitForActiveConnectionAttempt(app)
                        waitForTerminalState(app)
                    }
                assertTrue("the plain VPN leg did not connect: $vpnState", vpnState?.name == "CONNECTED")
                val plainSnapshot = app.container.connectionController.snapshot.value
                assertFalse("the plain VPN leg unexpectedly applied Tor", plainSnapshot.torActive)
                assertTrue("the plain VPN leg retained an applied Tor route", plainSnapshot.appliedTorRoute == null)
                val vpnExit = requireSuccessfulNumericIp("plain VPN", runVpnBoundIpRefresh(app))
                Log.d(TEST_TAG, "liveTor vpnRefreshOk=true")
                disconnectAndWaitForIdle(app)

                settings.updatePrivacyRoutePermitted(true)
                settings.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
                settings.updatePrivacyRouteScope(PrivacyRouteScope.ALL_APPS)
                settings.updatePrivacyRouteBypassVpnTunnel(false)
                settings.updatePrivacyRouteBlockAppsWhenTorUnavailable(true)

                app.container.connectionController.connect(profile.id, protocolOptionId = optionId)
                val torState =
                    withTimeoutOrNull(LIVE_LEG_TERMINAL_BUDGET_MS) {
                        waitForActiveConnectionAttempt(app)
                        waitForTerminalState(app)
                    }
                assertTrue("the Tor leg did not connect: $torState", torState?.name == "CONNECTED")

                var seen = ""
                val ready =
                    withTimeoutOrNull(TOR_BOOTSTRAP_BUDGET_MS) {
                        while (true) {
                            val tor = app.container.connectionController.runtimeUiState.value.tor
                            val label = tor.javaClass.simpleName
                            if (label != seen) {
                                seen = label
                                Log.d(TEST_TAG, "liveTor transition=$label")
                            }
                            when (tor) {
                                is RuntimeTorUiState.Ready -> return@withTimeoutOrNull tor
                                else -> delay(500)
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        null
                    }
                Log.d(TEST_TAG, "liveTor ready=${ready != null} lastSeen=$seen")
                assertTrue(
                    "Tor did not become Ready within ${TOR_BOOTSTRAP_BUDGET_MS}ms; lastSeen=$seen",
                    ready != null,
                )

                val torExit = requireSuccessfulNumericIp("VPN+Tor", runVpnBoundIpRefresh(app))
                Log.d(TEST_TAG, "liveTor torRefreshOk=true exitsDiffer=${vpnExit != torExit}")
                assertTrue(
                    "the VPN+Tor exit did not differ from the plain VPN exit: traffic bypassed Tor",
                    vpnExit != torExit,
                )

                disconnectAndWaitForIdle(app)
                val stopped = app.container.connectionController.snapshot.value.state
                assertTrue("the Tor leg did not stop cleanly: $stopped", stopped.name == "IDLE")
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                val cleanupFailure =
                    withContext(NonCancellable) {
                        runCatching { assertLiveTorStopped(app) }.exceptionOrNull()
                    }
                val restoreFailure =
                    withContext(NonCancellable) {
                        runCatching { restoreSettings(settings, previous) }.exceptionOrNull()
                    }
                val finalizationFailure = cleanupFailure ?: restoreFailure
                if (cleanupFailure != null && restoreFailure != null) {
                    cleanupFailure.addSuppressed(restoreFailure)
                }
                primaryFailure?.let { original -> finalizationFailure?.let(original::addSuppressed) }
                    ?: finalizationFailure?.let { throw it }
            }
        }
    }

    private suspend fun assertLiveTorStopped(app: FoxholeApplication) {
        disconnectAndWaitForIdle(app)
        FoxholeConnectionServiceContract.stopAllServices(app)
        val stopped =
            waitUntil(timeoutMs = LIVE_CLEANUP_BUDGET_MS) {
                app.container.connectionController.snapshot.value.state.name == "IDLE" &&
                    !hasFoxholeRuntimeServices(app) &&
                    !hasActiveFoxholeVpnNetwork(app)
            }
        assertTrue("the live Tor gate did not clean up its runtime", stopped)
    }

    private fun requireSuccessfulNumericIp(
        label: String,
        refreshResult: String,
    ): String {
        val normalized = refreshResult.strictNumericIpOrNull()
        assertTrue("$label exit refresh did not return strict ok:<numeric IP>", normalized != null)
        return requireNotNull(normalized)
    }

    private fun String.strictNumericIpOrNull(): String? {
        if (!startsWith(IP_REFRESH_OK_PREFIX)) return null
        val candidate = removePrefix(IP_REFRESH_OK_PREFIX)
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

    private companion object {
        const val IP_REFRESH_OK_PREFIX = "ok:"
        const val LIVE_LEG_TERMINAL_BUDGET_MS = 150_000L
        const val LIVE_CLEANUP_BUDGET_MS = 30_000L
        const val TOR_BOOTSTRAP_BUDGET_MS = 180_000L
    }
}
