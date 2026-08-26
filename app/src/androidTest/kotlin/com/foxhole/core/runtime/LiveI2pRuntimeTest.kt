package com.foxhole.core.runtime

import android.os.SystemClock
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
import com.foxhole.guard.runtime.FoxholeConnectionServiceContract
import com.foxhole.guard.runtime.I2PD_COLD_READY_TOTAL_BUDGET_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

@RunWith(AndroidJUnit4::class)
internal class LiveI2pRuntimeTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun manualI2pRouterJoinsTheNetworkAndStopsCleanly() {
        assumeTrue(
            "live i2p runtime skipped: pass -e foxhole.liveI2p 1 to run it",
            InstrumentationRegistry.getArguments().getString("foxhole.liveI2p") == "1",
        )
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

                val i2pStartedAt = SystemClock.elapsedRealtime()
                val i2pDeadline = i2pStartedAt + I2PD_COLD_READY_TOTAL_BUDGET_MS
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

                val requestedBuildingBudgetMs =
                    longArgument("foxhole.i2pBudgetMs", I2P_NETWORK_BUDGET_MS)
                        .coerceIn(0L, I2P_NETWORK_BUDGET_MS)
                val buildingBudgetMs = minOf(requestedBuildingBudgetMs, remainingI2pBudgetMs(i2pDeadline))
                var lastPhase: I2pNetworkPhase? = null
                var nextReadinessDiagnosticAt = 0L
                val tunnelsBuilding =
                    waitUntil(timeoutMs = buildingBudgetMs) {
                        val snapshot = app.container.connectionController.i2pPhase.value
                        if (snapshot.phase != lastPhase) {
                            lastPhase = snapshot.phase
                            Log.d(
                                TEST_TAG,
                                "liveI2p phase=${snapshot.phase} tunnelsBuilt=${snapshot.tunnelsBuilt} " +
                                    "elapsedMs=${SystemClock.elapsedRealtime() - i2pStartedAt}",
                            )
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now >= nextReadinessDiagnosticAt) {
                            logReadinessDiagnostic(app = app, startedAt = i2pStartedAt, now = now)
                            nextReadinessDiagnosticAt = now + READINESS_DIAGNOSTIC_INTERVAL_MS
                        }
                        snapshot.phase == I2pNetworkPhase.BUILDING_TUNNELS || snapshot.phase.networkUp
                    }
                assertTrue(
                    "the I2P router never started building tunnels within ${buildingBudgetMs}ms " +
                        "(last phase=$lastPhase, i2pd state=${app.container.i2pdManager.snapshot().state})",
                    tunnelsBuilding,
                )
                val carrierConnected =
                    waitForCarrierConnectedWithDiagnostics(
                        app = app,
                        deadline = i2pDeadline,
                        startedAt = i2pStartedAt,
                    )
                assertTrue(
                    "the service-owned I2P carrier never reached CONNECTED within the shared " +
                        "${I2PD_COLD_READY_TOTAL_BUDGET_MS}ms cold-start budget " +
                        "(last phase=${app.container.connectionController.i2pPhase.value.phase}, " +
                        "i2pd state=${app.container.i2pdManager.snapshot().state})",
                    carrierConnected,
                )

                assertEquals(
                    "the i2pd child is not running after the carrier reached CONNECTED",
                    I2pdState.RUNNING,
                    app.container.i2pdManager.snapshot().state,
                )
                assertEquals(
                    "the authenticated SOCKS and service-owned carrier proof did not stay CONNECTED",
                    I2pNetworkPhase.CONNECTED,
                    app.container.connectionController.i2pPhase.value.phase,
                )

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

