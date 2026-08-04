package com.foxhole.core.runtime

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.networkUp
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.HTTP_OK
import com.foxhole.guard.ProfileRuntimeSessionAndroidTestSupport
import com.foxhole.guard.core.settings.updateI2pEnabled
import com.foxhole.guard.core.settings.updateI2pEngaged
import com.foxhole.guard.i2pSocksHttpGet
import com.foxhole.guard.overlayHttpGet
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live I2P on hardware: does the bundled router actually join the network, and does anything
 * travel over it.
 *
 * Nothing in the suite covered I2P before this. `libi2pd.so` ships in the APK, the app starts
 * it as a supervised child and the whole feature has been judged from the app's own phase
 * pill — which is derived from i2pd's stdout. That is exactly the wrong instrument: the phase
 * moves to BUILDING_TUNNELS the moment a log line mentions a tunnel, so a router that reseeds,
 * prints, and then reaches nobody looks identical on screen to one that works.
 *
 * **So the assertions are the router's own counters and a byte-carrying request, not the
 * phase.** The phase is only used as a gate to know when to start asking. What must hold is:
 *  - the netdb has peers (`Routers` > 0) — the router reseeded and learned about the network;
 *  - it built its own client tunnels (`Client Tunnels` > 0) — peers accepted it;
 *  - it moved bytes in both directions — the tunnels are not decorative.
 * All three are read from the loopback webconsole, which is the only status surface i2pd
 * exposes, through the same parser the I2P window uses.
 *
 * Every phase change is logged as it happens. Without that, a run that ends on the budget
 * cannot distinguish "i2pd never started" from "i2pd was still building tunnels at minute
 * five", and those are different defects with different owners.
 *
 * **What this does not prove:** that a user ever sees `I2pNetworkPhase.CONNECTED` on this
 * path. Its only writer is `I2pdProcessManager.awaitReady`, which the app calls from the
 * profile connect path and not from the guard start path — so with I2P raising the guard on
 * its own the pill stops at BUILDING_TUNNELS. The test calls `awaitReady` itself, and the
 * phase seen before it does so is logged rather than asserted, so this file records the gap
 * instead of papering over it.
 *
 * The eepsite leg is opt-in for a product reason, not a convenience one: the app configures
 * i2pd with every remote addressbook subscription suppressed (`buildI2pdConfLines`), so only
 * `*.b32.i2p` resolves out of the box and there is no name this test could bake in. Pass
 * `foxhole.i2pTarget=http://<base32>.b32.i2p/...` to include it.
 *
 * Manual gate: `-Pandroid.testInstrumentationRunnerArguments.foxhole.liveI2p=1`.
 */
