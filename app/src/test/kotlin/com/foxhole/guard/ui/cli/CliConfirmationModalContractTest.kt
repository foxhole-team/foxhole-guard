package com.foxhole.guard.ui.cli

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.PanelAppearance
import com.foxhole.guard.ui.cli.components.CliSheetHeaderIconRole
import com.foxhole.guard.ui.cli.components.cliModalHeaderControlOffsetFor
import com.foxhole.guard.ui.cli.components.cliModalHeaderIconOffsetFor
import com.foxhole.guard.ui.cli.components.cliModalSurfaceColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliConfirmationModalContractTest {

    @Test
    fun `only the explicit dark appearance makes modal surfaces black`() {
        val panel = Color(0xFF334455)

        assertEquals(panel, cliModalSurfaceColor(PanelAppearance.AUTO, panel))
        assertEquals(panel, cliModalSurfaceColor(PanelAppearance.STANDARD, panel))
        assertEquals(panel, cliModalSurfaceColor(PanelAppearance.LIGHT, panel))
        assertEquals(Color.Black, cliModalSurfaceColor(PanelAppearance.DARK, panel))
    }

    @Test
    fun `profile overlays and dropdowns use the shared modal palette rule`() {
        listOf(
            "profiles/CliProfileEditorScreen.kt",
            "profiles/CliManualProfileEditor.kt",
            "profiles/CliQrScannerOverlay.kt",
            "profiles/CliProfileTransfer.kt",
            "components/CliDropdownOption.kt",
        ).forEach { relative ->
            assertTrue(
                "$relative bypasses the shared modal palette rule",
                cli(relative).contains("cliModalSurfaceColor("),
            )
        }
    }

    @Test
    fun `material sheets and platform dialogs own their motion`() {
        val frame = cli("components/CliModalFrame.kt")
        val bottomSheet = cli("components/CliBottomSheet.kt")
        val platformModalConsumers = listOf(
            cli("components/CliInputModal.kt"),
            cli("onboarding/CliOnboardingWizard.kt"),
            cli("profiles/CliProfileEditorScreen.kt"),
            cli("profiles/CliProfileTransfer.kt"),
            cli("profiles/CliQrScannerOverlay.kt"),
        )

        assertFalse(frame.contains("cliModalContentEnter"))
        assertFalse(frame.contains("CLI_MODAL_CONTENT_"))
        assertFalse(bottomSheet.contains(".cliModalContentEnter()"))
        assertFalse(bottomSheet.contains("surfaceMotion"))
        assertFalse(bottomSheet.contains("cliBottomSheetSurfaceMotion"))
        assertTrue(bottomSheet.contains("sheetState.hide()"))
        platformModalConsumers.forEach { source -> assertFalse(source.contains("cliModalContentEnter")) }
    }

    @Test
    fun `the inline yes-no row no longer exists`() {
        assertFalse(
            "CliYesNoRow is back, so inline confirmations are possible again",
            cli("components/CliRows.kt").contains("fun CliYesNoRow"),
        )
    }

    @Test
    fun `the confirm sheet keeps actions above the shared accent close control`() {
        val source = cli("components/CliConfirmSheet.kt")
        val bottomSheet = cli("components/CliBottomSheet.kt")

        assertTrue(source.contains("CliBottomSheet("))
        assertTrue(source.contains("closeActionTag = cancelTag"))
        assertTrue(source.contains("CliSheetActionsRow("))
        assertFalse(source.contains("cancelButton("))
        assertFalse(source.contains("filled = true"))
        val singleAction = source.substringAfter("actions.size == 1 ->").substringBefore("else ->")
        assertTrue(singleAction.contains("actionButton(actions.single(), modifier.fillMaxWidth())"))
        assertTrue(bottomSheet.contains("Modifier.verticalScroll(bodyScrollState)"))
        assertTrue(
            bottomSheet.indexOf("Modifier.verticalScroll(bodyScrollState)") <
                bottomSheet.indexOf("CliModalCloseButton("),
        )
        assertTrue(bottomSheet.contains("color = LocalCliColors.current.accent"))
    }

    @Test
    fun `vpn tor choices use two actions above the shared cancel footer`() {
        val prompt = cli("home/CliTorPromptPanel.kt")
        val buttons = cli("home/CliHomeButtons.kt")

        listOf(
            "TorTransitionPrompt.VpnTorStop",
            "TorTransitionPrompt.VpnTorModeChoice",
            "confirmVpnTorStopTor(prompt)",
            "confirmVpnTorStopAll(prompt)",
            "confirmVpnTorModeChoice(prompt, RoutingModePreset.TOR)",
            "confirmVpnTorModeChoice(prompt, RoutingModePreset.VPN)",
        ).forEach { contract -> assertTrue("missing $contract", prompt.contains(contract)) }
        assertTrue(prompt.contains("closeLabel = if (prompt.isVpnTorChoice())"))
        assertTrue(prompt.contains("R.string.cli_common_no_cancel"))
        assertTrue(prompt.contains("horizontal = prompt.isVpnTorChoice()"))
        assertTrue(buttons.indexOf("onVpnTorStopRequested()") < buttons.indexOf("onToggleConnection()"))
    }

    @Test
    fun `smart profile test shares the fixed footer with accent close`() {
        val sheet = cli("profiles/CliSmartProfileSheet.kt")
        val bottomSheet = cli("components/CliBottomSheet.kt")

        assertTrue(sheet.contains("footerTrailing = {"))
        assertTrue(sheet.contains("CLI_SMART_SHEET_TEST_TAG"))
        assertTrue(sheet.contains("CLI_SMART_SHEET_CLOSE_TAG"))
        assertTrue(sheet.indexOf("footerTrailing = {") < sheet.indexOf("CliProtocolDropdown("))
        assertTrue(sheet.contains("onOptionSelected = requestSheetDismiss"))
        val testButton = sheet.substringAfter("internal fun CliSmartTestButton(")
        assertTrue(testButton.contains("color = colors.ok"))
        assertFalse(testButton.substringBefore("if (confirmVpnOnlyTest)").contains("filled = true"))
        assertTrue(bottomSheet.contains("footerLeading: (@Composable RowScope.() -> Unit)? = null"))
        assertTrue(bottomSheet.contains("footerTrailing: (@Composable RowScope.() -> Unit)? = null"))
        assertTrue(bottomSheet.contains("horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)"))
        assertTrue(bottomSheet.contains("Modifier.verticalScroll(bodyScrollState)"))
        assertTrue(
            bottomSheet.indexOf("Modifier.verticalScroll(bodyScrollState)") <
                bottomSheet.indexOf("footerTrailing?.invoke(this)"),
        )
        val footer = bottomSheet.substringAfter("footerLeading?.invoke(this)")
        assertTrue(footer.indexOf("CliModalCloseButton(") < footer.indexOf("footerTrailing?.invoke(this)"))
    }

    @Test
    fun `shared bottom sheet hides once before its owner unmounts it`() {
        val bottomSheet = cli("components/CliBottomSheet.kt")
        val request = bottomSheet
            .substringAfter("val dismissSheet: ((() -> Unit)?) -> Unit =")
            .substringBefore("return CliBottomSheetDismissRequests(")

        assertTrue(request.contains("if (!dismissInProgress)"))
        assertTrue(request.contains("try {"))
        assertFalse(request.contains("surfaceMotion"))
        assertFalse(request.contains("EXIT_HANDOFF"))
        assertTrue(request.contains("sheetState.hide()"))
        assertTrue(request.contains("} finally {"))
        assertTrue(request.contains("if (sheetState.isVisible)"))
        assertTrue(request.contains("dismissInProgress = false"))
        assertTrue(request.indexOf("sheetState.hide()") < request.indexOf("currentOnDismiss()"))
        assertFalse(request.contains("catch ("))
        assertFalse(request.contains("delay("))
        assertTrue(bottomSheet.contains("onDismissRequest = requestDismiss"))
        assertEquals(2, Regex("onClick = requestDismiss").findAll(bottomSheet).count())
        assertTrue(bottomSheet.contains("LocalCliBottomSheetDismissRequest provides requestDismiss"))
        assertTrue(bottomSheet.contains("LocalCliBottomSheetDismissAfter provides requestDismissAfter"))
        assertTrue(bottomSheet.contains("autoDismissAfterMillis?.let"))
    }

    @Test
    fun `modal header icons lift two steps without moving the trailing control`() {
        assertEquals((-4).dp, cliModalHeaderIconOffsetFor("Confirm"))
        assertEquals((-4).dp, cliModalHeaderIconOffsetFor("Подтвердить"))
        assertEquals(
            (-4).dp,
            cliModalHeaderIconOffsetFor("Information", CliSheetHeaderIconRole.INFORMATION),
        )
        assertEquals(
            (-4).dp,
            cliModalHeaderIconOffsetFor(
                "Information",
                CliSheetHeaderIconRole.INFORMATION,
                pixelArtEnabled = false,
            ),
        )
        assertEquals((-2).dp, cliModalHeaderControlOffsetFor("Confirm"))
        assertEquals((-2).dp, cliModalHeaderControlOffsetFor("Подтвердить"))

        val titleRow = cli("components/CliBottomSheet.kt")
            .substringAfter("if (title != null)")
            .substringBefore("Spacer(modifier = Modifier.height(CliSpacing.sm))")
        assertTrue(titleRow.contains("Row(verticalAlignment = Alignment.Top)"))
        assertTrue(titleRow.contains(".height(CliHeaderControlSlotHeight)"))
        assertTrue(titleRow.contains("contentAlignment = Alignment.Center"))
        assertEquals(2, Regex("Spacer\\(modifier = Modifier\\.width\\(CliSpacing\\.xs\\)\\)").findAll(titleRow).count())
        assertEquals(1, Regex("cliModalHeaderIconOffsetFor\\(").findAll(titleRow).count())
        assertEquals(1, Regex("cliModalHeaderControlOffsetFor\\(").findAll(titleRow).count())
        assertTrue(titleRow.contains("resolvedHeaderIconRole"))
        assertTrue(titleRow.contains("pixelArtEnabled"))
    }

    @Test
    fun `every modal title uses the default foreground colour`() {
        val bottomSheet = cli("components/CliBottomSheet.kt")
        val inputModal = cli("components/CliInputModal.kt")

        assertTrue(bottomSheet.contains("color = colors.fg"))
        assertTrue(inputModal.contains("color = colors.fg"))
        assertTrue(cli("profiles/CliProfileEditorScreen.kt").contains("titleColor = colors.fg"))
        assertTrue(cli("profiles/CliManualProfileEditor.kt").contains("titleColor = colors.fg"))
        assertTrue(
            cli("profiles/CliQrScannerOverlay.kt")
                .contains("style = CliType.title, color = colors.fg"),
        )
        assertTrue(cli("profiles/CliProfileTransfer.kt").contains("titleColor = colors.fg"))
    }

    @Test
    fun `home action rows animate coordinated layout changes`() {
        val buttons = cli("home/CliHomeButtons.kt")

        assertTrue(buttons.contains("label = \"homePrimaryActions\""))
        assertTrue(buttons.contains("label = \"homeSecondaryActions\""))
        assertEquals(1, Regex("targetState = row,").findAll(buttons).count())
        assertTrue(buttons.contains("private fun CliMorphingActionRow("))
        assertTrue(buttons.contains("transition.animateFloat("))
        assertTrue(buttons.contains("transition.animateDp("))
        assertTrue(buttons.contains("button in transition.currentState || button in transition.targetState"))
        assertFalse(buttons.contains("fillMaxWidth().animateContentSize()"))
    }

    @Test
    fun `every modal family exposes the same close action`() {
        val bottomSheet = cli("components/CliBottomSheet.kt")
        val input = cli("components/CliInputModal.kt")
        val button = cli("components/CliButton.kt")

        assertTrue(bottomSheet.contains("CliModalCloseButton("))
        assertTrue(bottomSheet.contains("R.string.cli_common_close_action"))
        assertTrue(bottomSheet.contains("contentWindowInsets = { BottomSheetDefaults.windowInsets }"))
        assertTrue(input.contains("CliModalCloseButton("))
        assertTrue(button.contains(".defaultMinSize(minHeight = 48.dp)"))
        listOf(
            "profiles/CliProfileEditorScreen.kt",
            "profiles/CliManualProfileEditor.kt",
            "profiles/CliQrScannerOverlay.kt",
            "profiles/CliProfileTransfer.kt",
        ).forEach { relative ->
            val source = cli(relative)
            assertTrue("$relative bypasses the shared close control", source.contains("CliModalCloseButton("))
        }
        listOf(
            "components/CliInputModal.kt",
            "profiles/CliProfileEditorScreen.kt",
            "profiles/CliQrScannerOverlay.kt",
            "profiles/CliProfileTransfer.kt",
        ).forEach { relative ->
            val source = cli(relative)
            assertTrue(
                "$relative can draw its footer under system navigation",
                source.contains("decorFitsSystemWindows = true"),
            )
        }
    }

    @Test
    fun `the destructive row asks in the modal and keeps its row in place`() {
        val row = cli("components/CliRows.kt").substringAfter("internal fun CliDestructiveRow")

        assertTrue(row.contains("CliActionRow(label = label, icon = icon, onTap = { onArm(key) })"))
        assertTrue(row.contains("if (confirmAction == key)"))
        assertTrue(row.contains("CliConfirmSheet("))
        assertTrue(row.contains("onArm(null)"))
        assertTrue(row.contains("onConfirm()"))
    }

    @Test
    fun `the firewall consent is a modal, not an inline chip pair`() {
        val source = cli("settings/CliModulesSection.kt")

        assertTrue(source.contains("CliConfirmSheet("))
        assertTrue(source.contains("R.string.cli_cfg_firewall_consent_body"))
        assertTrue(source.contains("R.string.cli_cfg_firewall_disable_body"))
        assertFalse(source.contains("CliChip("))
    }

    @Test
    fun `module activation sheets outlive collapsible panel content`() {
        val source = cli("settings/CliModulesSection.kt")
        val section = source.substringAfter("internal fun CliModulesSection")
            .substringBefore("private fun CliTorModuleBlock")

        assertTrue(section.contains("var torActivation by remember"))
        assertTrue(section.contains("var sentinelActivation by remember"))
        assertTrue(section.indexOf("torActivation?.let") > section.indexOf("CliPanel("))
        assertTrue(section.indexOf("sentinelActivation?.let") > section.indexOf("CliPanel("))
    }

    @Test
    fun `the encryption consent is a modal on both directions`() {
        val panel = cli("settings/CliPinPanel.kt")
        val section = cli("settings/CliLockSection.kt")

        assertTrue(panel.contains("internal fun CliEncryptionConsentSheet"))
        assertTrue(panel.contains("R.string.cli_lock_consent_body"))
        assertTrue(panel.contains("R.string.cli_lock_disable_warning"))
        assertTrue(section.contains("consentFlow = CliPinFlow.ENABLE"))
        assertTrue(section.contains("consentFlow = CliPinFlow.DISABLE"))
        assertTrue(section.contains("pinFlow = CliPinFlow.CHANGE"))
    }

    @Test
    fun `every remaining inline confirmation moved to the modal`() {
        listOf(
            "profiles/CliProfileListItem.kt" to "R.string.cli_prof_delete_confirm",
            "profiles/CliProfileEditorProtocolForm.kt" to "R.string.cli_data_destructive_confirm",
            "settings/CliWebAppsSubScreen.kt" to "R.string.cli_webapps_firewall_consent",
            "settings/CliDataSubScreen.kt" to "R.string.cli_data_restore_confirm",
        ).forEach { (relative, question) ->
            val source = cli(relative)
            assertTrue("$relative lost the question $question", source.contains(question))
            assertTrue("$relative asks without a modal", source.contains("CliConfirmSheet("))
        }
    }

    @Test
    fun `no control row is start-aligned`() {
        listOf(
            "home/CliTorPromptPanel.kt",
            "profiles/CliProfileDialogs.kt",
            "profiles/CliProfileEditorProtocolForm.kt",
            "profiles/CliProfileListItem.kt",
            "settings/CliPinPanel.kt",
            "webapps/CliWebAppsScreen.kt",
        ).forEach { relative ->
            val source = cli(relative)
            val aligned =
                source.contains("Alignment.End") ||
                    source.contains("Arrangement.End") ||
                    source.contains("Alignment.CenterHorizontally") ||
                    source.contains("CliSheetActionsRow(") ||
                    source.contains("modifier = Modifier.fillMaxWidth()")
            assertTrue("$relative: a control row is still flush against the left edge", aligned)
        }
    }

    @Test
    fun `the log tabs deliberately keep reading order`() {
        val source = cli("logs/CliLogsScreen.kt")
        val tabs = source.substringAfter("CliLogTab.entries.forEach")

        assertFalse(tabs.take(TAB_ROW_WINDOW).contains("Alignment.End"))
    }

    @Test
    fun `the home screen no longer carries a tor consent`() {
        val facts = cli("home/CliHomeFacts.kt")
        val screen = cli("home/CliHomeScreen.kt")

        assertFalse(facts.contains("CliTorConsentPanel"))
        assertFalse(screen.contains("CliTorConsentPanel"))
        assertFalse(screen.contains("torConsentOpen"))
    }

    @Test
    fun `the confirm sheet is the only bottom confirmation shape`() {
        val components = File(cliRoot(), "components").listFiles().orEmpty()
        val declaring =
            components.filter { file ->
                file.isFile && file.readText().contains("confirmLabel ?: stringResource")
            }

        assertEquals(1, declaring.size)
    }

    private fun cli(relative: String): String = File(cliRoot(), relative).readText()

    private fun cliRoot(): File =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli"),
        ).first(File::isDirectory)
}

private const val TAB_ROW_WINDOW = 400
