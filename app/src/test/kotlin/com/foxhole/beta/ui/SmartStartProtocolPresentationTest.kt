package com.foxhole.beta.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartStartProtocolPresentationTest {
    @Test
    fun `recommended status wins for the recommended healthy candidate`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = true,
                recommended = true,
                latencyMs = 80L,
                latencyDown = false,
                latencyUnavailable = false,
            )

        assertEquals(SmartStartProtocolStatus.RECOMMENDED, presentation.status)
    }

    @Test
    fun `available status is used for a non recommended candidate with normal data`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = true,
                recommended = false,
                latencyMs = 420L,
                latencyDown = false,
                latencyUnavailable = false,
            )

        assertEquals(SmartStartProtocolStatus.AVAILABLE, presentation.status)
    }

    @Test
    fun `slow status is used only for high latency non recommended candidates`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = true,
                recommended = false,
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
                recommended = true,
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
                recommended = false,
                latencyMs = null,
                latencyDown = false,
                latencyUnavailable = true,
            )

        assertEquals(SmartStartProtocolStatus.NO_DATA, presentation.status)
    }

    @Test
    fun `disabled status wins over recommendation and failure state`() {
        val presentation =
            resolveSmartStartProtocolPresentation(
                included = false,
                recommended = true,
                latencyMs = null,
                latencyDown = true,
                latencyUnavailable = true,
            )

        assertEquals(SmartStartProtocolStatus.DISABLED, presentation.status)
        assertEquals(SmartStartProtocolDisabledReason.MANUAL_OFF, presentation.disabledReason)
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
    fun `english and russian copy uses recommended instead of fastest`() {
        val enStrings = resourceText("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
        val ruStrings = resourceText("src/main/res/values-ru/strings.xml", "app/src/main/res/values-ru/strings.xml")

        assertEquals("Recommended", stringValue(enStrings, "smart_profile_menu_recommended_badge"))
        assertEquals("Recommended", stringValue(enStrings, "smart_start_protocol_status_recommended"))
        assertEquals("Рекомендовано", stringValue(ruStrings, "smart_profile_menu_recommended_badge"))
        assertEquals("Рекомендовано", stringValue(ruStrings, "smart_start_protocol_status_recommended"))
        assertFalse(enStrings.contains("Fastest"))
        assertFalse(ruStrings.contains("Fastest"))
    }

    @Test
    fun `dashboard and protocol management copy matches current product wording`() {
        val enStrings = resourceText("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
        val ruStrings = resourceText("src/main/res/values-ru/strings.xml", "app/src/main/res/values-ru/strings.xml")

        assertEquals("VPN server information", stringValue(enStrings, "home_network_connection_info_title"))
        assertEquals("Current IP address", stringValue(enStrings, "home_network_current_ip_title"))
        assertEquals("Connection status", stringValue(enStrings, "home_network_profile_info_title"))
        assertEquals("Country:", stringValue(enStrings, "home_network_country_label"))
        assertEquals("IP:", stringValue(enStrings, "home_network_ip_label"))
        assertEquals("Provider:", stringValue(enStrings, "home_network_provider_label"))
        assertEquals(
            "Smart start protocols",
            stringValue(enStrings, "smart_profile_menu_title"),
        )
        assertEquals(
            "Manage available protocols. Tap a protocol for on/off.\\nPeriodically refresh Smart start to choose a faster VPN tunnel.",
            stringValue(enStrings, "smart_profile_metrics_refresh_hint"),
        )
        assertEquals(
            "Manage available protocols. Tap a protocol for on/off.\\nPeriodically refresh Smart start to choose a faster VPN tunnel.",
            stringValue(enStrings, "smart_profile_metrics_refresh_compact_hint"),
        )
        assertEquals("favorite", stringValue(enStrings, "smart_profile_legend_favorite"))
        assertEquals(
            "recommended for reconnect",
            stringValue(enStrings, "smart_profile_legend_reconnect_recommended"),
        )
        assertEquals("Updated: %1\$s", stringValue(enStrings, "smart_profile_metrics_last_updated"))
        assertEquals("Disabled", stringValue(enStrings, "smart_start_protocol_status_disabled"))
        assertEquals(
            "Profile configuration did not load. Try reopening the profile.",
            stringValue(enStrings, "profile_config_load_timeout"),
        )

        assertEquals("Информация о сервере VPN", stringValue(ruStrings, "home_network_connection_info_title"))
        assertEquals("Текущий IP адрес", stringValue(ruStrings, "home_network_current_ip_title"))
        assertEquals("Статус соединения", stringValue(ruStrings, "home_network_profile_info_title"))
        assertEquals("Country:", stringValue(ruStrings, "home_network_country_label"))
        assertEquals("IP:", stringValue(ruStrings, "home_network_ip_label"))
        assertEquals("Provider:", stringValue(ruStrings, "home_network_provider_label"))
        assertEquals(
            "Протоколы Smart start",
            stringValue(ruStrings, "smart_profile_menu_title"),
        )
        assertEquals(
            "Управление доступными протоколами. Нажмите на протокол для on/off.\\nПериодически обновляйте Смарт старт для выбора более быстрого туннеля VPN.",
            stringValue(ruStrings, "smart_profile_metrics_refresh_hint"),
        )
        assertEquals(
            "Управление доступными протоколами. Нажмите на протокол для on/off.\\nПериодически обновляйте Смарт старт для выбора более быстрого туннеля VPN.",
            stringValue(ruStrings, "smart_profile_metrics_refresh_compact_hint"),
        )
        assertEquals("избранное", stringValue(ruStrings, "smart_profile_legend_favorite"))
        assertEquals(
            "рекомендованный для переподключения",
            stringValue(ruStrings, "smart_profile_legend_reconnect_recommended"),
        )
        assertEquals("Обновлено: %1\$s", stringValue(ruStrings, "smart_profile_metrics_last_updated"))
        assertEquals("Отключено", stringValue(ruStrings, "smart_start_protocol_status_disabled"))
        assertEquals(
            "Конфигурация профиля не загрузилась. Откройте профиль снова.",
            stringValue(ruStrings, "profile_config_load_timeout"),
        )
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
