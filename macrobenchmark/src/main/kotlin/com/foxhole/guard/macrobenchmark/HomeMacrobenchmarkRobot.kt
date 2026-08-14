package com.foxhole.guard.macrobenchmark

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.io.File
import java.util.regex.Pattern

abstract class HomeMacrobenchmarkRobot {
    protected fun openSettingsHome() {
        ensureFoxholeForeground()
        repeat(OPEN_SETTINGS_ATTEMPTS) { attempt ->
            if (isDashboardVisible()) {
                clickSettingsBottomNav()
                if (settleSettingsHome()) {
                    return
                }
            }
            if (settleSettingsHome()) {
                return
            }
            clickSettingsBottomNav()
            if (settleSettingsHome()) {
                return
            }
            clickSettingsBottomNav()
            if (settleSettingsHome()) {
                return
            }
            if (attempt == 0) {
                device.pressBack()
            }
            device.waitForIdle()
        }
        error("Settings home did not open; ${visibleSettingsState()}")
    }

    protected fun ensureFoxholeForeground() {
        device.waitForIdle()
        if (isDashboardOrSettingsVisible()) {
            return
        }
        device.executeShellCommand("am start -W -n $PACKAGE_NAME/$MAIN_ACTIVITY_CLASS_NAME")
        device.waitForIdle()
        repeat(DETAIL_OPEN_POLL_COUNT) {
            if (isDashboardOrSettingsVisible()) {
                return
            }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
    }

    private fun isDashboardOrSettingsVisible(): Boolean =
        isDashboardVisible() || isSettingsHomeVisible()

    protected fun openSettingsDetail(target: SettingsDetailTarget): Boolean {
        repeat(SETTINGS_FIND_ATTEMPTS) { attempt ->
            val row =
                findByTestTag(target.tag)
                    ?: target.labels.firstNotNullOfOrNull { label -> device.findObject(By.text(label)) }
            row?.let {
                if (clickCenter(row)) {
                    device.waitForIdle()
                    if (waitForSettingsDetail(target)) {
                        return true
                    }
                    if (!isSettingsHomeVisible()) {
                        error("Settings detail screen did not open: ${target.detailTag}; ${visibleSettingsState()}")
                    }
                }
                device.waitForIdle()
                return@repeat
            }
            if (attempt < SETTINGS_FIND_ATTEMPTS - 1) {
                swipeSettingsUp()
            }
        }
        if (target.optional) {
            return false
        }
        target.tapYRatio?.let { tapYRatio ->
            resetSettingsScrollToTop()
            device.click(device.displayWidth / 2, (device.displayHeight * tapYRatio).toInt())
            device.waitForIdle()
            if (waitForSettingsDetail(target)) {
                return true
            }
            error("Settings detail screen did not open: ${target.detailTag}; ${visibleSettingsState()}")
        }
        error("Settings detail row was not found: ${target.tag}, ${target.labels.joinToString()}")
    }

    private fun waitForSettingsDetail(target: SettingsDetailTarget): Boolean =
        waitForAnyText(target.labels) || waitForTestTag(target.detailTag)

    protected fun waitForTestTag(tag: String): Boolean {
        if (!RESOURCE_TEST_TAGS_AVAILABLE) {
            return false
        }
        repeat(DETAIL_OPEN_POLL_COUNT) {
            if (findByTestTag(tag) != null) {
                return true
            }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return false
    }

    protected fun findByTestTag(tag: String) =
        if (RESOURCE_TEST_TAGS_AVAILABLE) {
            device.findObject(By.res(tag))
                ?: device.findObject(By.res(PACKAGE_NAME, tag))
                ?: device.findObject(By.res(Pattern.compile(".*${Pattern.quote(tag)}$")))
        } else {
            null
        }

    private fun findByAnyText(labels: List<String>) =
        labels.firstNotNullOfOrNull { label -> device.findObject(By.text(label)) }

    protected fun waitForAnyText(labels: List<String>): Boolean {
        repeat(DETAIL_OPEN_POLL_COUNT) {
            if (findByAnyText(labels) != null) {
                return true
            }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return false
    }

    protected fun toggleFirstUnlockedAppInPicker() {
        repeat(APP_PICKER_ROW_FIND_ATTEMPTS) {
            val firstRow = findFirstAppPickerRow()
            if (firstRow != null && clickCenter(firstRow)) {
                return
            }
            device.waitForIdle()
            Thread.sleep(APP_PICKER_ROW_FIND_DELAY_MS)
        }
    }

    private fun findFirstAppPickerRow(): UiObject2? =
        device.findObjects(By.res(Pattern.compile(".*routing_apps_picker_row_.*")))
            .firstOrNull()
            ?: device.findObjects(By.clazz("android.view.View"))
                .firstOrNull { node ->
                    node.safeText()?.contains(APP_PICKER_SEARCH_QUERY, ignoreCase = true) == true
                }

    private fun UiObject2.safeText(): String? =
        try {
            text
        } catch (_: StaleObjectException) {
            null
        }

    private fun isSettingsHomeVisible() =
        findByTestTag("settings_screen") != null ||
            (
                findByAnyText(SETTINGS_HOME_PRIMARY_LABELS) != null &&
                    findByAnyText(SETTINGS_HOME_SECONDARY_LABELS) != null
            ) ||
            (
                findByAnyText(SETTINGS_HOME_TITLE_LABELS) != null &&
                    findByAnyText(SETTINGS_HOME_SECONDARY_LABELS) != null
            )

    private fun isDashboardVisible() =
        findByTestTag("home_dashboard_list") != null || findByAnyText(DASHBOARD_ANCHOR_LABELS) != null

    protected fun waitForDashboardVisible(): Boolean {
        repeat(DETAIL_OPEN_POLL_COUNT) {
            if (isDashboardVisible()) {
                return true
            }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return false
    }

    private fun settleSettingsHome(): Boolean {
        if (!waitForSettingsHomeVisible()) {
            return false
        }
        resetSettingsScrollToTop()
        return waitForSettingsHomeVisible()
    }

    protected fun waitForSettingsHomeVisible(): Boolean {
        repeat(SETTINGS_HOME_OPEN_POLL_COUNT) {
            if (isSettingsHomeVisible()) {
                return true
            }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return false
    }

    protected fun visibleSettingsState(): String {
        val visibleTags =
            SETTINGS_DEBUG_TAGS
                .filter { tag -> findByTestTag(tag) != null }
                .joinToString()
        val visibleLabels =
            SETTINGS_DEBUG_LABELS
                .filter { label -> device.findObject(By.text(label)) != null }
                .joinToString()
        return "visibleTags=${visibleTags.ifBlank { "none" }} " +
            "visibleLabels=${visibleLabels.ifBlank { "none" }} " +
            "hierarchy=${dumpWindowHierarchyForDebug()}"
    }

    private fun dumpWindowHierarchyForDebug(): String =
        runCatching {
            val file =
                File(
                    InstrumentationRegistry.getInstrumentation().context.cacheDir,
                    "foxhole-macrobenchmark-hierarchy.xml",
                )
            device.dumpWindowHierarchy(file)
            file.absolutePath
        }.getOrElse { error ->
            "unavailable:${error.message}"
        }

    protected fun clickSettingsBottomNav() {
        findByAnyText(BOTTOM_NAV_SETTINGS_LABELS)?.let { node ->
            if (clickCenter(node)) {
                return
            }
        }
        val settingsNavX = (device.displayWidth * SETTINGS_NAV_X_RATIO).toInt()
        val bottomNavY = (device.displayHeight * BOTTOM_NAV_Y_RATIO).toInt()
        device.click(settingsNavX, bottomNavY)
    }

    protected fun clickDashboardBottomNav() {
        findByAnyText(BOTTOM_NAV_DASHBOARD_LABELS)?.let { node ->
            if (clickCenter(node)) {
                return
            }
        }
        val dashboardNavX = (device.displayWidth * DASHBOARD_NAV_X_RATIO).toInt()
        val bottomNavY = (device.displayHeight * BOTTOM_NAV_Y_RATIO).toInt()
        device.click(dashboardNavX, bottomNavY)
    }

    protected fun openImportFilePickerAndReturn() {
        if (!clickTestTag("home_import_action")) {
            return
        }
        device.waitForIdle()
        if (clickTestTag("home_import_from_file_action")) {
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
        } else {
            device.pressBack()
            device.waitForIdle()
        }
    }

    protected fun clickConnectAndReturnFromVpnPermission() {
        val connectButton = findByTestTag("home_connect_button") ?: return
        if (clickCenter(connectButton)) {
            device.waitForIdle()
            handleRuntimePermissionDialog(approve = false)
            device.pressBack()
            device.waitForIdle()
        }
    }

    protected fun openTrafficMapDetailsAndReturn() {
        if (findTrafficMapDetailsActionAfterScroll() == null) {
            check(waitForDashboardVisible()) {
                "Dashboard unavailable before traffic map benchmark fallback; ${visibleSettingsState()}"
            }
            return
        }
        if (!clickTrafficMapDetailsAction() && !waitForTrafficMapDetailVisible()) {
            check(waitForDashboardVisible()) {
                "Dashboard unavailable after traffic map details click fallback; ${visibleSettingsState()}"
            }
            return
        }
        device.waitForIdle()
        check(waitForTrafficMapDetailVisible()) {
            "Traffic map detail screen did not open; ${visibleSettingsState()}"
        }
        check(waitForTrafficMapDetailMapVisible()) {
            "Traffic map detail canvas missing; ${visibleSettingsState()}"
        }
        device.pressBack()
        device.waitForIdle()
        check(waitForDashboardVisible()) {
            "Dashboard did not return after traffic map detail back; ${visibleSettingsState()}"
        }
    }

    private fun clickTrafficMapDetailsAction(): Boolean {
        repeat(TRAFFIC_MAP_ACTION_CLICK_ATTEMPTS) {
            val detailsAction = findTrafficMapDetailsActionAfterScroll() ?: return@repeat
            if (clickObject(detailsAction)) {
                device.waitForIdle()
                if (waitForTrafficMapDetailVisible()) {
                    return true
                }
            }
            val refreshedDetailsAction = findTrafficMapDetailsActionAfterScroll() ?: return@repeat
            if (clickCenter(refreshedDetailsAction)) {
                device.waitForIdle()
                if (waitForTrafficMapDetailVisible()) {
                    return true
                }
            }
            device.waitForIdle()
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return false
    }

    private fun findTrafficMapDetailsActionAfterScroll(): UiObject2? {
        findTrafficMapDetailsAction()?.let { return it }
        if (isDashboardVisible()) {
            scrollDashboardToTrafficMapDetailsAction()
        }
        return findTrafficMapDetailsAction()
    }

    private fun waitForTrafficMapDetailVisible(): Boolean {
        repeat(DETAIL_OPEN_POLL_COUNT) {
            if (findByTestTag("traffic_map_detail_screen") != null || findByAnyText(TRAFFIC_MAP_DETAIL_LABELS) != null) {
                return true
            }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return false
    }

    private fun waitForTrafficMapDetailMapVisible(): Boolean {
        repeat(DETAIL_OPEN_POLL_COUNT) {
            if (
                findByTestTag("traffic_map_detail_world_map") != null ||
                findByTestTag("traffic_map_detail_world_map_loading") != null ||
                findByTestTag("traffic_map_detail_world_map_error") != null ||
                findByAnyText(TRAFFIC_MAP_DETAIL_STATE_LABELS) != null
            ) {
                return true
            }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return false
    }

    private fun findTrafficMapDetailsAction(): UiObject2? =
        TRAFFIC_MAP_DETAILS_ACTION_LABELS.firstNotNullOfOrNull { label ->
            device.findObject(By.desc(label))
        } ?: findByTestTag("home_traffic_map_details_action")

    protected fun scrollDashboardToTrafficMapDetailsAction() {
        val centerX = device.displayWidth / 2
        val upperY = (device.displayHeight * UPPER_SWIPE_Y_RATIO).toInt()
        val lowerY = (device.displayHeight * LOWER_SWIPE_Y_RATIO).toInt()
        repeat(TRAFFIC_MAP_CARD_SCROLL_ATTEMPTS) {
            if (findTrafficMapDetailsAction() != null) {
                return
            }
            device.swipe(centerX, lowerY, centerX, upperY, SWIPE_STEPS)
            device.waitForIdle()
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
    }

    private fun clickTestTag(tag: String): Boolean {
        val node = findByTestTag(tag) ?: return false
        return clickCenter(node)
    }

    protected fun handleRuntimePermissionDialog(approve: Boolean) {
        val pattern =
            if (approve) {
                Pattern.compile("^(OK|Ok|Allow|Разрешить|Да)$")
            } else {
                Pattern.compile("^(Don't allow|Deny|Cancel|Not now|Запретить|Отмена|Не сейчас|Нет)$")
            }
        val button = device.findObject(By.text(pattern)) ?: return
        clickCenter(button)
        device.waitForIdle()
    }

    protected fun revokeNotificationPermissionIfPossible() {
        runCatching {
            device.executeShellCommand("pm revoke $PACKAGE_NAME android.permission.POST_NOTIFICATIONS")
        }
    }

    protected fun foxholeLauncherIntent(trafficMapStress: Boolean = false) =
        Intent(Intent.ACTION_MAIN).apply {
            setClassName(PACKAGE_NAME, MAIN_ACTIVITY_CLASS_NAME)
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(TEST_TAGS_AS_RESOURCE_ID_EXTRA, true)
            if (trafficMapStress) {
                putExtra(TRAFFIC_MAP_BENCHMARK_STRESS_EXTRA, TRAFFIC_MAP_BENCHMARK_STRESS_MAX_LOAD)
            }
        }

    protected fun clickCenter(node: UiObject2): Boolean =
        try {
            val center = node.visibleCenter
            device.click(center.x, center.y)
            true
        } catch (_: StaleObjectException) {
            false
        }

    private fun clickObject(node: UiObject2): Boolean =
        try {
            node.click()
            true
        } catch (_: StaleObjectException) {
            false
        }

    private fun swipeSettingsUp() {
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

    protected fun setBatterySaver(enabled: Boolean) {
        runCatching {
            device.executeShellCommand("cmd power set-mode ${if (enabled) 1 else 0}")
        }
    }

    protected fun setNightMode(mode: String) {
        runCatching {
            device.executeShellCommand("cmd uimode night $mode")
        }
    }

    protected val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
}
