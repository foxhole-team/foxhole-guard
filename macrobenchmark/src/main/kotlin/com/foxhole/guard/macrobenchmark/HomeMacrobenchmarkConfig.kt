package com.foxhole.guard.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.test.platform.app.InstrumentationRegistry

internal const val PACKAGE_NAME = BuildConfig.TARGET_PACKAGE_NAME
internal const val MAIN_ACTIVITY_CLASS_NAME = "com.foxhole.guard.ui.cli.CliMainActivity"
private const val SHORT_ITERATIONS = 3
private const val RELEASE_ITERATIONS = 10
private const val BENCHMARK_ITERATIONS_ARGUMENT = "foxhole.benchmarkIterations"
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
internal const val TRAFFIC_MAP_LOAD_SHAPES_TRACE = "TrafficMap/loadShapes"
internal const val TRAFFIC_MAP_BUILD_COUNTRY_REGISTRY_TRACE = "TrafficMap/buildCountryRegistry"
internal const val TRAFFIC_MAP_RENDER_LAND_BITMAP_TRACE = "TrafficMap/renderLandBitmap"
internal const val TRAFFIC_MAP_BUILD_ROUTES_TRACE = "TrafficMap/buildRoutes"
internal const val TRAFFIC_MAP_DRAW_TRACE = "TrafficMap/draw"
private const val BASELINE_PROFILE_MODE_ARGUMENT = "foxhole.baselineProfileMode"
internal const val TRAFFIC_MAP_BENCHMARK_STRESS_EXTRA =
    "com.foxhole.guard.extra.TRAFFIC_MAP_BENCHMARK_STRESS"
internal const val TRAFFIC_MAP_BENCHMARK_STRESS_MAX_LOAD = "max_load"
internal const val TEST_TAGS_AS_RESOURCE_ID_EXTRA = "com.foxhole.guard.extra.TEST_TAGS_AS_RESOURCE_ID"
internal const val CLI_SCREEN_HOME_TAG = "cli_screen_home"
internal const val CLI_SCREEN_PROFILES_TAG = "cli_screen_profiles"
internal const val CLI_SCREEN_APPS_TAG = "cli_screen_apps"
internal const val CLI_SCREEN_MAP_TAG = "cli_screen_map"
internal const val CLI_SCREEN_STATS_TAG = "cli_screen_stats"
internal const val CLI_SCREEN_SETTINGS_TAG = "cli_screen_settings"
internal const val CLI_DOCK_HOME_TAG = "cli_dock_home"
internal const val CLI_DOCK_PROFILES_TAG = "cli_dock_profiles"
internal const val CLI_DOCK_APPS_TAG = "cli_dock_apps"
internal const val CLI_DOCK_MAP_TAG = "cli_dock_map"
internal const val CLI_DOCK_STATS_TAG = "cli_dock_stats"
internal const val CLI_DOCK_SETTINGS_TAG = "cli_dock_settings"
internal const val CLI_HOME_FACTS_TAG = "cli_home_facts"
internal const val CLI_HOME_PRIMARY_ACTION_TAG = "cli_home_primary_action"
internal const val BENCHMARK_SETUP_POLL_COUNT = 600
internal const val BENCHMARK_SETUP_STABLE_POLL_COUNT = 20
internal const val BENCHMARK_SETUP_DISMISS_ATTEMPTS = 4
internal const val BENCHMARK_UI_POLL_DELAY_MS = 50L
internal val ONBOARDING_SKIP_LABELS = listOf("skip setup", "пропустить настройку")
internal val STARTUP_SHEET_CLOSE_LABELS =
    listOf("close", "закрыть", "i understand", "OK", "Ok", "ОК")
internal val RUNTIME_PERMISSION_DENY_LABELS =
    listOf(
        "Don't allow",
        "Don’t allow",
        "Deny",
        "Not now",
        "Запретить",
        "Не разрешать",
        "Не сейчас",
    )