@RunWith(AndroidJUnit4::class)
internal class LiveI2pRuntimeTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun manualI2pRouterJoinsTheNetworkAndStopsCleanly() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveI2p") != "1") {
            Log.d(TEST_TAG, "live i2p runtime skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            shell("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            if (!ensureVpnPermission(app)) {
                assertTrue("vpn permission missing for the live I2P gate", false)
                return@runBlocking
            }
            val settings = app.container.settingsRepository
            val previous = settings.current()
            try {
                resetRelevantSettings(app)
                baselineRuntimeSettings(app)

                // I2P with no profile is expected to raise the transparent firewall guard by
                // itself (LocalGuardRuntime.localGuardModeOrNull). Asserting that first means a
                // later failure cannot be blamed on the test having started the wrong runtime.
                settings.updateI2pEnabled(true)
                settings.updateI2pEngaged(true)
                assertEquals(
                    "enabling I2P alone did not select the transparent guard runtime",
                    LocalGuardMode.FIREWALL,
                    settings.current().localGuardModeOrNull(),
                )
                app.container.connectionController.syncLocalGuard()
                assertTrue(
                    "the I2P carrier guard never reached CONNECTED",
                    waitUntil(timeoutMs = GUARD_START_TIMEOUT_MS) {
                        val snapshot = app.container.connectionController.snapshot.value
                        snapshot.state == ConnectionState.CONNECTED &&
                            snapshot.profileId == LOCAL_GUARD_PROFILE_ID
                    },
                )

                val budgetMs = longArgument("foxhole.i2pBudgetMs", I2P_NETWORK_BUDGET_MS)
                val startedAt = System.currentTimeMillis()
                var lastPhase: I2pNetworkPhase? = null
                val networkUp =
                    waitUntil(timeoutMs = budgetMs) {
                        val snapshot = app.container.connectionController.i2pPhase.value
                        if (snapshot.phase != lastPhase) {
                            lastPhase = snapshot.phase
                            Log.d(
                                TEST_TAG,
                                "liveI2p phase=${snapshot.phase} tunnelsBuilt=${snapshot.tunnelsBuilt} " +
                                    "elapsedMs=${System.currentTimeMillis() - startedAt}",
                            )
                        }
                        snapshot.phase.networkUp
                    }
                assertTrue(
                    "the I2P router never started building tunnels within ${budgetMs}ms " +
                        "(last phase=$lastPhase, i2pd state=${app.container.i2pdManager.snapshot().state})",
                    networkUp,
                )

                // The authenticated SOCKS negotiation proves the client proxy is usable at all.
                val phaseBeforeProbe = app.container.connectionController.i2pPhase.value.phase
                assertTrue(
                    "the i2pd SOCKS proxy never accepted its per-start credentials",
                    app.container.i2pdManager.awaitReady(SOCKS_READY_BUDGET_MS),
                )
                assertEquals(
                    "the i2pd child is not running after a successful readiness probe",
                    I2pdState.RUNNING,
                    app.container.i2pdManager.snapshot().state,
                )
                // `awaitReady` is the only writer of I2pNetworkPhase.CONNECTED, and on THIS path
                // — I2P raising the transparent guard with no profile — nothing in the app calls
                // it: startI2pdReadinessProbe lives on the profile connect path only. So the
                // transition asserted here proves the publisher works, not that a user ever sees
                // it; the phase production actually leaves the pill on is the one logged above.
                Log.d(TEST_TAG, "liveI2p phaseBeforeReadinessProbe=$phaseBeforeProbe")
                assertEquals(
                    "the SOCKS readiness probe did not publish CONNECTED",
                    I2pNetworkPhase.CONNECTED,
                    app.container.connectionController.i2pPhase.value.phase,
                )

                // The facts. A process that started, printed tunnel lines and reached nobody
                // fails here and nowhere else.
                val status = awaitRouterStatus(ROUTER_STATUS_BUDGET_MS)
                Log.d(
                    TEST_TAG,
                    "liveI2p router status=${status.networkStatus} routers=${status.knownRouters} " +
                        "clientTunnels=${status.clientTunnels} version=${status.version} " +
                        "rx=${status.rxTotalBytes} tx=${status.txTotalBytes}",
                )
                assertTrue(
                    "the I2P netdb stayed empty: the router never reseeded (routers=${status.knownRouters})",
                    (status.knownRouters ?: 0) > 0,
                )
                assertTrue(
                    "the router built no client tunnels of its own (clientTunnels=${status.clientTunnels})",
                    (status.clientTunnels ?: 0) > 0,
                )
                assertTrue(
                    "no I2P traffic crossed the wire in either direction (rx=${status.rxTotalBytes} tx=${status.txTotalBytes})",
                    (status.rxTotalBytes ?: 0L) > 0L && (status.txTotalBytes ?: 0L) > 0L,
                )

                fetchEepsiteIfConfigured()

                // Teardown asserted in the body, not in `finally`: a stop that leaves the child
                // alive is the defect that once kept libi2pd.so running for the best part of an
                // hour and disabled I2P and TOR until the process died.
                settings.updateI2pEnabled(false)
                app.container.connectionController.syncLocalGuard()
                assertTrue(
                    "the I2P carrier guard did not stop after I2P was disabled",
                    waitUntil(timeoutMs = RUNTIME_STOP_TIMEOUT_MS) {
                        app.container.connectionController.snapshot.value.state == ConnectionState.IDLE
                    },
                )
                assertTrue(
                    "the i2pd child did not reach IDLE after teardown: ${app.container.i2pdManager.snapshot().state}",
                    waitUntil(timeoutMs = RUNTIME_STOP_TIMEOUT_MS) {
                        app.container.i2pdManager.snapshot().state == I2pdState.IDLE
                    },
                )
                assertEquals(
                    "the I2P phase did not fall back to OFFLINE after teardown",
                    I2pNetworkPhase.OFFLINE,
                    app.container.connectionController.i2pPhase.value.phase,
                )
                assertTrue(
                    "the i2pd SOCKS endpoint stayed published after teardown",
                    I2pdSocksProxy.endpoint == null,
                )
                assertTrue(
                    "the i2pd webconsole endpoint stayed published after teardown",
                    I2pdWebConsole.endpoint == null,
                )
            } finally {
                settings.updateI2pEnabled(false)
                app.container.i2pdManager.kill("live_i2p_test_cleanup")
                FoxholeConnectionServiceContract.startForegroundService(
                    context = app,
                    mode = TrafficMode.TUNNEL,
                    action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
                    suppressLocalGuard = true,
                )
                waitUntil(timeoutMs = RUNTIME_STOP_TIMEOUT_MS) {
                    app.container.connectionController.snapshot.value.state == ConnectionState.IDLE
                }
                delay(2_000)
                restoreSettings(settings, previous)
            }
        }
    }

    /**
     * Polls the loopback webconsole until the router reports peers and its own tunnels.
     *
     * `awaitReady` only proves the SOCKS listener answers, which it does within seconds of the
     * child starting; joining the network takes minutes, so the counters need their own wait
     * rather than a single read after the phase moved.
     */
    private suspend fun awaitRouterStatus(budgetMs: Long): I2pRouterStatus {
        var latest = I2pRouterStatus()
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            val endpoint = I2pdWebConsole.endpoint
            val html =
                if (endpoint == null) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            overlayHttpGet(
                                url = "http://127.0.0.1:${endpoint.port}/",
                                timeoutMs = WEB_CONSOLE_TIMEOUT_MS,
                                basicAuth = I2PD_WEB_CONSOLE_USER to endpoint.password,
                            ).body.toString(Charsets.UTF_8)
                        }.getOrNull()
                    }
                }
            if (html != null) {
                latest = parseI2pdWebConsoleStatus(html)
                val joined =
                    (latest.knownRouters ?: 0) > 0 &&
                        (latest.clientTunnels ?: 0) > 0 &&
                        (latest.rxTotalBytes ?: 0L) > 0L &&
                        (latest.txTotalBytes ?: 0L) > 0L
                if (joined) {
                    return latest
                }
                Log.d(
                    TEST_TAG,
                    "liveI2p console routers=${latest.knownRouters} clientTunnels=${latest.clientTunnels} " +
                        "rx=${latest.rxTotalBytes} tx=${latest.txTotalBytes}",
                )
            }
            delay(ROUTER_STATUS_POLL_MS)
        }
        return latest
    }

    /**
     * The optional end-to-end leg: a real HTTP response out of the I2P network.
     *
     * An eepsite is reachable by no other path, so a 200 with a body through the router's own
     * SOCKS proxy is proof of carriage that no clearnet fallback could fake.
     */
    private suspend fun fetchEepsiteIfConfigured() {
        val target =
            InstrumentationRegistry.getArguments()
                .getString("foxhole.i2pTarget")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        if (target == null) {
            Log.d(
                TEST_TAG,
                "liveI2p eepsite leg skipped: pass foxhole.i2pTarget=http://<base32>.b32.i2p/ to include it " +
                    "(the app ships i2pd with addressbook subscriptions disabled, so plain .i2p names do not resolve)",
            )
            return
        }
        val endpoint = I2pdSocksProxy.endpoint ?: error("i2pd published no SOCKS endpoint")
        val response =
            withContext(Dispatchers.IO) {
                i2pSocksHttpGet(endpoint = endpoint, url = target, timeoutMs = EEPSITE_TIMEOUT_MS)
            }
        Log.d(TEST_TAG, "liveI2p eepsite status=${response.statusCode} bytes=${response.body.size}")
        assertEquals("the eepsite did not answer 200 through the I2P SOCKS proxy", HTTP_OK, response.statusCode)
        assertTrue("the eepsite answered with an empty body", response.body.isNotEmpty())
    }

    private companion object {
        const val GUARD_START_TIMEOUT_MS = 40_000L

        /** I2P tunnel construction is minutes, not seconds, on a phone. Generous on purpose. */
        const val I2P_NETWORK_BUDGET_MS = 300_000L
        const val SOCKS_READY_BUDGET_MS = 120_000L

        /** Peers and tunnels usually land within a minute of the phase moving; three is slack. */
        const val ROUTER_STATUS_BUDGET_MS = 180_000L
        const val ROUTER_STATUS_POLL_MS = 5_000L
        const val RUNTIME_STOP_TIMEOUT_MS = 30_000L
        const val WEB_CONSOLE_TIMEOUT_MS = 5_000
        const val EEPSITE_TIMEOUT_MS = 180_000
    }
}
