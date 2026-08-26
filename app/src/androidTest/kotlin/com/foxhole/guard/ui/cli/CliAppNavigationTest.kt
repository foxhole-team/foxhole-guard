package com.foxhole.guard.ui.cli

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.guard.onboardingCompletedRule
import com.foxhole.guard.ui.cli.components.CLI_DOCK_TAG
import com.foxhole.guard.ui.cli.components.cliDockItemTag
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

class CliAppNavigationTest {
    private val composeRule = createAndroidComposeRule<CliMainActivity>()
    private val notificationsPermissionRule =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    val packageName =
                        InstrumentationRegistry.getInstrumentation().targetContext.packageName
                    InstrumentationRegistry
                        .getInstrumentation()
                        .uiAutomation
                        .executeShellCommand(
                            "pm grant $packageName android.permission.POST_NOTIFICATIONS",
                        ).close()
                    base.evaluate()
                }
            }
        }

    @get:Rule
    val ruleChain: RuleChain =
        RuleChain
            .outerRule(notificationsPermissionRule)
            .around(onboardingCompletedRule())
            .around(composeRule)

    @Test
    fun dockNavigatesAcrossEveryUnconditionalCliScreen() {
        waitForScreen(CliScreen.HOME)
        composeRule.onNodeWithTag(CLI_APP_ROOT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(CLI_DOCK_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithTag(cliDockItemTag(CliScreen.STATS)).assertCountEquals(0)

        listOf(
            CliScreen.PROFILES,
            CliScreen.APPS,
            CliScreen.MAP,
            CliScreen.SETTINGS,
            CliScreen.HOME,
        ).forEach { screen ->
            composeRule.onNodeWithTag(cliDockItemTag(screen)).performClick()
            waitForScreen(screen)
        }
    }

    @Test
    fun systemBackFromSettingsReturnsToHome() {
        waitForScreen(CliScreen.HOME)
        composeRule.onNodeWithTag(cliDockItemTag(CliScreen.SETTINGS)).performClick()
        waitForScreen(CliScreen.SETTINGS)

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        waitForScreen(CliScreen.HOME)
    }

    @Test
    fun launcherExposesOnlyTheCurrentCliSurfaceContract() {
        waitForScreen(CliScreen.HOME)
        composeRule.onAllNodesWithTag("home_dashboard_list").assertCountEquals(0)
        composeRule.onAllNodesWithTag("bottom_nav_settings").assertCountEquals(0)
        composeRule.onAllNodesWithTag("app_section_swipe_surface").assertCountEquals(0)
        composeRule.onNodeWithTag(cliScreenTag(CliScreen.HOME)).assertIsDisplayed()
    }

    private fun waitForScreen(screen: CliScreen) {
        composeRule.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MS) {
            runCatching {
                composeRule
                    .onNodeWithTag(cliScreenTag(screen), useUnmergedTree = true)
                    .assertIsDisplayed()
            }.isSuccess
        }
    }

    private companion object {
        const val SCREEN_TIMEOUT_MS = 10_000L
    }
}
