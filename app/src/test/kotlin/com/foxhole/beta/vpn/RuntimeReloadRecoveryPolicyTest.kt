package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.VpnSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeReloadRecoveryPolicyTest {
    @Test
    fun `reload restore is attempted for same profile with changed config`() {
        assertTrue(
            shouldAttemptRuntimeReloadRestore(
                previousSession = testSession(configJson = """{"route":"vpn"}"""),
                failedSession = testSession(configJson = """{"route":"vpn","tor":true}"""),
            ),
        )
    }

    @Test
    fun `reload restore is not attempted without a previous runtime`() {
        assertFalse(
            shouldAttemptRuntimeReloadRestore(
                previousSession = null,
                failedSession = testSession(configJson = """{"route":"vpn","tor":true}"""),
            ),
        )
    }

    @Test
    fun `reload restore is not attempted for another profile or identical config`() {
        assertFalse(
            shouldAttemptRuntimeReloadRestore(
                previousSession = testSession(profileId = 1L, configJson = """{"route":"vpn"}"""),
                failedSession = testSession(profileId = 2L, configJson = """{"route":"vpn","tor":true}"""),
            ),
        )
        assertFalse(
            shouldAttemptRuntimeReloadRestore(
                previousSession = testSession(configJson = """{"route":"vpn"}"""),
                failedSession = testSession(configJson = """{"route":"vpn"}"""),
            ),
        )
    }

    private fun testSession(
        profileId: Long = 1L,
        configJson: String,
    ): VpnSession =
        VpnSession(
            profileId = profileId,
            profileName = "Profile",
            protocolHint = ProtocolHint.VLESS,
            protocolOptionId = "vless",
            configJson = configJson,
            correlationId = "session-$profileId",
        )
}
