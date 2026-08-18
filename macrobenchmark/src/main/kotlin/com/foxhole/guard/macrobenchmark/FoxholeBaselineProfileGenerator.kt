package com.foxhole.guard.macrobenchmark

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
import java.io.File
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
                rule.contains("com/foxhole/guard") || rule.contains("androidx/compose")
            },
        ) {
            pressHome()
            startActivityAndWait(foxholeLauncherIntent())
            device.waitForIdle()
            dismissAlphaNoticeIfPresent()
            scrollDashboard()
            openSettingsAndReturn()
            openDashboardSettingsTrafficRoundTrip()
            openSettingsDetail(
                rowTag = "settings_dns_action",
                detailTag = "dns_settings_screen",
                labels = listOf("DNS"),
            )
            openSettingsDetail(
                rowTag = "settings_traffic_action",
                detailTag = "traffic_settings_screen",
                labels = listOf("Network", "Сеть"),
            )
            openSettingsDetail(
                rowTag = "settings_statistics_action",
                detailTag = "statistics_settings_screen",
                labels = listOf("Statistics", "Статистика"),
            )
            openSettingsDetail(
                rowTag = "settings_security_action",
                detailTag = "security_settings_screen",
                labels = listOf("Security", "Безопасность"),
            )
            openSettingsDetail(
                rowTag = "settings_application_action",
                detailTag = "application_settings_screen",
                labels = listOf("App settings", "Настройки приложения"),
            )
            openSettingsDetail(
                rowTag = "settings_privacy_route_action",
                detailTag = "privacy_route_settings_screen",
                labels = listOf("TOR routing", "TOR-маршрутизация"),
                optional = true,
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

    private fun MacrobenchmarkScope.openDashboardSettingsTrafficRoundTrip() {
        openSettingsDetail(
            rowTag = "settings_traffic_action",
            detailTag = "traffic_settings_screen",
            labels = listOf("Network", "Сеть"),
        )
        clickDashboardBottomNav()
        waitForDashboard()
    }

    private fun MacrobenchmarkScope.openSettingsDetail(
        rowTag: String,
        detailTag: String,
        labels: List<String>,
        optional: Boolean = false,
    ) {
        openSettingsHome()
        resetSettingsScrollToTop()
        var row = findByTestTag(rowTag) ?: findByAnyText(labels)
        var findAttempts = 0
        while (row == null && findAttempts < SETTINGS_FIND_ATTEMPTS) {
            swipeSettingsListUp()
            row = findByTestTag(rowTag) ?: findByAnyText(labels)
            findAttempts++
        }
        if (row == null) {
            if (optional) {
                resetSettingsScrollToTop()
                return
            }
            error("Baseline profile: settings row $rowTag was not found; ${visibleStateForDebug()}")
        }
        if (!clickCenter(row)) {
            val refreshed = findByTestTag(rowTag) ?: findByAnyText(labels)
            if (refreshed == null) {
                if (optional) {
                    resetSettingsScrollToTop()
                    return
                }
                error("Baseline profile: settings row $rowTag went stale; ${visibleStateForDebug()}")
            }
            clickCenter(refreshed)
        }
        val detailOpened = waitForTestTag(detailTag) || waitForAnyText(labels.filter { it != "DNS" })
        if (!detailOpened) {
            if (optional) {
                resetSettingsScrollToTop()
                return
            }
            error("Baseline profile: detail $detailTag did not open; ${visibleStateForDebug()}")
        }
        device.pressBack()
        device.waitForIdle()
        check(waitForSettingsHome()) {
            "Baseline profile: settings home did not return after $detailTag; ${visibleStateForDebug()}"
        }
    }

    private fun dismissAlphaNoticeIfPresent() {
        if (!waitForTestTag("alpha_notice_sheet") && findByAnyText(ALPHA_NOTICE_LABELS) == null) {
            return
        }
        val confirm = findByAnyText(ALPHA_NOTICE_CONFIRM_LABELS) ?: return
        clickCenter(confirm)
        device.waitForIdle()
    }

    private fun swipeSettingsListUp() {
        val centerX = device.displayWidth / 2
        val upperY = (device.displayHeight * UPPER_SWIPE_Y_RATIO).toInt()
        val lowerY = (device.displayHeight * LOWER_SWIPE_Y_RATIO).toInt()
        device.swipe(centerX, lowerY, centerX, upperY, SWIPE_STEPS)
        device.waitForIdle()
    }

    private fun resetSettingsScrollToTop() {
        val centerX = device.displayWidth / 2
        val upperY = (device.displayHeight * UPPER_SWIPE_Y_RATIO).toInt()
        val lowerY = (device.displayHeight * LOWER_SWIPE_Y_RATIO).toInt()
        repeat(SETTINGS_RESET_SCROLL_ATTEMPTS) {
            device.swipe(centerX, upperY, centerX, lowerY, SWIPE_STEPS)
            device.waitForIdle()
        }
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
            ensureFoxholeForeground()
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
            clickSettingsBottomNav()
            if (waitForSettingsHome()) {
                return
            }
            if (attempt == 0) {
                device.pressBack()
            }
            device.waitForIdle()
        }
        error("Baseline profile: settings home did not open; ${visibleStateForDebug()}")
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
        findByTestTag("bottom_nav_settings")?.let { node ->
            if (clickCenter(node)) {
                return
            }
        }
        findByAnyText(BOTTOM_NAV_SETTINGS_LABELS)?.let { node ->
            if (clickCenter(node)) {
                return
            }
        }
        findByDesc(BOTTOM_NAV_SETTINGS_LABELS)?.let { node ->
            if (clickCenter(node)) {
                return
            }
        }
        if (device.currentPackageName != PACKAGE_NAME) {
            return
        }
        val settingsNavX = (device.displayWidth * SETTINGS_NAV_X_RATIO).toInt()
        val bottomNavY = (device.displayHeight * BOTTOM_NAV_Y_RATIO).toInt()
        device.click(settingsNavX, bottomNavY)
    }

    private fun clickDashboardBottomNav() {
        findByTestTag("bottom_nav_dashboard")?.let { node ->
            if (clickCenter(node)) {
                return
            }
        }
        findByAnyText(BOTTOM_NAV_DASHBOARD_LABELS)?.let { node ->
            if (clickCenter(node)) {
                return
            }
        }
        findByDesc(BOTTOM_NAV_DASHBOARD_LABELS)?.let { node ->
            if (clickCenter(node)) {
                return
            }
        }
        if (device.currentPackageName != PACKAGE_NAME) {
            return
        }
        val dashboardNavX = (device.displayWidth * DASHBOARD_NAV_X_RATIO).toInt()
        val bottomNavY = (device.displayHeight * BOTTOM_NAV_Y_RATIO).toInt()
        device.click(dashboardNavX, bottomNavY)
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

    private fun waitForSettingsHome(): Boolean {
        if (!waitUntil { isSettingsHomeVisible() }) {
            return false
        }
        device.waitForIdle()
        repeat(SETTINGS_SETTLE_POLLS) {
            if (isSettingsHomeVisible() && !isDashboardVisible()) {
                return true
            }
            Thread.sleep(OPEN_POLL_DELAY_MS)
        }
        return isSettingsHomeVisible() && !isDashboardVisible()
    }

    private fun isSettingsHomeVisible(): Boolean =
        findByTestTag("settings_screen") != null ||
            (
                findByAnyText(SETTINGS_HOME_PRIMARY_LABELS) != null &&
                    findByAnyText(SETTINGS_HOME_SECONDARY_LABELS) != null
            ) ||
            (
                findByAnyText(SETTINGS_HOME_TITLE_LABELS) != null &&
                    findByAnyText(SETTINGS_HOME_SECONDARY_LABELS) != null
            )

    private fun MacrobenchmarkScope.ensureFoxholeForeground() {
        device.waitForIdle()
        if (isDashboardVisible() || isSettingsHomeVisible()) {
            return
        }
        startActivityAndWait(foxholeLauncherIntent())
        device.waitForIdle()
        repeat(OPEN_POLL_COUNT) {
            if (isDashboardVisible() || isSettingsHomeVisible()) {
                return
            }
            Thread.sleep(OPEN_POLL_DELAY_MS)
        }
        error("Baseline profile: the app never returned to the foreground; ${visibleStateForDebug()}")
    }

    private fun visibleStateForDebug(): String {
        val visibleTags =
            GENERATOR_DEBUG_TAGS
                .filter { tag -> findByTestTag(tag) != null }
                .joinToString()
                .ifBlank { "none" }
        val visibleLabels =
            GENERATOR_DEBUG_LABELS
                .filter { label -> findByAnyText(listOf(label)) != null }
                .joinToString()
                .ifBlank { "none" }
        return "foreground=${device.currentPackageName} visibleTags=$visibleTags " +
            "visibleLabels=$visibleLabels hierarchy=${dumpWindowHierarchyForDebug()}"
    }

    private fun waitForDashboard(): Boolean =
        waitUntil {
            isDashboardVisible() && findByTestTag("settings_screen") == null
        }

    private fun isDashboardVisible(): Boolean =
        findByTestTag("home_dashboard_list") != null ||
            (findByTestTag("settings_screen") == null && findByAnyText(DASHBOARD_ANCHOR_LABELS) != null)

    private fun waitForAnyText(labels: List<String>): Boolean =
        waitUntil { findByAnyText(labels) != null }

    private fun waitUntil(predicate: () -> Boolean): Boolean {
        repeat(OPEN_POLL_COUNT) {
            if (predicate()) {
                return true
            }
            Thread.sleep(OPEN_POLL_DELAY_MS)
        }
        println("Foxhole baseline-profile wait timed out; hierarchy=${dumpWindowHierarchyForDebug()}")
        return false
    }

    private fun dumpWindowHierarchyForDebug(): String =
        runCatching {
            val file =
                File(
                    InstrumentationRegistry.getInstrumentation().context.cacheDir,
                    "foxhole-baseline-hierarchy.xml",
                )
            device.dumpWindowHierarchy(file)
            file.absolutePath
        }.getOrElse { error ->
            "unavailable:${error.message}"
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

    private fun findByDesc(labels: List<String>) =
        labels.firstNotNullOfOrNull { label -> device.findObject(By.desc(label)) }

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
            putExtra(TEST_TAGS_AS_RESOURCE_ID_EXTRA, true)
        }

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private companion object {
        private const val PACKAGE_NAME = BuildConfig.TARGET_PACKAGE_NAME
        private const val MAIN_ACTIVITY_CLASS_NAME = "com.foxhole.guard.ui.cli.CliMainActivity"
        private const val APP_PICKER_SEARCH_QUERY = "com."
        private const val TEST_TAGS_AS_RESOURCE_ID_EXTRA = "com.foxhole.guard.extra.TEST_TAGS_AS_RESOURCE_ID"
        private const val DASHBOARD_NAV_X_RATIO = 0.25f
        private const val SETTINGS_NAV_X_RATIO = 0.75f
        private const val BOTTOM_NAV_Y_RATIO = 0.93f
        private const val UPPER_SWIPE_Y_RATIO = 0.32f
        private const val LOWER_SWIPE_Y_RATIO = 0.78f
        private const val SWIPE_STEPS = 24
        private const val SETTINGS_FIND_ATTEMPTS = 4
        private const val SETTINGS_RESET_SCROLL_ATTEMPTS = 3
        private const val SETTINGS_SETTLE_POLLS = 20
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
        private val SETTINGS_HOME_TITLE_LABELS = listOf("Settings", "Настройки")
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
        private val ALPHA_NOTICE_LABELS = listOf("Test build", "Тестовая сборка")
        private val ALPHA_NOTICE_CONFIRM_LABELS = listOf("OK", "Ok", "Хорошо")
        private val GENERATOR_DEBUG_TAGS =
            listOf(
                "settings_screen",
                "home_dashboard_list",
                "traffic_settings_screen",
                "dns_settings_screen",
                "routing_apps_picker_screen",
            )
        private val GENERATOR_DEBUG_LABELS =
            listOf(
                "Settings",
                "Настройки",
                "Dashboard",
                "Дашборд",
                "DNS",
            )
    }
}
