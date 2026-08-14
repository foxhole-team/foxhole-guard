package com.foxhole.core.runtime

import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import com.foxhole.guard.runtime.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeStartTimeoutPolicyTest {
    @Test
    fun `ordinary vpn sessions use the standard runtime start timeout`() {
        val session =
            session(
                profileId = 1L,
                configJson = """{"outbounds":[{"type":"vless","tag":"proxy"}]}""",
            )

        assertEquals(RUNTIME_START_TIMEOUT_MS, runtimeStartTimeoutMsForSession(session))
    }

    @Test
    fun `tor over vpn sessions use the extended tor runtime start timeout`() {
        val session =
            session(
                profileId = 1L,
                configJson = """{"outbounds":[{"type":"vless","tag":"proxy"},{"type":"tor","tag":"tor-over-vpn"}]}""",
                torActive = true,
            )

        assertEquals(TOR_RUNTIME_START_TIMEOUT_MS, runtimeStartTimeoutMsForSession(session))
    }

    @Test
    fun `tor only native tor sessions use the extended tor runtime start timeout`() {
        val session =
            session(
                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                configJson = """{"outbounds":[{"type":"tor","tag":"proxy"}]}""",
                torActive = true,
            )

        assertEquals(TOR_RUNTIME_START_TIMEOUT_MS, runtimeStartTimeoutMsForSession(session))
    }

    @Test
    fun `a stale tor profile id alone cannot extend the runtime timeout`() {
        val session =
            session(
                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                configJson = """{"outbounds":[{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":19050}]}""",
            )

        assertEquals(RUNTIME_START_TIMEOUT_MS, runtimeStartTimeoutMsForSession(session))
    }

    private fun session(
        profileId: Long,
        configJson: String,
        torActive: Boolean = false,
    ): VpnSession =
        VpnSession(
            profileId = profileId,
            profileName = "test",
            protocolHint = ProtocolHint.VLESS,
            configJson = configJson,
            correlationId = "test-session",
            torActive = torActive,
        )
}
