package com.foxhole.guard

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.tunnelSelectedPackages
import com.foxhole.guard.core.settings.updatePerAppRoutingMode
import com.foxhole.guard.core.settings.updateSelectedPackages
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A per-app split tunnel raised on hardware, watched from inside it.
 *
 * The instrumentation process is itself an application the policy applies to,
 * which is what makes this testable without a second app.
 *
 * **It asks the kernel, not a web service.** The obvious version of this test —
 * fetch the exit address through the app's own IP panel — measures nothing:
 * `TunnelValidationGateway.refreshIpInfo` binds its socket to the VPN `Network`
 * whenever the tunnel is up, which forces the request into the tunnel *past*
 * the per-app split. It reports the proxy's address for an excluded app too,
 * and the first version of this test duly "found" a split failure that was not
 * there.
 *
 * So the measurement is the source address the kernel picks for an unbound
 * socket: connect a `DatagramSocket` to a public address and read
 * `localAddress`. Nothing is sent, no network is needed, and the answer is
 * exactly what an ordinary socket in this process would get.
 *
 * **What this can and cannot see.** Ф-ГА2 moved the split off
 * `VpnService.Builder`: the tun is full-device and membership lives in
 * `package_name` route rules inside the engine. So the source address separates
 * "tunnel up" from "tunnel down" — it can no longer separate "in the split" from
 * "outside it", and this process is inside every non-empty include set by
 * design. What the split legs prove here is that an include split still REACHES
 * CONNECTED (it did not: the assembled config was rejected before the tun was
 * built) and that this app keeps riding the tunnel it validates through. The
 * routing of somebody else's package is a unit-test question about the
 * assembled config, not something one process can observe about another.
 *
 * Manual: needs a real subscription and a working network. Skipped unless
 * `-Pandroid.testInstrumentationRunnerArguments.foxhole.liveSplit=1`.
 */
@RunWith(AndroidJUnit4::class)
internal class LiveApplicationSplitAndroidTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun manualIncludeSplitConnectsAndKeepsThisPackageInsideTheTunnel() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveSplit") != "1") {
            Log.d(TEST_TAG, "live split skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val subscription = smartSubscriptionInput()
            if (subscription == null) {
                assertTrue("subscription input missing for the live split gate", false)
                return@runBlocking
            }
            if (!ensureVpnPermission(app)) {
                assertTrue("vpn permission missing for the live split gate", false)
                return@runBlocking
            }

            val settings = app.container.settingsRepository
            val previous = settings.current()
            val self = app.packageName
            try {
                resetRelevantSettings(app)
                clearProfiles(app)
                baselineRuntimeSettings(app)
                val profile =
                    app.container.profileRepository.importProfile(
                        rawInput = subscription,
                        preferredName = "Live Split",
                    )
                app.container.connectionController.setActiveProfile(profile.id)
                val target =
                    probeTargetFor(app, profile.id, setOf(ProtocolHint.VLESS, ProtocolHint.TROJAN))
                assertTrue("the subscription exposed no connectable option", target != null)
                val optionId = target?.optionId

                // The address with no split at all, to name the tunnel's exit.
                app.container.connectionController.connect(profile.id, protocolOptionId = optionId)
                assertTrue(
                    "the full-tunnel leg did not connect",
                    waitForTerminalState(app).name == "CONNECTED",
                )
                val fullTunnelSource = unboundSourceAddress()
                Log.d(TEST_TAG, "liveSplit fullTunnelSource=$fullTunnelSource")
                assertTrue("no source address on the full tunnel", fullTunnelSource.isNotBlank())
                disconnectAndWaitForIdle(app)
                val offSource = unboundSourceAddress()
                Log.d(TEST_TAG, "liveSplit offSource=$offSource")
                assertNotEquals(
                    "the source address did not change when the tunnel came up: " +
                        "this device cannot tell inside from outside, so the rest proves nothing",
                    offSource,
                    fullTunnelSource,
                )

                // The split: include exactly one OTHER package.
                //
                // Not `listOf(self)` — that is a no-op the product guarantees.
                // `updateSelectedPackages` drops this package from the selection
                // (the picker never offers it either), the empty selection sends
                // the mode back to FULL_TUNNEL, and the leg silently measured a
                // full tunnel while claiming to measure an include split. It
                // "passed" for that reason, which is worse than failing.
                val other = firstInstalledPackageExcept(self)
                settings.updatePerAppRoutingMode(PerAppRoutingMode.INCLUDE_SELECTED_APPS)
                settings.updateSelectedPackages(listOf(other))
                // The settings path is half the split, so it is asserted rather
                // than assumed: an expert write silently disarmed by safe mode
                // (or by a mode that falls back to FULL_TUNNEL on an empty
                // selection) would otherwise reach the runtime as "no split" and
                // the leg below would still connect and prove nothing.
                val splitSettings = settings.current()
                assertEquals(
                    "the include mode did not survive the settings write",
                    PerAppRoutingMode.INCLUDE_SELECTED_APPS,
                    splitSettings.expert.perAppRoutingMode,
                )
                assertTrue(
                    "the include selection did not survive the settings write",
                    splitSettings.expert.tunnelSelectedPackages().contains(other),
                )
                app.container.connectionController.connect(profile.id, protocolOptionId = optionId)
                assertTrue(
                    "the leg that routes only another package did not connect",
                    waitForTerminalState(app).name == "CONNECTED",
                )
                // Reaching CONNECTED is itself the routing evidence for this
                // process: validation probes THROUGH the tunnel, and it only
                // confirms because the product keeps its own package inside every
                // non-empty include set (RuntimeTunInbound.buildSplitPlan). So the
                // source address here must still be the tunnel's.
                val includedSource = unboundSourceAddress()
                Log.d(TEST_TAG, "liveSplit includedSource=$includedSource other=$other")
                assertEquals(
                    "this app left the tunnel it was validating through",
                    fullTunnelSource,
                    includedSource,
                )

                // There is deliberately NO "and the other app's traffic went
                // direct" leg. Ф-ГА2 moved the split off the VpnService builder:
                // the tun is always full-device and the split is package_name
                // route rules inside the engine, so an unbound socket in ANY
                // process gets the tun address whether or not its package is in
                // the split — this measurement cannot see the difference any
                // more. And this process can never be the outside app, because
                // the product pins its own package into the include set. Proving
                // the excluded side needs a second app; the rule reaching the
                // engine is covered by RuntimeConfigAssemblerLanesTest and the
                // translator contract in FoxCoreAssemblerMigrationTest.

                disconnectAndWaitForIdle(app)
                assertEquals(
                    "the split legs did not stop cleanly",
                    "IDLE",
                    app.container.connectionController.snapshot.value.state.name,
                )
            } finally {
                restoreSettings(settings, previous)
            }
        }
    }

    /**
     * The source address the kernel would use for an unbound socket.
     *
     * `connect` on a datagram socket sends nothing; it only fixes the peer so
     * the routing table can be consulted. That makes this a pure question about
     * routing, answerable with no network and no external service.
     */
    private fun unboundSourceAddress(): String =
        DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName(ROUTE_PROBE_PEER), ROUTE_PROBE_PORT)
            socket.localAddress.hostAddress.orEmpty()
        }

    private companion object {
        /** A routable public address. Nothing is sent to it. */
        const val ROUTE_PROBE_PEER = "1.1.1.1"
        const val ROUTE_PROBE_PORT = 443
    }
}
