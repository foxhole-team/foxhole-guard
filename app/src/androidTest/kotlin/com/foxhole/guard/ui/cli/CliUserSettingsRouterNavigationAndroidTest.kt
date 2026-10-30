package com.foxhole.guard.ui.cli

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.InterfaceMode
import com.foxhole.core.model.Settings
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.updateInterfaceMode
import com.foxhole.guard.onboardingCompletedRule
import com.foxhole.guard.ui.cli.components.cliDockItemTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

class CliUserSettingsRouterNavigationAndroidTest {
    private val compose = createAndroidComposeRule<CliMainActivity>()
    private val qaOnly = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                check(context.packageName.matches(Regex("com\\.foxhole\\.guard\\.qa\\.r[a-f0-9]{32}")))
                base.evaluate()
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(qaOnly)
        .around(onboardingCompletedRule(InterfaceMode.USER)).around(compose)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun app() = context.applicationContext as FoxholeApplication

    @Test
    fun userModulesAndAdditionsExposeExistingControlsAndRouterLivesInsideAdditions() {
        waitForHome()
        val before = runBlocking { app().container.settingsRepository.current() }
        compose.onNodeWithTag(cliDockItemTag(CliScreen.SETTINGS)).performClick()
        label(R.string.cli_cfg_group_privacy).performScrollTo().performClick()
        label(R.string.cli_cfg_tor_core).performScrollTo().assertIsDisplayed()
        label(R.string.cli_cfg_i2p_core).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("guard_settings_openwrt_entry").assertDoesNotExist()
        label(R.string.cli_cfg_group_extras).performScrollTo().performClick()
        label(R.string.cli_extras_webapps).performScrollTo().assertIsDisplayed()
        label(R.string.guard_router_settings_title).performScrollTo().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("guard_settings_openwrt_entry").performScrollTo().performClick()
        returnFromRouterToSettings()
        label(R.string.cli_cfg_group_extras).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        label(R.string.guard_router_back_settings).assertDoesNotExist()
        assertPoliciesUnchanged(before)
    }

    @Test
    fun homeShortcutUsesTheSameSettingsRouterDestinationInUserAndExpertModes() {
        waitForHome()
        val before = runBlocking { app().container.settingsRepository.current() }
        for (mode in listOf(InterfaceMode.USER, InterfaceMode.EXPERT)) {
            runBlocking { app().container.settingsRepository.updateInterfaceMode(mode) }
            compose.onNodeWithTag(cliDockItemTag(CliScreen.HOME)).performClick()
            waitForHome()
            compose.onNodeWithTag("guard_home_openwrt_shortcut").performClick()
            returnFromRouterToSettings()
            label(R.string.cli_cfg_group_extras).performScrollTo().assertIsDisplayed()
        }
        runBlocking { app().container.settingsRepository.updateInterfaceMode(before.ui.interfaceMode) }
        assertPoliciesUnchanged(before)
    }

    private fun returnFromRouterToSettings() {
        compose.waitUntil(timeoutMillis = 10_000L) {
            runCatching { label(R.string.guard_router_back_settings).assertIsDisplayed() }.isSuccess
        }
        compose.waitForIdle()
        val footer = label(R.string.guard_router_back_settings).fetchSemanticsNode().boundsInRoot
        val dock = compose.onNodeWithTag(cliDockItemTag(CliScreen.SETTINGS)).fetchSemanticsNode().boundsInRoot
        assertTrue("Router footer overlaps the app dock", footer.bottom <= dock.top)
        label(R.string.guard_router_vpn_close).assertDoesNotExist()
        label(R.string.guard_router_back_settings).performClick()
    }

    private fun waitForHome() {
        compose.waitUntil(timeoutMillis = 10_000L) {
            runCatching { compose.onNodeWithTag("guard_home_openwrt_shortcut").assertIsDisplayed() }.isSuccess
        }
    }

    private fun label(id: Int) = compose.onNodeWithText(context.getString(id), ignoreCase = true)

    private fun assertPoliciesUnchanged(before: Settings) {
        val after = runBlocking { app().container.settingsRepository.current() }
        assertEquals(before.connection.safeModeEnabled, after.connection.safeModeEnabled)
        assertEquals(before.traffic, after.traffic)
        assertEquals(before.expert.core, after.expert.core)
        assertEquals(before.expert.allowInsecureTls, after.expert.allowInsecureTls)
        assertEquals(before.privacyRoute.permitted, after.privacyRoute.permitted)
        assertEquals(before.i2p.enabled, after.i2p.enabled)
        assertEquals(before.webApps.enabled, after.webApps.enabled)
        assertEquals(before.expert.localSurfaces.allowLanAccess, after.expert.localSurfaces.allowLanAccess)
    }
}
