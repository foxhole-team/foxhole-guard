package com.foxhole.guard.ui.cli.home

import com.foxhole.core.model.AnomalyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliStatusColumnsTest {

    @Test
    fun `terminal starts ordinary lines in lowercase without rewriting identity values`() {
        assertEquals("waiting for connection", cliTerminalLineText("Waiting for connection"))
        assertEquals("ожидание подключения", cliTerminalLineText("Ожидание подключения"))
        assertEquals("VPN profile", cliTerminalLineText("VPN profile"))
        assertEquals("FoxHole Guard · v0.1.0", cliTerminalLineText("FoxHole Guard · v0.1.0"))
        assertEquals("scenario:", cliTerminalKeyLabel("Scenario"))
    }

    @Test
    fun `terminal commands are lowercased for display without changing their length`() {
        val command = "START VPN --MODE=TOR"

        assertEquals("start vpn --mode=tor", cliTerminalCommandText(command))
        assertEquals(command.length, cliTerminalCommandText(command).length)
    }

    @Test
    fun `a journal event keeps its tag and message in one wrapping line`() {
        val row = cliEventColumnRow(
            event = "connection",
            text = "connect failed: handshake timeout",
            tone = CliLineTone.ERR,
        )

        assertEquals("connection", row.key)
        assertEquals("connect failed: handshake timeout", row.value)
        assertTrue(row.inlineValue)
        assertFalse("a table row is placed, not typed", row.typed)
        assertEquals(CliLineTone.ERR, row.tone)
        assertEquals(CliLineTone.ERR, row.keyTone)
    }

    @Test
    fun `the longest sentinel event stays in the wrapping inline renderer`() {
        val longestEvent = sentinelEventLabels().maxByOrNull(String::length).orEmpty()
        val longDescription = "d".repeat(LONG_DESCRIPTION_CHARS)

        assertTrue("no sentinel label was read from the resources", longestEvent.isNotEmpty())
        val row = cliEventColumnRow(event = longestEvent, text = longDescription, tone = CliLineTone.WARN)
        assertEquals(longestEvent, row.key)
        assertEquals(longDescription, row.value)
        assertTrue(row.inlineValue)

        val inline = inlineValueSource()
        assertTrue(inline.contains("softWrap = true"))
        assertTrue(inline.contains("textAlign = TextAlign.Start"))
    }

    @Test
    fun `terminal values stay aligned to the right edge`() {
        val columns = keyValueColumnsSource()
        val value = cli("home/CliHomeTerminal.kt")
            .substringAfter("private fun RowScope.CliTerminalValueText(")
            .substringBefore("private fun CliTerminalAppIcon(")

        assertTrue(columns.contains("horizontalArrangement = Arrangement.End"))
        assertTrue(columns.contains("FLAGGED_KEY_COLUMN_WEIGHT"))
        assertTrue(columns.contains("FLAGGED_VALUE_COLUMN_WEIGHT"))
        assertTrue(value.contains("textAlign = TextAlign.End"))
    }

    @Test
    fun `terminal IP keeps its full semantic text on one visual line`() {
        val value = cli("home/CliHomeTerminal.kt")
            .substringAfter("private fun RowScope.CliTerminalValueText(")
            .substringBefore("private const val INLINE_PACKAGE_SLOT_PREFIX")

        assertTrue(value.contains("cliTerminalValueStaysOnOneLine(line.icon, value)"))
        assertTrue(value.contains("maxLines = if (keepOnOneLine) 1 else BODY_MAX_LINES"))
        assertTrue(value.contains("softWrap = !keepOnOneLine"))
        assertTrue(value.contains("TextOverflow.Ellipsis"))
        assertTrue("the unabridged value remains the Text semantics payload", value.contains("text = value"))
        assertTrue(cliTerminalValueStaysOnOneLine(CliLineIcon.IP, "not resolved yet"))
        assertTrue(cliTerminalValueStaysOnOneLine(null, "198.51.100.10 · US"))
        assertTrue(cliTerminalValueStaysOnOneLine(null, "198.51.100.10\u2009·\u2009US"))
        assertTrue(cliTerminalValueStaysOnOneLine(null, "2001:db8::1 · NL"))
        assertFalse(cliTerminalValueStaysOnOneLine(null, "a long provider description"))
    }

    @Test
    fun `route identity stays in the shared right aligned terminal columns`() {
        val identities = cli("home/CliRouteIdentityRows.kt")
            .substringAfter("internal fun cliStatusRouteIdentityRows(")

        assertFalse(identities.contains("inlineValue = true"))
    }

    @Test
    fun `app rule icons finish at the terminal value edge`() {
        val columns = keyValueColumnsSource()

        assertTrue(columns.contains("line.packages.forEachIndexed"))
        assertTrue(columns.contains("index < line.packages.lastIndex"))
        assertTrue(columns.contains("line.value != null"))
        assertTrue(columns.contains("line.flagCountry != null"))
    }

    @Test
    fun `connecting scenario reports that it is waiting without changing the state mapping`() {
        val stateLabels = cli("home/CliHomeTerminal.kt")
            .substringAfter("internal fun stateLabel(state: ConnectionState)")
            .substringBefore("\n}")
        assertTrue(
            stateLabels.contains(
                "ConnectionState.CONNECTING -> stringResource(R.string.cli_home_state_connecting)",
            ),
        )
        assertTrue(
            resource("values/strings.xml").contains(
                "name=\"cli_home_state_connecting\">Waiting for connection</string>",
            ),
        )
        assertTrue(
            resource("values-ru/strings.xml").contains(
                "name=\"cli_home_state_connecting\">Ожидание подключения</string>",
            ),
        )
        assertTrue(
            resource("values/strings.xml").contains(
                "name=\"cli_cfg_home_additional_info_map\">Traffic map</string>",
            ),
        )
        assertTrue(
            resource("values-ru/strings.xml").contains(
                "name=\"cli_cfg_home_additional_info_map\">Карта трафика</string>",
            ),
        )
    }

    @Test
    fun `journal and sentinel rows print no clock of their own`() {
        val extended = cli("home/CliStatusExtendedRows.kt")

        assertFalse(extended.contains("CliFormat.clock("))
        assertFalse(extended.contains("EVENT_INDENT"))
        assertTrue(extended.contains("cliEventColumnRow("))
    }

    @Test
    fun `every sentinel type names its own event`() {
        val labels = AnomalyType.entries.map(AnomalyType::cliSentinelTypeLabelRes)

        assertEquals("two types share one label", AnomalyType.entries.size, labels.distinct().size)
    }

    @Test
    fun `an informational notice keeps its message inline and types one line naming the kind`() {
        val rows = cliInfoNoticeRows(
            event = "no DNS from the provider",
            description = "1.1.1.1 in use",
            kind = "info: no DNS from the provider",
        )

        assertEquals(2, rows.size)
        val notice = rows.first()
        assertEquals("no DNS from the provider", notice.key)
        assertEquals("1.1.1.1 in use", notice.value)
        assertTrue(notice.inlineValue)
        assertFalse("the printed pair never types", notice.typed)

        val kind = rows.last()
        assertEquals("info: no DNS from the provider", kind.key)
        assertNull("the kind line is one line, not a column", kind.value)
        assertTrue(kind.typed)
    }

    @Test
    fun `both locales carry the notice event, its detail and the kind prefix`() {
        listOf("values/strings.xml", "values-ru/strings.xml").forEach { path ->
            val strings = resource(path)
            listOf(
                "cli_home_status_info_kind",
                "cli_home_status_info_event_provider_dns",
                "cli_home_status_info_value_provider_dns",
                "cli_home_status_info_event_firewall",
                "cli_home_status_info_event_pending_apps",
                "cli_home_status_info_value_apps",
            ).forEach { key ->
                assertTrue("$key is missing from $path", strings.contains("name=\"$key\""))
            }
        }
    }

    @Test
    fun `the column shape survives a restart`() {
        val store = cli("CliTerminalStore.kt")

        assertTrue(store.contains("val valueLeading: Boolean = false"))
        assertTrue(store.contains("valueLeading = valueLeading"))
    }

    @Test
    fun `short and full status print facts as a table and messages as wrapping lines`() {
        val home = cli("home/CliHomeScreen.kt")
        val renderer = inlineValueSource()
        val columns = keyValueColumnsSource()
        val status = cli("home/CliStatusRows.kt")
        val extended = cli("home/CliStatusExtendedRows.kt")
        val store = cli("CliTerminalStore.kt")

        assertFalse(home.contains("emitBlock(extendedRows, inlineValues = true)"))
        assertFalse(home.contains("emitBlock(statusRows, inlineValues = true)"))
        assertTrue(home.contains("terminal.emitBlock(extendedRows)"))
        assertTrue(home.contains("terminal.emitBlock(statusRows)"))
        assertTrue(columns.contains("Modifier.weight(keyColumnWeight)"))
        assertTrue(columns.contains("horizontalArrangement = Arrangement.End"))
        assertTrue(renderer.contains("softWrap = true"))
        assertTrue(renderer.contains("textAlign = TextAlign.Start"))
        assertFalse(renderer.contains("KEY_COLUMN_WEIGHT"))
        assertFalse(renderer.contains("VALUE_COLUMN_WEIGHT"))
        assertTrue(status.contains("inlineValue = true"))
        assertTrue(extended.contains("inlineValue = true"))
        assertTrue(store.contains("val inlineValue: Boolean = false"))
        assertTrue(store.contains("inlineValue = inlineValue"))
    }

    private fun keyValueColumnsSource(): String =
        cli("home/CliHomeTerminal.kt")
            .substringAfter("private fun RowScope.CliTerminalKeyValueColumns(")
            .substringBefore("private fun RowScope.CliTerminalValueText(")

    private fun inlineValueSource(): String =
        cli("home/CliHomeTerminal.kt")
            .substringAfter("private fun RowScope.CliTerminalInlineValueRow(")
            .substringBefore("private fun CliTerminalLeadSlot(")

    private fun sentinelEventLabels(): List<String> =
        Regex("""<string name="cli_sentinel_type_[a-z_]+">([^<]+)</string>""")
            .findAll(resource("values-ru/strings.xml"))
            .map { match -> match.groupValues[1] }
            .toList()

    private fun cli(relative: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli", relative),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli", relative),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli", relative),
        ).first(File::isFile).readText()

    private fun resource(relative: String): String =
        listOf(
            File("src/main/res", relative),
            File("app/src/main/res", relative),
            File("../app/src/main/res", relative),
        ).first(File::isFile).readText()

    private companion object {
        const val LONG_DESCRIPTION_CHARS = 240
    }
}
