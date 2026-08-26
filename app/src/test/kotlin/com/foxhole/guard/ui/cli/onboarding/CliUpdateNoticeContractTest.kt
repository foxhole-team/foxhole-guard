package com.foxhole.guard.ui.cli.onboarding

import com.foxhole.core.model.HomeAdditionalInfoCategory
import com.foxhole.core.model.UiSettings
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.withUpdateNoticeChoices
import com.foxhole.guard.core.settings.withUpdateNoticeTrafficMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliUpdateNoticeContractTest {
    @Test
    fun `update notice contains the three 0 1 0 changes in both locales`() {
        assertEquals(
            listOf(
                "App redesign",
                "Traffic display on the Home screen",
                "Improved FoxHole Core network-engine stability",
            ),
            noticeLines(resource("values/strings.xml")),
        )
        assertEquals(
            listOf(
                "Редизайн приложения",
                "Отображение трафика на главной странице",
                "Улучшена стабильность сетевого ядра FoxHole Core",
            ),
            noticeLines(resource("values-ru/strings.xml")),
        )
    }

    @Test
    fun `update notice has three matching icons and both default-on choices`() {
        val items = updateNoticeItems("redesign\nwidget\ncore")
        val source = cli("onboarding/CliUpdateNoticeSheet.kt")

        assertEquals(listOf("redesign", "widget", "core"), items.map(CliQuickStartItem::text))
        assertEquals(
            listOf(R.drawable.lin_star, R.drawable.lin_map, R.drawable.lin_shield),
            items.map(CliQuickStartItem::icon),
        )
        assertTrue(source.contains("onDismiss = viewModel::dismissAlphaNotice"))
        assertTrue(source.contains("R.string.cli_update_notice_home_map"))
        assertTrue(source.contains("R.string.cli_cfg_pixel_art"))
        assertTrue(source.contains("checked = showTrafficMap"))
        assertTrue(source.contains("checked = useFoxholeStyle"))
        assertTrue(source.contains("onDismiss(showTrafficMap, useFoxholeStyle)"))
        assertFalse(source.contains("onRetroThemeRestored"))
        listOf("values/strings.xml", "values-ru/strings.xml").forEach { path ->
            val resource = resource(path)
            assertFalse(resource.contains("cli_update_notice_retro"))
            assertFalse(resource.contains("cli_update_notice_retro_note"))
        }
    }

    @Test
    fun `traffic map choice defaults on and applies both outcomes`() {
        assertTrue(UPDATE_NOTICE_TRAFFIC_MAP_DEFAULT_ENABLED)
        assertTrue(UPDATE_NOTICE_FOXHOLE_STYLE_DEFAULT_ENABLED)

        val enabled = UiSettings(
            showHomeAdditionalInfo = false,
            homeAdditionalInfoCategory = HomeAdditionalInfoCategory.ROUTE,
        ).withUpdateNoticeTrafficMap(enabled = true)
        assertTrue(enabled.showHomeAdditionalInfo)
        assertEquals(HomeAdditionalInfoCategory.MAP, enabled.homeAdditionalInfoCategory)

        val disabled = UiSettings(
            showHomeAdditionalInfo = true,
            homeAdditionalInfoCategory = HomeAdditionalInfoCategory.ROUTE,
        ).withUpdateNoticeTrafficMap(enabled = false)
        assertFalse(disabled.showHomeAdditionalInfo)
        assertEquals(HomeAdditionalInfoCategory.ROUTE, disabled.homeAdditionalInfoCategory)

        val modern = UiSettings(pixelArtEnabled = true)
            .withUpdateNoticeChoices(
                showTrafficMap = true,
                useFoxholeStyle = false,
                shownVersionCode = 108,
            )
        assertFalse(modern.pixelArtEnabled)
        assertEquals(108, modern.alphaNoticeShownVersionCode)
    }

    private fun noticeLines(xml: String): List<String> {
        val body = Regex("""<string name="cli_update_notice_body">(.*?)</string>""")
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?: error("missing cli_update_notice_body")
        return body.split("\\n")
    }

    private fun resource(path: String): String =
        File(requireNotNull(System.getProperty("user.dir")), "src/main/res/$path").readText()

    private fun cli(path: String): String =
        File(
            requireNotNull(System.getProperty("user.dir")),
            "src/main/kotlin/com/foxhole/guard/ui/cli/$path",
        ).readText()
}
