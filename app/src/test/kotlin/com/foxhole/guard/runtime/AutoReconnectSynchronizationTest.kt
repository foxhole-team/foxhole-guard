package com.foxhole.guard.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

// Auto-reconnect state of the shipping services is touched by the IO health loop and
// Default-dispatcher commands at once; every schedule/cancel path must hold the owner's lock,
// or a cancel<->schedule race leaves an uncancelled job / drifted attempt counter behind.
class AutoReconnectSynchronizationTest {
    @Test
    fun `vpn schedule and cancel paths hold the state lock beyond getOrPut`() {
        val source =
            java.io.File("src/main/kotlin/com/foxhole/guard/runtime/RuntimeReconnectCoordinator.kt").readText()
        assertTrue(
            Regex(
                """scheduleAutoReconnect\(reason: String\) \{[^}]*?synchronized\(reconnectState\)""",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(source)
        )
        assertTrue(
            Regex(
                """cancelScheduledAutoReconnect\(resetAttempts: Boolean\) \{\s*val reconnectState = autoReconnectState\(\)\s*synchronized\(reconnectState\)"""
            ).containsMatchIn(source)
        )
    }
}
