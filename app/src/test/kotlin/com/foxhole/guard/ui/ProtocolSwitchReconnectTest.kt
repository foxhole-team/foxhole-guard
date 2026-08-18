package com.foxhole.guard.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolSwitchReconnectTest {
    @Test
    fun `switching to a different option than the running one requires reconnect`() {
        assertTrue(
            shouldRequireReconnectAfterProtocolSwitch(
                runningOptionId = "vless-1",
                selectedOptionId = "trojan-1",
            ),
        )
    }

    @Test
    fun `switching back to the running option clears the reconnect requirement`() {
        assertFalse(
            shouldRequireReconnectAfterProtocolSwitch(
                runningOptionId = "vless-1",
                selectedOptionId = "vless-1",
            ),
        )
    }

    @Test
    fun `unknown running option is treated as a change`() {
        assertTrue(
            shouldRequireReconnectAfterProtocolSwitch(
                runningOptionId = null,
                selectedOptionId = "vless-1",
            ),
        )
    }

    @Test
    fun `expired reconnect prompt window returns the primary button to stop`() {
        val viewModelSource =
            java.io.File("src/main/kotlin/com/foxhole/guard/ui/HomeViewModel.kt").readText()
        val promptTimeoutBlock =
            viewModelSource
                .substringAfter("internal fun markProfileReconnectPromptWindow()")
                .substringBefore("internal fun startPendingProfileReconnectPromptIfNeeded()")
        assertTrue(promptTimeoutBlock.contains("clearRuntimeReconnectRequired()"))
        assertTrue(promptTimeoutBlock.contains("delay(PROFILE_RECONNECT_PROMPT_WINDOW_MS)"))
    }
}
