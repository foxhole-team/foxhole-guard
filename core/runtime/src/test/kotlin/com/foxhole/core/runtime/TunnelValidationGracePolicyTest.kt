package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TunnelValidationGracePolicyTest {
    @Test
    fun `does not grant validation grace without successful tunnel activity`() {
        assertNull(
            selectTunnelValidationGracePolicy(
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = false),
            ),
        )
    }

    @Test
    fun `does not grant validation grace when runtime already exposed a fatal error`() {
        assertNull(
            selectTunnelValidationGracePolicy(
                evidence =
                TunnelValidationEvidence(
                    hasSuccessfulTunnelActivity = true,
                    fatalRuntimeMessage = "authentication failed",
                ),
            ),
        )
    }

    @Test
    fun `granted validation grace uses the bounded default retry budget`() {
        val policy =
            selectTunnelValidationGracePolicy(
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = true),
            )

        assertEquals(CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS, policy?.totalTimeoutMs)
        assertEquals(2, policy?.attempts)
    }

    @Test
    fun `outer validation grace budget matches the bounded default`() {
        assertEquals(CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS, maxTunnelValidationGraceTimeoutMs())
    }
}
