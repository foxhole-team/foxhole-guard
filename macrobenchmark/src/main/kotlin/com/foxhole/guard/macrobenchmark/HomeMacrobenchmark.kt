package com.foxhole.guard.macrobenchmark

import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class HomeMacrobenchmark : HomeMacrobenchmarkRobot() {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Before
    fun prepareTargetState() {
        prepareBenchmarkState(ensureMapEnabled = true)
    }

    @Test
    fun startup() =
        measureStartup(StartupMode.COLD)

    @Test
    fun warmStartup() =
        measureStartup(StartupMode.WARM)

    @Test
    fun profileSelectorForwardBackMotion() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = frameMetricsWithTrace(),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait(foxholeLauncherIntent())
                device.waitForIdle()
            },
        ) {
            profileSelectorRoundTrip()
        }

    @Test
    fun mapColdOpenMemory() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics =
                memoryFrameMetricsWithTrace(
                    TRAFFIC_MAP_LOAD_SHAPES_TRACE,
                    TRAFFIC_MAP_BUILD_COUNTRY_REGISTRY_TRACE,
                    TRAFFIC_MAP_RENDER_LAND_BITMAP_TRACE,
                    TRAFFIC_MAP_BUILD_ROUTES_TRACE,
                    TRAFFIC_MAP_DRAW_TRACE,
                ),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait(foxholeLauncherIntent())
            assertDashboardVisible()
            openRootMapAndReturn()
        }

    @Test
    fun rootNavigationMemoryStressCuj() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = memoryFrameMetricsWithTrace(),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait(foxholeLauncherIntent())
                device.waitForIdle()
            },
        ) {
            rootNavigationMemoryStress()
        }

    private fun measureStartup(startupMode: StartupMode) =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
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
            metrics = frameMetricsWithTrace(),
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
            clickSettingsBottomNav()
            device.waitForIdle()
            check(waitForSettingsHomeVisible()) { "Settings did not open during Home scroll CUJ" }
            clickDashboardBottomNav()
            device.waitForIdle()
            assertDashboardVisible()
            check(device.swipe(centerX, lowerY, centerX, upperY, SWIPE_STEPS)) {
                "Home upward swipe was rejected"
            }
            device.waitForIdle()
            check(device.swipe(centerX, upperY, centerX, lowerY, SWIPE_STEPS)) {
                "Home downward swipe was rejected"
            }
            device.waitForIdle()
        }

    @Test
    fun bottomNavigationRoundTrip() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = frameMetricsWithTrace(),
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
            check(waitForSettingsHomeVisible()) { "Settings did not open during dock round trip" }
            clickDashboardBottomNav()
            device.waitForIdle()
            assertDashboardVisible()
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
    fun dashboardSettingsTrafficRoundTrip() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = frameMetricsWithTrace(),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait(foxholeLauncherIntent())
                device.waitForIdle()
            },
        ) {
            assertDashboardVisible()
            clickSettingsBottomNav()
            check(waitForSettingsHomeVisible()) { "Settings did not open during Network round trip" }
            check(
                openSettingsDetail(
                    SettingsDetailTarget(
                        tag = "settings_traffic_action",
                        detailTag = "traffic_settings_screen",
                        labels = listOf("Network", "Сеть"),
                        tapYRatio = 0.18f,
                    ),
                ),
            ) { "Network settings CUJ performed no transition; ${visibleSettingsState()}" }
            device.pressBack()
            device.waitForIdle()
            assertDashboardVisible()
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
            metrics = frameMetricsWithTrace(),
            compilationMode = BENCHMARK_COMPILATION_MODE,
            iterations = benchmarkIterations(),
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait(foxholeLauncherIntent())
                device.waitForIdle()
            },
        ) {
            appsPickerSearchRoundTrip()
        }
    }

    @Test
    fun dashboardTrafficMapOpen() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics =
                memoryFrameMetricsWithTrace(
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
            assertDashboardVisible()
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
                memoryFrameMetricsWithTrace(
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
                assertDashboardVisible()
                openTrafficMapDetailsAndReturn()
                setNightMode("yes")
                device.waitForIdle()
                openTrafficMapDetailsAndReturn()
                setBatterySaver(enabled = true)
                device.waitForIdle()
                openRootMapAndReturn()
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
                memoryFrameMetricsWithTrace(
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
                assertDashboardVisible()
                clickConnectAndReturnFromVpnPermission()
                ensureFoxholeForeground()
                assertDashboardVisible()
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
            metrics = frameMetricsWithTrace(),
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
            assertDashboardVisible()
            openImportFilePickerAndReturn()
            clickConnectAndReturnFromVpnPermission()
            openSettingsHome()
            device.pressBack()
            device.waitForIdle()
        }

    private fun measureSettingsDetailTransition(target: SettingsDetailTarget) {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = frameMetricsWithTrace(*target.traceSections.toTypedArray()),
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
            check(openSettingsDetail(target)) {
                "Settings CUJ performed no transition for ${target.tag}; ${visibleSettingsState()}"
            }
            device.pressBack()
            device.waitForIdle()
        }
    }
}
