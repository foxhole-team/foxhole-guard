package com.foxhole.core.runtime

import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import com.foxhole.guard.runtime.requiresColdRestartAfterTorRemoval
import com.foxhole.guard.runtime.shouldAttemptRuntimeReloadRestore
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

    @Test
    fun `removing tor uses a cold restart but adding or retaining tor may hot reload`() {
        assertTrue(
            requiresColdRestartAfterTorRemoval(
                previousSession = testSession(configJson = "old", torActive = true),
                nextSession = testSession(configJson = "new", torActive = false),
            ),
        )
        assertFalse(
            requiresColdRestartAfterTorRemoval(
                previousSession = testSession(configJson = "old", torActive = false),
                nextSession = testSession(configJson = "new", torActive = true),
            ),
        )
        assertFalse(
            requiresColdRestartAfterTorRemoval(
                previousSession = testSession(configJson = "old", torActive = true),
                nextSession = testSession(configJson = "new", torActive = true),
            ),
        )
    }

    private fun testSession(
        profileId: Long = 1L,
        configJson: String,
        torActive: Boolean = false,
    ): VpnSession =
        VpnSession(
            profileId = profileId,
            profileName = "Profile",
            protocolHint = ProtocolHint.VLESS,
            protocolOptionId = "vless",
            configJson = configJson,
            correlationId = "session-$profileId",
            torActive = torActive,
        )
}
