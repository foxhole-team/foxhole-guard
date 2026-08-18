package com.foxhole.guard.ui

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.Settings
import com.foxhole.guard.core.settings.activeRoutingModePreset
import com.foxhole.guard.runtime.FoxholeVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileSelectionActionTest {

    private val vpnProfileId = 42L
    private val otherProfileId = 7L

    private fun settingsFor(preset: RoutingModePreset): Settings =
        when (preset) {
            RoutingModePreset.TOR ->
                Settings(
                    privacyRoute = PrivacyRouteSettings(
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        bypassVpnTunnel = true,
                        scope = PrivacyRouteScope.SELECTED_APPS,
                    ),
                )
            RoutingModePreset.VPN_TOR ->
                Settings(
                    privacyRoute = PrivacyRouteSettings(
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        bypassVpnTunnel = false,
                        scope = PrivacyRouteScope.SELECTED_APPS,
                    ),
                )
            else -> Settings(privacyRoute = PrivacyRouteSettings(mode = PrivacyRouteMode.OFF))
        }

    private val idle = ConnectionSnapshot(state = ConnectionState.IDLE)

    private val liveTorOnly =
        ConnectionSnapshot(
            state = ConnectionState.CONNECTED,
            profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
        )

    @Test
    fun `picking a profile starts nothing in TOR, VPN and VPN+TOR mode`() {
        listOf(RoutingModePreset.TOR, RoutingModePreset.VPN, RoutingModePreset.VPN_TOR).forEach { preset ->
            assertEquals("mode fixture", preset, settingsFor(preset).activeRoutingModePreset())
            assertEquals(
                "mode $preset with nothing running",
                ProfileSelectionAction.REMEMBER,
                profileSelectionAction(idle, vpnProfileId),
            )
        }
    }

    @Test
    fun `picking a VPN profile while the TOR-only runtime is up starts nothing`() {
        assertEquals(
            ProfileSelectionAction.REMEMBER,
            profileSelectionAction(liveTorOnly, vpnProfileId),
        )
    }

    @Test
    fun `re-picking the profile that is already connected changes nothing`() {
        assertEquals(
            ProfileSelectionAction.REMEMBER,
            profileSelectionAction(
                ConnectionSnapshot(state = ConnectionState.CONNECTED, profileId = vpnProfileId),
                vpnProfileId,
            ),
        )
    }

    @Test
    fun `changing the profile under a live VPN raises the switch sheet instead of switching`() {
        listOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.RECONNECTING)
            .forEach { state ->
                assertEquals(
                    "live $state",
                    ProfileSelectionAction.CONFIRM_SWITCH,
                    profileSelectionAction(
                        ConnectionSnapshot(state = state, profileId = vpnProfileId),
                        otherProfileId,
                    ),
                )
            }
    }

    @Test
    fun `cancelling the switch sheet applies nothing`() {
        val dismissBody =
            File("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelTorSupport.kt")
                .readText()
                .substringAfter("internal fun HomeViewModel.dismissTorTransitionPrompt()")
                .substringBefore("\n}")

        assertTrue(dismissBody.contains("torTransitionPromptMutable.value = null"))
        listOf(
            "connect(",
            "requestReconnect",
            "activateAndConnectProfile",
            "setActiveProfile",
            "selectProfileProtocolOption",
            "settingsRepository",
        ).forEach { forbidden ->
            assertFalse("cancel must not call $forbidden", dismissBody.contains(forbidden))
        }
        val switchSupport =
            File("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelProfileSwitchSupport.kt").readText()
        assertTrue(switchSupport.contains("TorTransitionPrompt.SwitchProfileWhileConnected("))
    }

    @Test
    fun `only the switch-sheet confirm may reach the connecting path`() {
        val source =
            File("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelProfileSwitchSupport.kt").readText()
        val connectCallSites =
            source.lines().count { line -> "activateAndConnectProfile(" in line && "fun " !in line }

        assertEquals("activateAndConnectProfile call sites", 1, connectCallSites)
        assertTrue(
            "the single call site must be the B2 confirm",
            source
                .substringAfter("fun HomeViewModel.confirmSwitchProfileWhileConnected")
                .substringBefore("private fun HomeViewModel.activateAndConnectProfile")
                .contains("activateAndConnectProfile(prompt.profileId)"),
        )
        assertTrue(source.contains("internal fun HomeViewModel.onQuickSelectorSingleProfileSelected"))
        assertEquals(
            "applyProfileSelection call sites",
            2,
            source.lines().count { line -> "applyProfileSelection(profileId)" in line && "fun " !in line },
        )
    }

    @Test
    fun `a protocol picked from an inactive smart profile selects that profile safely`() {
        val source =
            File("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelTorSupport.kt")
                .readText()
                .substringAfter("internal fun HomeViewModel.selectProtocolOptionAndMaybeReconnect")
                .substringBefore("private fun HomeViewModel.scheduleProtocolSwitchRevert")

        assertTrue(source.contains("val updated = container.profileRepository.selectProfileProtocolOption"))
        assertTrue(source.contains("onQuickSelectorSingleProfileSelected(profileId)"))
        assertFalse(source.contains("else {\n                connect(profileId)"))
    }
}
