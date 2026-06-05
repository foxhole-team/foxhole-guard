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
            iterations = SHORT_ITERATIONS,
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
            iterations = SHORT_ITERATIONS,
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
            iterations = SHORT_ITERATIONS,
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
                labels = listOf("Smart start", "Умный старт"),
                tapYRatio = 0.12f,
            ),
        )

    @Test
    fun settingsRoutingAppsPickerSearch() {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = frameMetricsWithTrace(APP_PICKER_FILTER_TRACE, APP_ICON_LOAD_TRACE, SETTINGS_NAVIGATION_TRACE),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = SHORT_ITERATIONS,
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
            waitForTestTag("routing_apps_picker_screen")
            findByTestTag("routing_apps_picker_search")?.setText(APP_PICKER_SEARCH_QUERY)
                ?: error("Routing app picker search missing; ${visibleSettingsState()}")
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
                    TRAFFIC_MAP_BUILD_ROUTES_TRACE,
                    TRAFFIC_MAP_DRAW_TRACE,
                ),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = SHORT_ITERATIONS,
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
            waitForTestTag("home_dashboard_list")
            val centerX = device.displayWidth / 2
            val upperY = (device.displayHeight * UPPER_SWIPE_Y_RATIO).toInt()
            val lowerY = (device.displayHeight * LOWER_SWIPE_Y_RATIO).toInt()
            repeat(2) {
                device.swipe(centerX, lowerY, centerX, upperY, SWIPE_STEPS)
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

    private fun measureSettingsDetailTransition(target: SettingsDetailTarget) {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = frameMetricsWithTrace(SETTINGS_NAVIGATION_TRACE),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = SHORT_ITERATIONS,
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
        repeat(OPEN_SETTINGS_ATTEMPTS) { attempt ->
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
        return waitForTestTag(target.detailTag)
    }

    private fun waitForTestTag(tag: String): Boolean {
        repeat(DETAIL_OPEN_POLL_COUNT) {
            if (findByTestTag(tag) != null) {
                return true
            }
            Thread.sleep(DETAIL_OPEN_POLL_DELAY_MS)
        }
        return false
    }

    private fun findByTestTag(tag: String) =
        device.findObject(By.res(tag))
            ?: device.findObject(By.res(PACKAGE_NAME, tag))
            ?: device.findObject(By.res(Pattern.compile(".*${Pattern.quote(tag)}$")))

    private fun findByAnyText(labels: List<String>) =
        labels.firstNotNullOfOrNull { label -> device.findObject(By.text(label)) }

    private fun toggleFirstUnlockedAppInPicker() {
        val firstRow =
            device.findObjects(By.res(Pattern.compile(".*routing_apps_picker_row_.*")))
                .firstOrNull()
                ?: device.findObjects(By.clazz("android.view.View"))
                    .firstOrNull { node -> node.text?.contains(APP_PICKER_SEARCH_QUERY, ignoreCase = true) == true }
                ?: return
        clickCenter(firstRow)
    }

    private fun isSettingsHomeVisible() =
        findByTestTag("settings_screen") != null || findByAnyText(SETTINGS_HOME_ANCHOR_LABELS) != null

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

    private fun foxholeLauncherIntent() =
        Intent(Intent.ACTION_MAIN).apply {
            setClassName(PACKAGE_NAME, MAIN_ACTIVITY_CLASS_NAME)
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

    private fun clickCenter(node: UiObject2): Boolean =
        try {
            val center = node.visibleCenter
            device.click(center.x, center.y)
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

    private val device: UiDevice
        get() = UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())

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
        private const val APP_PICKER_SEARCH_QUERY = "com."
        private const val HOME_FIRST_COMPOSITION_TRACE = "HomeScreen first composition"
        private const val SETTINGS_NAVIGATION_TRACE = "Settings/navigation"
        private const val APP_PICKER_FILTER_TRACE = "AppPicker/filter"
        private const val APP_ICON_LOAD_TRACE = "AppIcon/load"
        private const val TRAFFIC_MAP_LOAD_SHAPES_TRACE = "TrafficMap/loadShapes"
        private const val TRAFFIC_MAP_RENDER_LAND_BITMAP_TRACE = "TrafficMap/renderLandBitmap"
        private const val TRAFFIC_MAP_BUILD_ROUTES_TRACE = "TrafficMap/buildRoutes"
        private const val TRAFFIC_MAP_DRAW_TRACE = "TrafficMap/draw"
        private val SETTINGS_HOME_ANCHOR_LABELS = listOf("Smart start", "Умный старт", "DNS")
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
                baselineProfileMode = BaselineProfileMode.Disable,
                warmupIterations = 1,
            )
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
