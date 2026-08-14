package com.foxhole.guard.runtime

import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A reload whose new session cannot be built must not take the old route down with it.
 *
 * Reported from the Pixel on 2026-08-10: a VLESS tunnel had been up for half an hour when the MODE
 * button asked for VPN+TOR. The session for the new config failed to build, the reload path called
 * `fail()`, and the STOP that followed left the device on the open network under its real address —
 * the config that could not be BUILT destroyed the one that was already RUNNING.
 *
 * Nothing is replaced at the point this decision is made, so a live route has nothing to recover
 * from and everything to keep. Only a failure with no live route left to protect still fails
 * closed.
 */
class ReloadSessionFailureActionTest {
    @Test
    fun `a live route survives a session build that failed`() {
        listOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.RECONNECTING).forEach { state ->
            assertEquals(
                state.name,
                ReloadSessionFailureAction.KEEP_LIVE_ROUTE,
                reloadSessionFailureAction(session(), state),
            )
        }
    }

    @Test
    fun `without a live session the failure still fails closed`() {
        assertEquals(
            ReloadSessionFailureAction.FAIL_CLOSED,
            reloadSessionFailureAction(null, ConnectionState.CONNECTED),
        )
        listOf(ConnectionState.IDLE, ConnectionState.ERROR).forEach { state ->
            assertEquals(
                state.name,
                ReloadSessionFailureAction.FAIL_CLOSED,
                reloadSessionFailureAction(session(), state),
            )
        }
    }

    /**
     * The other half of the guarantee: that the reload path actually asks. `FoxholeVpnService` is
     * an Android `Service` and this module has no Robolectric, so the call site is checked as
     * source — narrowly, and only for the one thing that regressed: the session-load failure
     * handler reaching `fail()` (a STOP command) without consulting the decision above.
     */
    @Test
    fun `the reload session-load failure handler routes through the decision instead of stopping`() {
        val handler =
            File("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceReloadSupport.kt")
                .readText()
                .substringAfter("private suspend fun FoxholeVpnService.loadReloadSessionOrFail(")
                .substringBefore("/** What a reload does")
                .lineSequence()
                .filterNot { line -> line.trimStart().startsWith("//") }
                .joinToString("\n")

        assertTrue("the handler must exist", handler.isNotBlank())
        assertTrue(
            "the session-load failure must consult reloadSessionFailureAction",
            handler.contains("reloadSessionFailureAction("),
        )
        assertTrue(
            "keeping the live route must be one of its outcomes",
            handler.contains("keepLiveRuntimeAfterReloadSessionFailure("),
        )
        assertEquals(
            "fail() may be reached only through the FAIL_CLOSED branch",
            1,
            handler.split("fail(").size - 1,
        )
        assertTrue(
            "the only fail() left must be the fail-closed branch",
            handler.contains("ReloadSessionFailureAction.FAIL_CLOSED -> fail(message)"),
        )
    }

    private fun session() =
        VpnSession(
            profileId = 1L,
            profileName = "owner",
            protocolHint = ProtocolHint.VLESS,
            configJson = "{}",
            correlationId = "reload-failure",
        )
}
