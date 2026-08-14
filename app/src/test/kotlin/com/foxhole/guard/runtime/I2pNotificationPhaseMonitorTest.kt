package com.foxhole.guard.runtime

import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.I2pPhaseSnapshot
import com.foxhole.core.model.LanProxyPhase
import com.foxhole.core.model.LanProxyStatusSnapshot
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TorPhaseSnapshot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class I2pNotificationPhaseMonitorTest {
    @Test
    fun `notification refresh follows confirmed Tor phase edges without an eager initial build`() =
        runBlocking {
            val refreshedPhases = mutableListOf<TorNetworkPhase>()

            collectTorNotificationPhaseChanges(
                snapshots = flowOf(
                    TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTING),
                    TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTING),
                    TorPhaseSnapshot(phase = TorNetworkPhase.BUILDING_CIRCUITS),
                    TorPhaseSnapshot(phase = TorNetworkPhase.CONNECTED),
                ),
                onPhaseChanged = refreshedPhases::add,
            )

            assertEquals(
                listOf(TorNetworkPhase.BUILDING_CIRCUITS, TorNetworkPhase.CONNECTED),
                refreshedPhases,
            )
        }

    @Test
    fun `notification refresh follows confirmed I2P phase edges without an eager initial build`() =
        runBlocking {
            val refreshedPhases = mutableListOf<I2pNetworkPhase>()

            collectI2pNotificationPhaseChanges(
                snapshots = flowOf(
                    I2pPhaseSnapshot(phase = I2pNetworkPhase.STARTING),
                    I2pPhaseSnapshot(phase = I2pNetworkPhase.STARTING),
                    I2pPhaseSnapshot(phase = I2pNetworkPhase.BUILDING_TUNNELS),
                    I2pPhaseSnapshot(phase = I2pNetworkPhase.CONNECTED),
                    I2pPhaseSnapshot(phase = I2pNetworkPhase.CONNECTED),
                ),
                onPhaseChanged = refreshedPhases::add,
            )

            assertEquals(
                listOf(I2pNetworkPhase.BUILDING_TUNNELS, I2pNetworkPhase.CONNECTED),
                refreshedPhases,
            )
        }

    @Test
    fun `VPN service starts the I2P notification phase monitor after the early foreground claim`() {
        val service = source("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnService.kt")
        val onCreate =
            service
                .substringAfter("override fun onCreate()")
                .substringBefore("override fun onStartCommand")

        val claim = onCreate.indexOf("claimForegroundSlotEarly(notificationManager)")
        val torMonitor = onCreate.indexOf("startTorNotificationPhaseMonitoring()")
        val monitor = onCreate.indexOf("startI2pNotificationPhaseMonitoring()")
        assertTrue("the fast foreground claim must remain first", claim >= 0)
        assertTrue("the Tor phase monitor must be attached after the foreground claim", torMonitor > claim)
        assertTrue("the phase monitor must be attached after the foreground claim", monitor > claim)
    }

    @Test
    fun `notification drops stale LAN proxy line when the core listener turns off`() =
        runBlocking {
            val refreshedPhases = mutableListOf<LanProxyPhase>()

            collectLanProxyNotificationPhaseChanges(
                snapshots = flowOf(
                    LanProxyStatusSnapshot(phase = LanProxyPhase.READY),
                    LanProxyStatusSnapshot(phase = LanProxyPhase.READY),
                    LanProxyStatusSnapshot(phase = LanProxyPhase.OFF),
                ),
                onPhaseChanged = refreshedPhases::add,
            )

            assertEquals(listOf(LanProxyPhase.OFF), refreshedPhases)
        }

    @Test
    fun `VPN service publishes and observes the confirmed LAN proxy phase`() {
        val service = source("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnService.kt")
        val support = source("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceLanProxySupport.kt")

        assertTrue(service.contains("startLanProxyNotificationPhaseMonitoring()"))
        assertTrue(support.contains("FoxholeVpnRuntimeBridge.updateLanProxyStatus(status)"))
    }

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"), File("../app/$relative"))
            .first(File::isFile)
            .readText()
}
