package com.foxhole.guard.ui.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliOnboardingInfoAndPromptContractTest {
    @Test
    fun `first run exposes immediate locale and theme choices with platform modal motion`() {
        val wizard = cli("onboarding/CliOnboardingWizard.kt")
        val modalFrame = cli("components/CliModalFrame.kt")

        assertFalse(wizard.contains("cliModalContentEnter"))
        assertFalse(modalFrame.contains("cliModalContentEnter"))
        assertFalse(wizard.contains("tween("))
        assertFalse(wizard.contains("delay("))
        assertTrue(wizard.contains("viewModel.settingsRouteState.collectAsStateWithLifecycle()"))
        assertTrue(wizard.contains("AppLocale.entries.map"))
        assertTrue(wizard.contains("viewModel.onLocaleSelected(AppLocale.valueOf(id))"))
        assertTrue(wizard.contains("ThemeMode.entries.map"))
        assertTrue(wizard.contains("viewModel.onThemeSelected(ThemeMode.valueOf(id))"))
        assertTrue(wizard.contains("ThemeMode.OLED -> oledLabel"))
        assertTrue(wizard.contains("CliRowDivider()"))
    }

    @Test
    fun `information primitives and explicit hints use the semantic cyan token`() {
        val text = cli("components/CliText.kt")
        val contextHelp = cli("components/CliContextHelp.kt")
        val onboarding = cli("onboarding/CliOnboardingWizard.kt")
        val routing = cli("components/CliRoutingChangeConfirmSheet.kt")
        val dns = cli("settings/CliSettingsDnsSection.kt")

        listOf(text, onboarding, routing).forEach { source ->
            assertFalse(source.contains("colors.note"))
            assertTrue(source.contains("colors.info"))
        }
        assertFalse(dns.contains("colors.note"))
        assertFalse(dns.contains("R.string.cli_dns_filter_enable_info"))
        assertTrue(dns.contains("CliVerifiedUpdateProgress("))
        assertTrue(contextHelp.contains("tint = colors.info"))
        assertTrue(contextHelp.contains("color = colors.fg"))

        val oldNoteConsumers = cliRoot()
            .walkTopDown()
            .filter(File::isFile)
            .filter { file -> file.extension == "kt" && file.readText().contains("colors.note") }
            .map { file -> file.relativeTo(cliRoot()).invariantSeparatorsPath }
            .toList()
        assertEquals(emptyList<String>(), oldNoteConsumers)
        val speedValue = cli("home/CliHomeFacts.kt")
            .substringAfter("private fun CliSpeedValue(")
            .substringBefore("private const val SPEED_SLOT_SAMPLE")
        assertTrue(speedValue.contains("SpanStyle(color = colors.info)"))
        assertFalse(speedValue.contains("colors.data"))
    }

    @Test
    fun `first run keeps core guidance inline and removes body icons from its info sheets`() {
        val onboarding = cli("onboarding/CliOnboardingWizard.kt")
        val infoSheet = cli("components/CliInfoSheet.kt")

        assertTrue(onboarding.contains("CliConfirmSheet("))
        assertTrue(onboarding.contains("question = stringResource(R.string.cli_wizard_skip_note)"))
        assertTrue(onboarding.contains("text = stringResource(R.string.cli_wizard_license_body)"))
        assertTrue(onboarding.contains("text = stringResource(R.string.cli_wizard_i2p_banner_body)"))
        assertTrue(onboarding.contains("text = stringResource(R.string.cli_wizard_geo_body)"))
        assertTrue(onboarding.contains("text = stringResource(R.string.cli_wizard_download_skip_note)"))
        assertTrue(onboarding.contains("text = stringResource(R.string.cli_wizard_download_intro)"))
        assertFalse(onboarding.contains("infoText = stringResource(R.string.cli_wizard_license_body)"))
        assertFalse(onboarding.contains("infoText = stringResource(R.string.cli_wizard_i2p_banner_body)"))
        assertFalse(onboarding.contains("infoText = dataInfo"))
        assertFalse(onboarding.contains("infoText = stringResource(R.string.cli_wizard_download_intro)"))
        assertTrue(onboarding.contains("infoText = stringResource(R.string.cli_wizard_components_intro)"))
        assertTrue(
            onboarding.contains("LocalCliInfoSheetBodyIconVisible provides false"),
        )
        assertTrue(infoSheet.contains("LocalCliInfoSheetBodyIconVisible.current"))
        assertFalse(onboarding.contains("note = stringResource(R.string.cli_wizard_"))
        assertFalse(onboarding.contains(".cliDashedBorder(colors.info)"))
    }

    @Test
    fun `first run component choices expose only Tor and I2P modules`() {
        val onboarding = cli("onboarding/CliOnboardingWizard.kt")
        val components = onboarding
            .substringAfter("private fun WizardComponentsStep")
            .substringBefore("private fun WizardAppearancePanel")

        assertTrue(components.contains("R.string.cli_wizard_component_tor"))
        assertTrue(components.contains("R.string.cli_wizard_component_i2p"))
        assertFalse(components.contains("R.string.cli_wizard_component_dns"))
        assertFalse(components.contains("R.string.cli_cfg_more_anomaly"))
        assertTrue(onboarding.contains("R.string.cli_wizard_download_dns"))
        assertTrue(onboarding.contains("R.string.cli_wizard_download_sentinel"))
    }

    @Test
    fun `license rejection uses platform feedback and data skip completes onboarding`() {
        val onboarding = cli("onboarding/CliOnboardingWizard.kt")
        val licenseError = onboarding
            .substringAfter("if (validationError)")
            .substringBefore("private fun WizardComponentsStep")
        val skipData = onboarding
            .substringAfter("onSkipData = {")
            .substringBefore("\n                },")

        assertTrue(onboarding.contains("val licenseRejectFeedback = rememberCliRejectFeedback()"))
        assertTrue(onboarding.contains("feedbackScope.launch { licenseRejectFeedback.play() }"))
        assertTrue(licenseError.contains(".cliRejectShake(rejectFeedback)"))
        assertTrue(licenseError.contains(".cliDashedBorder(colors.err)"))
        assertTrue(licenseError.contains("id = R.drawable.lin_info"))
        assertTrue(licenseError.contains("tint = colors.err"))
        assertFalse(licenseError.contains("Text(text = \"!\""))
        assertTrue(skipData.contains("val skippedChoices = choices.withoutDownloads()"))
        assertTrue(skipData.contains("choices = skippedChoices"))
        assertTrue(skipData.contains("viewModel.finishOnboarding(skippedChoices)"))
        assertFalse(skipData.contains("startOnboardingDownloads"))
        assertFalse(skipData.contains("step = DOWNLOAD_STEP"))
        assertTrue(onboarding.contains("question = stringResource(R.string.cli_wizard_skip_note)"))
        assertTrue(onboarding.contains("viewModel.onOnboardingSkipped()"))
    }

    @Test
    fun `terminal prompt is localized with a cap height cursor on the text baseline`() {
        val terminal = cli("home/CliHomeTerminal.kt")
        val prompt = terminal
            .substringAfter("private fun CliPromptRow(")
            .substringBefore("private fun CliTerminalHeader(")
        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")

        assertTrue(
            prompt.contains(
                "cliTerminalCommandText(stringResource(R.string.cli_home_terminal_prompt)) + \" \"",
            ),
        )
        assertTrue(prompt.contains("cliTerminalCommandText(prompt.take(typedCount))"))
        assertFalse(prompt.contains("text = \">\""))
        assertFalse(prompt.contains("fhg"))
        assertFalse(terminal.contains("TERMINAL_PROMPT_MARKER_OFFSET"))
        assertTrue(prompt.contains("CliBlinkingCursor("))
        assertEquals(2, Regex("Modifier\\.alignByBaseline\\(\\)").findAll(prompt).count())
        assertTrue(prompt.contains("Modifier.alignBy { measured -> measured.measuredHeight }"))
        assertTrue(prompt.contains(".offset(y = CLI_TERMINAL_CURSOR_VERTICAL_OFFSET)"))
        assertFalse(terminal.contains("TERMINAL_PROMPT_CURSOR_OFFSET"))
        assertTrue(terminal.contains("CliType.body.fontSize * CLI_TERMINAL_CURSOR_CAP_HEIGHT_RATIO"))
        assertTrue(terminal.contains("width = cursorHeight * CLI_TERMINAL_CURSOR_WIDTH_RATIO"))
        assertTrue(terminal.contains("if (line.prompt) cliTerminalCommandText(rawBody)"))
        assertTrue(english.contains("name=\"cli_home_terminal_prompt\">command:</string>"))
        assertTrue(russian.contains("name=\"cli_home_terminal_prompt\">команда:</string>"))
        assertFalse(english.contains("name=\"cli_screen_header\""))
        assertFalse(russian.contains("name=\"cli_screen_header\""))
    }

    @Test
    fun `onboarding resources name language appearance and all supported themes`() {
        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")

        assertTrue(english.contains(">language and appearance</string>"))
        assertTrue(russian.contains(">язык и оформление</string>"))
        listOf("Automatic", "Dark", "Night", "Light").forEach { label ->
            assertTrue(english.contains(">$label</string>"))
        }
        listOf("Автоматически", "Тёмная", "Ночь", "Светлая").forEach { label ->
            assertTrue(russian.contains(">$label</string>"))
        }
    }

    private fun cli(relative: String): String = cliRoot().resolve(relative).readText()

    private fun cliRoot(): File = File("src/main/kotlin/com/foxhole/guard/ui/cli")

    private fun resource(relative: String): String = File("src/main/res/$relative").readText()
}
