package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSettings
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.components.currentLabelRes
import com.foxhole.guard.ui.cli.components.targetLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AtomicApplyScopeTest {

    @Test
    fun `atomic scenario application is on by default`() {
        assertTrue(ConnectionSettings().atomicConnection)
    }

    @Test
    fun `reconnect on drop is on by default and is a separate flag`() {
        assertTrue(ConnectionSettings().autoReconnect)
        assertTrue(ConnectionSettings(autoReconnect = false).atomicConnection)
        assertTrue(ConnectionSettings(atomicConnection = false).autoReconnect)
    }

    @Test
    fun `mode and scenario changes are held back only while atomic is off`() {
        assertTrue(requiresApplyConfirmation(AtomicApplyScope.MODE, atomicConnection = false))
        assertFalse(requiresApplyConfirmation(AtomicApplyScope.MODE, atomicConnection = true))
        assertTrue(requiresApplyConfirmation(AtomicApplyScope.SCENARIO, atomicConnection = false))
        assertFalse(requiresApplyConfirmation(AtomicApplyScope.SCENARIO, atomicConnection = true))
    }

    @Test
    fun `per-app and per-domain rules are always atomic, whatever the switch says`() {
        assertFalse(requiresApplyConfirmation(AtomicApplyScope.RULES, atomicConnection = false))
        assertFalse(requiresApplyConfirmation(AtomicApplyScope.RULES, atomicConnection = true))
    }

    @Test
    fun `confirmation summary keeps the current and future values separate`() {
        val mode = PendingRoutingScenarioChange.OperatingMode(
            current = RoutingModePreset.VPN,
            target = RoutingModePreset.VPN_TOR,
            scope = PrivacyRouteScope.ALL_APPS,
        )
        val vpnScenario = PendingRoutingScenarioChange.Vpn(
            current = VpnRoutingScenario.WHOLE_DEVICE,
            scenario = VpnRoutingScenario.SELECTED_EXCLUDE,
        )
        val torScenario = PendingRoutingScenarioChange.Tor(
            current = PrivacyRouteScope.ALL_APPS,
            scope = PrivacyRouteScope.SELECTED_APPS,
        )
        val i2pRelay = PendingRoutingScenarioChange.I2pRelay(
            currentEnabled = true,
            targetEnabled = false,
        )

        assertEquals(R.string.cli_st_vpn, mode.currentLabelRes())
        assertEquals(R.string.cli_home_status_mode_vpn_tor, mode.targetLabelRes())
        assertEquals(R.string.cli_route_vpn_whole_device, vpnScenario.currentLabelRes())
        assertEquals(R.string.cli_route_split_exclude, vpnScenario.targetLabelRes())
        assertEquals(R.string.cli_route_tor_device, torScenario.currentLabelRes())
        assertEquals(R.string.cli_route_tor_apps, torScenario.targetLabelRes())
        assertEquals(R.string.cli_route_change_enabled, i2pRelay.currentLabelRes())
        assertEquals(R.string.cli_route_change_disabled, i2pRelay.targetLabelRes())
    }

    @Test
    fun `routing change confirmation copy is mirrored in english and russian`() {
        val english = File("src/main/res/values/strings.xml").readText()
        val russian = File("src/main/res/values-ru/strings.xml").readText()
        listOf(
            "cli_route_change_confirm_title",
            "cli_route_change_confirm_question",
            "cli_route_change_confirm_info",
            "cli_route_change_current",
            "cli_route_change_target",
            "cli_route_change_i2p_relay",
            "cli_route_change_enabled",
            "cli_route_change_disabled",
            "cli_route_change_transition",
        ).forEach { key ->
            val marker = "name=\"$key\""
            assertTrue("EN misses $key", english.contains(marker))
            assertTrue("RU misses $key", russian.contains(marker))
        }
    }

    @Test
    fun `no rule path reads the atomic scenario flag`() {
        val appRouting =
            File("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelAppRoutingSettingsSupport.kt").readText()
        val presets =
            File("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelRoutingPresetSupport.kt").readText()
        val homeModes =
            File("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelTorSupport.kt").readText()
        val i2p =
            File("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelI2pSupport.kt").readText()

        assertTrue(
            "the VPN scenario entry point must consult the switch",
            appRouting
                .substringAfter("fun HomeViewModel.onVpnRoutingScenarioSelected")
                .substringBefore("fun HomeViewModel.confirmPendingRoutingScenario")
                .contains("requiresApplyConfirmation(AtomicApplyScope.SCENARIO"),
        )
        val i2pRequest =
            i2p
                .substringAfter("fun HomeViewModel.onI2pEngagedChanged")
                .substringBefore("fun HomeViewModel.applyI2pEngagement")
        assertTrue(i2pRequest.contains("requiresApplyConfirmation(AtomicApplyScope.MODE"))
        assertTrue(i2pRequest.contains("PendingRoutingScenarioChange.I2pRelay"))
        val confirmDispatcher =
            appRouting
                .substringAfter("private fun HomeViewModel.applyRoutingScenarioChange")
                .substringBefore("private fun HomeViewModel.applyVpnRoutingScenario")
        assertTrue(confirmDispatcher.contains("applyI2pEngagement(change.targetEnabled)"))
        assertFalse(confirmDispatcher.contains("onI2pEngagedChanged"))
        assertTrue(
            "the Tor scenario entry point must consult the switch",
            appRouting
                .substringAfter("fun HomeViewModel.onPrivacyRouteScopeSelected")
                .substringBefore("private fun HomeViewModel.applyPrivacyRouteScopeSelection")
                .contains("requiresApplyConfirmation(AtomicApplyScope.SCENARIO"),
        )
        assertTrue(
            "the Home operating-mode entry point must consult the switch",
            homeModes
                .substringAfter("fun HomeViewModel.onConnectModeSwitchRequested")
                .substringBefore("private fun HomeViewModel.startLiveModeSwitchPrompt")
                .contains("requiresApplyConfirmation(AtomicApplyScope.MODE"),
        )
        listOf(
            "onSelectedPackagesChanged",
            "onAppLaneChanged",
            "onAppLaneEditsApplied",
            "onQuarantinedAppResolved",
            "onPrivacyRouteSelectedPackagesChanged",
            "onBlockedPackagesChanged",
        ).forEach { functionName ->
            val handler =
                appRouting.substringAfter("fun HomeViewModel.$functionName")
                    .substringBefore("\ninternal fun HomeViewModel.")
            assertFalse("$functionName must stay independent", handler.contains("atomicConnection"))
        }
        assertFalse(presets.contains("atomicConnection"))
    }

    @Test
    fun `network scenario and rule rows all have contextual icons`() {
        val settings =
            File("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliSettingsScreen.kt").readText()
        val rules =
            File("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliNetworkRulesSection.kt").readText()

        listOf(
            "cli_cfg_atomic_connection",
            "cli_cfg_auto_reconnect",
            "cli_cfg_auto_start",
            "cli_cfg_latency_method",
            "cli_cfg_tun_stack",
            "cli_cfg_prefer_ipv6",
        ).forEach { key ->
            assertTrue(
                "$key needs an icon",
                Regex("label = stringResource\\(R.string.$key\\),\\s+icon = R.drawable.pix_[a-z_]+")
                    .containsMatchIn(settings),
            )
        }
        assertTrue(settings.contains("icon = R.drawable.pix_settings"))
        assertTrue(settings.contains("label = \"mtu\",\n        icon = R.drawable.pix_up"))
        assertTrue(rules.substringAfter("private fun wifiBinding").contains("iconRes = R.drawable.pix_link"))
        assertTrue(rules.substringAfter("private fun cellularBinding").contains("iconRes = R.drawable.pix_device"))
        listOf(
            "cli_cfg_nr_data_saver",
            "cli_cfg_nr_auto_connect",
            "cli_cfg_nr_profile",
            "cli_cfg_nr_protocol",
        ).forEach { key ->
            assertTrue(
                "$key needs an icon",
                Regex("label = stringResource\\(R.string.$key\\),\\s+icon = R.drawable.pix_[a-z_]+")
                    .containsMatchIn(rules),
            )
        }
    }

    @Test
    fun `mode confirmation keeps applied label and waits for idle before replacement start`() {
        val buttons =
            File("src/main/kotlin/com/foxhole/guard/ui/cli/home/CliHomeButtons.kt").readText()
        val home =
            File("src/main/kotlin/com/foxhole/guard/ui/cli/home/CliHomeScreen.kt").readText()
        val torPrompt =
            File("src/main/kotlin/com/foxhole/guard/ui/cli/home/CliTorPromptPanel.kt").readText()
        val torSupport =
            File("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelTorSupport.kt").readText()

        assertTrue(buttons.contains("val shownMode = displayedMode"))
        assertFalse(buttons.contains("val shownMode = pendingModeCycle ?: displayedMode"))
        val modeCycle =
            buttons
                .substringAfter("private fun cycleConnectMode")
                .substringBefore("private fun applyConnectModeSwitch")
        assertTrue(modeCycle.contains("ConnectModeSwitchRequestResult.DEFERRED -> next"))
        assertTrue(modeCycle.contains("ConnectModeSwitchRequestResult.APPLIED ->"))
        assertFalse(
            "a deferred tap must not print a command before confirmation",
            modeCycle.substringBefore("return when").contains("terminal.command"),
        )
        assertTrue(
            home.substringAfter("private fun CliHomeConfirmationSlot")
                .contains("modeCommandFor(change.target)"),
        )
        assertTrue(torPrompt.contains("onLiveModeSwitchConfirmed(prompt.target)"))
        assertTrue(
            torPrompt.indexOf("onLiveModeSwitchConfirmed(prompt.target)") <
                torPrompt.indexOf("viewModel.confirmLiveModeSwitch(prompt)"),
        )

        val torHandoff =
            torSupport
                .substringAfter("LiveModeSwitchKind.TOR_STOPS_VPN ->")
                .substringBefore("private suspend fun HomeViewModel.awaitConfirmedModeHandoffIdle")
        assertTrue(torHandoff.contains("suppressLocalGuard = true"))
        assertTrue(torHandoff.contains("userInitiated = false"))
        assertTrue(torHandoff.contains("if (awaitConfirmedModeHandoffIdle())"))
        assertTrue(torHandoff.contains("startRoutingMode(target, scope)"))

        val idleBarrier =
            torSupport
                .substringAfter("private suspend fun HomeViewModel.awaitConfirmedModeHandoffIdle")
                .substringBefore("internal fun HomeViewModel.maybePromptStartTcpVpnWhileTorOnlyActive")
        assertTrue(idleBarrier.contains("snapshot.state == ConnectionState.IDLE"))
        assertTrue(idleBarrier.contains("snapshot.state == ConnectionState.ERROR"))
        assertTrue(idleBarrier.contains("terminalState?.state == ConnectionState.IDLE"))
    }
}
