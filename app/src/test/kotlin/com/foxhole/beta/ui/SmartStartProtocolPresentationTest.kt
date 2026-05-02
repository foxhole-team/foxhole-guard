package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SmartStartProtocolPresentationTest {
    @Test
    fun `recommended candidate keeps measured status instead of replacing status text`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = true,
                latencyMs = 80L,
                latencyDown = false,
                latencyUnavailable = false,
            )

        assertEquals(SmartStartProtocolStatus.AVAILABLE, presentation.status)
        assertEquals(
            LatencyQuality.FAST,
            classifyVpnLatency(latencyMs = 80L, failed = false, unavailable = false),
        )
    }

    @Test
    fun `available status is used for a candidate with normal data`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = true,
                latencyMs = 420L,
                latencyDown = false,
                latencyUnavailable = false,
            )

        assertEquals(SmartStartProtocolStatus.AVAILABLE, presentation.status)
    }

    @Test
    fun `slow status is used for high latency candidates`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = true,
                latencyMs = 900L,
                latencyDown = false,
                latencyUnavailable = false,
            )

        assertEquals(SmartStartProtocolStatus.SLOW, presentation.status)
    }

    @Test
    fun `recently failed status wins over stale recommendation`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = true,
                latencyMs = null,
                latencyDown = true,
                latencyUnavailable = false,
            )

        assertEquals(SmartStartProtocolStatus.RECENTLY_FAILED, presentation.status)
    }

    @Test
    fun `no data status is used when no latency exists`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = true,
                latencyMs = null,
                latencyDown = false,
                latencyUnavailable = false,
            )

        assertEquals(SmartStartProtocolStatus.NO_DATA, presentation.status)
    }

    @Test
    fun `unavailable status is used when analysis ran but latency was unavailable`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = true,
                latencyMs = null,
                latencyDown = false,
                latencyUnavailable = true,
            )

        assertEquals(SmartStartProtocolStatus.UNAVAILABLE, presentation.status)
    }

    @Test
    fun `disabled status wins over stale recommendation and failure state`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = false,
                latencyMs = null,
                latencyDown = true,
                latencyUnavailable = true,
            )

        assertEquals(SmartStartProtocolStatus.DISABLED, presentation.status)
        assertEquals(SmartStartProtocolDisabledReason.MANUAL_OFF, presentation.disabledReason)
    }

    @Test
    fun `ui latency display is capped at three digits`() {
        assertEquals(1L, boundedDisplayLatencyMs(0L))
        assertEquals(430L, boundedDisplayLatencyMs(430L))
        assertEquals(999L, boundedDisplayLatencyMs(2_181L))
    }

    @Test
    fun `single profile protocol latency keys match dashboard metric keys`() {
        assertEquals("vless", protocolLatencyOptionId(ProtocolHint.VLESS))
        assertEquals("shadowsocks", protocolLatencyOptionId(ProtocolHint.SHADOWSOCKS))
        assertEquals("hysteria2", protocolLatencyOptionId(ProtocolHint.HYSTERIA2))
        assertEquals(null, protocolLatencyOptionId(ProtocolHint.UNKNOWN))
        assertEquals(null, protocolLatencyOptionId(ProtocolHint.SING_BOX))
    }

    @Test
    fun `favorite and recommended stars collapse to best instead of three stars`() {
        assertEquals(
            2,
            smartProfileConditionStarCount(
                favorite = true,
                recommended = true,
                topRecommended = true,
            ),
        )
        assertEquals(
            2,
            smartProfileConditionStarCount(
                favorite = true,
                recommended = true,
                topRecommended = false,
            ),
        )
        assertEquals(
            2,
            smartProfileConditionStarCount(
                favorite = false,
                recommended = true,
                topRecommended = true,
            ),
        )
        assertEquals(
            1,
            smartProfileConditionStarCount(
                favorite = true,
                recommended = false,
                topRecommended = false,
            ),
        )
    }

    @Test
    fun `dashboard menu keeps compact status rows without detailed metrics table`() {
        val layout = resolveSmartStartProtocolMenuLayout(showMetricsTable = false)

        assertFalse(layout.showHeader)
        assertFalse(layout.showDetailedMetrics)
        assertTrue(layout.showCompactStatusRows)
    }

    @Test
    fun `profiles menu keeps detailed metrics rows`() {
        val layout = resolveSmartStartProtocolMenuLayout(showMetricsTable = true)

        assertTrue(layout.showHeader)
        assertTrue(layout.showDetailedMetrics)
        assertFalse(layout.showCompactStatusRows)
    }

    @Test
    fun `smart profile legend footer stays left aligned without bottom gutter`() {
        val compact =
            resolveSmartStartProtocolLegendFooterLayout(
                resolveSmartStartProtocolMenuLayout(showMetricsTable = false),
            )
        val detailed =
            resolveSmartStartProtocolLegendFooterLayout(
                resolveSmartStartProtocolMenuLayout(showMetricsTable = true),
            )

        assertFalse(compact.centered)
        assertFalse(detailed.centered)
        assertEquals(1, compact.bottomPaddingDp)
        assertEquals(1, detailed.bottomPaddingDp)
        assertEquals(4, compact.topPaddingDp)
        assertEquals(5, detailed.topPaddingDp)
    }

    @Test
    fun `smart profile menu width uses protocol legend and hint bases`() {
        assertEquals(320f, smartProfileMenuWidthBasisPx(protocolAndLegendWidthPx = 320f, hintWidthPx = 240f))
        assertEquals(340f, smartProfileMenuWidthBasisPx(protocolAndLegendWidthPx = 260f, hintWidthPx = 340f))
    }

    @Test
    fun `manual protocol selector width uses row and legend bases`() {
        assertEquals(260f, protocolSelectorWidthBasisPx(protocolLabelWidthPx = 260f, legendWidthPx = 180f))
        assertEquals(220f, protocolSelectorWidthBasisPx(protocolLabelWidthPx = 190f, legendWidthPx = 220f))
    }

    @Test
    fun `english and russian copy keeps recommended wording as badge only`() {
        val enStrings = resourceText("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
        val ruStrings = resourceText("src/main/res/values-ru/strings.xml", "app/src/main/res/values-ru/strings.xml")

        assertEquals("Recommended", stringValue(enStrings, "smart_profile_menu_recommended_badge"))
        assertEquals("Рекомендовано", stringValue(ruStrings, "smart_profile_menu_recommended_badge"))
        assertFalse(enStrings.contains("name=\"smart_start_protocol_status_recommended\""))
        assertFalse(ruStrings.contains("name=\"smart_start_protocol_status_recommended\""))
    }

    @Test
    fun `dashboard and protocol management copy matches current product wording`() {
        val enStrings = resourceText("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
        val ruStrings = resourceText("src/main/res/values-ru/strings.xml", "app/src/main/res/values-ru/strings.xml")

        assertEquals("VPN server information", stringValue(enStrings, "home_network_connection_info_title"))
        assertEquals("Current IP address", stringValue(enStrings, "home_network_current_ip_title"))
        assertEquals("Connection status", stringValue(enStrings, "home_network_profile_info_title"))
        assertEquals("Country:", stringValue(enStrings, "home_network_country_label"))
        assertEquals("City:", stringValue(enStrings, "home_network_city_label"))
        assertEquals("IP:", stringValue(enStrings, "home_network_ip_label"))
        assertEquals("Provider:", stringValue(enStrings, "home_network_provider_label"))
        assertEquals("Time:", stringValue(enStrings, "home_network_connect_time_label"))
        assertEquals("Current session", stringValue(enStrings, "home_session_traffic_title"))
        assertEquals("Volume over:", stringValue(enStrings, "home_total_traffic_title"))
        assertEquals("Import VPN links/configs", stringValue(enStrings, "import_from_clipboard_summary"))
        assertEquals("Choose a configuration on this device", stringValue(enStrings, "import_from_file_summary"))
        assertEquals("Scan a QR code to add a VPN configuration", stringValue(enStrings, "scan_qr_code_summary"))
        assertEquals("Manage VPN protocols", stringValue(enStrings, "smart_profile_menu_title"))
        assertEquals("Protocol", stringValue(enStrings, "smart_profile_menu_protocol_column"))
        assertEquals("Status", stringValue(enStrings, "smart_profile_menu_status_column"))
        assertEquals("Server ping", stringValue(enStrings, "smart_profile_menu_server_ping_column"))
        assertEquals("Latency %1\$s", stringValue(enStrings, "smart_profile_menu_latency_column"))
        assertFalse(enStrings.contains("name=\"smart_profile_menu_dashboard_on_column\""))
        assertFalse(enStrings.contains("name=\"smart_profile_menu_on_column\""))
        assertEquals(
            "Tap a protocol to turn it on/off\\nRefresh periodically to improve connection quality",
            stringValue(enStrings, "smart_profile_metrics_refresh_hint"),
        )
        assertEquals(
            "Tap a protocol to turn it on/off\\nRefresh periodically to improve connection quality",
            stringValue(enStrings, "smart_profile_metrics_refresh_compact_hint"),
        )
        assertFalse(enStrings.contains("name=\"smart_profile_legend_current\""))
        assertEquals("Fast", stringValue(enStrings, "smart_profile_legend_favorite"))
        assertEquals(
            "Best",
            stringValue(enStrings, "smart_profile_legend_reconnect_recommended"),
        )
        assertFalse(enStrings.contains("name=\"smart_profile_legend_unsafe\""))
        assertFalse(enStrings.contains("name=\"smart_profile_legend_title\""))
        assertFalse(enStrings.contains("name=\"smart_profile_metrics_refreshing\""))
        assertFalse(enStrings.contains("name=\"smart_profile_menu_active_badge\""))
        assertEquals("Updated: %1\$s", stringValue(enStrings, "smart_profile_metrics_last_updated"))
        assertEquals("Never updated", stringValue(enStrings, "smart_profile_metrics_never_updated"))
        assertEquals("Off", stringValue(enStrings, "smart_start_protocol_status_disabled"))
        assertEquals(
            "Profile configuration did not load. Try reopening the profile.",
            stringValue(enStrings, "profile_config_load_timeout"),
        )
        assertEquals(
            "Run analysis of available protocols?",
            stringValue(enStrings, "smart_profile_metrics_refresh_confirm_title"),
        )
        assertEquals("Enable expert settings?", stringValue(enStrings, "expert_unlock_confirm_title"))
        assertEquals("Enable expert settings", stringValue(enStrings, "show_advanced_settings_title"))
        assertEquals("BETA", stringValue(enStrings, "beta_badge"))
        assertEquals("VPN protocol analysis", stringValue(enStrings, "smart_start_first_analysis_title"))
        assertEquals(
            "The first VPN protocol analysis will check every available VPN protocol in this configuration. After Foxhole finds the three best VPN protocols, future connections will use only those. Periodically refresh all available VPN protocols in Smart start settings to improve connection quality.",
            stringValue(enStrings, "smart_start_first_analysis_body"),
        )
        assertEquals("Continue", stringValue(enStrings, "smart_start_first_analysis_continue"))
        assertEquals("Turn off LAN Proxy?", stringValue(enStrings, "lan_proxy_disable_confirm_title"))
        assertEquals("Quick start", stringValue(enStrings, "help_quick_start_title"))
        assertEquals("Connection modes", stringValue(enStrings, "help_connection_modes_title"))
        assertEquals("Diagnostics and support", stringValue(enStrings, "help_diagnostics_support_title"))

        assertEquals("Информация о сервере VPN", stringValue(ruStrings, "home_network_connection_info_title"))
        assertEquals("Текущий IP адрес", stringValue(ruStrings, "home_network_current_ip_title"))
        assertEquals("Статус соединения", stringValue(ruStrings, "home_network_profile_info_title"))
        assertEquals("Страна:", stringValue(ruStrings, "home_network_country_label"))
        assertEquals("Город:", stringValue(ruStrings, "home_network_city_label"))
        assertEquals("IP:", stringValue(ruStrings, "home_network_ip_label"))
        assertEquals("Провайдер:", stringValue(ruStrings, "home_network_provider_label"))
        assertEquals("Время:", stringValue(ruStrings, "home_network_connect_time_label"))
        assertEquals("Текущая сессия", stringValue(ruStrings, "home_session_traffic_title"))
        assertEquals("Объем за:", stringValue(ruStrings, "home_total_traffic_title"))
        assertEquals("Импортируйте ссылки/конфиги VPN", stringValue(ruStrings, "import_from_clipboard_summary"))
        assertEquals("Выберите конфигурацию на устройстве", stringValue(ruStrings, "import_from_file_summary"))
        assertEquals(
            "Отсканируйте QR-код для добавления конфигурации VPN",
            stringValue(ruStrings, "scan_qr_code_summary"),
        )
        assertEquals("Управление протоколами VPN", stringValue(ruStrings, "smart_profile_menu_title"))
        assertEquals("Протокол", stringValue(ruStrings, "smart_profile_menu_protocol_column"))
        assertEquals("Статус", stringValue(ruStrings, "smart_profile_menu_status_column"))
        assertEquals("Server ping", stringValue(ruStrings, "smart_profile_menu_server_ping_column"))
        assertEquals("Latency %1\$s", stringValue(ruStrings, "smart_profile_menu_latency_column"))
        assertFalse(ruStrings.contains("name=\"smart_profile_menu_dashboard_on_column\""))
        assertFalse(ruStrings.contains("name=\"smart_profile_menu_on_column\""))
        assertEquals(
            "Нажмите на протокол для включения/отключения\\nПериодически обновляйте для улучшения коннекта",
            stringValue(ruStrings, "smart_profile_metrics_refresh_hint"),
        )
        assertEquals(
            "Нажмите на протокол для включения/отключения\\nПериодически обновляйте для улучшения коннекта",
            stringValue(ruStrings, "smart_profile_metrics_refresh_compact_hint"),
        )
        assertFalse(ruStrings.contains("name=\"smart_profile_legend_current\""))
        assertEquals("Быстрый", stringValue(ruStrings, "smart_profile_legend_favorite"))
        assertEquals(
            "Лучший",
            stringValue(ruStrings, "smart_profile_legend_reconnect_recommended"),
        )
        assertFalse(ruStrings.contains("name=\"smart_profile_legend_unsafe\""))
        assertFalse(ruStrings.contains("name=\"smart_profile_legend_title\""))
        assertFalse(ruStrings.contains("name=\"smart_profile_metrics_refreshing\""))
        assertFalse(ruStrings.contains("name=\"smart_profile_menu_active_badge\""))
        assertEquals("Обновлено: %1\$s", stringValue(ruStrings, "smart_profile_metrics_last_updated"))
        assertEquals("Никогда не обновлялся", stringValue(ruStrings, "smart_profile_metrics_never_updated"))
        assertEquals("Отключено", stringValue(ruStrings, "smart_start_protocol_status_disabled"))
        assertEquals(
            "Конфигурация профиля не загрузилась. Откройте профиль снова.",
            stringValue(ruStrings, "profile_config_load_timeout"),
        )
        assertEquals(
            "Выполнить анализ доступных протоколов?",
            stringValue(ruStrings, "smart_profile_metrics_refresh_confirm_title"),
        )
        assertEquals("Включить экспертные настройки?", stringValue(ruStrings, "expert_unlock_confirm_title"))
        assertEquals("Включить экспертные настройки", stringValue(ruStrings, "show_advanced_settings_title"))
        assertEquals("BETA", stringValue(ruStrings, "beta_badge"))
        assertEquals("Анализ протоколов VPN", stringValue(ruStrings, "smart_start_first_analysis_title"))
        assertEquals(
            "Первый анализ доступных протоколов VPN будет выполнен по всем протоколам VPN в конфигурации. После определения трех лучших протоколов VPN подключение будет выполняться только по ним. Периодически обновляйте все доступные протоколы VPN в настройках Смарт старт для улучшения качества соединения.",
            stringValue(ruStrings, "smart_start_first_analysis_body"),
        )
        assertEquals("Продолжить", stringValue(ruStrings, "smart_start_first_analysis_continue"))
        assertEquals("Выключить LAN Proxy?", stringValue(ruStrings, "lan_proxy_disable_confirm_title"))
        assertEquals("Быстрый старт", stringValue(ruStrings, "help_quick_start_title"))
        assertEquals("Режимы подключения", stringValue(ruStrings, "help_connection_modes_title"))
        assertEquals("Диагностика и поддержка", stringValue(ruStrings, "help_diagnostics_support_title"))
    }

    private fun resourceText(vararg candidates: String): String =
        candidates
            .asSequence()
            .map(::File)
            .first { file -> file.isFile }
            .readText()

    private fun stringValue(
        content: String,
        name: String,
    ): String {
        val pattern = Regex("""<string name="$name">([^<]+)</string>""")
        return requireNotNull(pattern.find(content)) { "missing string resource $name" }.groupValues[1]
    }
}
