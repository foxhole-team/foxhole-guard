package com.foxhole.guard.ui.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliTerminalCopyContractTest {
    @Test
    fun `manual geodata refresh echoes a localised command before progress`() {
        val home = cli("home/CliHomeScreen.kt")
        val geoRefresh = home
            .substringAfter("private fun rememberProfileGeoRefresh(")
            .substringBefore("private fun CliClearTerminalSheet(")
        val refresh = geoRefresh
            .substringAfter("return CliProfileGeoRefreshActions(")

        assertTrue(home.contains("R.string.cli_cmd_update_geodata"))
        assertTrue(refresh.contains("terminal.command(refreshCommand)"))
        assertTrue(refresh.contains("terminal.beginGeoRefresh(refreshingMessage)"))
        assertTrue(geoRefresh.contains("if (terminal.promptText != null) return@LaunchedEffect"))
        assertTrue(geoRefresh.contains("delay(GEO_TERMINAL_PROGRESS_VISIBLE_MS)"))
        assertTrue(
            refresh.indexOf("terminal.command(refreshCommand)") <
                refresh.indexOf("terminal.beginGeoRefresh(refreshingMessage)"),
        )
        assertEquals("update geodata", value("values/strings.xml", "cli_cmd_update_geodata"))
        assertEquals("обновление геоданных", value("values-ru/strings.xml", "cli_cmd_update_geodata"))
    }

    @Test
    fun `terminal line resources do not end in full stops`() {
        listOf("values/strings.xml", "values-ru/strings.xml").forEach { path ->
            val terminalValues = STRING.findAll(resource(path))
                .filter { match -> TERMINAL_LINE_KEY.matches(match.groupValues[1]) }
                .associate { match -> match.groupValues[1] to match.groupValues[2] }

            assertTrue(terminalValues.containsKey("cli_home_network_geo_updated"))
            terminalValues.forEach { (key, text) ->
                assertFalse("$path:$key ends in a full stop", text.trimEnd().endsWith('.'))
            }
        }
    }

    private fun value(path: String, key: String): String =
        STRING.findAll(resource(path))
            .single { match -> match.groupValues[1] == key }
            .groupValues[2]

    private fun cli(relative: String): String =
        File("src/main/kotlin/com/foxhole/guard/ui/cli", relative).readText()

    private fun resource(relative: String): String =
        File("src/main/res", relative).readText()

    private companion object {
        val STRING = Regex("""<string name="([^"]+)">([^<]*)</string>""")
        val TERMINAL_LINE_KEY = Regex(
            """cli_(?:home_term_.+|cmd_.+|home_network_geo_(?:refreshing|updated)|home_status_(?:note|full_note))""",
        )
    }
}
