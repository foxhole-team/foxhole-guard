package com.foxhole.beta.vpn

import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.NotificationSnapshot
import com.foxhole.beta.core.model.TrafficSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionNotificationSupportTest {
    @Test
    fun `active states keep stop action in the controller notification`() {
        listOf(
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.RECONNECTING,
        ).forEach { state ->
            val action = notificationActionForState(state)

            assertEquals(FoxholeConnectionServiceContract.ACTION_DISCONNECT, action.serviceAction)
            assertEquals(R.string.disconnect, action.labelRes)
            assertTrue(action.ongoing)
        }
    }

    @Test
    fun `idle states switch the controller notification back to start`() {
        listOf(ConnectionState.IDLE, ConnectionState.ERROR).forEach { state ->
            val action = notificationActionForState(state)

            assertEquals(FoxholeConnectionServiceContract.ACTION_RESTORE, action.serviceAction)
            assertEquals(R.string.connect, action.labelRes)
            assertFalse(action.ongoing)
        }
    }

    @Test
    fun `smart start analysis keeps stop command with auto connect label`() {
        val snapshot =
            NotificationSnapshot(
                state = ConnectionState.CONNECTED,
                statusMessage = "Analysis",
            )
        val action =
            notificationActionForSnapshot(
                snapshot = snapshot,
                analysisStatus = "Analysis",
            )

        assertEquals(FoxholeConnectionServiceContract.ACTION_DISCONNECT, action.serviceAction)
        assertEquals(R.string.notification_action_auto_connect, action.labelRes)
        assertTrue(action.ongoing)
    }

    @Test
    fun `clearing transient state can preserve the last ip info`() {
        val previousIpInfo = FoxholeVpnRuntimeBridge.ipInfo.value
        val previousTraffic = FoxholeVpnRuntimeBridge.traffic.value
        val previousHighFrequency = FoxholeVpnRuntimeBridge.highFrequencyTrafficUpdates.value
        val retainedIp =
            IpInfo(
                ip = "198.51.100.10",
                ipv4 = "198.51.100.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Foxhole Test ISP",
                fetchedAt = 1234L,
            )

        try {
            FoxholeVpnRuntimeBridge.updateIpInfo(retainedIp)
            FoxholeVpnRuntimeBridge.updateTraffic(
                TrafficSnapshot(
                    available = true,
                    rxTotalBytes = 1024,
                    txTotalBytes = 2048,
                ),
            )
            FoxholeVpnRuntimeBridge.setHighFrequencyTrafficUpdates(true)

            FoxholeVpnRuntimeBridge.clearTransientState(clearIpInfo = false)

            assertEquals(retainedIp, FoxholeVpnRuntimeBridge.ipInfo.value)
            assertEquals(TrafficSnapshot(), FoxholeVpnRuntimeBridge.traffic.value)
            assertFalse(FoxholeVpnRuntimeBridge.highFrequencyTrafficUpdates.value)
        } finally {
            FoxholeVpnRuntimeBridge.updateIpInfo(previousIpInfo)
            FoxholeVpnRuntimeBridge.updateTraffic(previousTraffic)
            FoxholeVpnRuntimeBridge.setHighFrequencyTrafficUpdates(previousHighFrequency)
        }
    }

    @Test
    fun `runtime ip update keeps known location when same address refresh is partial`() {
        val previousIpInfo = FoxholeVpnRuntimeBridge.ipInfo.value
        val rich =
            IpInfo(
                ip = "198.51.100.10",
                ipv4 = "198.51.100.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Foxhole Test ISP",
                fetchedAt = 1234L,
            )
        val partial =
            rich.copy(
                countryCode = null,
                countryName = null,
                city = null,
                isp = null,
                fetchedAt = 2234L,
            )

        try {
            FoxholeVpnRuntimeBridge.updateIpInfo(rich)
            FoxholeVpnRuntimeBridge.updateIpInfo(partial)

            assertEquals(
                rich.copy(fetchedAt = partial.fetchedAt),
                FoxholeVpnRuntimeBridge.ipInfo.value,
            )
        } finally {
            FoxholeVpnRuntimeBridge.updateIpInfo(previousIpInfo)
        }
    }
}