    private suspend fun awaitRouterStatus(budgetMs: Long): I2pRouterStatus {
        var latest = I2pRouterStatus()
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            val status = readI2pdRouterStatus(timeoutMs = ROUTER_STATUS_TIMEOUT_MS)
            if (status != null) {
                latest = status
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

    private suspend fun waitForCarrierConnectedWithDiagnostics(
        app: FoxholeApplication,
        deadline: Long,
        startedAt: Long,
    ): Boolean {
        var nextDiagnosticAt = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (app.container.connectionController.i2pPhase.value.phase.networkUp) {
                return true
            }
            val now = SystemClock.elapsedRealtime()
            if (now >= nextDiagnosticAt) {
                logReadinessDiagnostic(app = app, startedAt = startedAt, now = now)
                nextDiagnosticAt = now + READINESS_DIAGNOSTIC_INTERVAL_MS
            }
            delay(READINESS_POLL_MS)
        }
        return app.container.connectionController.i2pPhase.value.phase.networkUp
    }

    private suspend fun logReadinessDiagnostic(
        app: FoxholeApplication,
        startedAt: Long,
        now: Long,
    ) {
        val diagnostic = collectReadinessDiagnostic()
        val phase = app.container.connectionController.i2pPhase.value.phase
        Log.d(
            TEST_TAG,
            "liveI2p readiness elapsedMs=${now - startedAt} " +
                "phase=$phase manager=${app.container.i2pdManager.snapshot().state} " +
                "socksPublished=${diagnostic.socksPublished} socksAuth=${diagnostic.socksAuth} " +
                "consolePublished=${diagnostic.consolePublished} " +
                "statusAvailable=${diagnostic.statusAvailable} " +
                "routers=${diagnostic.knownRouters} clientTunnels=${diagnostic.clientTunnels} " +
                "network=${diagnostic.networkStatus}",
        )
    }

    private suspend fun collectReadinessDiagnostic(): I2pReadinessDiagnostic =
        withContext(Dispatchers.IO) {
            val socksEndpoint = I2pdSocksProxy.endpoint
            val socksAuth = socksEndpoint?.let(::probeSocksAuthentication)
            val console = I2pdWebConsole.endpoint
            val status = readI2pdRouterStatus(console, READINESS_DIAGNOSTIC_TIMEOUT_MS)
            I2pReadinessDiagnostic(
                socksPublished = socksEndpoint != null,
                socksAuth = socksAuth,
                consolePublished = console != null,
                statusAvailable = status != null,
                knownRouters = status?.knownRouters,
                clientTunnels = status?.clientTunnels,
                networkStatus = status?.networkStatus.toSafeNetworkStatus(),
            )
        }

    private fun probeSocksAuthentication(endpoint: I2pdSocksProxyEndpoint): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.soTimeout = READINESS_DIAGNOSTIC_TIMEOUT_MS
                socket.connect(
                    InetSocketAddress("127.0.0.1", endpoint.port),
                    READINESS_DIAGNOSTIC_TIMEOUT_MS,
                )
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                output.write(byteArrayOf(0x05, 0x01, 0x02))
                output.flush()
                check(input.read() == 0x05 && input.read() == 0x02)
                val username = endpoint.username.toByteArray(Charsets.UTF_8)
                val password = endpoint.password.toByteArray(Charsets.UTF_8)
                output.write(0x01)
                output.write(username.size)
                output.write(username)
                output.write(password.size)
                output.write(password)
                output.flush()
                input.read() == 0x01 && input.read() == 0x00
            }
        }.getOrDefault(false)

    private fun String?.toSafeNetworkStatus(): String =
        when (this?.trim()?.lowercase()) {
            "ok" -> "OK"
            "firewalled" -> "FIREWALLED"
            "testing" -> "TESTING"
            null -> "UNKNOWN"
            else -> "OTHER"
        }

    private fun remainingI2pBudgetMs(deadline: Long): Long =
        (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    private companion object {
        const val GUARD_START_TIMEOUT_MS = 40_000L

        const val I2P_NETWORK_BUDGET_MS = 300_000L

        const val ROUTER_STATUS_BUDGET_MS = 180_000L
        const val ROUTER_STATUS_POLL_MS = 5_000L
        const val RUNTIME_STOP_TIMEOUT_MS = 30_000L
        const val ROUTER_STATUS_TIMEOUT_MS = 5_000
        const val EEPSITE_TIMEOUT_MS = 180_000
        const val READINESS_DIAGNOSTIC_INTERVAL_MS = 30_000L
        const val READINESS_DIAGNOSTIC_TIMEOUT_MS = 2_000
        const val READINESS_POLL_MS = 250L
    }
}

private data class I2pReadinessDiagnostic(
    val socksPublished: Boolean,
    val socksAuth: Boolean?,
    val consolePublished: Boolean,
    val statusAvailable: Boolean,
    val knownRouters: Int?,
    val clientTunnels: Int?,
    val networkStatus: String,
)
