package com.foxhole.guard.ui.cli

import com.foxhole.guard.ui.cli.logs.formatDiagnosticMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliLogsAndModulesLayoutContractTest {
    @Test
    fun `module settings gear and dashed arrow follow the selected accent`() {
        val module = cli("settings/CliModuleBlock.kt")

        assertTrue(module.contains("val settingsActionColor = colors.accent"))
        assertTrue(module.contains("CliModuleSettingsConnector(color = settingsActionColor)"))
        assertTrue(module.contains("actionColor = settingsActionColor"))
    }

    @Test
    fun `journal formatter separates a headline from structured fields and line breaks`() {
        val formatted = formatDiagnosticMessage(
            "connection failed · profile=vless\nreason=dns · retry=2",
        )

        assertEquals("connection failed", formatted.headline)
        assertEquals(listOf("profile=vless", "reason=dns", "retry=2"), formatted.details)
        assertEquals("—", formatDiagnosticMessage("  ").headline)
    }

    @Test
    fun `journal layout gives content full height and keeps the dock below it`() {
        val source = cli("logs/CliLogsScreen.kt")
        val layout = source
            .substringAfter("ColumnScope.CliJournalLayout")
            .substringBefore("private fun CliJournalDock(")

        val content = layout.indexOf("CLI_LOGS_CONTENT_TAG")
        val dock = layout.indexOf("CliJournalDock")
        assertFalse(layout.contains("CLI_LOGS_SETTINGS_TAG"))
        assertFalse(layout.contains("cli_logs_settings"))
        assertTrue("content panel must be present", content >= 0)
        assertTrue("bottom dock must follow content", dock > content)
        assertTrue(layout.contains("Modifier.fillMaxWidth().weight(1f).testTag(CLI_LOGS_CONTENT_TAG)"))
    }

    @Test
    fun `journal dock keeps only selectors while header gear opens canonical action sheet`() {
        val source = cli("logs/CliLogsScreen.kt")
        val header = source.substringAfter("internal fun CliLogsScreen").substringBefore("when (tab)")
        val dock = source
            .substringAfter("private fun CliJournalDock")
            .substringBefore("private fun CliJournalActionsSheet")
        val sheet = source
            .substringAfter("private fun CliJournalActionsSheet")
            .substringBefore("private fun CliLogsActionsButton")
        val gear = source
            .substringAfter("private fun CliLogsActionsButton")
            .substringBefore("private val LOG_ACTIONS_BUTTON_SIZE")

        assertTrue(header.contains("trailing ="))
        assertTrue(header.contains("CliLogsActionsButton"))
        assertTrue(gear.contains("R.drawable.pix_settings"))
        assertTrue(gear.contains("CLI_LOGS_ACTIONS_BUTTON_TAG"))
        assertTrue(source.contains("LOG_ACTIONS_BUTTON_SIZE = 48.dp"))
        assertTrue(!dock.contains("R.drawable.pix_info"))
        assertTrue(!dock.contains("cli_logs_controls_hint"))
        assertTrue(!dock.contains("actions()"))
        assertTrue(!dock.contains("cli_logs_clear"))
        assertTrue(!dock.contains("cli_logs_save"))
        assertTrue(dock.contains("CliLogTab.entries.forEach"))
        assertTrue(dock.contains("modifier = Modifier.weight(1f)"))
        assertTrue(sheet.contains("if (open)"))
        assertTrue(sheet.contains("CliBottomSheet("))
        assertTrue(sheet.contains("CLI_LOGS_ACTIONS_SHEET_TAG"))
        assertTrue(sheet.contains("title = stringResource(R.string.cli_logs_settings)"))
        assertTrue(sheet.contains("AnimatedContent("))
        assertTrue(sheet.contains("cliSlide(forward = targetState == CliJournalActionsPage.CONFIRM_CLEAR)"))
        assertTrue(sheet.contains("CliJournalPrimaryActions("))
        assertTrue(sheet.indexOf("settings()") < sheet.indexOf("CliJournalPrimaryActions("))
        assertTrue(sheet.contains("CLI_LOGS_SETTINGS_TAG"))
        assertTrue(sheet.contains("CliJournalClearConfirmation("))
        assertTrue(sheet.contains("ActivityResultContracts.CreateDocument(\"text/plain\")"))
        assertTrue(sheet.contains("documentLauncher.launch(exportFileName)"))
        assertTrue(sheet.contains("exporting = true"))
        assertTrue(sheet.contains("scope.launch { viewModel.emitError(saveFailedMessage) }"))
        assertTrue(!sheet.contains("Intent.createChooser"))
        assertTrue(!source.contains("createDiagnosticsArchive"))
        assertTrue(!source.contains("exportDiagnostics"))

        val saveHandler = sheet
            .substringAfter("onSave = {")
            .substringBefore("onDismiss = onDismiss")
        assertFalse("SAF save must not remove its own action sheet", saveHandler.contains("onDismiss()"))

        val appTab = source.substringAfter("CliLogTab.APP ->").substringBefore("CliLogTab.NET ->")
        assertTrue(appTab.contains("exportEntries ="))
        assertTrue(appTab.contains("entry.tag == NETWORK_ACTIVITY_TAG"))
        assertTrue(appTab.contains("foxhole-app-journal.txt"))

        val networkTab = source.substringAfter("ColumnScope.CliNetworkLog").substringBefore("SecJournalItem")
        assertTrue(networkTab.contains("foxhole-network-journal.txt"))
        assertTrue(networkTab.contains("exportKind = CliJournalExportKind.NETWORK_ACTIVITY"))

        val securityTab = source.substringAfter("ColumnScope.CliSecurityLog")
        assertTrue(securityTab.contains("foxhole-security-journal.txt"))
        assertTrue(securityTab.contains("onClear = null"))

        val primary = source
            .substringAfter("private fun CliJournalPrimaryActions")
            .substringBefore("private fun CliJournalClearConfirmation")
        assertTrue(primary.contains("Column("))
        assertTrue(primary.contains("R.string.cli_logs_clear"))
        assertTrue(primary.contains("R.string.cli_logs_save"))
        assertEquals(2, Regex("modifier = Modifier\\.weight\\(1f\\)").findAll(primary).count())
        assertTrue(primary.contains("horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)"))
        assertTrue(primary.contains("R.string.cli_common_no_cancel"))

        val confirmation = source.substringAfter("private fun CliJournalClearConfirmation")
        assertTrue(confirmation.contains("Column("))
        assertTrue(confirmation.contains("R.string.cli_logs_clear_question"))
        assertTrue(confirmation.contains("CliSheetActionsRow("))
        assertTrue(confirmation.contains("tone = CliSheetActionTone.DESTRUCTIVE"))
    }

    @Test
    fun `all journal-specific controls live in the gear sheet settings slot`() {
        val source = cli("logs/CliLogsScreen.kt")
        val appTab = source.substringAfter("CliLogTab.APP ->").substringBefore("CliLogTab.NET ->")
        val networkTab = source.substringAfter("ColumnScope.CliNetworkLog").substringBefore("SecJournalItem")
        val securityTab = source.substringAfter("ColumnScope.CliSecurityLog")

        assertTrue(
            appTab.substringAfter("settings = {").contains("CliRetentionRow("),
        )
        assertTrue(
            networkTab.substringAfter("settings = {")
                .contains("if (!state.settingsHydrated)"),
        )
        assertTrue(
            networkTab.substringAfter("settings = {")
                .contains("CliRetentionRow("),
        )
        assertTrue(
            securityTab.substringAfter("settings = {")
                .contains("CliSecurityHeader("),
        )
        assertTrue(
            securityTab.substringAfter("settings = {")
                .contains("CliRetentionRow("),
        )
        assertEquals(3, Regex("CliRetentionRow\\(").findAll(source).count())
    }

    @Test
    fun `module settings use a raised orange dashed elbow with separated affordances and dim text`() {
        val block = cli("settings/CliModuleBlock.kt")
        val modules = cli("settings/CliModulesSection.kt")
        val extras = cli("settings/CliExtrasSection.kt")

        assertFalse(block.contains("cliDashedBorder"))
        assertFalse(block.contains("CliRowDivider("))
        assertTrue(block.contains("Row(modifier = Modifier.offset(y = MODULE_SETTINGS_ROW_OFFSET))"))
        assertTrue(block.contains("CliModuleSettingsConnector(color = settingsActionColor)"))
        assertTrue(block.contains("Spacer(modifier = Modifier.width(MODULE_SETTINGS_CONNECTOR_GAP))"))
        assertTrue(block.contains("moveTo(x, 0f)"))
        assertTrue(block.contains("lineTo(x, y)"))
        assertTrue(block.contains("lineTo(endX, y)"))
        assertTrue(block.contains("lineTo(endX - arrowLength, y + arrowLength)"))
        assertTrue(block.contains("PathEffect.dashPathEffect("))
        assertEquals(1, Regex("PathEffect\\.dashPathEffect\\(").findAll(block).count())
        assertTrue(block.contains("actionColor = settingsActionColor"))
        assertTrue(block.contains("labelColor = colors.dim"))
        assertTrue(block.contains("MODULE_SETTINGS_ROW_OFFSET = (-4).dp"))
        assertTrue(block.contains("MODULE_SETTINGS_CONNECTOR_GAP = 4.dp"))
        assertTrue(block.indexOf("CliToggleRow(") < block.indexOf("CliActionRow("))
        assertEquals(3, Regex("CliDivider\\(color = colors\\.borderBright\\)").findAll(modules).count())
        assertEquals(1, Regex("CliDivider\\(color = colors\\.borderBright\\)").findAll(extras).count())
    }

    @Test
    fun `about shares only the sanitized app archive through a granted system chooser`() {
        val about = cli("settings/CliAboutSubScreen.kt")
        val export = app("src/main/kotlin/com/foxhole/guard/ui/HomeViewModelConfigRulesSupport.kt")

        assertTrue(about.contains("infoText = stringResource(R.string.cli_about_log_share_note)"))
        assertTrue(about.contains("withContext(Dispatchers.IO) { viewModel.exportDiagnostics() }"))
        assertTrue(about.contains("Intent.createChooser("))
        assertTrue(export.contains("container.diagnosticsLogger.createExportFile()"))
        assertTrue(export.contains("type = \"application/gzip\""))
        assertTrue(export.contains("putExtra(Intent.EXTRA_STREAM, uri)"))
        assertTrue(export.contains("clipData = ClipData.newRawUri(subject, uri)"))
        assertTrue(export.contains("addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)"))
    }

    @Test
    fun `network journal persistence is independent from statistics and uses journal retention`() {
        val repository = app(
            "src/main/kotlin/com/foxhole/guard/core/sentinel/anomaly/AnomalyRepository.kt",
        )
        val flow = repository
            .substringAfter("val recentNetworkActivityEvents")
            .substringBefore("val recentProtocolMetricEvents")

        assertTrue(flow.contains("settings.expert.networkActivityLogging"))
        assertFalse(flow.contains("settings.statistics.enabled"))
        assertTrue(flow.contains("networkJournalRetentionCutoff(nowProvider(), settings)"))
        assertTrue(
            repository.contains(
                "settings.expert.effectiveDiagnosticsRetention().cutoffOrNull(nowMs) ?: 0L",
            ),
        )
    }

    @Test
    fun `new journal copy is mirrored in English and Russian`() {
        val english = strings("values/strings.xml")
        val russian = strings("values-ru/strings.xml")
        val keys = listOf(
            "cli_logs_settings",
            "cli_logs_clear",
            "cli_logs_clear_question",
            "cli_logs_save",
            "cli_logs_retention",
            "cli_common_no_cancel",
            "cli_about_log_share_note",
            "cli_about_log_share",
            "cli_about_log_share_chooser",
            "cli_about_log_share_failed",
        )

        keys.forEach { key ->
            assertEquals(1, Regex("""<string name="$key">.+</string>""").findAll(english).count())
            assertEquals(1, Regex("""<string name="$key">.+</string>""").findAll(russian).count())
        }
        assertTrue(!english.contains("cli_logs_controls_hint"))
        assertTrue(!russian.contains("cli_logs_controls_hint"))
    }

    private fun cli(relative: String): String =
        findFile("src/main/kotlin/com/foxhole/guard/ui/cli/$relative").readText()

    private fun strings(relative: String): String = findFile("src/main/res/$relative").readText()

    private fun app(relative: String): String = findFile(relative).readText()

    private fun findFile(relative: String): File =
        listOf(File(relative), File("app/$relative"), File("../app/$relative"))
            .first(File::isFile)
}
