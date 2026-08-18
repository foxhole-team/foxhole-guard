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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures the kernel's source address for an unbound socket: refreshIpInfo binds to the VPN Network, so the app's own IP panel reports the tunnel exit even for an excluded app.
 * Manual gate: -Pandroid.testInstrumentationRunnerArguments.foxhole.liveSplit=1.
 */
@RunWith(AndroidJUnit4::class)
internal class LiveApplicationSplitAndroidTest : ProfileRuntimeSessionAndroidTestSupport() {
    @Test
    fun manualIncludeSplitConnectsAndKeepsThisPackageInsideTheTunnel() {
        assumeTrue(
            "live split skipped: pass -e foxhole.liveSplit 1 to run it",
            InstrumentationRegistry.getArguments().getString("foxhole.liveSplit") == "1",
        )
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

                val other = firstInstalledPackageExcept(self)
                settings.updatePerAppRoutingMode(PerAppRoutingMode.INCLUDE_SELECTED_APPS)
                settings.updateSelectedPackages(listOf(other))
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
                val includedSource = unboundSourceAddress()
                Log.d(TEST_TAG, "liveSplit includedSource=$includedSource other=$other")
                assertEquals(
                    "this app left the tunnel it was validating through",
                    fullTunnelSource,
                    includedSource,
                )

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

    private fun unboundSourceAddress(): String =
        DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName(ROUTE_PROBE_PEER), ROUTE_PROBE_PORT)
            socket.localAddress.hostAddress.orEmpty()
        }

    private companion object {
        const val ROUTE_PROBE_PEER = "1.1.1.1"
        const val ROUTE_PROBE_PORT = 443
    }
}
