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
    protected fun prepareBenchmarkState(ensureMapEnabled: Boolean = false) {
        prepareBenchmarkTargetState(ensureMapEnabled = ensureMapEnabled)
    }

    protected fun assertDashboardVisible() {
        check(waitForDashboardVisible()) {
            "Dashboard did not become visible; ${visibleSettingsState()}"
        }
    }

    protected fun profileSelectorRoundTrip() {
        assertDashboardVisible()
        val facts = findByTestTag(CLI_HOME_FACTS_TAG) ?: findByAnyText(HOME_PRIMARY_ANCHOR_LABELS)
        check(facts != null && clickCenter(facts)) {
            "Home profile facts did not open; ${visibleSettingsState()}"
        }
        device.waitForIdle()
        check(waitForAnyText(PROFILE_SELECTOR_TITLE_LABELS)) {
            "Home profile selector did not appear; ${visibleSettingsState()}"
        }
        device.pressBack()
        device.waitForIdle()
        assertDashboardVisible()
    }

    protected fun openProfilesAndReturn() {
        clickDockItem(
            tag = CLI_DOCK_PROFILES_TAG,
            labels = PROFILES_DOCK_LABELS,
            fallbackXRatio = 0.25f,
        )
        check(waitForScreen(CLI_SCREEN_PROFILES_TAG, PROFILE_SCREEN_ANCHOR_LABELS)) {
            "Profiles screen did not open; ${visibleSettingsState()}"
        }
        device.pressBack()
        device.waitForIdle()
        assertDashboardVisible()
    }

    protected fun openRootMapAndReturn(stressScroll: Boolean = false) {
        clickDockItem(
            tag = CLI_DOCK_MAP_TAG,
            labels = MAP_DOCK_LABELS,
            fallbackXRatio = 0.58f,
        )
        check(
            waitForScreenHandlingStartupSheets(
                CLI_SCREEN_MAP_TAG,
                MAP_DISABLED_LABELS + MAP_CONTENT_ANCHOR_LABELS,
            ),
        ) {
            "Map screen did not open; ${visibleSettingsState()}"
        }
        findByAnyText(MAP_DISABLED_LABELS)?.let {
            val enable = waitForObjectByText(MAP_ENABLE_LABELS)
            check(enable != null && clickCenter(enable)) {
                "Map enable action was unavailable; ${visibleSettingsState()}"
            }
            device.waitForIdle()
        }
        check(waitForScreenHandlingStartupSheets(CLI_SCREEN_MAP_TAG, MAP_CONTENT_ANCHOR_LABELS)) {
            "Map content did not render; ${visibleSettingsState()}"
        }
        if (stressScroll) {
            repeat(3) {
                val centerX = device.displayWidth / 2
                val upperY = (device.displayHeight * UPPER_SWIPE_Y_RATIO).toInt()
                val lowerY = (device.displayHeight * LOWER_SWIPE_Y_RATIO).toInt()
                check(device.swipe(centerX, lowerY, centerX, upperY, SWIPE_STEPS)) {
                    "Map stress swipe was rejected"
                }
                check(device.swipe(centerX, upperY, centerX, lowerY, SWIPE_STEPS)) {
                    "Map stress return swipe was rejected"
                }
                device.waitForIdle()
            }
        }
        device.pressBack()
        device.waitForIdle()
        assertDashboardVisible()
    }

    protected fun rootNavigationMemoryStress() {
        openProfilesAndReturn()
        openRootMapAndReturn(stressScroll = true)
        openDockScreenAndReturn(
            tag = CLI_DOCK_APPS_TAG,
            labels = APPS_DOCK_LABELS,
            fallbackXRatio = 0.42f,
            screenTag = CLI_SCREEN_APPS_TAG,
            screenLabels = APP_SCREEN_ANCHOR_LABELS,
        )
        openDockScreenAndReturn(
            tag = CLI_DOCK_STATS_TAG,
            labels = STATS_DOCK_LABELS,
            fallbackXRatio = 0.75f,
            screenTag = CLI_SCREEN_STATS_TAG,
            screenLabels = STATS_SCREEN_ANCHOR_LABELS,
        )
        openSettingsHome()
        device.pressBack()
        device.waitForIdle()
        assertDashboardVisible()
    }

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
        if (target.tag == "settings_statistics_action") {
            clickDockItem(
                tag = CLI_DOCK_STATS_TAG,
                labels = STATS_DOCK_LABELS,
                fallbackXRatio = 0.75f,
            )
            return waitForScreen(CLI_SCREEN_STATS_TAG, STATS_SCREEN_ANCHOR_LABELS)
        }
        repeat(SETTINGS_FIND_ATTEMPTS) { attempt ->
            val row = findSettingsTarget(target)
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
        waitForScreen(target.detailTag, currentSettingsExpandedLabels(target))

    private fun findSettingsTarget(target: SettingsDetailTarget): UiObject2? =
        findByTestTag(target.tag) ?: findByAnyText(currentSettingsTargetLabels(target))

    private fun currentSettingsTargetLabels(target: SettingsDetailTarget): List<String> =
        when (target.tag) {
            "settings_traffic_action" -> SETTINGS_NETWORK_SECTION_LABELS
            "settings_dns_action" -> SETTINGS_DNS_SECTION_LABELS
            "settings_security_action" -> SETTINGS_SECURITY_SECTION_LABELS
            "settings_application_action" -> SETTINGS_APPLICATION_SECTION_LABELS
            "settings_expert_action" -> SETTINGS_MODULES_SECTION_LABELS
            "settings_diagnostics_action" -> SETTINGS_JOURNALS_LABELS
            else -> target.labels
        }

    private fun currentSettingsExpandedLabels(target: SettingsDetailTarget): List<String> =
        when (target.tag) {
            "settings_traffic_action" -> SETTINGS_NETWORK_EXPANDED_LABELS
            "settings_dns_action" -> SETTINGS_DNS_EXPANDED_LABELS
            "settings_security_action" -> SETTINGS_SECURITY_EXPANDED_LABELS
            "settings_application_action" -> SETTINGS_APPLICATION_EXPANDED_LABELS
            "settings_expert_action" -> SETTINGS_MODULES_EXPANDED_LABELS
            "settings_diagnostics_action" -> LOGS_SCREEN_ANCHOR_LABELS
            else -> target.labels
        }

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
        labels.firstNotNullOfOrNull { label -> device.findObject(By.text(caseInsensitiveExactPattern(label))) }

    private fun findByAnyDesc(labels: List<String>) =
        labels.firstNotNullOfOrNull { label -> device.findObject(By.desc(caseInsensitiveExactPattern(label))) }

    private fun waitForObjectByText(labels: List<String>): UiObject2? {
        repeat(DETAIL_OPEN_POLL_COUNT) {
            findByAnyText(labels)?.let { return it }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return null
    }

    private fun waitForAnyTextOrDesc(labels: List<String>): Boolean {
        repeat(DETAIL_OPEN_POLL_COUNT) {
            if (findByAnyText(labels) != null || findByAnyDesc(labels) != null) {
                return true
            }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return false
    }

    private fun waitForScreen(tag: String, labels: List<String>): Boolean {
        repeat(DETAIL_OPEN_POLL_COUNT) {
            if (
                findByTestTag(tag) != null ||
                findByAnyText(labels) != null ||
                findByAnyDesc(labels) != null
            ) {
                return true
            }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return false
    }

    private fun waitForScreenHandlingStartupSheets(tag: String, labels: List<String>): Boolean {
        repeat(DETAIL_OPEN_POLL_COUNT) {
            findByAnyText(STARTUP_SHEET_CLOSE_LABELS)?.let { close ->
                if (clickCenter(close)) {
                    device.waitForIdle()
                }
            }
            if (
                findByTestTag(tag) != null ||
                findByAnyText(labels) != null ||
                findByAnyDesc(labels) != null
            ) {
                return true
            }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return false
    }

    private fun caseInsensitiveExactPattern(label: String): Pattern =
        Pattern.compile(
            "^${Pattern.quote(label)}$",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
        )

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
        error("No tappable app row was found after filtering; ${visibleSettingsState()}")
    }

    protected fun appsPickerSearchRoundTrip() {
        assertDashboardVisible()
        clickDockItem(
            tag = CLI_DOCK_APPS_TAG,
            labels = APPS_DOCK_LABELS,
            fallbackXRatio = 0.42f,
        )
        check(waitForScreen(CLI_SCREEN_APPS_TAG, APP_SCREEN_ANCHOR_LABELS)) {
            "Scenarios screen did not open; ${visibleSettingsState()}"
        }
        val addApps = waitForObjectByText(APP_PICKER_OPEN_LABELS)
        check(addApps != null && clickCenter(addApps)) {
            "App picker action did not open; ${visibleSettingsState()}"
        }
        device.waitForIdle()
        val search = waitForEditableField()
        check(search != null) {
            "App picker filter field did not appear; ${visibleSettingsState()}"
        }
        search.setText(APP_PICKER_SEARCH_QUERY)
        device.waitForIdle()
        Thread.sleep(200L)
        toggleFirstUnlockedAppInPicker()
        device.waitForIdle()
        toggleFirstUnlockedAppInPicker()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
        check(waitForScreen(CLI_SCREEN_APPS_TAG, APP_SCREEN_ANCHOR_LABELS)) {
            "Scenarios screen did not return after app picker; ${visibleSettingsState()}"
        }
        device.pressBack()
        device.waitForIdle()
        assertDashboardVisible()
    }

    private fun waitForEditableField(): UiObject2? {
        repeat(DETAIL_OPEN_POLL_COUNT) {
            device.findObject(By.clazz("android.widget.EditText"))?.let { return it }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return null
    }

    private fun waitForExternalActivity(): Boolean {
        repeat(DETAIL_OPEN_POLL_COUNT) {
            val currentPackage = device.currentPackageName
            if (currentPackage != null && currentPackage != PACKAGE_NAME) {
                return true
            }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return false
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

    private fun isDashboardVisible() =
        findByTestTag(CLI_SCREEN_HOME_TAG) != null ||
            findByTestTag("home_dashboard_list") != null ||
            (
                findByAnyText(HOME_PRIMARY_ANCHOR_LABELS) != null &&
                    findByAnyText(HOME_SECONDARY_ANCHOR_LABELS) != null
            )

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
        clickDockItem(
            tag = CLI_DOCK_SETTINGS_TAG,
            labels = BOTTOM_NAV_SETTINGS_LABELS,
            fallbackXRatio = SETTINGS_NAV_X_RATIO,
        )
    }

    protected fun clickDashboardBottomNav() {
        clickDockItem(
            tag = CLI_DOCK_HOME_TAG,
            labels = BOTTOM_NAV_DASHBOARD_LABELS,
            fallbackXRatio = DASHBOARD_NAV_X_RATIO,
        )
    }

    private fun clickDockItem(
        tag: String,
        labels: List<String>,
        fallbackXRatio: Float,
    ) {
        val node = findByTestTag(tag) ?: findByAnyDesc(labels) ?: findByAnyText(labels)
        if (node != null && clickCenter(node)) {
            return
        }
        check(device.currentPackageName == PACKAGE_NAME) {
            "Cannot use dock coordinate fallback outside FoxHole; ${visibleSettingsState()}"
        }
        val x = (device.displayWidth * fallbackXRatio).toInt()
        val y = (device.displayHeight * BOTTOM_NAV_Y_RATIO).toInt()
        check(device.click(x, y)) {
            "Dock coordinate fallback was rejected for $tag"
        }
    }

    private fun openDockScreenAndReturn(
        tag: String,
        labels: List<String>,
        fallbackXRatio: Float,
        screenTag: String,
        screenLabels: List<String>,
    ) {
        assertDashboardVisible()
        clickDockItem(tag = tag, labels = labels, fallbackXRatio = fallbackXRatio)
        device.waitForIdle()
        check(waitForScreen(screenTag, screenLabels)) {
            "Dock screen $screenTag did not open; ${visibleSettingsState()}"
        }
        device.pressBack()
        device.waitForIdle()
        assertDashboardVisible()
    }

    private fun dismissStartupSheets() {
        repeat(BENCHMARK_SETUP_DISMISS_ATTEMPTS) {
            val close = findByAnyText(STARTUP_SHEET_CLOSE_LABELS) ?: return
            check(clickCenter(close)) {
                "Startup sheet close action went stale; ${visibleSettingsState()}"
            }
            device.waitForIdle()
        }
    }

    protected fun openImportFilePickerAndReturn() {
        if (clickTestTag("home_import_action")) {
            device.waitForIdle()
            check(clickTestTag("home_import_from_file_action")) {
                "Legacy import file action did not appear; ${visibleSettingsState()}"
            }
        } else {
            clickDockItem(
                tag = CLI_DOCK_PROFILES_TAG,
                labels = PROFILES_DOCK_LABELS,
                fallbackXRatio = 0.25f,
            )
            check(waitForScreen(CLI_SCREEN_PROFILES_TAG, PROFILE_SCREEN_ANCHOR_LABELS)) {
                "Profiles screen did not open for file permission flow; ${visibleSettingsState()}"
            }
            val fileAction = waitForObjectByText(PROFILE_IMPORT_FILE_LABELS)
            check(fileAction != null && clickCenter(fileAction)) {
                "Profile file picker action was unavailable; ${visibleSettingsState()}"
            }
        }
        device.waitForIdle()
        check(waitForExternalActivity()) {
            "System document picker did not open; ${visibleSettingsState()}"
        }
        device.pressBack()
        device.waitForIdle()
        if (!isDashboardVisible()) {
            device.pressBack()
            device.waitForIdle()
        }
        assertDashboardVisible()
    }

    protected fun clickConnectAndReturnFromVpnPermission() {
        assertDashboardVisible()
        val connectButton =
            findByTestTag("home_connect_button")
                ?: findByTestTag(CLI_HOME_PRIMARY_ACTION_TAG)
                ?: findByAnyText(HOME_PRIMARY_ACTION_LABELS)
        check(connectButton != null && clickCenter(connectButton)) {
            "Home primary action was unavailable; ${visibleSettingsState()}"
        }
        device.waitForIdle()
        if (device.currentPackageName != PACKAGE_NAME) {
            handleRuntimePermissionDialog(approve = false)
            if (device.currentPackageName != PACKAGE_NAME) {
                device.pressBack()
                device.waitForIdle()
            }
        }
        if (!isDashboardVisible()) {
            device.pressBack()
            device.waitForIdle()
        }
        assertDashboardVisible()
    }

    protected fun openTrafficMapDetailsAndReturn() {
        if (findTrafficMapDetailsAction() == null) {
            openRootMapAndReturn()
            return
        }
        if (!clickTrafficMapDetailsAction() && !waitForTrafficMapDetailVisible()) {
            error("Traffic map details action did not navigate; ${visibleSettingsState()}")
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

internal fun prepareBenchmarkTargetState(ensureMapEnabled: Boolean = false) {
    BenchmarkTargetSetup(
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()),
    ).prepare(ensureMapEnabled = ensureMapEnabled)
}

private class BenchmarkTargetSetup(
    private val device: UiDevice,
) {
    fun prepare(ensureMapEnabled: Boolean) {
        device.pressHome()
        device.executeShellCommand("am force-stop $PACKAGE_NAME")
        device.executeShellCommand(
            "am start -W -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER -n $PACKAGE_NAME/$MAIN_ACTIVITY_CLASS_NAME",
        )
        device.waitForIdle()
        waitForStableScreen(
            allowOnboardingSkip = true,
            ready = ::homeReady,
            failureMessage = "Benchmark setup could not settle on Home after first-run UI",
        )
        if (ensureMapEnabled) {
            enableAndWarmMapThroughUi()
        }
        device.pressHome()
        device.waitForIdle()
    }

    private fun enableAndWarmMapThroughUi() {
        clickDock(labels = MAP_DOCK_LABELS, fallbackXRatio = 0.58f)
        waitForStableScreen(
            ready = {
                findText(MAP_DISABLED_LABELS) != null ||
                    findText(MAP_CONTENT_ANCHOR_LABELS) != null
            },
            failureMessage = "Benchmark setup could not open Map",
        )
        findText(MAP_DISABLED_LABELS)?.let {
            val enable = waitForText(MAP_ENABLE_LABELS)
            check(enable != null && clickCenter(enable)) {
                "Benchmark setup could not enable Map"
            }
            device.waitForIdle()
        }
        waitForStableScreen(
            ready = { findText(MAP_CONTENT_ANCHOR_LABELS) != null },
            failureMessage = "Benchmark setup enabled Map but its content did not render",
        )
        clickDock(labels = HOME_DOCK_LABELS, fallbackXRatio = DASHBOARD_NAV_X_RATIO)
        waitForStableScreen(
            ready = ::homeReady,
            failureMessage = "Benchmark setup could not return from Map to Home",
        )
    }

    private fun waitForStableScreen(
        allowOnboardingSkip: Boolean = false,
        ready: () -> Boolean,
        failureMessage: String,
    ) {
        var stablePolls = 0
        var lastState = "target not ready"
        repeat(BENCHMARK_SETUP_POLL_COUNT) {
            val handledAction = handleTransientUi(allowOnboardingSkip)
            if (handledAction != null) {
                stablePolls = 0
                lastState = "handled $handledAction"
            } else if (ready()) {
                stablePolls += 1
                lastState = "ready $stablePolls/$BENCHMARK_SETUP_STABLE_POLL_COUNT"
                if (stablePolls >= BENCHMARK_SETUP_STABLE_POLL_COUNT) {
                    return
                }
            } else {
                stablePolls = 0
                lastState = "target not ready"
            }
            Thread.sleep(BENCHMARK_UI_POLL_DELAY_MS)
        }
        error("$failureMessage; package=${device.currentPackageName}; lastState=$lastState")
    }

    private fun handleTransientUi(allowOnboardingSkip: Boolean): String? {
        if (allowOnboardingSkip) {
            findText(ONBOARDING_SKIP_LABELS)?.let { skip ->
                clickTransientAction(skip)
                return "onboarding skip"
            }
        }
        findText(RUNTIME_PERMISSION_DENY_LABELS)?.let { deny ->
            clickTransientAction(deny)
            return "runtime permission"
        }
        findText(STARTUP_SHEET_CLOSE_LABELS)?.let { close ->
            clickTransientAction(close)
            return "startup sheet"
        }
        return null
    }

    private fun clickTransientAction(node: UiObject2) {
        if (clickCenter(node)) {
            device.waitForIdle()
        }
    }

    private fun clickDock(labels: List<String>, fallbackXRatio: Float) {
        val node = findDesc(labels) ?: findText(labels)
        if (node != null && clickCenter(node)) {
            device.waitForIdle()
            return
        }
        val x = (device.displayWidth * fallbackXRatio).toInt()
        val y = (device.displayHeight * BOTTOM_NAV_Y_RATIO).toInt()
        check(device.click(x, y)) { "Benchmark dock coordinate fallback was rejected" }
        device.waitForIdle()
    }

    private fun homeReady(): Boolean =
        device.currentPackageName == PACKAGE_NAME &&
            findText(HOME_PRIMARY_ANCHOR_LABELS) != null &&
            findText(HOME_SECONDARY_ANCHOR_LABELS) != null

    private fun waitForText(labels: List<String>): UiObject2? {
        repeat(BENCHMARK_SETUP_POLL_COUNT) {
            findText(labels)?.let { return it }
            Thread.sleep(BENCHMARK_UI_POLL_DELAY_MS)
        }
        return null
    }

    private fun findText(labels: List<String>): UiObject2? =
        device.findObject(By.text(exactPattern(labels)))

    private fun findDesc(labels: List<String>): UiObject2? =
        device.findObject(By.desc(exactPattern(labels)))

    private fun exactPattern(labels: List<String>): Pattern =
        Pattern.compile(
            labels.joinToString(prefix = "^(?:", postfix = ")$", separator = "|") { label ->
                Pattern.quote(label)
            },
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
        )

    private fun clickCenter(node: UiObject2): Boolean =
        try {
            val center = node.visibleCenter
            device.click(center.x, center.y)
            true
        } catch (_: StaleObjectException) {
            false
        }
}
