package com.foxhole.guard.ui.cli.settings

import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.components.CLI_MENU_ROW_MIN_HEIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal class CliScenarioManagementRowHeightContractTest {
    private fun source(path: String): String =
        File("src/main/kotlin/com/foxhole/guard/ui/cli/$path").readText()

    @Test
    fun `scenario management uses the same 44dp row floor as settings`() {
        val section = source("settings/CliRoutingModeSection.kt")

        assertEquals(44.dp, CLI_MENU_ROW_MIN_HEIGHT)
        assertEquals(
            2,
            Regex("rowMinHeight = CLI_MENU_ROW_MIN_HEIGHT").findAll(section).count(),
        )
        assertFalse(section.contains("rowMinHeight = 44.dp"))
        assertFalse(section.contains("padding(vertical = CliSpacing.xs)"))
        assertEquals(2, Regex("CliRowDivider\\(\\)").findAll(section).count())
    }

    @Test
    fun `shared dropdown and toggle rows retain the canonical pressable floor`() {
        val dropdown = source("components/CliDropdownOption.kt")
        val rows = source("components/CliRows.kt")

        assertTrue(dropdown.contains(".cliPanelRowPressable(enabled = enabled"))
        val toggle = rows
            .substringAfter("internal fun CliToggleRow(")
            .substringBefore("internal fun CliRowInfoGlyph(")
        assertTrue(toggle.contains(".cliPanelRowPressable(enabled = enabled)"))
        assertTrue(rows.contains("CLI_MENU_ROW_MIN_HEIGHT = 44.dp"))
    }

    @Test
    fun `compact proxy credentials stay local while every other caller keeps 48dp`() {
        val input = source("components/CliInputRow.kt")
        val secret = source("components/CliSecretRow.kt")

        assertTrue(input.contains("rowMinHeight: Dp = 48.dp"))
        assertTrue(input.contains("defaultMinSize(minHeight = rowMinHeight)"))
        assertTrue(secret.contains("rowMinHeight: Dp = 48.dp"))
        assertTrue(secret.contains("val actionSize = minOf(rowMinHeight, SECRET_ACTION_SIZE)"))
        assertTrue(secret.contains("size = actionSize"))
        assertTrue(secret.contains("rowMinHeight = rowMinHeight"))
    }
}
