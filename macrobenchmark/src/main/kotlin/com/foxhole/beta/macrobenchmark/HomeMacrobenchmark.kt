package com.foxhole.beta.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
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
@OptIn(ExperimentalMetricApi::class)
class HomeMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startup() =
        measureStartup(StartupMode.COLD)

    @Test
    fun warmStartup() =
        measureStartup(StartupMode.WARM)

    private fun measureStartup(startupMode: StartupMode) =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics =
                listOf(StartupTimingMetric()) +
                    traceMetrics(HOME_FIRST_COMPOSITION_TRACE),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = startupMode,
        ) {
            pressHome()
            startActivityAndWait()
        }

    @Test
    fun homeScroll() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = frameMetricsWithTrace(HOME_FIRST_COMPOSITION_TRACE),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait(foxholeLauncherIntent())
                device.waitForIdle()
            },
        ) {
            val centerX = device.displayWidth / 2
            val upperY = (device.displayHeight * UPPER_SWIPE_Y_RATIO).toInt()
            val lowerY = (device.displayHeight * LOWER_SWIPE_Y_RATIO).toInt()
            val dashboardNavX = (device.displayWidth * DASHBOARD_NAV_X_RATIO).toInt()
            val settingsNavX = (device.displayWidth * SETTINGS_NAV_X_RATIO).toInt()
            val bottomNavY = (device.displayHeight * BOTTOM_NAV_Y_RATIO).toInt()
            device.click(settingsNavX, bottomNavY)
            device.waitForIdle()
            device.click(dashboardNavX, bottomNavY)
            device.waitForIdle()
            device.swipe(centerX, lowerY, centerX, upperY, SWIPE_STEPS)
            device.waitForIdle()
            device.swipe(centerX, upperY, centerX, lowerY, SWIPE_STEPS)
            device.waitForIdle()
        }

    @Test
    fun bottomNavigationRoundTrip() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = frameMetricsWithTrace(SETTINGS_NAVIGATION_TRACE),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait(foxholeLauncherIntent())
                device.waitForIdle()
            },
        ) {
            clickSettingsBottomNav()
            device.waitForIdle()
            clickDashboardBottomNav()
            device.waitForIdle()
        }

    @Test
    fun settingsTrafficTransition() =
        measureSettingsDetailTransition(
            SettingsDetailTarget(
                tag = "settings_traffic_action",
                detailTag = "traffic_settings_screen",
                labels = listOf("Network", "Сеть"),
                tapYRatio = 0.18f,
            ),
        )

    @Test
    fun settingsDnsTransition() =
        measureSettingsDetailTransition(
            SettingsDetailTarget(
                tag = "settings_dns_action",
                detailTag = "dns_settings_screen",
                labels = listOf("DNS"),
                tapYRatio = 0.24f,
            ),
        )

    @Test
    fun settingsSmartStartTransition() =
        measureSettingsDetailTransition(
            SettingsDetailTarget(
                tag = "settings_smart_start_action",
                detailTag = "smart_start_settings_screen",
                labels = listOf("Smart start", "Смарт старт", "Умный старт"),
                tapYRatio = 0.12f,
            ),
        )

    @Test
    fun settingsRoutingAppsPickerSearch() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = frameMetricsWithTrace(APP_PICKER_FILTER_TRACE, APP_ICON_LOAD_TRACE, SETTINGS_NAVIGATION_TRACE),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait(foxholeLauncherIntent())
                device.waitForIdle()
            },
        ) {
            openSettingsHome()
            if (
                !openSettingsDetail(
                    SettingsDetailTarget(
                        tag = "settings_routing_apps_action",
                        detailTag = "routing_apps_screen",
                        labels = listOf("Apps", "Приложения"),
                        tapYRatio = 0.50f,
                    ),
                )
            ) {
                return@measureRepeated
            }
            val addButton =
                findByTestTag("routing_apps_add_exception_action")
                    ?: device.findObject(By.text("Add"))
                    ?: device.findObject(By.text("Добавить"))
                    ?: error("Routing app add button missing; ${visibleSettingsState()}")
            clickCenter(addButton)
            if (!waitForAnyText(APP_PICKER_ANCHOR_LABELS) && !waitForTestTag("routing_apps_picker_screen")) {
                return@measureRepeated
            }
            findByTestTag("routing_apps_picker_search")?.setText(APP_PICKER_SEARCH_QUERY)
                ?: return@measureRepeated
            device.waitForIdle()
            toggleFirstUnlockedAppInPicker()
            device.waitForIdle()
            toggleFirstUnlockedAppInPicker()
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
        }
    }

    @Test
    fun dashboardTrafficMapOpen() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics =
                frameMetricsWithTrace(
                    TRAFFIC_MAP_LOAD_SHAPES_TRACE,
                    TRAFFIC_MAP_RENDER_LAND_BITMAP_TRACE,
                    TRAFFIC_MAP_RENDER_HIGHLIGHT_BITMAP_TRACE,
                    TRAFFIC_MAP_BUILD_ROUTES_TRACE,
                    TRAFFIC_MAP_DRAW_TRACE,
                ),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait(foxholeLauncherIntent())
                device.waitForIdle()
            },
        ) {
            clickSettingsBottomNav()
            device.waitForIdle()
            clickDashboardBottomNav()
            device.waitForIdle()
            waitForDashboardVisible()
            val centerX = device.displayWidth / 2
            val upperY = (device.displayHeight * UPPER_SWIPE_Y_RATIO).toInt()
            val lowerY = (device.displayHeight * LOWER_SWIPE_Y_RATIO).toInt()
            repeat(DASHBOARD_TRAFFIC_MAP_WARM_SCROLLS) {
                device.swipe(centerX, lowerY, centerX, upperY, SWIPE_STEPS)
                device.waitForIdle()
            }
            openTrafficMapDetailsAndReturn()
        }

    @Test
    fun dashboardTrafficMapStress() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics =
                frameMetricsWithTrace(
                    TRAFFIC_MAP_LOAD_SHAPES_TRACE,
                    TRAFFIC_MAP_RENDER_LAND_BITMAP_TRACE,
                    TRAFFIC_MAP_RENDER_HIGHLIGHT_BITMAP_TRACE,
                    TRAFFIC_MAP_BUILD_ROUTES_TRACE,
                    TRAFFIC_MAP_DRAW_TRACE,
                ),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = StartupMode.WARM,
            setupBlock = {
                setBatterySaver(enabled = false)
                setNightMode("no")
                pressHome()
                startActivityAndWait(foxholeLauncherIntent(trafficMapStress = true))
                device.waitForIdle()
            },
        ) {
            try {
                waitForDashboardVisible()
                openTrafficMapDetailsAndReturn()
                setNightMode("yes")
                device.waitForIdle()
                openTrafficMapDetailsAndReturn()
                setBatterySaver(enabled = true)
                device.waitForIdle()
                scrollDashboardToTrafficMapDetailsAction()
            } finally {
                setBatterySaver(enabled = false)
                setNightMode("no")
                device.waitForIdle()
            }
        }

    @Test
    fun dashboardTrafficMapStartVpnStress() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics =
                frameMetricsWithTrace(
                    TRAFFIC_MAP_LOAD_SHAPES_TRACE,
                    TRAFFIC_MAP_RENDER_LAND_BITMAP_TRACE,
                    TRAFFIC_MAP_RENDER_HIGHLIGHT_BITMAP_TRACE,
                    TRAFFIC_MAP_BUILD_ROUTES_TRACE,
                    TRAFFIC_MAP_DRAW_TRACE,
                ),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = StartupMode.WARM,
            setupBlock = {
                setBatterySaver(enabled = false)
                setNightMode("no")
                pressHome()
                startActivityAndWait(foxholeLauncherIntent(trafficMapStress = true))
                device.waitForIdle()
            },
        ) {
            try {
                waitForDashboardVisible()
                clickConnectAndReturnFromVpnPermission()
                ensureFoxholeForeground()
                waitForDashboardVisible()
                device.waitForIdle()
                openTrafficMapDetailsAndReturn()
            } finally {
                setBatterySaver(enabled = false)
                setNightMode("no")
                device.waitForIdle()
            }
        }

    @Test
    fun settingsSecurityTransition() =
        measureSettingsDetailTransition(
            SettingsDetailTarget(
                tag = "settings_security_action",
                detailTag = "security_settings_screen",
                labels = listOf("Security", "Безопасность"),
                tapYRatio = 0.38f,
            ),
        )

    @Test
    fun settingsApplicationTransition() =
        measureSettingsDetailTransition(
            SettingsDetailTarget(
                tag = "settings_application_action",
                detailTag = "application_settings_screen",
                labels = listOf("App settings", "Настройки приложения"),
                tapYRatio = 0.62f,
            ),
        )

    @Test
    fun settingsExpertTransition() =
        measureSettingsDetailTransition(
            SettingsDetailTarget(
                tag = "settings_expert_action",
                detailTag = "expert_settings_screen",
                labels = listOf("Expert Settings", "Экспертные настройки"),
                optional = true,
            ),
        )

    @Test
    fun settingsDiagnosticsTransition() =
        measureSettingsDetailTransition(
            SettingsDetailTarget(
                tag = "settings_diagnostics_action",
                detailTag = "diagnostics_settings_screen",
                labels = listOf("Logs", "Журналы"),
                tapYRatio = 0.68f,
            ),
        )

    @Test
    fun settingsStatisticsTransition() =
        measureSettingsDetailTransition(
            SettingsDetailTarget(
                tag = "settings_statistics_action",
                detailTag = "statistics_settings_screen",
                labels = listOf("Statistics", "Статистика"),
                tapYRatio = 0.74f,
            ),
        )

    @Test
    fun permissionFlow() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = frameMetricsWithTrace(SETTINGS_NAVIGATION_TRACE),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                revokeNotificationPermissionIfPossible()
                startActivityAndWait(foxholeLauncherIntent())
                device.waitForIdle()
            },
        ) {
            handleRuntimePermissionDialog(approve = false)
            waitForDashboardVisible()
            openImportFilePickerAndReturn()
            clickConnectAndReturnFromVpnPermission()
            openSettingsHome()
            device.pressBack()
            device.waitForIdle()
        }

    private fun measureSettingsDetailTransition(target: SettingsDetailTarget) {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = frameMetricsWithTrace(SETTINGS_NAVIGATION_TRACE),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait(foxholeLauncherIntent())
                device.waitForIdle()
            },
        ) {
            openSettingsHome()
            if (!openSettingsDetail(target)) {
                return@measureRepeated
            }
            device.pressBack()
            device.waitForIdle()
        }
    }

    private fun openSettingsHome() {
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
            swipeDashboardToSettings()
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

    private fun ensureFoxholeForeground() {
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

    private fun openSettingsDetail(target: SettingsDetailTarget): Boolean {
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

    private fun waitForSettingsDetail(target: SettingsDetailTarget): Boolean {
        return waitForAnyText(target.labels) || waitForTestTag(target.detailTag)
    }

    private fun waitForTestTag(tag: String): Boolean {
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

    private fun waitForAnyText(labels: List<String>): Boolean {
        repeat(DETAIL_OPEN_POLL_COUNT) {
            if (findByAnyText(labels) != null) {
                return true
            }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return false
    }

    private fun toggleFirstUnlockedAppInPicker() {
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
            )

    private fun isDashboardVisible() =
        findByTestTag("home_dashboard_list") != null || findByAnyText(DASHBOARD_ANCHOR_LABELS) != null

    private fun waitForDashboardVisible(): Boolean {
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

    private fun waitForSettingsHomeVisible(): Boolean {
        repeat(SETTINGS_HOME_OPEN_POLL_COUNT) {
            if (isSettingsHomeVisible()) {
                return true
            }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return false
    }

    private fun visibleSettingsState(): String {
        val visibleTags =
            SETTINGS_DEBUG_TAGS
                .filter { tag -> findByTestTag(tag) != null }
                .joinToString()
        val visibleLabels =
            SETTINGS_DEBUG_LABELS
                .filter { label -> device.findObject(By.text(label)) != null }
                .joinToString()
        return "visibleTags=${visibleTags.ifBlank { "none" }} visibleLabels=${visibleLabels.ifBlank { "none" }}"
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

    private fun openImportFilePickerAndReturn() {
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

    private fun clickConnectAndReturnFromVpnPermission() {
        val connectButton = findByTestTag("home_connect_button") ?: return
        if (clickCenter(connectButton)) {
            device.waitForIdle()
            handleRuntimePermissionDialog(approve = false)
            device.pressBack()
            device.waitForIdle()
        }
    }

    private fun openTrafficMapDetailsAndReturn() {
        if (findTrafficMapDetailsActionAfterScroll() == null) {
            check(waitForDashboardVisible()) {
                "Dashboard unavailable before traffic map benchmark fallback; ${visibleSettingsState()}"
            }
            return
        }
        if (!clickTrafficMapDetailsAction()) {
            check(waitForDashboardVisible()) {
                "Dashboard unavailable after traffic map details click fallback; ${visibleSettingsState()}"
            }
            return
        }
        device.waitForIdle()
        check(waitForTestTag("traffic_map_detail_screen")) {
            "Traffic map detail screen did not open; ${visibleSettingsState()}"
        }
        check(waitForTestTag("traffic_map_detail_world_map") || waitForTestTag("traffic_map_detail_world_map_loading")) {
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
                if (waitForTestTag("traffic_map_detail_screen")) {
                    return true
                }
            }
            val refreshedDetailsAction = findTrafficMapDetailsActionAfterScroll() ?: return@repeat
            if (clickCenter(refreshedDetailsAction)) {
                device.waitForIdle()
                if (waitForTestTag("traffic_map_detail_screen")) {
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

    private fun findTrafficMapDetailsAction(): UiObject2? =
        TRAFFIC_MAP_DETAILS_ACTION_LABELS.firstNotNullOfOrNull { label ->
            device.findObject(By.desc(label))
        } ?: findByTestTag("home_traffic_map_details_action")

    private fun scrollDashboardToTrafficMapDetailsAction() {
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

    private fun swipeDashboardToSettings() {
        val startX = (device.displayWidth * ROOT_SWIPE_START_X_RATIO).toInt()
        val endX = (device.displayWidth * ROOT_SWIPE_END_X_RATIO).toInt()
        val centerY = (device.displayHeight * ROOT_SWIPE_Y_RATIO).toInt()
        device.swipe(startX, centerY, endX, centerY, SWIPE_STEPS)
    }

    private fun clickTestTag(tag: String): Boolean {
        val node = findByTestTag(tag) ?: return false
        return clickCenter(node)
    }

    private fun handleRuntimePermissionDialog(approve: Boolean) {
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

    private fun revokeNotificationPermissionIfPossible() {
        runCatching {
            device.executeShellCommand("pm revoke $PACKAGE_NAME android.permission.POST_NOTIFICATIONS")
        }
    }

    private fun foxholeLauncherIntent(trafficMapStress: Boolean = false) =
        Intent(Intent.ACTION_MAIN).apply {
            setClassName(PACKAGE_NAME, MAIN_ACTIVITY_CLASS_NAME)
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (trafficMapStress) {
                putExtra(TRAFFIC_MAP_BENCHMARK_STRESS_EXTRA, TRAFFIC_MAP_BENCHMARK_STRESS_MAX_LOAD)
            }
        }

    private fun clickCenter(node: UiObject2): Boolean =
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

    private fun setBatterySaver(enabled: Boolean) {
        runCatching {
            device.executeShellCommand("cmd power set-mode ${if (enabled) 1 else 0}")
        }
    }

    private fun setNightMode(mode: String) {
        runCatching {
            device.executeShellCommand("cmd uimode night $mode")
        }
    }

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private data class SettingsDetailTarget(
        val tag: String,
        val detailTag: String,
        val labels: List<String>,
        val tapYRatio: Float? = null,
        val optional: Boolean = false,
    )

    private companion object {
        private const val PACKAGE_NAME = BuildConfig.TARGET_PACKAGE_NAME
        private const val MAIN_ACTIVITY_CLASS_NAME = "com.foxhole.beta.MainActivity"
        private const val SHORT_ITERATIONS = 3
        private const val RELEASE_ITERATIONS = 10
        private const val BENCHMARK_ITERATIONS_ARGUMENT = "foxhole.benchmarkIterations"
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
        private const val SETTINGS_FIND_ATTEMPTS = 4
        private const val SETTINGS_RESET_SCROLL_ATTEMPTS = 3
        private const val SETTINGS_HOME_OPEN_POLL_COUNT = 60
        private const val DETAIL_OPEN_POLL_COUNT = 120
        private const val DETAIL_OPEN_POLL_DELAY_MS = 50L
        private const val DASHBOARD_TRAFFIC_MAP_WARM_SCROLLS = 2
        private const val TRAFFIC_MAP_CARD_SCROLL_ATTEMPTS = 8
        private const val TRAFFIC_MAP_ACTION_CLICK_ATTEMPTS = 4
        private const val APP_PICKER_SEARCH_QUERY = "com."
        private const val APP_PICKER_ROW_FIND_ATTEMPTS = 5
        private const val APP_PICKER_ROW_FIND_DELAY_MS = 100L
        private val RESOURCE_TEST_TAGS_AVAILABLE = PACKAGE_NAME.endsWith(".debug")
        private const val HOME_FIRST_COMPOSITION_TRACE = "HomeScreen first composition"
        private const val SETTINGS_NAVIGATION_TRACE = "Settings/navigation"
        private const val APP_PICKER_FILTER_TRACE = "AppPicker/filter"
        private const val APP_ICON_LOAD_TRACE = "AppIcon/load"
        private const val TRAFFIC_MAP_LOAD_SHAPES_TRACE = "TrafficMap/loadShapes"
        private const val TRAFFIC_MAP_RENDER_LAND_BITMAP_TRACE = "TrafficMap/renderLandBitmap"
        private const val TRAFFIC_MAP_RENDER_HIGHLIGHT_BITMAP_TRACE = "TrafficMap/renderHighlightBitmap"
        private const val TRAFFIC_MAP_BUILD_ROUTES_TRACE = "TrafficMap/buildRoutes"
        private const val TRAFFIC_MAP_DRAW_TRACE = "TrafficMap/draw"
        private const val BASELINE_PROFILE_MODE_ARGUMENT = "foxhole.baselineProfileMode"
        private const val TRAFFIC_MAP_BENCHMARK_STRESS_EXTRA =
            "com.foxhole.beta.extra.TRAFFIC_MAP_BENCHMARK_STRESS"
        private const val TRAFFIC_MAP_BENCHMARK_STRESS_MAX_LOAD = "max_load"
        private val SETTINGS_HOME_PRIMARY_LABELS = listOf("Smart start", "Смарт старт", "Умный старт")
        private val SETTINGS_HOME_SECONDARY_LABELS = listOf("Network", "Сеть", "DNS")
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
        private val APP_PICKER_ANCHOR_LABELS =
            listOf(
                "Applications",
                "Приложения",
                "Search",
                "Поиск",
            )
        private val BOTTOM_NAV_SETTINGS_LABELS = listOf("Settings", "Настройки")
        private val BOTTOM_NAV_DASHBOARD_LABELS = listOf("Dashboard", "Дашборд")
        private val TRAFFIC_MAP_DETAILS_ACTION_LABELS =
            listOf("Open traffic map details", "Открыть детали карты трафика")
        private val SETTINGS_DEBUG_TAGS =
            listOf(
                "settings_screen",
                "home_dashboard_list",
                "traffic_settings_screen",
                "dns_settings_screen",
                "security_settings_screen",
                "application_settings_screen",
                "expert_settings_screen",
                "diagnostics_settings_screen",
                "statistics_settings_screen",
            )
        private val SETTINGS_DEBUG_LABELS =
            listOf(
                "Settings",
                "Network",
                "DNS",
                "Security",
                "App settings",
                "Logs",
                "Statistics",
            )
        private val BENCHMARK_COMPILATION_MODE =
            CompilationMode.Partial(
                baselineProfileMode = benchmarkBaselineProfileMode(),
                warmupIterations = 1,
            )

        private fun benchmarkBaselineProfileMode(): BaselineProfileMode =
            when (
                InstrumentationRegistry
                    .getArguments()
                    .getString(BASELINE_PROFILE_MODE_ARGUMENT)
                    ?.lowercase()
            ) {
                "require" -> BaselineProfileMode.Require
                "use_if_available", "useifavailable" -> BaselineProfileMode.UseIfAvailable
                else -> BaselineProfileMode.Disable
            }

        private fun benchmarkIterations(): Int =
            when (
                InstrumentationRegistry
                    .getArguments()
                    .getString(BENCHMARK_ITERATIONS_ARGUMENT)
                    ?.lowercase()
            ) {
                "release" -> RELEASE_ITERATIONS
                else -> SHORT_ITERATIONS
            }
    }
}

@OptIn(ExperimentalMetricApi::class)
private fun frameMetricsWithTrace(vararg traceSections: String) =
    listOf(FrameTimingMetric()) + traceMetrics(*traceSections)

@OptIn(ExperimentalMetricApi::class)
private fun traceMetrics(vararg traceSections: String) =
    traceSections.map { section ->
        TraceSectionMetric(
            sectionName = section,
            mode = TraceSectionMetric.Mode.Sum,
            label = section.traceMetricLabel(),
        )
    }

private fun String.traceMetricLabel(): String =
    replace("/", " ")
        .split(" ")
        .filter(String::isNotBlank)
        .joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }
