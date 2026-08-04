package com.foxhole.guard.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class HomeMacrobenchmark : HomeMacrobenchmarkRobot() {
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
                traceSections = listOf(TRAFFIC_SETTINGS_FIRST_COMPOSITION_TRACE),
            ),
        )

    @Test
    fun dashboardSettingsTrafficRoundTrip() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = frameMetricsWithTrace(SETTINGS_NAVIGATION_TRACE, TRAFFIC_SETTINGS_FIRST_COMPOSITION_TRACE),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait(foxholeLauncherIntent())
                device.waitForIdle()
            },
        ) {
            waitForDashboardVisible()
            clickSettingsBottomNav()
            waitForSettingsHomeVisible()
            if (
                !openSettingsDetail(
                    SettingsDetailTarget(
                        tag = "settings_traffic_action",
                        detailTag = "traffic_settings_screen",
                        labels = listOf("Network", "Сеть"),
                        tapYRatio = 0.18f,
                    ),
                )
            ) {
                return@measureRepeated
            }
            device.pressBack()
            device.waitForIdle()
            waitForSettingsHomeVisible()
            clickDashboardBottomNav()
            waitForDashboardVisible()
            device.waitForIdle()
        }

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
            metrics = frameMetricsWithTrace(SETTINGS_NAVIGATION_TRACE, *target.traceSections.toTypedArray()),
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
}
