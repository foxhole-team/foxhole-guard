package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyReloadOutcomeTest {
    @Test
    fun `a permanent refusal and a retryable one are not the same failure`() {
        // Permanent capability failures must not collapse into retryable revision conflicts.
        assertEquals(
            FoxCoreRuntimeFailure.POLICY_TOR_UNAVAILABLE,
            policyReloadFailure(FoxholeNativeEngine.RELOAD_TOR_UNAVAILABLE.toLong()),
        )
        assertEquals(
            FoxCoreRuntimeFailure.POLICY_REVISION_CONFLICT,
            policyReloadFailure(FoxholeNativeEngine.RELOAD_REVISION_CONFLICT.toLong()),
        )
        assertEquals(
            FoxCoreRuntimeFailure.POLICY_I2P_UNAVAILABLE,
            policyReloadFailure(FoxholeNativeEngine.RELOAD_I2P_UNAVAILABLE.toLong()),
        )
        assertEquals(
            FoxCoreRuntimeFailure.POLICY_UNKNOWN_OUTBOUND,
            policyReloadFailure(FoxholeNativeEngine.RELOAD_UNKNOWN_OUTBOUND.toLong()),
        )
    }

    @Test
    fun `the four route refusals share one failure because the user fixes them the same way`() {
        val routeCodes =
            listOf(
                FoxholeNativeEngine.RELOAD_OVERLAY_WITHOUT_FAKE_IP,
                FoxholeNativeEngine.RELOAD_NO_ATTRIBUTION,
                FoxholeNativeEngine.RELOAD_PACKET_TUNNEL_REJECTS_FAKE_IP,
                FoxholeNativeEngine.RELOAD_PACKET_TUNNEL_REJECTS_PRIMARY_DNS,
            )
        routeCodes.forEach { code ->
            assertEquals(
                "code $code",
                FoxCoreRuntimeFailure.POLICY_ROUTE_UNSUPPORTED,
                policyReloadFailure(code.toLong()),
            )
        }
    }

    @Test
    fun `zero means the handle is not running, not a policy problem`() {
        assertEquals(FoxCoreRuntimeFailure.NOT_RUNNING, policyReloadFailure(0L))
    }

    @Test
    fun `an unrecognised code still fails rather than being read as success`() {
        assertEquals(FoxCoreRuntimeFailure.POLICY_RELOAD_FAILED, policyReloadFailure(-99L))
        assertEquals(
            FoxCoreRuntimeFailure.POLICY_RELOAD_FAILED,
            policyReloadFailure(FoxholeNativeEngine.RELOAD_INVALID.toLong()),
        )
    }

    @Test
    fun `only the revision conflict is retried`() {
        assertTrue(policyReloadIsRetryable(FoxholeNativeEngine.RELOAD_REVISION_CONFLICT.toLong()))
        // Everything else refuses identically forever; a retry loop on those is
        // battery spent to report nothing.
        listOf(
            FoxholeNativeEngine.RELOAD_INVALID,
            FoxholeNativeEngine.RELOAD_UNKNOWN_OUTBOUND,
            FoxholeNativeEngine.RELOAD_TOR_UNAVAILABLE,
            FoxholeNativeEngine.RELOAD_I2P_UNAVAILABLE,
            FoxholeNativeEngine.RELOAD_OVERLAY_WITHOUT_FAKE_IP,
            FoxholeNativeEngine.RELOAD_NO_ATTRIBUTION,
            FoxholeNativeEngine.RELOAD_PACKET_TUNNEL_REJECTS_FAKE_IP,
            FoxholeNativeEngine.RELOAD_PACKET_TUNNEL_REJECTS_PRIMARY_DNS,
        ).forEach { code ->
            assertFalse("code $code", policyReloadIsRetryable(code.toLong()))
        }
    }

    @Test
    fun `the core is asked for words only when the code cannot be acted on alone`() {
        // One extra JNI call, taken only where it can tell us something: -1
        // covers both a truncated write and a field this schema removed.
        assertTrue(policyReloadNeedsDetail(FoxholeNativeEngine.RELOAD_INVALID.toLong()))
        assertFalse(policyReloadNeedsDetail(FoxholeNativeEngine.RELOAD_TOR_UNAVAILABLE.toLong()))
        assertFalse(policyReloadNeedsDetail(FoxholeNativeEngine.RELOAD_REVISION_CONFLICT.toLong()))
    }
}
