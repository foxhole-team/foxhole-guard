package com.foxhole.guard.router.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.R
import com.foxhole.guard.router.RouterControlPreferences
import com.foxhole.guard.router.RouterControlStage
import com.foxhole.guard.router.RouterControlState
import com.foxhole.guard.router.RouterSavedSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuardRouterSettingsEntryAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val pairingId = "pair-" + "a".repeat(32)
    private val paired = RouterControlState(
        stage = RouterControlStage.DISABLED,
        preferences = RouterControlPreferences(pairingId),
        selectedRouterId = pairingId,
        savedRouters = listOf(RouterSavedSummary(pairingId, 1, null, "192.168.1.1")),
    )

    @Test fun unpairedRouterKeepsSetupOpenAndItsSwitchUnavailable() {
        var opened = 0
        compose.setContent { GuardRouterTestTheme {
            GuardRouterSettingsBlock(RouterControlState(), onToggle = {}, onOpen = { opened++ })
        } }
        compose.onNodeWithText(context.getString(R.string.guard_router_settings_title))
            .assertIsDisplayed().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText(context.getString(R.string.guard_router_settings_pair_first)).assertIsDisplayed()
        compose.onNodeWithTag("guard_settings_openwrt_entry").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun pairedRouterHasAnIndentedSettingsRowBelowItsUsableSwitch() {
        var toggled: Boolean? = null
        var opened = 0
        compose.setContent { GuardRouterTestTheme {
            GuardRouterSettingsBlock(paired, onToggle = { toggled = it }, onOpen = { opened++ })
        } }
        val toggle = compose.onNodeWithText(context.getString(R.string.guard_router_settings_title))
        val settings = compose.onNodeWithTag("guard_settings_openwrt_entry")
        toggle.assertIsEnabled().assertIsOff().performClick()
        val primaryBounds = toggle.fetchSemanticsNode().boundsInRoot
        val settingsBounds = settings.fetchSemanticsNode().boundsInRoot
        assertTrue(settingsBounds.left > primaryBounds.left)
        assertTrue(settingsBounds.center.y > primaryBounds.center.y)
        settings.performClick()
        compose.runOnIdle { assertEquals(true, toggled); assertEquals(1, opened) }
    }

    @Test fun enabledManagementCanBeTurnedOffWithoutOpeningSettings() {
        var toggled: Boolean? = null
        var opened = 0
        val enabled = paired.copy(stage = RouterControlStage.OFFLINE,
            preferences = RouterControlPreferences(pairingId, enabled = true))
        compose.setContent { GuardRouterTestTheme {
            GuardRouterSettingsBlock(enabled, onToggle = { toggled = it }, onOpen = { opened++ })
        } }
        compose.onNodeWithText(context.getString(R.string.guard_router_settings_title)).assertIsOn().performClick()
        compose.runOnIdle { assertEquals(false, toggled); assertEquals(0, opened) }
    }

    @Test fun loadingCannotDisplayRetainedEnabledPreferencesAsReady() {
        val enabled = paired.copy(preferences = RouterControlPreferences(pairingId, enabled = true))
        compose.setContent { GuardRouterTestTheme {
            GuardRouterSettingsBlock(enabled, onToggle = {}, onOpen = {}, initialising = true)
        } }
        compose.onNodeWithText(context.getString(R.string.guard_router_settings_title)).assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("guard_settings_openwrt_entry").assertHasClickAction()
    }
}
