package com.foxhole.guard.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.test.platform.app.InstrumentationRegistry

internal const val PACKAGE_NAME = BuildConfig.TARGET_PACKAGE_NAME
internal const val MAIN_ACTIVITY_CLASS_NAME = "com.foxhole.guard.ui.cli.CliMainActivity"
private const val SHORT_ITERATIONS = 3
private const val RELEASE_ITERATIONS = 10
private const val BENCHMARK_ITERATIONS_ARGUMENT = "foxhole.benchmarkIterations"
// Six equal CLI dock slots: Home is first and Settings is last. These ratios are only the
// semantics-unavailable fallback, but they still need to target the actual navigation items.
internal const val DASHBOARD_NAV_X_RATIO = 0.09f
internal const val SETTINGS_NAV_X_RATIO = 0.91f
internal const val BOTTOM_NAV_Y_RATIO = 0.955f
internal const val UPPER_SWIPE_Y_RATIO = 0.32f
internal const val LOWER_SWIPE_Y_RATIO = 0.78f
internal const val SWIPE_STEPS = 24
internal const val OPEN_SETTINGS_ATTEMPTS = 3
internal const val SETTINGS_FIND_ATTEMPTS = 4
internal const val SETTINGS_RESET_SCROLL_ATTEMPTS = 3
internal const val SETTINGS_HOME_OPEN_POLL_COUNT = 60
internal const val DETAIL_OPEN_POLL_COUNT = 120
internal const val DETAIL_OPEN_POLL_DELAY_MS = 50L
internal const val DASHBOARD_TRAFFIC_MAP_WARM_SCROLLS = 2
internal const val TRAFFIC_MAP_CARD_SCROLL_ATTEMPTS = 8
internal const val TRAFFIC_MAP_ACTION_CLICK_ATTEMPTS = 4
internal const val APP_PICKER_SEARCH_QUERY = "com."
internal const val APP_PICKER_ROW_FIND_ATTEMPTS = 5
internal const val APP_PICKER_ROW_FIND_DELAY_MS = 100L
internal val RESOURCE_TEST_TAGS_AVAILABLE = PACKAGE_NAME.endsWith(".debug")
internal const val HOME_FIRST_COMPOSITION_TRACE = "HomeScreen first composition"
internal const val TRAFFIC_SETTINGS_FIRST_COMPOSITION_TRACE = "TrafficSettings first composition"
internal const val SETTINGS_NAVIGATION_TRACE = "Settings/navigation"
internal const val APP_PICKER_FILTER_TRACE = "AppPicker/filter"
internal const val APP_ICON_LOAD_TRACE = "AppIcon/load"
internal const val TRAFFIC_MAP_LOAD_SHAPES_TRACE = "TrafficMap/loadShapes"
internal const val TRAFFIC_MAP_RENDER_LAND_BITMAP_TRACE = "TrafficMap/renderLandBitmap"
internal const val TRAFFIC_MAP_RENDER_HIGHLIGHT_BITMAP_TRACE = "TrafficMap/renderHighlightBitmap"
internal const val TRAFFIC_MAP_BUILD_ROUTES_TRACE = "TrafficMap/buildRoutes"
internal const val TRAFFIC_MAP_DRAW_TRACE = "TrafficMap/draw"
private const val BASELINE_PROFILE_MODE_ARGUMENT = "foxhole.baselineProfileMode"
internal const val TRAFFIC_MAP_BENCHMARK_STRESS_EXTRA =
    "com.foxhole.guard.extra.TRAFFIC_MAP_BENCHMARK_STRESS"
internal const val TRAFFIC_MAP_BENCHMARK_STRESS_MAX_LOAD = "max_load"
internal const val TEST_TAGS_AS_RESOURCE_ID_EXTRA = "com.foxhole.guard.extra.TEST_TAGS_AS_RESOURCE_ID"
internal val SETTINGS_HOME_PRIMARY_LABELS = listOf("Smart start", "Смарт старт", "Умный старт")
internal val SETTINGS_HOME_TITLE_LABELS = listOf("Settings", "Настройки")
internal val SETTINGS_HOME_SECONDARY_LABELS = listOf("Network", "Сеть", "DNS")
internal val DASHBOARD_ANCHOR_LABELS =
    listOf(
        "FOXHOLE",
        "Dashboard",
        "Дашборд",
        "VPN profile",
        "VPN профиль",
        "Traffic Map",
        "Карта трафика",
    )
internal val APP_PICKER_ANCHOR_LABELS =
    listOf(
        "Applications",
        "Приложения",
        "Search",
        "Поиск",
    )
internal val BOTTOM_NAV_SETTINGS_LABELS = listOf("Settings", "Настройки")
internal val BOTTOM_NAV_DASHBOARD_LABELS = listOf("Dashboard", "Дашборд")
internal val TRAFFIC_MAP_DETAILS_ACTION_LABELS =
    listOf("Open traffic map details", "Открыть детали карты трафика")
internal val TRAFFIC_MAP_DETAIL_LABELS = listOf("Traffic map", "Карта трафика")
internal val TRAFFIC_MAP_DETAIL_STATE_LABELS =
    listOf(
        "Traffic map unavailable",
        "No active connections",
        "Loading traffic map",
        "Map data unavailable",
        "Карта трафика недоступна",
        "Нет активных подключений",
        "Загрузка карты трафика",
        "Данные карты недоступны",
    )
internal val SETTINGS_DEBUG_TAGS =
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
internal val SETTINGS_DEBUG_LABELS =
    listOf(
        "Settings",
        "Network",
        "DNS",
        "Security",
        "App settings",
        "Logs",
        "Statistics",
    )
internal val BENCHMARK_COMPILATION_MODE =
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

internal fun benchmarkIterations(): Int =
    when (
        InstrumentationRegistry
            .getArguments()
            .getString(BENCHMARK_ITERATIONS_ARGUMENT)
            ?.lowercase()
    ) {
        "release" -> RELEASE_ITERATIONS
        else -> SHORT_ITERATIONS
    }
