package com.foxhole.guard.ui.cli.settings

import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.onboarding.QUICK_START_TABLE_ICON_DROP
import com.foxhole.guard.ui.cli.onboarding.quickStartTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliHelpFormattingTest {
    @Test
    fun `smart profile help becomes metric and latency tables in both locales`() {
        listOf("values/strings.xml", "values-ru/strings.xml").forEach { path ->
            val content = smartHelpContent(resourceString(path, "cli_help_smart_body"))

            assertNotNull(content)
            requireNotNull(content)
            assertEquals(listOf("T", "P", "L"), content.metrics.map(CliHelpTableRow::key))
            assertEquals(4, content.latencies.size)
            assertFalse(content.metricsTitle.endsWith(':'))
            assertFalse(content.latencyTitle.endsWith(':'))
            assertFalse(content.metrics.last().value.endsWith('.'))
            assertFalse(content.latencies.last().endsWith('.'))
            assertFalse(content.latencies.any { label -> label.contains("no data", ignoreCase = true) })
            assertFalse(content.latencies.any { label -> label.contains("нет данных", ignoreCase = true) })
        }
    }

    @Test
    fun `latency help table hides its heading while keeping both value columns left aligned`() {
        val help = source("ui/cli/settings/CliHelpSubScreen.kt")
        assertTrue(help.contains(".height(IntrinsicSize.Max)"))
        assertTrue(help.contains("showTitle = false"))
        assertTrue(help.contains("SMART_HELP_TABLE_CELL_HEIGHT"))
        assertFalse(help.contains("alignValuesEnd = true"))
        assertEquals(2, "alignValuesEnd = false".toRegex().findAll(help).count())
    }

    @Test
    fun `quick actions omit the tap and hold legend`() {
        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")
        val quickStart = source("ui/cli/onboarding/CliQuickStartItem.kt")
        assertTrue(english.contains("<string name=\"cli_help_start_title\">quick actions</string>"))
        assertTrue(russian.contains("<string name=\"cli_help_start_title\">быстрые действия</string>"))
        assertFalse(english.contains("<string name=\"cli_help_start_title\">quick start</string>"))
        assertFalse(russian.contains("<string name=\"cli_help_start_title\">быстрый старт</string>"))
        listOf(english, russian).forEach { strings ->
            assertFalse(strings.contains("cli_help_action_tap"))
            assertFalse(strings.contains("cli_help_action_hold"))
        }
        assertFalse(quickStart.contains("CliQuickActionLegend"))
        assertFalse(quickStart.contains("QUICK_ACTION_TRAILING_ICONS"))
        assertTrue(english.contains("<string name=\"cli_quick_start_title\">Quick start</string>"))
        assertTrue(russian.contains("<string name=\"cli_quick_start_title\">Быстрый старт</string>"))
        assertEquals(
            4,
            quickStartTable(resourceString("values/strings.xml", "cli_help_start_body"))?.rows?.size,
        )
        val table = requireNotNull(quickStartTable(resourceString("values/strings.xml", "cli_help_start_body")))
        assertEquals(
            listOf(
                com.foxhole.guard.R.drawable.lin_terminal,
                com.foxhole.guard.R.drawable.lin_power,
                com.foxhole.guard.R.drawable.lin_status,
            ),
            table.rows.drop(1).map { row -> row.icon },
        )
        assertEquals(1.dp, QUICK_START_TABLE_ICON_DROP)
        assertTrue(quickStart.contains("modifier = Modifier.offset(y = QUICK_START_TABLE_ICON_DROP)"))
    }

    @Test
    fun `quick start restores detailed guidance below the tables in both locales`() {
        val english = resourceString("values/strings.xml", "cli_help_start_details")
        val russian = resourceString("values-ru/strings.xml", "cli_help_start_details")
        val firstRun = source("ui/cli/onboarding/CliQuickStartSheet.kt")
        val help = source("ui/cli/settings/CliHelpSubScreen.kt")

        assertTrue(english.contains("Control logic -"))
        assertTrue(russian.contains("Логика управления -"))
        assertTrue(english.contains("Add a VPN profile"))
        assertTrue(russian.contains("Добавление профиля VPN"))
        assertTrue(english.contains("whole-device scenario"))
        assertTrue(russian.contains("Сценарий «всё устройство»"))
        assertTrue(firstRun.contains("detailsBody = detailsBody"))
        assertTrue(help.contains("detailsBody = stringResource(R.string.cli_help_start_details)"))
    }

    private fun resourceString(path: String, key: String): String =
        Regex("""<string name="$key">(.*?)</string>""")
            .find(resource(path))
            ?.groupValues
            ?.get(1)
            ?.replace("\\n", "\n")
            ?: error("missing $key in $path")

    private fun resource(path: String): String =
        File(requireNotNull(System.getProperty("user.dir")), "src/main/res/$path").readText()

    private fun source(path: String): String =
        File(requireNotNull(System.getProperty("user.dir")), "src/main/kotlin/com/foxhole/guard/$path").readText()
}
