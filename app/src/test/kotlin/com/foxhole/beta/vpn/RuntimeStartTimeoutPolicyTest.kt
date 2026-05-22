package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.VpnSession
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
            )

        assertEquals(TOR_RUNTIME_START_TIMEOUT_MS, runtimeStartTimeoutMsForSession(session))
    }

    @Test
    fun `tor only sessions use the extended tor runtime start timeout`() {
        val session =
            session(
                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                configJson = """{"outbounds":[{"type":"tor","tag":"proxy"}]}""",
            )

        assertEquals(TOR_RUNTIME_START_TIMEOUT_MS, runtimeStartTimeoutMsForSession(session))
    }

    private fun session(
        profileId: Long,
        configJson: String,
    ): VpnSession =
        VpnSession(
            profileId = profileId,
            profileName = "test",
            protocolHint = ProtocolHint.VLESS,
            configJson = configJson,
            correlationId = "test-session",
        )
}
