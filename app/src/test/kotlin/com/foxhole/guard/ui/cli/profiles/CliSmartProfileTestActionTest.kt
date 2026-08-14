package com.foxhole.guard.ui.cli.profiles

import com.foxhole.guard.ui.HomeProtocolMetricsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliSmartProfileTestActionTest {
    @Test
    fun `idle test starts and running test stops`() {
        assertEquals(SmartProfileTestAction.START, smartProfileTestAction(testing = false))
        assertEquals(SmartProfileTestAction.STOP, smartProfileTestAction(testing = true))
    }

    @Test
    fun `vpn plus tor asks before entering fail closed vpn protocol test`() {
        assertTrue(protocolTestRequiresVpnOnlyConfirmation(privacyRouteEnabled = true))
        assertFalse(protocolTestRequiresVpnOnlyConfirmation(privacyRouteEnabled = false))
    }

    @Test
    fun `cancel presentation clears immediately without waiting for probe job`() {
        val state = HomeProtocolMetricsState()
        state.markRefreshStarted(profileId = 42L)
        state.protocolMetricsRefreshingOptionIdByProfileIdMutable.value = mapOf(42L to "trojan")
        state.dashboardConnectionMetricsLoadingMutable.value = true
        state.dashboardConnectionMetricsLoadingStartedAtMs = 123L

        state.clearRefreshPresentation()

        assertTrue(state.protocolMetricsRefreshingProfileIdsMutable.value.isEmpty())
        assertTrue(state.protocolMetricsRefreshingOptionIdByProfileIdMutable.value.isEmpty())
        assertFalse(state.dashboardConnectionMetricsLoadingMutable.value)
        assertEquals(0L, state.dashboardConnectionMetricsLoadingStartedAtMs)
    }

    @Test
    fun `test labels are canonical caps without idle dots`() {
        val english = findFile("src/main/res/values/strings.xml").readText()
        val russian = findFile("src/main/res/values-ru/strings.xml").readText()
        val source =
            findFile(
                "src/main/kotlin/com/foxhole/guard/ui/cli/profiles/CliSmartProfileSheet.kt",
            ).readText()

        assertTrue(english.contains("<string name=\"cli_prof_test_button\">TEST</string>"))
        assertTrue(english.contains("<string name=\"cli_prof_stop_test_button\">STOP TEST</string>"))
        assertTrue(russian.contains("<string name=\"cli_prof_test_button\">ТЕСТ</string>"))
        assertTrue(russian.contains("<string name=\"cli_prof_stop_test_button\">ЗАВЕРШИТЬ ТЕСТ</string>"))
        assertFalse(source.contains("+ \"…\""))
        assertTrue(source.contains("SmartProfileTestAction.STOP -> viewModel.cancelSmartProfileMetricsRefresh()"))
    }

    private fun findFile(relative: String): File =
        listOf(File(relative), File("app/$relative"), File("../app/$relative"))
            .first(File::isFile)
}
