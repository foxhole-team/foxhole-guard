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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live Tor-over-VPN on hardware: does the traffic actually go through Tor.
 *
 * The gap this fills is specific. `TorRuntimeInstallerDeviceTest` checks that a
 * Tor configuration translates and that Arti's state directory can be created —
 * neither of which requires a single byte to reach the Tor network. Tor
 * bootstrap, routing and stop have never been exercised on a device, and they
 * are three of the unchecked boxes in the beta checklist.
 *
 * **The assertion that matters is the exit address, not the state.** A tunnel
 * that reports `Ready` while the packets went out over the plain VPN is the
 * failure mode worth testing for: it looks correct from every screen the user
 * has. So the test records the exit address without Tor, turns Tor on, and
 * requires the address to change. Equal addresses fail the test even though
 * every status in the app would read healthy.
 *
 * Manual, like the other live gates here: it needs a real subscription and a
 * working network, and it is skipped unless
 * `-Pandroid.testInstrumentationRunnerArguments.foxhole.liveTor=1` is passed.
 */
@RunWith(AndroidJUnit4::class)
internal class LiveTorRouteAndroidTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun manualTorOverVpnChangesTheExitAddressAndStopsCleanly() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveTor") != "1") {
            Log.d(TEST_TAG, "live tor route skipped")
            return
        }
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

                // Leg one: the VPN exit, with Tor off. This is the address the
                // Tor leg has to differ from, and taking it first is what makes
                // the comparison mean anything.
                app.container.connectionController.connect(profile.id, protocolOptionId = optionId)
                val vpnState = waitForTerminalState(app)
                assertTrue("the plain VPN leg did not connect: $vpnState", vpnState.name == "CONNECTED")
                val vpnExit = runVpnBoundIpRefresh(app)
                Log.d(TEST_TAG, "liveTor vpnExit=$vpnExit")
                assertTrue("no exit address without Tor", vpnExit.isNotBlank())
                disconnectAndWaitForIdle(app)

                // Leg two: the same profile with Tor over it, and fail-closed if
                // Tor does not come up — otherwise a bootstrap failure would
                // quietly answer this test with the plain VPN address and the
                // comparison below would be measuring nothing.
                settings.updatePrivacyRoutePermitted(true)
                settings.updatePrivacyRouteMode(PrivacyRouteMode.TOR_OVER_VPN)
                settings.updatePrivacyRouteScope(PrivacyRouteScope.ALL_APPS)
                settings.updatePrivacyRouteBypassVpnTunnel(false)
                settings.updatePrivacyRouteBlockAppsWhenTorUnavailable(true)

                app.container.connectionController.connect(profile.id, protocolOptionId = optionId)
                val torState = waitForTerminalState(app)
                assertTrue("the Tor leg did not connect: $torState", torState.name == "CONNECTED")

                // Every distinct state is logged, not just the terminal one.
                // A run that ends in `null` otherwise cannot say whether Tor
                // was bootstrapping slowly or never started at all, and those
                // are different defects.
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
                // Asserted on movement, not on Ready. The published state used to
                // be pinned to `Off` for the life of the process — nothing fed it
                // — so this line was a bare log. It is fed now, and leaving `Off`
                // is the claim worth failing on: it proves the pipeline reports a
                // route the runtime really engaged.
                //
                // `Ready` is deliberately NOT the gate. It waits for the exit
                // address the runtime observes for the Tor route, and that probe
                // is a best-effort background refresh; requiring it inside a fixed
                // budget tests the probe's schedule rather than Tor. What Tor
                // actually did is asserted below, on the address itself — which is
                // what this test was written to measure.
                Log.d(TEST_TAG, "liveTor state=$ready lastSeen=$seen")
                assertNotEquals(
                    "the Tor state never left Off, so nothing reported the route the runtime engaged",
                    "Off",
                    seen,
                )

                val torExit = runVpnBoundIpRefresh(app)
                Log.d(TEST_TAG, "liveTor torExit=$torExit vpnExit=$vpnExit")
                assertTrue("no exit address with Tor on", torExit.isNotBlank())
                // The whole point. Same address means the packets did not go
                // through Tor, whatever the status says.
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
        /** Arti over a proxied VPN leg, on a phone. Generous on purpose. */
        const val TOR_BOOTSTRAP_BUDGET_MS = 180_000L
    }
}
