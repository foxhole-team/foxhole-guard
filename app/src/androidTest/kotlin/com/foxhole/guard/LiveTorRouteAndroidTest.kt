package com.foxhole.guard

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.runtime.RuntimeTorUiState
import com.foxhole.guard.core.settings.updatePrivacyRouteBlockAppsWhenTorUnavailable
import com.foxhole.guard.core.settings.updatePrivacyRouteBypassVpnTunnel
import com.foxhole.guard.core.settings.updatePrivacyRouteMode
import com.foxhole.guard.core.settings.updatePrivacyRoutePermitted
import com.foxhole.guard.core.settings.updatePrivacyRouteScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

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
            try {
                resetRelevantSettings(app)
                clearProfiles(app)
                baselineRuntimeSettings(app)
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
                val vpnState = waitForTerminalState(app)
                assertTrue("the plain VPN leg did not connect: $vpnState", vpnState.name == "CONNECTED")
                val vpnExit = runVpnBoundIpRefresh(app)
                Log.d(TEST_TAG, "liveTor vpnExit=$vpnExit")
                assertTrue("no exit address without Tor", vpnExit.isNotBlank())
                disconnectAndWaitForIdle(app)

                settings.updatePrivacyRoutePermitted(true)
                settings.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
                settings.updatePrivacyRouteScope(PrivacyRouteScope.ALL_APPS)
                settings.updatePrivacyRouteBypassVpnTunnel(false)
                settings.updatePrivacyRouteBlockAppsWhenTorUnavailable(true)

                app.container.connectionController.connect(profile.id, protocolOptionId = optionId)
                val torState = waitForTerminalState(app)
                assertTrue("the Tor leg did not connect: $torState", torState.name == "CONNECTED")

                var seen = ""
                val ready =
                    withTimeoutOrNull(TOR_BOOTSTRAP_BUDGET_MS) {
                        while (true) {
                            val tor = app.container.connectionController.runtimeUiState.value.tor
                            val label = tor.javaClass.simpleName
                            if (label != seen) {
                                seen = label
                                Log.d(TEST_TAG, "liveTor transition=$label detail=$tor")
                            }
                            when (tor) {
                                is RuntimeTorUiState.Ready -> return@withTimeoutOrNull tor
                                else -> delay(500)
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        null
                    }
                Log.d(TEST_TAG, "liveTor state=$ready lastSeen=$seen")
                assertNotEquals(
                    "the Tor state never left Off, so nothing reported the route the runtime engaged",
                    "Off",
                    seen,
                )

                val torExit = runVpnBoundIpRefresh(app)
                Log.d(TEST_TAG, "liveTor torExit=$torExit vpnExit=$vpnExit")
                assertTrue("no exit address with Tor on", torExit.isNotBlank())
                assertNotEquals(
                    "the exit address did not change with Tor on: traffic bypassed it",
                    vpnExit,
                    torExit,
                )

                disconnectAndWaitForIdle(app)
                val stopped = app.container.connectionController.snapshot.value.state
                assertTrue("the Tor leg did not stop cleanly: $stopped", stopped.name == "IDLE")
            } finally {
                restoreSettings(settings, previous)
            }
        }
    }

    private companion object {
        const val TOR_BOOTSTRAP_BUDGET_MS = 180_000L
    }
}
