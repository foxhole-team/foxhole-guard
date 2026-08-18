package com.foxhole.guard.runtime

import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun `the reload session-load failure handler routes through the decision instead of stopping`() {
        val handler =
            File("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceReloadSupport.kt")
                .readText()
                .substringAfter("private suspend fun FoxholeVpnService.loadReloadSessionOrFail(")
                .substringBefore("internal enum class ReloadSessionFailureAction")
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
