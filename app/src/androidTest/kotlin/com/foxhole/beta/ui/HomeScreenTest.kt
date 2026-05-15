package com.foxhole.beta.ui

import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.foxhole.beta.R
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.MainActivity
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

class HomeScreenTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    private val notificationsPermissionRule =
        TestRule { base, _ ->
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
    val ruleChain: RuleChain = RuleChain.outerRule(notificationsPermissionRule).around(composeRule)

    @Before
    fun cancelBackgroundWorkForDeterministicUiTests() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FoxholeApplication
        runCatching {
            WorkManager.getInstance(app).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        }
        composeRule.waitForIdle()
    }

    @Test
    fun opensSettingsFromBottomNavigation() {
        composeRule.onNodeWithTag("home_connect_button").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom_nav_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onAllNodesWithText("show debug logs").assertCountEquals(0)
        composeRule.onAllNodesWithText("показывать локальные логи").assertCountEquals(0)
    }

    @Test
    fun legacySwipeZonesAreRemoved() {
        composeRule.onAllNodesWithTag("dashboard_forward_swipe_zone").assertCountEquals(0)
        composeRule.onAllNodesWithTag("screen_back_swipe_zone").assertCountEquals(0)
    }

    @Test
    fun horizontalSwipesSwitchDashboardAndSettingsSections() {
        composeRule.onNodeWithTag("home_connect_button").assertIsDisplayed()
        composeRule.onNodeWithTag("app_section_swipe_surface").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()

        composeRule.onNodeWithTag("app_section_swipe_surface").performTouchInput { swipeRight() }
        composeRule.onNodeWithTag("home_connect_button").assertIsDisplayed()
    }

    @Test
    fun systemBackFromRoutingAppsReturnsSettingsHome() {
        composeRule.onNodeWithTag("bottom_nav_settings").performClick()
        composeRule.onNodeWithTag("settings_routing_apps_action").performClick()
        composeRule.onNodeWithTag("routing_apps_add_exception_action").assertIsDisplayed()

        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_routing_apps_action").assertIsDisplayed()
    }

    @Test
    fun settingsDetailHidesBottomBarAndBackReturnsSettingsRoot() {
        composeRule.onNodeWithTag("bottom_nav_settings").performClick()
        composeRule.onNodeWithTag("settings_routing_apps_action").performClick()
        composeRule.onNodeWithTag("routing_apps_add_exception_action").assertIsDisplayed()
        composeRule.onAllNodesWithTag("bottom_nav_dashboard").assertCountEquals(0)
        composeRule.onAllNodesWithTag("bottom_nav_settings").assertCountEquals(0)

        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_routing_apps_action").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom_nav_settings").assertIsDisplayed()
        composeRule.onAllNodesWithTag("routing_apps_add_exception_action").assertCountEquals(0)
    }

    @Test
    fun rapidBottomNavigationTapsKeepUiResponsive() {
        repeat(4) {
            composeRule.onNodeWithTag("bottom_nav_settings").performClick()
            composeRule.onNodeWithTag("bottom_nav_dashboard").performClick()
        }

        composeRule.onNodeWithTag("home_connect_button").assertIsDisplayed()
        composeRule.onNodeWithTag("bottom_nav_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
    }

    @Test
    fun opensProfilesFromHomeAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithTag("home_profiles_action").performClick()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.profile_list_title)).assertIsDisplayed()
    }

    @Test
    fun profilesExportActionSelectsInlineTargetsAndOpensDestinationDialog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        prepareSmartExportProfile()
        val profileId =
            runBlocking {
                val app = context.applicationContext as FoxholeApplication
                app.container.profileRepository.profiles
                    .first()
                    .first { profile -> profile.name.contains("Instrumentation Smart", ignoreCase = true) }
                    .id
            }

        composeRule.onNodeWithTag("home_profiles_action").performClick()
        composeRule.onNodeWithTag("profiles_export_action").assertIsDisplayed().performClick()

        composeRule.onNodeWithTag("profiles_export_action").assertIsNotEnabled()
        composeRule.onNodeWithTag("profiles_export_profile_selector_${profileId}").performClick()
        composeRule.onNodeWithTag("profiles_export_action").assertIsEnabled().performClick()
        composeRule.onNodeWithText(context.getString(R.string.profile_export_destination_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.share_archive)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.profile_export_save_to_disk)).assertIsDisplayed()
    }

    @Test
    fun profilesRowTapSelectsProfileWithoutOpeningDetailAndDeleteDialogShowsName() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as FoxholeApplication
        prepareSelectableProfiles()
        val targetProfileId =
            runBlocking {
                app.container.profileRepository.profiles
                    .first()
                    .first { profile -> profile.name == "Selection B" }
                    .id
            }

        composeRule.onNodeWithTag("home_profiles_action").performClick()
        composeRule.onNodeWithTag("profiles_profile_row_${targetProfileId}").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking {
                app.container.profileRepository.activeProfile.first()?.id == targetProfileId
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.profile_list_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("profiles_profile_row_${targetProfileId}").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("profiles_profile_edit_action_${targetProfileId}").assertIsDisplayed()
        composeRule.onNodeWithTag("profiles_profile_delete_action_${targetProfileId}").performClick()
        composeRule.onNodeWithText(context.getString(R.string.delete_profile_title)).assertIsDisplayed()
        composeRule
            .onNodeWithText("${context.getString(R.string.delete_profile_summary)}\n\nSelection B")
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.delete_label)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.close)).assertIsDisplayed()
    }

    @Test
    fun singleProfileEditOpensConfigFormWithoutStuckLoading() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as FoxholeApplication
        prepareSingleEditableProfile()
        val targetProfileId =
            runBlocking {
                app.container.profileRepository.profiles
                    .first()
                    .first { profile -> profile.name == "Editable Single" }
                    .id
            }

        composeRule.onNodeWithTag("home_profiles_action").performClick()
        composeRule.onNodeWithTag("profiles_profile_row_${targetProfileId}").performTouchInput { swipeLeft() }
        composeRule.onNodeWithTag("profiles_profile_edit_action_${targetProfileId}").performClick()

        val serverLabel = context.getString(R.string.profile_editor_server)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(serverLabel).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(serverLabel).assertIsDisplayed()
        composeRule.onAllNodesWithText(context.getString(R.string.loading_label)).assertCountEquals(0)
    }

    @Test
    fun opensUniversalImportMenuFromHomeAction() {
        composeRule.onNodeWithTag("home_import_action").performClick()
        composeRule.onNodeWithTag("home_import_from_clipboard_action").assertIsDisplayed()
        composeRule.onNodeWithTag("home_import_from_file_action").assertIsDisplayed()
        composeRule.onNodeWithTag("home_import_from_qr_action").assertIsDisplayed()
    }

    @Test
    fun settingsHomeShowsApplicationsAndSitesShortcuts() {
        composeRule.onNodeWithTag("bottom_nav_settings").performClick()
        composeRule.onNodeWithTag("settings_routing_apps_action").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_routing_sites_action").assertIsDisplayed()
        composeRule.onAllNodesWithText("Safe defaults").assertCountEquals(0)
    }

    @Test
    fun settingsFooterShowsGithubRepositoryAction() {
        composeRule.onNodeWithTag("bottom_nav_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_github_repository_action"))
        composeRule.onNodeWithTag("settings_github_repository_action").assertIsDisplayed()
    }

    @Test
    fun hiddenExpertSettingsCanBeRestoredFromVersionCard() {
        setExpertSettingsVisible(visible = false)
        composeRule.onNodeWithTag("bottom_nav_settings").performClick()
        waitForSettingsHomeExpertActionHidden()
        tapFooterVersionCardUntilUnlockDialog()
        composeRule.onNodeWithTag("confirm_dialog_confirm_button").performClick()
        assertSettingsHomeExpertActionDisplayed()

        composeRule.onNodeWithTag("settings_expert_action").performClick()
        clickShowAdvancedSettingsSwitch()

        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onAllNodesWithTag("settings_expert_action").assertCountEquals(0)
        tapFooterVersionCardUntilUnlockDialog()
        composeRule.onNodeWithTag("confirm_dialog_confirm_button").performClick()
        assertSettingsHomeExpertActionDisplayed()
    }

    @Test
    fun versionCardDoesNothingWhenExpertSettingsAreAlreadyVisible() {
        setExpertSettingsVisible(visible = true)
        composeRule.onNodeWithTag("bottom_nav_settings").performClick()
        waitForSettingsHomeExpertActionVisible()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_expert_action"))
        composeRule.onNodeWithTag("settings_expert_action").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_footer_version_card"))
        repeat(5) {
            composeRule.onNodeWithTag("settings_footer_version_card").performClick()
        }

        composeRule.onAllNodesWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.expert_unlock_confirm_title),
        ).assertCountEquals(0)
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_expert_action"))
        composeRule.onNodeWithTag("settings_expert_action").assertIsDisplayed()
    }

    @Test
    fun screenshotToggleUpdatesWindowSecureFlag() {
        setBlockScreenshots(false)
        assertSecureFlag(expected = false)

        setBlockScreenshots(true)
        assertSecureFlag(expected = true)

        setBlockScreenshots(false)
        assertSecureFlag(expected = false)
    }

    @Test
    fun appSettingsExposeScreenshotToggleAndUpdateWindowSecureFlag() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        setBlockScreenshots(true)
        assertSecureFlag(expected = true)

        composeRule.onNodeWithTag("bottom_nav_settings").performClick()
        composeRule
            .onNodeWithTag("settings_screen")
            .performScrollToNode(hasTestTag("settings_application_action"))
        composeRule.onNodeWithTag("settings_application_action").tapNearTop()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag("settings_screen")
            .performScrollToNode(hasText(context.getString(R.string.block_screenshots_title)))
        composeRule.onNodeWithText(context.getString(R.string.block_screenshots_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.block_screenshots_title)).performClick()
        assertSecureFlag(expected = false)

        setBlockScreenshots(false)
        assertSecureFlag(expected = false)
    }

    private fun setExpertSettingsVisible(visible: Boolean) {
        composeRule.activityRule.scenario.onActivity { activity ->
            val app = activity.application as FoxholeApplication
            runBlocking {
                app.container.settingsRepository.unlockExpertSettings()
                app.container.settingsRepository.updateShowExpertSettings(visible)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FoxholeApplication
            runBlocking {
                app.container.settingsRepository.settings.first().ui.showExpertSettings == visible
            }
        }
        composeRule.waitForIdle()
    }

    private fun tapFooterVersionCardUntilUnlockDialog() {
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_footer_version_card"))
        composeRule.onNodeWithTag("settings_screen").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        repeat(5) {
            composeRule.onNodeWithTag("settings_footer_version_card").performClick()
            composeRule.waitForIdle()
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("confirm_dialog_confirm_button").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForSettingsHomeExpertActionHidden() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("settings_expert_action").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForSettingsHomeExpertActionVisible() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_screen").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("settings_expert_action").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertSettingsHomeExpertActionDisplayed() {
        waitForSettingsHomeExpertActionVisible()
        composeRule.onNodeWithTag("settings_screen").performScrollToNode(hasTestTag("settings_expert_action"))
        composeRule.onNodeWithTag("settings_expert_action").assertIsDisplayed()
    }

    private fun clickShowAdvancedSettingsSwitch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.show_advanced_settings_title)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(title).assertIsDisplayed().performClick()
    }

    private fun setBlockScreenshots(enabled: Boolean) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FoxholeApplication
        runBlocking {
            app.container.settingsRepository.updateBlockScreenshots(enabled)
        }
        composeRule.waitForIdle()
    }

    private fun assertSecureFlag(expected: Boolean) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            var matches = false
            composeRule.activityRule.scenario.onActivity { activity ->
                val secureEnabled = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                matches = secureEnabled == expected
            }
            matches
        }
        composeRule.activityRule.scenario.onActivity { activity ->
            val secureEnabled = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            if (expected) {
                assertTrue(secureEnabled)
            } else {
                assertFalse(secureEnabled)
            }
        }
    }

    private fun prepareSmartExportProfile() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FoxholeApplication
        runBlocking {
            app.container.profileDatabase.clearAllTables()
            deleteChildren(File(app.filesDir, "profile-secrets"))
            app.container.profileRepository.importProfile(
                """
                vless://11111111-1111-1111-1111-111111111111@1.1.1.1:8443?encryption=none&security=none&type=tcp#Instrumentation%20Smart
                trojan://secret@8.8.8.8:8444?security=tls&type=tcp#Instrumentation%20Smart
                """.trimIndent(),
            )
        }
        composeRule.waitForIdle()
    }

    private fun prepareSelectableProfiles() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FoxholeApplication
        runBlocking {
            app.container.profileDatabase.clearAllTables()
            deleteChildren(File(app.filesDir, "profile-secrets"))
            app.container.profileRepository.importProfile(
                "vless://11111111-1111-1111-1111-111111111111@1.1.1.1:443?encryption=none&security=none&type=tcp#Selection%20A",
            )
            app.container.profileRepository.importProfile(
                "trojan://secret@8.8.8.8:443?security=tls&type=tcp#Selection%20B",
            )
        }
        composeRule.waitForIdle()
    }

    private fun prepareSingleEditableProfile() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FoxholeApplication
        runBlocking {
            app.container.profileDatabase.clearAllTables()
            deleteChildren(File(app.filesDir, "profile-secrets"))
            app.container.profileRepository.importProfile(
                "vless://11111111-1111-1111-1111-111111111111@203.0.113.10:443?encryption=none&security=none&type=tcp#Editable%20Single",
            )
        }
        composeRule.waitForIdle()
    }

    private fun deleteChildren(dir: File) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                deleteChildren(child)
            }
            child.delete()
        }
    }

    @Test
    fun routingAppsScreenOpensPickerFromAddExceptionButton() {
        composeRule.onNodeWithTag("bottom_nav_settings").performClick()
        composeRule.onNodeWithTag("settings_routing_apps_action").performClick()
        composeRule.onNodeWithTag("routing_apps_add_exception_action").assertIsDisplayed()
        composeRule.onNodeWithTag("routing_apps_add_exception_action").performClick()
        composeRule.onNodeWithTag("routing_apps_picker_screen").assertIsDisplayed()
    }

    @Test
    fun routingAppsPickerFiltersInstalledPackages() {
        val targetPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName

        composeRule.onNodeWithTag("bottom_nav_settings").performClick()
        composeRule.onNodeWithTag("settings_routing_apps_action").performClick()
        composeRule.onNodeWithTag("routing_apps_add_exception_action").performClick()
        composeRule.onNodeWithTag("routing_apps_picker_search").performTextInput(targetPackage)
        composeRule.onAllNodesWithText(targetPackage).assertCountEquals(1)
    }

    @Test
    fun routingSitesScreenOpensAddExceptionDialog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithTag("bottom_nav_settings").performClick()
        composeRule
            .onNodeWithTag("settings_screen")
            .performScrollToNode(hasTestTag("settings_routing_sites_action"))
        composeRule.onNodeWithTag("settings_routing_sites_action").tapNearTop()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag("settings_screen")
            .performScrollToNode(hasTestTag("routing_sites_add_exception_action"))
        composeRule.onNodeWithTag("routing_sites_add_exception_action").performClick()
        composeRule.onNodeWithText(context.getString(R.string.add_site_exception)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.action_label)).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.tapNearTop() {
        performTouchInput {
            click(topLeft + Offset(x = 24f, y = 24f))
        }
    }
}
