package com.foxhole.beta.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
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
            openSettingsDetail(
                rowTag = "settings_dns_action",
                detailTag = "dns_settings_screen",
                labels = listOf("DNS"),
            )
            openSettingsDetail(
                rowTag = "settings_smart_start_action",
                detailTag = "smart_start_settings_screen",
                labels = listOf("Smart start", "Смарт старт"),
            )
            openSettingsDetail(
                rowTag = "settings_statistics_action",
                detailTag = "statistics_settings_screen",
                labels = listOf("Statistics", "Статистика"),
            )
            openRoutingAppsPickerSearch()
        }
    }

    private fun MacrobenchmarkScope.openSettingsAndReturn() {
        clickSettingsBottomNav()
        waitForSettingsHome()
        clickDashboardBottomNav()
        waitForDashboard()
    }

    private fun MacrobenchmarkScope.openSettingsDetail(
        rowTag: String,
        detailTag: String,
        labels: List<String>,
    ) {
        openSettingsHome()
        val row = findByTestTag(rowTag) ?: findByAnyText(labels) ?: return
        if (!clickCenter(row)) {
            return
        }
        waitForAnyText(labels) || waitForTestTag(detailTag)
        device.pressBack()
        device.waitForIdle()
        waitForSettingsHome()
    }

    private fun MacrobenchmarkScope.openRoutingAppsPickerSearch() {
        openSettingsHome()
        val routingApps =
            findByTestTag("settings_routing_apps_action")
                ?: findByAnyText(listOf("Apps", "Приложения", "Фильтр приложений"))
                ?: return
        if (!clickCenter(routingApps)) {
            return
        }
        if (!waitForAnyText(listOf("Applications", "Приложения")) && !waitForTestTag("routing_apps_screen")) {
            return
        }
        val addAction =
            findByTestTag("routing_apps_add_exception_action")
                ?: findByAnyText(listOf("Add", "Добавить"))
        if (addAction == null || !clickCenter(addAction)) {
            device.pressBack()
            device.waitForIdle()
            return
        }
        if (waitForAnyText(listOf("Applications", "Приложения", "Search", "Поиск")) || waitForTestTag("routing_apps_picker_screen")) {
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
            if (isDashboardVisible()) {
                clickSettingsBottomNav()
                if (waitForSettingsHome()) {
                    return
                }
            }
            if (waitForSettingsHome()) {
                return
            }
            clickSettingsBottomNav()
            if (waitForSettingsHome()) {
                return
            }
            swipeDashboardToSettings()
            if (waitForSettingsHome()) {
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
        findByAnyText(BOTTOM_NAV_SETTINGS_LABELS)?.let { node ->
            if (clickCenter(node)) {
                return
            }
        }
        val settingsNavX = (device.displayWidth * SETTINGS_NAV_X_RATIO).toInt()
        val bottomNavY = (device.displayHeight * BOTTOM_NAV_Y_RATIO).toInt()
        device.click(settingsNavX, bottomNavY)
    }

    private fun clickDashboardBottomNav() {
        findByAnyText(BOTTOM_NAV_DASHBOARD_LABELS)?.let { node ->
            if (clickCenter(node)) {
                return
            }
        }
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

    private fun waitForTestTag(tag: String): Boolean {
        if (!RESOURCE_TEST_TAGS_AVAILABLE) {
            return false
        }
        repeat(OPEN_POLL_COUNT) {
            if (findByTestTag(tag) != null) {
                return true
            }
            Thread.sleep(OPEN_POLL_DELAY_MS)
        }
        return false
    }

    private fun waitForSettingsHome(): Boolean =
        waitUntil {
            findByTestTag("settings_screen") != null ||
                (
                    findByAnyText(SETTINGS_HOME_PRIMARY_LABELS) != null &&
                        findByAnyText(SETTINGS_HOME_SECONDARY_LABELS) != null
                )
        }

    private fun waitForDashboard(): Boolean =
        waitUntil {
            isDashboardVisible()
        }

    private fun isDashboardVisible(): Boolean =
        findByTestTag("home_dashboard_list") != null ||
            findByAnyText(DASHBOARD_ANCHOR_LABELS) != null

    private fun waitForAnyText(labels: List<String>): Boolean =
        waitUntil { findByAnyText(labels) != null }

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        repeat(OPEN_POLL_COUNT) {
            if (predicate()) {
                return true
            }
            Thread.sleep(OPEN_POLL_DELAY_MS)
        }
        return false
    }

    private fun findByTestTag(tag: String) =
        if (RESOURCE_TEST_TAGS_AVAILABLE) {
            device.findObject(By.res(tag))
                ?: device.findObject(By.res(PACKAGE_NAME, tag))
                ?: device.findObject(By.res(Pattern.compile(".*${Pattern.quote(tag)}$")))
        } else {
            null
        }

    private fun findByAnyText(labels: List<String>) =
        labels.firstNotNullOfOrNull { label -> device.findObject(By.text(label)) }

    private fun clickCenter(node: UiObject2): Boolean =
        try {
            val center = node.visibleCenter
            device.click(center.x, center.y)
            device.waitForIdle()
            true
        } catch (_: StaleObjectException) {
            false
        }

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
        private val RESOURCE_TEST_TAGS_AVAILABLE = PACKAGE_NAME.endsWith(".debug")
        private val SETTINGS_HOME_PRIMARY_LABELS =
            listOf(
                "Smart start",
                "Смарт старт",
                "Умный старт",
            )
        private val SETTINGS_HOME_SECONDARY_LABELS =
            listOf(
                "Network",
                "Сеть",
                "DNS",
            )
        private val DASHBOARD_ANCHOR_LABELS =
            listOf(
                "FOXHOLE",
                "Dashboard",
                "Дашборд",
                "VPN profile",
                "VPN профиль",
                "Traffic Map",
                "Карта трафика",
            )
        private val BOTTOM_NAV_SETTINGS_LABELS = listOf("Settings", "Настройки")
        private val BOTTOM_NAV_DASHBOARD_LABELS = listOf("Dashboard", "Дашборд")
    }
}
