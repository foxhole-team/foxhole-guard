package com.foxhole.guard.core.sharing

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.runtime.NativeRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSharePolicyTest {
    @Test
    fun `display name strips paths controls and bounds utf8`() {
        val sanitized = sanitizeFileShareDisplayName(" ../secret\\\u0000" + "я".repeat(300))

        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains('\\'))
        assertFalse(sanitized.any(Char::isISOControl))
        assertTrue(sanitized.encodeToByteArray().size <= 240)
    }

    @Test
    fun `media type accepts tokens and rejects headers`() {
        assertEquals("image/png", sanitizeFileShareMediaType("image/png"))
        assertEquals(
            "application/octet-stream",
            sanitizeFileShareMediaType("text/plain\r\nX-Leak: yes"),
        )
    }

    @Test
    fun `optional password is bounded printable ascii`() {
        assertTrue(validFileSharePassword(null))
        assertTrue(validFileSharePassword("correct-horse".toCharArray()))
        assertFalse(validFileSharePassword("short".toCharArray()))
        assertFalse(validFileSharePassword("пароль123".toCharArray()))
        assertFalse(validFileSharePassword("bad password".toCharArray()))
    }

    @Test
    fun `audit parser accepts only fixed event vocabulary`() {
        val parsed =
            parseFileShareEventKinds(
                """{"events":[{"kind":"download_authorized"},{"kind":"url=https_secret"}]}""",
            )

        assertEquals(listOf("download_authorized"), parsed)
    }

    /**
     * A share may only ever leave the device through an onion service. The
     * predicate that enforces it is a conjunction of two facts that are easy to
     * confuse: the tunnel is *connected*, and the applied runtime actually
     * carries a Tor route. Either one alone is a publication over something
     * that is not Tor, which the sharing crate says must be unrepresentable.
     *
     * `torActive` is deliberately the applied-config flag rather than the
     * settings toggle, so an armed-but-not-engaged route reads as not ready.
     */
    @Test
    fun `publication is refused unless the tunnel is connected AND carries tor`() {
        assertTrue(
            "connected with a live Tor route is the one publishable state",
            torRouteReady(ConnectionSnapshot(state = ConnectionState.CONNECTED, torActive = true)),
        )
        assertFalse(
            "connected without Tor must not publish: that would be a clearnet share",
            torRouteReady(ConnectionSnapshot(state = ConnectionState.CONNECTED, torActive = false)),
        )
        listOf(
            ConnectionState.IDLE,
            ConnectionState.CONNECTING,
            ConnectionState.RECONNECTING,
            ConnectionState.ERROR,
        ).forEach { state ->
            assertFalse(
                "torActive without a connected tunnel must not publish ($state)",
                torRouteReady(ConnectionSnapshot(state = state, torActive = true)),
            )
        }
    }

    @Test
    fun `active share is pinned to one live native generation`() {
        val generationSeven =
            NativeRuntimeSnapshot.NONE.copy(
                hasEngineHandle = true,
                nativeGeneration = 7L,
            )

        assertTrue(isCurrentShareRuntime(7L, generationSeven))
        assertFalse(isCurrentShareRuntime(null, generationSeven))
        assertFalse(isCurrentShareRuntime(6L, generationSeven))
        assertFalse(
            isCurrentShareRuntime(
                7L,
                generationSeven.copy(hasEngineHandle = false),
            ),
        )
    }
}
