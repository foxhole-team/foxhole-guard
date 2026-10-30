package com.foxhole.guard.ui.cli

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.InterfaceMode
import com.foxhole.core.model.Settings
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.updateInterfaceMode
import com.foxhole.guard.onboardingCompletedRule
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.components.cliDockItemTag
import com.foxhole.guard.ui.cli.home.CLI_USER_HOME_MAP_TAG
import com.foxhole.guard.ui.cli.logs.CLI_LOGS_SCREEN_TAG
import com.foxhole.guard.ui.cli.logs.CLI_SENTINEL_JOURNAL_TAG
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

class CliUserMapNavigationTest {
    private val compose = createAndroidComposeRule<CliMainActivity>()
    private var originalSettings: Settings? = null
    private val notificationsPermissionRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val packageName = instrumentation.targetContext.packageName
                instrumentation.uiAutomation.executeShellCommand(
                    "pm grant $packageName android.permission.POST_NOTIFICATIONS",
                ).close()
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(notificationsPermissionRule)
        .around(onboardingCompletedRule(InterfaceMode.USER)).around(compose)

    @Before
    fun useMapWithoutDatabasePrompts() {
        runBlocking {
            val repository = app().container.settingsRepository
            originalSettings = repository.current()
            repository.update { current ->
                current.copy(
                    ui = current.ui.copy(trafficMapEnabled = false),
                    expert = current.expert.copy(
                        firewallEnabled = false,
                        pendingQuarantinePackages = emptyList(),
                        pendingQuarantineAppDetails = emptyList(),
                    ),
                )
            }
        }
        waitForScreen(CliScreen.HOME)
        compose.waitUntil(timeoutMillis = 10_000L) {
            val home = ViewModelProvider(compose.activity)[HomeViewModel::class.java].homeRouteState.value
            !home.settings.ui.trafficMapEnabled && !home.settings.expert.firewallEnabled
        }
    }

    @After
    fun restoreMapSettings() {
        val original = originalSettings ?: return
        runBlocking {
            app().container.settingsRepository.update { current ->
                current.copy(
                    ui = current.ui.copy(
                        interfaceMode = original.ui.interfaceMode,
                        trafficMapEnabled = original.ui.trafficMapEnabled,
                    ),
                    expert = original.expert,
                )
            }
        }
    }

    @Test
    fun userOpensMapFromHomeAndSystemBackReturnsHome() {
        compose.onNodeWithTag(cliDockItemTag(CliScreen.MAP)).assertDoesNotExist()
        compose.onNodeWithTag(CLI_USER_HOME_MAP_TAG).performScrollTo().performClick()
        waitForScreen(CliScreen.MAP)
        compose.onNodeWithTag(cliDockItemTag(CliScreen.MAP)).assertDoesNotExist()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        waitForScreen(CliScreen.HOME)
        compose.onNodeWithTag(CLI_USER_HOME_MAP_TAG).assertIsDisplayed()
    }

    @Test
    fun changingInterfaceModeDoesNotCloseTheOpenMap() {
        compose.onNodeWithTag(CLI_USER_HOME_MAP_TAG).performScrollTo().performClick()
        waitForScreen(CliScreen.MAP)

        runBlocking { app().container.settingsRepository.updateInterfaceMode(InterfaceMode.EXPERT) }
        compose.waitForIdle()
        compose.onNodeWithTag(cliDockItemTag(CliScreen.MAP)).assertIsDisplayed()
        waitForScreen(CliScreen.MAP)

        runBlocking { app().container.settingsRepository.updateInterfaceMode(InterfaceMode.USER) }
        compose.waitForIdle()
        compose.onNodeWithTag(cliDockItemTag(CliScreen.MAP)).assertDoesNotExist()
        waitForScreen(CliScreen.MAP)
    }

    @Test
    fun userCanOpenSentinelJournalAndModeChangesKeepTheJournalOpen() {
        compose.onNodeWithTag(cliDockItemTag(CliScreen.SETTINGS)).performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.cli_cfg_more_journals), ignoreCase = true)
            .performScrollTo().performClick()
        compose.onNodeWithTag(CLI_LOGS_SCREEN_TAG).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cli_logs_tab_sentinel), ignoreCase = true).performClick()
        compose.onNodeWithTag(CLI_SENTINEL_JOURNAL_TAG).assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 10_000L) {
            !ViewModelProvider(compose.activity)[HomeViewModel::class.java].uiState.value.installedAppsLoading
        }
        val expertBefore = runBlocking { app().container.settingsRepository.current().expert }
        runBlocking { app().container.settingsRepository.updateInterfaceMode(InterfaceMode.EXPERT) }
        compose.onNodeWithTag(CLI_SENTINEL_JOURNAL_TAG).assertIsDisplayed()
        runBlocking { app().container.settingsRepository.updateInterfaceMode(InterfaceMode.USER) }
        compose.onNodeWithTag(CLI_SENTINEL_JOURNAL_TAG).assertIsDisplayed()
        org.junit.Assert.assertEquals(expertBefore, runBlocking { app().container.settingsRepository.current().expert })
    }

    private fun waitForScreen(screen: CliScreen) {
        compose.waitUntil(timeoutMillis = 10_000L) {
            runCatching {
                compose.onNodeWithTag(cliScreenTag(screen)).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun app(): FoxholeApplication =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FoxholeApplication
}
