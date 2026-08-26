package com.foxhole.guard.ui.cli

import androidx.compose.ui.unit.dp
import com.foxhole.guard.ui.cli.components.CLI_HELP_CONTENT_ICON_LIFT
import com.foxhole.guard.ui.cli.home.CLI_TERMINAL_CURSOR_CAP_HEIGHT_RATIO
import com.foxhole.guard.ui.cli.home.CLI_TERMINAL_CURSOR_VERTICAL_OFFSET
import com.foxhole.guard.ui.cli.home.CLI_TERMINAL_CURSOR_WIDTH_RATIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliCursorAndHelpAlignmentContractTest {
    @Test
    fun `terminal cursor follows body cap height and shares its baseline`() {
        val terminal = source("home/CliHomeTerminal.kt")
        val prompt = terminal
            .substringAfter("private fun CliPromptRow(")
            .substringBefore("private fun CliTerminalHeader(")
        val cursor = terminal.substringAfter("private fun CliBlinkingCursor(")

        assertEquals(0.72f, CLI_TERMINAL_CURSOR_CAP_HEIGHT_RATIO)
        assertEquals(0.5f, CLI_TERMINAL_CURSOR_WIDTH_RATIO)
        assertEquals(1.dp, CLI_TERMINAL_CURSOR_VERTICAL_OFFSET)
        assertEquals(2, Regex("Modifier\\.alignByBaseline\\(\\)").findAll(prompt).count())
        assertTrue(prompt.contains("Modifier.alignBy { measured -> measured.measuredHeight }"))
        assertTrue(prompt.contains(".offset(y = CLI_TERMINAL_CURSOR_VERTICAL_OFFSET)"))
        assertTrue(cursor.contains("CliType.body.fontSize * CLI_TERMINAL_CURSOR_CAP_HEIGHT_RATIO"))
        assertTrue(cursor.contains("width = cursorHeight * CLI_TERMINAL_CURSOR_WIDTH_RATIO"))
    }

    @Test
    fun `help content glyphs have one shared upward optical offset`() {
        val helpNote = source("components/CliContextHelp.kt")
            .substringAfter("internal fun CliHelpNote(")
            .substringBefore("internal fun CliTopBarHelpButton(")
        val itemRow = source("components/CliIconTextItems.kt")
            .substringAfter("private fun CliIconTextItemRow(")
            .substringBefore("private val ICON_TEXT_LINE_HEIGHT")

        assertEquals((-1).dp, CLI_HELP_CONTENT_ICON_LIFT)
        assertTrue(helpNote.contains("modifier = Modifier.offset(y = CLI_HELP_CONTENT_ICON_LIFT)"))
        assertTrue(itemRow.contains("modifier = Modifier.offset(y = CLI_HELP_CONTENT_ICON_LIFT)"))
    }

    private fun source(relative: String): String =
        File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative").readText()
}
