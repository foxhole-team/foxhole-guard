package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.VpnSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeValidationSessionGuardTest {
    @Test
    fun `accepts exact active runtime validation session`() {
        val session = session()

        assertTrue(session.matchesRuntimeValidationSession(session))
    }

    @Test
    fun `rejects missing or stale runtime validation sessions`() {
        val session = session()

        assertFalse((null as VpnSession?).matchesRuntimeValidationSession(session))
        assertFalse(session.copy(profileId = session.profileId + 1).matchesRuntimeValidationSession(session))
        assertFalse(session.copy(correlationId = "new-session").matchesRuntimeValidationSession(session))
        assertFalse(session.copy(protocolOptionId = "trojan").matchesRuntimeValidationSession(session))
    }

    private fun session() =
        VpnSession(
            profileId = 42L,
            profileName = "Smart",
            protocolHint = ProtocolHint.VLESS,
            protocolOptionId = "vless",
            configJson = "{}",
            correlationId = "session-1",
        )
}