internal val HOME_DOCK_LABELS = listOf("home", "главная")
internal val PROFILES_DOCK_LABELS = listOf("profiles", "профили")
internal val APPS_DOCK_LABELS = listOf("scenarios", "сценарии")
internal val MAP_DOCK_LABELS = listOf("map", "карта")
internal val STATS_DOCK_LABELS = listOf("stats", "статистика")
internal val SETTINGS_DOCK_LABELS = listOf("settings", "настройки")
internal val HOME_PRIMARY_ANCHOR_LABELS =
    listOf("VPN profile", "VPN profile:", "профиль VPN", "профиль VPN:")
internal val HOME_SECONDARY_ANCHOR_LABELS =
    listOf("scenario", "scenario:", "сценарий", "сценарий:")
internal val HOME_PRIMARY_ACTION_LABELS =
    listOf("START", "СТАРТ", "STOP", "СТОП", "RECONNECT", "РЕКОННЕКТ", "RESTART", "РЕСТАРТ")
internal val PROFILE_SCREEN_ANCHOR_LABELS = listOf("VPN profiles", "профили VPN")
internal val PROFILE_IMPORT_FILE_LABELS = listOf("File", "Файл")
internal val PROFILE_SELECTOR_TITLE_LABELS = listOf("VPN profiles", "профили VPN")
internal val APP_SCREEN_ANCHOR_LABELS = listOf("rules for apps", "правила для приложений")
internal val APP_PICKER_OPEN_LABELS = listOf("add apps", "добавить приложения")
internal val MAP_CONTENT_ANCHOR_LABELS = listOf("countries", "страны")
internal val MAP_DISABLED_LABELS =
    listOf("traffic map is off", "модуль карты трафика отключен")
internal val MAP_ENABLE_LABELS = listOf("ENABLE", "ВКЛЮЧИТЬ")
internal val SETTINGS_NETWORK_SECTION_LABELS = listOf("network", "сеть")
internal val SETTINGS_DNS_SECTION_LABELS = listOf("DNS management", "управление DNS")
internal val SETTINGS_SECURITY_SECTION_LABELS = listOf("security", "безопасность")
internal val SETTINGS_APPLICATION_SECTION_LABELS = listOf("application", "приложение")
internal val SETTINGS_MODULES_SECTION_LABELS = listOf("modules", "модули")
internal val SETTINGS_EXTRAS_SECTION_LABELS = listOf("extras", "дополнения")
internal val SETTINGS_JOURNALS_LABELS = listOf("journals", "журналы")
internal val SETTINGS_NETWORK_EXPANDED_LABELS =
    listOf("atomic scenario switching", "атомарное переключение сценариев")
internal val SETTINGS_DNS_EXPANDED_LABELS = listOf("DNS protection", "защита DNS")
internal val SETTINGS_SECURITY_EXPANDED_LABELS =
    listOf("data encryption", "шифрование данных приложения")
internal val SETTINGS_APPLICATION_EXPANDED_LABELS = listOf("Color palette", "Цветовая палитра")
internal val SETTINGS_MODULES_EXPANDED_LABELS = listOf("Tor network access", "доступ к сети Tor")
internal val STATS_SCREEN_ANCHOR_LABELS = listOf("statistics settings", "настройки статистики")
internal val LOGS_SCREEN_ANCHOR_LABELS = listOf("journal settings", "настройки журнала")
internal val SETTINGS_HOME_PRIMARY_LABELS = SETTINGS_NETWORK_SECTION_LABELS
internal val SETTINGS_HOME_TITLE_LABELS = SETTINGS_DOCK_LABELS
internal val SETTINGS_HOME_SECONDARY_LABELS = SETTINGS_DNS_SECTION_LABELS
internal val DASHBOARD_ANCHOR_LABELS =
    listOf(
        "FOXHOLE",
        "home",
        "главная",
        "status",
        "состояние",
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
internal val BOTTOM_NAV_SETTINGS_LABELS = listOf("settings", "настройки")
internal val BOTTOM_NAV_DASHBOARD_LABELS = listOf("home", "главная")
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
        CLI_SCREEN_SETTINGS_TAG,
        CLI_SCREEN_HOME_TAG,
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
