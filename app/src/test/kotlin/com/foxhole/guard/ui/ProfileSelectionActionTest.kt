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

/**
 * Picking a profile on the home screen must never start anything.
 *
 * The bug this pins: with the mode set to TOR, tapping a VPN profile in the quick selector ran the
 * "activate and connect" path, so a pick raised a VPN — and, because TOR mode arms the Tor route,
 * VPN+TOR. The same path fired in VPN and VPN+TOR mode. A pick is a choice, not a command; the only
 * profile change that touches the runtime is one made under a live VPN, and that one confirms first.
 */
class ProfileSelectionActionTest {

    private val vpnProfileId = 42L
    private val otherProfileId = 7L

    /** The routing mode as the home screen's MODE button reports it, for each spec case. */
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
            // The case is the mode the home screen shows on the MODE button...
            assertEquals("mode fixture", preset, settingsFor(preset).activeRoutingModePreset())
            // ...and in every one of them a pick only remembers the choice.
            assertEquals(
                "mode $preset with nothing running",
                ProfileSelectionAction.REMEMBER,
                profileSelectionAction(idle, vpnProfileId),
            )
        }
    }

    @Test
    fun `picking a VPN profile while the TOR-only runtime is up starts nothing`() {
        // Mode TOR with Tor actually engaged: the standalone Tor runtime is not a primary
        // connection runtime, so the pick must neither confirm nor connect — it just registers.
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

    /**
     * Cancel on the switch sheet must be inert: the running tunnel keeps its profile and protocol
     * because the "no" answer writes nothing at all — it only drops the prompt.
     */
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
        // And the sheet a live switch raises is the profile-switch one, whose confirm is the only
        // thing that applies it.
        val switchSupport =
            File("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelProfileSwitchSupport.kt").readText()
        assertTrue(switchSupport.contains("TorTransitionPrompt.SwitchProfileWhileConnected("))
    }

    /**
     * The decision alone does not prove nothing starts — the wiring has to honour it. Both selector
     * entry points route through the one decision, and the connecting helper is reachable only from
     * the confirm of the switch sheet.
     */
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
        // Both selectors funnel through the shared decision rather than deciding for themselves.
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

        // The option write completes first. If that profile was not active, it then enters the
        // same remember-or-confirm decision already covered by the matrix above; closing the
        // selector must never leave the dashboard on the previous profile.
        assertTrue(source.contains("val updated = container.profileRepository.selectProfileProtocolOption"))
        assertTrue(source.contains("onQuickSelectorSingleProfileSelected(profileId)"))
        assertFalse(source.contains("else {\n                connect(profileId)"))
    }
}
