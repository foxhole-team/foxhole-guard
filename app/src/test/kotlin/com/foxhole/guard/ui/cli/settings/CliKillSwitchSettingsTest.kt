package com.foxhole.guard.ui.cli.settings

import com.foxhole.core.model.ExpertSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliKillSwitchSettingsTest {
    @Test
    fun `the switch ships disarmed`() {
        assertFalse(ExpertSettings().killSwitchEnabled)
    }

    @Test
    fun `the firewall screen owns the toggle and binds it to the stored setting`() {
        val screen = source("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliFirewallSubScreen.kt")
        val handler = source("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliKillSwitchSettingsSupport.kt")

        assertTrue(screen.contains("CliKillSwitchPanel(viewModel = viewModel, settings = settings)"))
        assertTrue(screen.contains("checked = settings.expert.killSwitchEnabled"))
        assertTrue(screen.contains("onToggle = viewModel::onKillSwitchEnabledChanged"))
        assertTrue(handler.contains("container.settingsRepository.updateKillSwitchEnabled(value)"))
        val panel = screen
            .substringAfter("private fun CliKillSwitchPanel")
            .substringBefore("private fun CliQuarantineQueue")
        assertFalse(panel.contains("cli_firewall_quarantine_needs_firewall"))
        assertFalse(panel.contains("firewallEnabled"))
    }

    @Test
    fun `the screen offers the system VPN settings, where the stronger switch lives`() {
        val screen = source("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliFirewallSubScreen.kt")

        assertTrue(screen.contains("android.provider.Settings.ACTION_VPN_SETTINGS"))
        assertTrue(screen.contains("R.string.cli_help_killswitch_link"))
    }

    @Test
    fun `the armed switch is wired to teardown and to tunnel loss`() {
        val teardown = source("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceTeardownSupport.kt")
        val callbacks = source("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceNetworkCallbacksSupport.kt")

        assertTrue(teardown.contains("event = FailClosedEvent.RUNTIME_STOPPING"))
        assertTrue(callbacks.contains("event = FailClosedEvent.TUNNEL_LOST"))
        val stopBody = teardown
            .substringAfter("internal suspend fun FoxholeVpnService.stopRuntimeFailClosed")
            .substringBefore("internal fun FoxholeVpnService.cutEveryFlowIfKillSwitchArmed")
        assertTrue(
            "the cut must be issued before stopFailClosed",
            stopBody.indexOf("cutEveryFlowIfKillSwitchArmed") < stopBody.indexOf("currentRuntime.stopFailClosed"),
        )
        val cutBody = teardown
            .substringAfter("internal fun FoxholeVpnService.cutEveryFlowIfKillSwitchArmed")
            .substringBefore("internal suspend fun FoxholeVpnService.awaitStoppedVpnNetworkTeardown")
        assertFalse(cutBody.contains(".start("))
        assertFalse(cutBody.contains("reload"))
        assertFalse(cutBody.contains("killProcess"))
    }

    @Test
    fun `both kill switches are distinguishable from the visible text alone`() {
        listOf("values", "values-ru").forEach { qualifier ->
            val strings = source("src/main/res/$qualifier/strings.xml")
            val visible = listOf(
                "cli_firewall_killswitch_software",
                "cli_firewall_killswitch_system",
                "cli_firewall_killswitch_info",
            ).joinToString("\n") { key -> value(strings, key) }

            assertTrue("$qualifier must call ours a software switch", visible.contains(softwareWord(qualifier)))
            assertTrue("$qualifier must name the OS switch", visible.contains(systemSwitchName(qualifier)))
            assertTrue("$qualifier must say ours does not replace it", visible.contains(notReplaced(qualifier)))
            assertTrue("$qualifier must admit the uncovered window", visible.contains(killedWord(qualifier)))
        }
    }

    private fun softwareWord(qualifier: String) = if (qualifier == "values") "software switch" else "программный"

    private fun systemSwitchName(qualifier: String) =
        if (qualifier == "values") "block connections without VPN" else "блокировать соединения без VPN"

    private fun notReplaced(qualifier: String) =
        if (qualifier == "values") "does not replace it" else "не заменяет"

    private fun killedWord(qualifier: String) = if (qualifier == "values") "killed" else "убит"

    private fun value(
        strings: String,
        key: String,
    ): String =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(strings)
            ?.groupValues
            ?.get(1)
            ?: error("missing string $key")

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"), File("../app/$relative"))
            .first(File::isFile)
            .readText()
}
