package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeForegroundIpPolicyTest {
    @Test
    fun `foreground auto refresh keeps app-owned ip fresh while idle connected or errored`() {
        assertTrue(shouldAutoRefreshIpOnForeground(ConnectionState.IDLE))
        assertTrue(shouldAutoRefreshIpOnForeground(ConnectionState.CONNECTED))
        assertTrue(shouldAutoRefreshIpOnForeground(ConnectionState.ERROR))
    }

    @Test
    fun `foreground auto refresh skips transitional connection states`() {
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.CONNECTING))
        assertFalse(shouldAutoRefreshIpOnForeground(ConnectionState.RECONNECTING))
    }

    @Test
    fun `foreground refresh is delayed on cold start and debounced`() {
        val source = homeViewModelSource()

        assertTrue(source.contains("FIRST_FOREGROUND_REFRESH_STARTUP_DELAY_MS = 2_500L"))
        assertTrue(source.contains("APP_FOREGROUND_REFRESH_MIN_INTERVAL_MS = 15_000L"))
        assertTrue(source.contains("if (!firstAppForegroundHandled)"))
        assertTrue(source.contains("delay(FIRST_FOREGROUND_REFRESH_STARTUP_DELAY_MS)"))
        assertTrue(source.contains("foreground refresh skipped: debounce"))
        assertTrue(source.contains("clearExistingIp = false"))
    }

    // The foreground refresh entry point lives in the supervisors support file; the tunables
    // stay on the HomeViewModel companion, so pin both sources together.
    private fun homeViewModelSource(): String =
        listOf("HomeViewModel.kt", "HomeViewModelSupervisorsSupport.kt").joinToString(separator = "\n") { name ->
            listOf(
                File("src/main/kotlin/com/foxhole/guard/ui/$name"),
                File("app/src/main/kotlin/com/foxhole/guard/ui/$name"),
                File("../app/src/main/kotlin/com/foxhole/guard/ui/$name"),
            ).first { file -> file.isFile }.readText()
        }
}
