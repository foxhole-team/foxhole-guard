package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.VpnSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeStoppedSnapshotTest {
    @Test
    fun `stopped ordinary runtime snapshot keeps route identity for stale ip filtering`() {
        val session =
            VpnSession(
                profileId = 42L,
                profileName = "Work",
                protocolHint = ProtocolHint.VLESS,
                protocolOptionId = "node-a",
                configJson = "{}",
                correlationId = "session-1",
            )

        val snapshot =
            stoppedRuntimeSnapshot(
                session = session,
                state = ConnectionState.IDLE,
                trafficMode = TrafficMode.TUNNEL,
            )

        assertEquals(ConnectionState.IDLE, snapshot.state)
        assertEquals(TrafficMode.TUNNEL, snapshot.trafficMode)
        assertEquals(42L, snapshot.profileId)
        assertEquals("Work", snapshot.profileName)
        assertEquals(ProtocolHint.VLESS, snapshot.protocolHint)
        assertEquals("node-a", snapshot.protocolOptionId)
    }

    @Test
    fun `stopped pseudo runtimes do not keep dashboard profile identity`() {
        val localGuard =
            stoppedRuntimeSnapshot(
                session =
                    VpnSession(
                        profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
                        profileName = "Firewall",
                        protocolHint = ProtocolHint.SING_BOX,
                        configJson = "{}",
                        correlationId = "local",
                    ),
                state = ConnectionState.IDLE,
                trafficMode = TrafficMode.TUNNEL,
            )
        val torOnly =
            stoppedRuntimeSnapshot(
                session =
                    VpnSession(
                        profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                        profileName = "TOR",
                        protocolHint = ProtocolHint.SING_BOX,
                        configJson = "{}",
                        correlationId = "tor",
                    ),
                state = ConnectionState.IDLE,
                trafficMode = TrafficMode.TUNNEL,
            )

        assertNull(localGuard.profileId)
        assertNull(localGuard.profileName)
        assertNull(torOnly.profileId)
        assertNull(torOnly.profileName)
    }

    @Test
    fun `stopped snapshot from previous bridge state preserves ordinary route identity`() {
        val snapshot =
            stoppedRuntimeSnapshot(
                previous =
                    ConnectionSnapshot(
                        state = ConnectionState.CONNECTED,
                        trafficMode = TrafficMode.PROXY,
                        profileId = 7L,
                        profileName = "Proxy",
                        protocolHint = ProtocolHint.TROJAN,
                        protocolOptionId = "tcp",
                    ),
                trafficMode = TrafficMode.PROXY,
            )

        assertEquals(ConnectionState.IDLE, snapshot.state)
        assertEquals(TrafficMode.PROXY, snapshot.trafficMode)
        assertEquals(7L, snapshot.profileId)
        assertEquals("Proxy", snapshot.profileName)
        assertEquals(ProtocolHint.TROJAN, snapshot.protocolHint)
        assertEquals("tcp", snapshot.protocolOptionId)
    }
}
