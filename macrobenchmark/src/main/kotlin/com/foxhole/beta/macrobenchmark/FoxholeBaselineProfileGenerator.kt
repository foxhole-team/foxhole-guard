package com.foxhole.beta.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

@RunWith(AndroidJUnit4::class)
class FoxholeBaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun criticalUserJourneys() {
        baselineProfileRule.collect(
            packageName = PACKAGE_NAME,
            outputFilePrefix = "foxhole",
            includeInStartupProfile = true,
            maxIterations = 8,
            stableIterations = 3,
            filterPredicate = { rule ->
                rule.contains("com/foxhole/beta") || rule.contains("androidx/compose")
            },
        ) {
            pressHome()
            startActivityAndWait(foxholeLauncherIntent())
            device.waitForIdle()
            scrollDashboard()
            openSettingsAndReturn()
            openSettingsDetail("settings_dns_action", "dns_settings_screen")
            openSettingsDetail("settings_smart_start_action", "smart_start_settings_screen")
            openSettingsDetail("settings_statistics_action", "statistics_settings_screen")
            openRoutingAppsPickerSearch()
        }
    }

    private fun MacrobenchmarkScope.openSettingsAndReturn() {
        clickSettingsBottomNav()
        waitForTestTag("settings_screen")
        clickDashboardBottomNav()
        waitForTestTag("home_dashboard_list")
    }

    private fun MacrobenchmarkScope.openSettingsDetail(
        rowTag: String,
        detailTag: String,
    ) {
        openSettingsHome()
        if (!clickTestTag(rowTag)) {
            return
        }
        waitForTestTag(detailTag)
        device.pressBack()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.openRoutingAppsPickerSearch() {
        openSettingsHome()
        if (!clickTestTag("settings_routing_apps_action")) {
            return
        }
        if (!waitForTestTag("routing_apps_screen")) {
            return
        }
        if (!clickTestTag("routing_apps_add_exception_action")) {
            device.pressBack()
            device.waitForIdle()
            return
        }
        if (waitForTestTag("routing_apps_picker_screen")) {
            findByTestTag("routing_apps_picker_search")?.setText(APP_PICKER_SEARCH_QUERY)
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
        }
        device.pressBack()
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.openSettingsHome() {
        repeat(OPEN_SETTINGS_ATTEMPTS) { attempt ->
            if (waitForTestTag("settings_screen")) {
                return
            }
            clickSettingsBottomNav()
            if (waitForTestTag("settings_screen")) {
                return
            }
            swipeDashboardToSettings()
            if (waitForTestTag("settings_screen")) {
                return
            }
            if (attempt == 0) {
                device.pressBack()
            }
            device.waitForIdle()
        }
    }

    private fun scrollDashboard() {
        val centerX = device.displayWidth / 2
        val upperY = (device.displayHeight * UPPER_SWIPE_Y_RATIO).toInt()
        val lowerY = (device.displayHeight * LOWER_SWIPE_Y_RATIO).toInt()
        device.swipe(centerX, lowerY, centerX, upperY, SWIPE_STEPS)
        device.waitForIdle()
        device.swipe(centerX, upperY, centerX, lowerY, SWIPE_STEPS)
        device.waitForIdle()
    }

    private fun clickSettingsBottomNav() {
        val settingsNavX = (device.displayWidth * SETTINGS_NAV_X_RATIO).toInt()
        val bottomNavY = (device.displayHeight * BOTTOM_NAV_Y_RATIO).toInt()
        device.click(settingsNavX, bottomNavY)
    }

    private fun clickDashboardBottomNav() {
        val dashboardNavX = (device.displayWidth * DASHBOARD_NAV_X_RATIO).toInt()
        val bottomNavY = (device.displayHeight * BOTTOM_NAV_Y_RATIO).toInt()
        device.click(dashboardNavX, bottomNavY)
    }

    private fun swipeDashboardToSettings() {
        val startX = (device.displayWidth * ROOT_SWIPE_START_X_RATIO).toInt()
        val endX = (device.displayWidth * ROOT_SWIPE_END_X_RATIO).toInt()
        val centerY = (device.displayHeight * ROOT_SWIPE_Y_RATIO).toInt()
        device.swipe(startX, centerY, endX, centerY, SWIPE_STEPS)
    }

    private fun clickTestTag(tag: String): Boolean {
        val node = findByTestTag(tag) ?: return false
        val center = node.visibleCenter
        device.click(center.x, center.y)
        device.waitForIdle()
        return true
    }

    private fun waitForTestTag(tag: String): Boolean {
        repeat(OPEN_POLL_COUNT) {
            if (findByTestTag(tag) != null) {
                return true
            }
            Thread.sleep(OPEN_POLL_DELAY_MS)
        }
        return false
    }

    private fun findByTestTag(tag: String) =
        device.findObject(By.res(tag))
            ?: device.findObject(By.res(PACKAGE_NAME, tag))
            ?: device.findObject(By.res(Pattern.compile(".*${Pattern.quote(tag)}$")))

    private fun foxholeLauncherIntent() =
        Intent(Intent.ACTION_MAIN).apply {
            setClassName(PACKAGE_NAME, MAIN_ACTIVITY_CLASS_NAME)
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private companion object {
        private const val PACKAGE_NAME = BuildConfig.TARGET_PACKAGE_NAME
        private const val MAIN_ACTIVITY_CLASS_NAME = "com.foxhole.beta.MainActivity"
        private const val APP_PICKER_SEARCH_QUERY = "com."
        private const val DASHBOARD_NAV_X_RATIO = 0.25f
        private const val SETTINGS_NAV_X_RATIO = 0.75f
        private const val BOTTOM_NAV_Y_RATIO = 0.93f
        private const val ROOT_SWIPE_START_X_RATIO = 0.86f
        private const val ROOT_SWIPE_END_X_RATIO = 0.14f
        private const val ROOT_SWIPE_Y_RATIO = 0.52f
        private const val UPPER_SWIPE_Y_RATIO = 0.32f
        private const val LOWER_SWIPE_Y_RATIO = 0.78f
        private const val SWIPE_STEPS = 24
        private const val OPEN_SETTINGS_ATTEMPTS = 3
        private const val OPEN_POLL_COUNT = 80
        private const val OPEN_POLL_DELAY_MS = 50L
    }
}
