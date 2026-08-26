package com.foxhole.guard.ui.cli.settings

import com.foxhole.guard.ui.VpnRoutingScenario
import com.foxhole.guard.ui.requiresSelectedApps
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal class CliVpnProxyModeSelectionTest {
    @Test
    fun `proxy server mode does not depend on an app selection`() {
        assertFalse(vpnConnNeedsApps(CliVpnConn.PROXY_SERVER))
        assertFalse(VpnRoutingScenario.PROXY_SERVER.requiresSelectedApps())
        assertFalse(VpnRoutingScenario.WHOLE_DEVICE.requiresSelectedApps())
    }

    @Test
    fun `selected app VPN modes still require an app selection`() {
        assertTrue(vpnConnNeedsApps(CliVpnConn.SELECTED_APPS))
        assertTrue(VpnRoutingScenario.SELECTED_INCLUDE.requiresSelectedApps())
        assertTrue(VpnRoutingScenario.SELECTED_EXCLUDE.requiresSelectedApps())
    }

    @Test
    fun `view model rejection uses the scenario policy instead of inherited proxy tunnel mode`() {
        val source = source("main/kotlin/com/foxhole/guard/ui/HomeViewModelAppRoutingSettingsSupport.kt")
        val guard =
            source.substringAfter("internal fun HomeViewModel.onVpnRoutingScenarioSelected")
                .substringBefore("val change = PendingRoutingScenarioChange.Vpn")

        assertTrue(guard.contains("value.requiresSelectedApps()"))
        assertFalse(guard.contains("targetMode != PerAppRoutingMode.FULL_TUNNEL"))
    }

    private fun source(relative: String): String =
        listOf(
            File("src", relative),
            File("app/src", relative),
            File("../app/src", relative),
        ).first(File::isFile).readText()
}
