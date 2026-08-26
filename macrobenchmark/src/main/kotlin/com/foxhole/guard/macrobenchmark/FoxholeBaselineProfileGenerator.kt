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
        prepareBenchmarkTargetState(ensureMapEnabled = true)
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
            openProfilesAndReturn()
            openMapAndReturn()
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

    private fun MacrobenchmarkScope.openProfilesAndReturn() {
        clickDockItem(CLI_DOCK_PROFILES_TAG, PROFILES_DOCK_LABELS, 0.25f)
        check(waitForAnyText(PROFILE_SCREEN_ANCHOR_LABELS)) {
            "Baseline profile: profiles screen did not open; ${visibleStateForDebug()}"
        }
        device.pressBack()
        device.waitForIdle()
        check(waitForDashboard()) {
            "Baseline profile: Home did not return after profiles; ${visibleStateForDebug()}"
        }
    }

    private fun MacrobenchmarkScope.openMapAndReturn() {
        clickDockItem(CLI_DOCK_MAP_TAG, MAP_DOCK_LABELS, 0.58f)
        check(waitForAnyText(MAP_CONTENT_ANCHOR_LABELS)) {
            "Baseline profile: map content did not open; ${visibleStateForDebug()}"
        }
        device.pressBack()
        device.waitForIdle()
        check(waitForDashboard()) {
            "Baseline profile: Home did not return after map; ${visibleStateForDebug()}"
        }
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
        if (rowTag == "settings_statistics_action") {
            clickStatsBottomNav()
            check(waitForAnyDesc(STATS_SCREEN_ANCHOR_LABELS)) {
                "Baseline profile: statistics screen did not open; ${visibleStateForDebug()}"
            }
            clickSettingsBottomNav()
            check(waitForSettingsHome()) {
                "Baseline profile: settings did not return after statistics; ${visibleStateForDebug()}"
            }
            return
        }
        resetSettingsScrollToTop()
        val targetLabels = currentSettingsTargetLabels(rowTag, labels)
        val detailLabels = currentSettingsDetailLabels(rowTag, labels)
        var row = findByTestTag(rowTag) ?: findByAnyText(targetLabels)
        var findAttempts = 0
        while (row == null && findAttempts < SETTINGS_FIND_ATTEMPTS) {
            swipeSettingsListUp()
            row = findByTestTag(rowTag) ?: findByAnyText(targetLabels)
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
            val refreshed = findByTestTag(rowTag) ?: findByAnyText(targetLabels)
            if (refreshed == null) {
                if (optional) {
                    resetSettingsScrollToTop()
                    return
                }
                error("Baseline profile: settings row $rowTag went stale; ${visibleStateForDebug()}")
            }
            clickCenter(refreshed)
        }
        val detailOpened = waitForTestTag(detailTag) || waitForAnyText(detailLabels)
        if (!detailOpened) {
            if (optional) {
                resetSettingsScrollToTop()
                return
            }
            error("Baseline profile: detail $detailTag did not open; ${visibleStateForDebug()}")
        }
        if (isCurrentCollapsibleSettingsTarget(rowTag)) {
            val section = findByAnyText(targetLabels)
            check(section != null && clickCenter(section)) {
                "Baseline profile: settings section $rowTag did not collapse; ${visibleStateForDebug()}"
            }
        } else {
            device.pressBack()
            device.waitForIdle()
        }
        check(waitForSettingsHome()) {
            "Baseline profile: settings home did not return after $detailTag; ${visibleStateForDebug()}"
        }
    }

    private fun currentSettingsTargetLabels(rowTag: String, fallback: List<String>): List<String> =
        when (rowTag) {
            "settings_traffic_action" -> SETTINGS_NETWORK_SECTION_LABELS
            "settings_dns_action" -> SETTINGS_DNS_SECTION_LABELS
            "settings_security_action" -> SETTINGS_SECURITY_SECTION_LABELS
            "settings_application_action" -> SETTINGS_APPLICATION_SECTION_LABELS
            "settings_privacy_route_action" -> SETTINGS_MODULES_SECTION_LABELS
            else -> fallback
        }

    private fun currentSettingsDetailLabels(rowTag: String, fallback: List<String>): List<String> =
        when (rowTag) {
            "settings_traffic_action" -> SETTINGS_NETWORK_EXPANDED_LABELS
            "settings_dns_action" -> SETTINGS_DNS_EXPANDED_LABELS
            "settings_security_action" -> SETTINGS_SECURITY_EXPANDED_LABELS
            "settings_application_action" -> SETTINGS_APPLICATION_EXPANDED_LABELS
            "settings_privacy_route_action" -> SETTINGS_MODULES_EXPANDED_LABELS
            else -> fallback.filter { it != "DNS" }
        }

    private fun isCurrentCollapsibleSettingsTarget(rowTag: String): Boolean =
        rowTag in
            setOf(
                "settings_traffic_action",
                "settings_dns_action",
                "settings_security_action",
                "settings_application_action",
                "settings_privacy_route_action",
            )

    private fun dismissAlphaNoticeIfPresent() {
        if (findByTestTag("alpha_notice_sheet") == null && findByAnyText(ALPHA_NOTICE_LABELS) == null) {
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
        ensureFoxholeForeground()
        clickAppsBottomNav()
        check(waitForAnyText(APP_SCREEN_ANCHOR_LABELS)) {
            "Baseline profile: scenarios screen did not open; ${visibleStateForDebug()}"
        }
        val addAction = findByAnyText(APP_PICKER_OPEN_LABELS)
        check(addAction != null && clickCenter(addAction)) {
            "Baseline profile: app picker action did not open; ${visibleStateForDebug()}"
        }
        val search = waitForEditableField()
        check(search != null) {
            "Baseline profile: app picker filter did not appear; ${visibleStateForDebug()}"
        }
        search.setText(APP_PICKER_SEARCH_QUERY)
        device.waitForIdle()
        Thread.sleep(200L)
        val row = waitForFilteredAppRow()
        check(row != null && clickCenter(row)) {
            "Baseline profile: filtered app row was unavailable; ${visibleStateForDebug()}"
        }
        val refreshed = waitForFilteredAppRow()
        check(refreshed != null && clickCenter(refreshed)) {
            "Baseline profile: filtered app row could not be restored; ${visibleStateForDebug()}"
        }
        device.pressBack()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
        check(waitForDashboard()) {
            "Baseline profile: Home did not return after app picker; ${visibleStateForDebug()}"
        }
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
        clickDockItem(CLI_DOCK_SETTINGS_TAG, SETTINGS_DOCK_LABELS, SETTINGS_NAV_X_RATIO)
    }

    private fun clickDashboardBottomNav() {
        clickDockItem(CLI_DOCK_HOME_TAG, HOME_DOCK_LABELS, DASHBOARD_NAV_X_RATIO)
    }

    private fun clickAppsBottomNav() {
        clickDockItem(CLI_DOCK_APPS_TAG, APPS_DOCK_LABELS, 0.42f)
    }

    private fun clickStatsBottomNav() {
        clickDockItem(CLI_DOCK_STATS_TAG, STATS_DOCK_LABELS, 0.75f)
    }

    private fun clickDockItem(tag: String, labels: List<String>, fallbackXRatio: Float) {
        val node = findByTestTag(tag) ?: findByDesc(labels) ?: findByAnyText(labels)
        if (node != null && clickCenter(node)) {
            return
        }
        check(device.currentPackageName == PACKAGE_NAME) {
            "Baseline profile: dock fallback requested outside FoxHole; ${visibleStateForDebug()}"
        }
        val x = (device.displayWidth * fallbackXRatio).toInt()
        val y = (device.displayHeight * BOTTOM_NAV_Y_RATIO).toInt()
        check(device.click(x, y)) { "Baseline profile: dock fallback was rejected for $tag" }
        device.waitForIdle()
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
        findByTestTag(CLI_SCREEN_SETTINGS_TAG) != null ||
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
            isDashboardVisible() && findByTestTag(CLI_SCREEN_SETTINGS_TAG) == null
        }

    private fun isDashboardVisible(): Boolean =
        findByTestTag(CLI_SCREEN_HOME_TAG) != null ||
            findByTestTag("home_dashboard_list") != null ||
            (
                findByAnyText(HOME_PRIMARY_ANCHOR_LABELS) != null &&
                    findByAnyText(HOME_SECONDARY_ANCHOR_LABELS) != null
            )

    private fun waitForAnyText(labels: List<String>): Boolean =
        waitUntil { findByAnyText(labels) != null }

    private fun waitForAnyDesc(labels: List<String>): Boolean =
        waitUntil { findByDesc(labels) != null }

    private fun waitForEditableField(): UiObject2? {
        repeat(OPEN_POLL_COUNT) {
            device.findObject(By.clazz("android.widget.EditText"))?.let { return it }
            Thread.sleep(OPEN_POLL_DELAY_MS)
        }
        return null
    }

    private fun waitForFilteredAppRow(): UiObject2? {
        repeat(OPEN_POLL_COUNT) {
            device.findObjects(By.clazz("android.view.View"))
                .firstOrNull { node ->
                    runCatching { node.text }
                        .getOrNull()
                        ?.contains(APP_PICKER_SEARCH_QUERY, ignoreCase = true) == true
                }
                ?.let { return it }
            Thread.sleep(OPEN_POLL_DELAY_MS)
        }
        return null
    }

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
        labels.firstNotNullOfOrNull { label -> device.findObject(By.text(exactPattern(label))) }

    private fun findByDesc(labels: List<String>) =
        labels.firstNotNullOfOrNull { label -> device.findObject(By.desc(exactPattern(label))) }

    private fun exactPattern(label: String): Pattern =
        Pattern.compile(
            "^${Pattern.quote(label)}$",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
        )

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
        private const val SETTINGS_SETTLE_POLLS = 20
        private const val OPEN_POLL_COUNT = 80
        private const val OPEN_POLL_DELAY_MS = 50L
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
