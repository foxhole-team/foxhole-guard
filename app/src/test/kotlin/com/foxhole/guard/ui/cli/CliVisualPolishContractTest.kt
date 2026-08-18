package com.foxhole.guard.ui.cli

import androidx.compose.ui.graphics.Color
import com.foxhole.guard.ui.cli.home.cliHomeButtonsReady
import com.foxhole.guard.ui.cli.home.cliStatusScale
import com.foxhole.guard.ui.cli.home.shouldAutoScrollTerminal
import com.foxhole.guard.ui.cli.home.terminalBottomScrollOffset
import com.foxhole.guard.ui.cli.home.terminalOutputBottomIndex
import com.foxhole.guard.ui.cli.onboarding.betaNoticeItems
import com.foxhole.guard.ui.cli.onboarding.quickStartItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliVisualPolishContractTest {
    @Test
    fun `status keeps preferred size when it fits and has a narrow-screen floor`() {
        assertEquals(1f, cliStatusScale(230f, 200f), 0f)
        assertEquals(0.55f, cliStatusScale(100f, 200f), 0f)
    }

    @Test
    fun `terminal notifications allow up to four lines`() {
        val source = cli("home/CliHomeTerminal.kt")
        assertTrue(source.contains("private const val BODY_MAX_LINES = 4"))
        assertTrue(source.contains("maxLines = BODY_MAX_LINES"))
    }

    @Test
    fun `terminal event glyphs align with the first text line`() {
        val terminal = cli("home/CliHomeTerminal.kt")
        val leadSlot = terminal
            .substringAfter("private fun CliTerminalLeadSlot(")
            .substringBefore("private fun RowScope.CliTerminalFootnoteBody(")

        assertTrue(leadSlot.contains("contentAlignment = Alignment.TopStart"))
        assertTrue(
            leadSlot.contains(
                "val modernStyle = LocalCliVisualStyle.current == VisualStyle.PLAIN",
            ),
        )
        assertTrue(leadSlot.contains("if (modernStyle && !promptMarker) 2.dp else 0.dp"))
        assertTrue(leadSlot.contains(".padding(top = firstLineOffset)"))
        assertFalse(leadSlot.contains("VisualStyle.PLAIN) 1.dp"))
        assertEquals(3, Regex("CliTerminalLeadSlot\\(").findAll(terminal).count())
        assertTrue(terminal.contains("icon = cliTerminalLineIcon(line)"))
        assertTrue(terminal.contains("icon = cliLineToneIcon(progress.titleTone, prompt = false)"))
        assertTrue(
            terminal.contains(
                "if (isCliWelcomeLine(line.text)) R.drawable.ic_qs_tile else cliLineToneIcon",
            ),
        )
    }

    @Test
    fun `terminal timestamps use one fixed grid with inset lighter brackets`() {
        val terminal = cli("home/CliHomeTerminal.kt")
        val timestamp = terminal
            .substringAfter("private fun CliTerminalTimestamp(")
            .substringBefore("private const val BLOCK_ROW_STEP_MS")

        assertTrue(terminal.contains("TIMESTAMP_SAMPLE = \"00:00:00\""))
        assertTrue(terminal.contains("TIMESTAMP_INNER_GAP = 2.dp"))
        assertTrue(terminal.contains("TIMESTAMP_BRACKET_LIGHTEN = 0.35f"))
        assertTrue(timestamp.contains("verticalAlignment = Alignment.CenterVertically"))
        assertTrue(timestamp.contains("lerp(colors.dim, colors.fg, TIMESTAMP_BRACKET_LIGHTEN)"))
        assertEquals(2, Regex("Modifier\\.width\\(metrics\\.bracketSlotWidth\\)").findAll(timestamp).count())
        assertTrue(timestamp.contains("Modifier.width(metrics.timeSlotWidth)"))
        assertEquals(4, Regex("CliTerminalTimestamp\\(").findAll(terminal).count())
    }

    @Test
    fun `cold start reserves the two button rows until profiles and settings are ready`() {
        assertFalse(cliHomeButtonsReady(profilesLoaded = false, settingsHydrated = false))
        assertFalse(cliHomeButtonsReady(profilesLoaded = true, settingsHydrated = false))
        assertFalse(cliHomeButtonsReady(profilesLoaded = false, settingsHydrated = true))
        assertTrue(cliHomeButtonsReady(profilesLoaded = true, settingsHydrated = true))

        val home = cli("home/CliHomeScreen.kt")
        assertTrue(home.contains(".height(CLI_HOME_BUTTONS_BLOCK_HEIGHT)"))
        assertTrue(home.contains("48.dp * 2 + CliSpacing.sm"))
        assertTrue(home.contains("R.string.cli_common_loading_interface"))

        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")
        assertTrue(english.contains(">loading data</string>"))
        assertTrue(english.contains(">loading interface</string>"))
        assertTrue(russian.contains(">загрузка данных</string>"))
        assertTrue(russian.contains(">загрузка интерфейса</string>"))
    }

    @Test
    fun `tor first run offers scenario auto switching behind the row info glyph`() {
        val tor = cli("settings/CliTorSubScreen.kt")
        val firstRun = tor.substringAfter("if (!privacyRoute.permitted) {").substringBefore("}")
        assertTrue(firstRun.contains("R.string.cli_cfg_atomic_connection"))
        assertTrue(firstRun.contains("checked = state.settings.connection.atomicConnection"))
        assertTrue(firstRun.contains("onToggle = viewModel::onAtomicConnectionChanged"))
        assertTrue(firstRun.contains("infoText = stringResource(R.string.cli_cfg_atomic_connection_note)"))
        assertFalse(
            "the explanation belongs behind the glyph, not in the section body",
            firstRun.contains("note = stringResource"),
        )

        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")
        assertTrue(english.contains("every operating-mode or VPN/Tor scenario switch asks for confirmation"))
        assertTrue(russian.contains("требует подтверждения"))
    }

    @Test
    fun `insecure import is gated on an explicit default-off consent`() {
        val dialogs = cli("profiles/CliProfileDialogs.kt")
        assertTrue(
            dialogs.contains(
                "var insecureTlsConsent by remember(confirmation.rawInput) { mutableStateOf(false) }",
            ),
        )
        assertTrue(
            dialogs.contains(
                "enabled = confirmation.canConfirm && (!confirmation.insecureTls || insecureTlsConsent)",
            ),
        )
        assertTrue(dialogs.contains("R.string.cli_prof_import_insecure_consent"))
        assertEquals(
            2,
            dialogs.split("(!confirmation.insecureTls || insecureTlsConsent)").size - 1,
        )

        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")
        assertTrue(english.contains("certificate verification is disabled for this server"))
        assertTrue(russian.contains("проверка сертификата отключена"))
        assertFalse(english.contains(">insecure-tls:"))
        assertFalse(russian.contains(">insecure-tls:"))
    }

    @Test
    fun `only route identity values type in and every other row replaces immediately`() {
        val keyValue = cli("components/CliText.kt")
        val typewriter = cli("components/CliTypewriterText.kt")
        val facts = cli("home/CliHomeFacts.kt")
        val identity = cli("home/CliRouteIdentityRows.kt")
        val buttons = cli("home/CliHomeButtons.kt")

        assertTrue(keyValue.contains("animateValue: Boolean = false"))
        assertTrue(identity.contains("animateValue = true"))
        assertFalse(typewriter.contains("deleteAllOnChange"))
        assertFalse(facts.contains("animateValue"))
        assertFalse(
            buttons.substringAfter("internal fun CliProfileProtocolFact").contains("animateValue"),
        )
    }

    @Test
    fun `profile pickers use divided rows and a marquee name protocol table`() {
        val selector = cli("home/CliProfileQuickSelector.kt")
        val profiles = cli("profiles/CliProfilesScreen.kt")

        assertTrue(selector.contains("CliProfileSelectorTableHeader()"))
        assertTrue(selector.contains("R.string.cli_home_key_profile"))
        assertTrue(selector.contains("R.string.cli_home_key_protocol"))
        assertTrue(selector.contains(".basicMarquee("))
        assertTrue(selector.contains("quickSelectorProtocolLabel(profile)"))
        assertTrue(cli("profiles/CliProtocolDropdown.kt").contains("textAlign = TextAlign.Center"))
        assertTrue(profiles.contains("itemsIndexed(state.profiles"))
        assertTrue(profiles.contains("if (index > 0) CliRowDivider()"))
    }

    @Test
    fun `blank template editor says create and keeps disabled button contours visible`() {
        val editor = cli("profiles/CliProfileEditorScreen.kt")

        assertTrue(editor.contains("creating = !allowAddProtocol"))
        assertTrue(editor.contains("if (creating) R.string.cli_prof_create_title"))
        assertEquals(2, Regex("dimWhenDisabled = false").findAll(editor).count())
    }

    @Test
    fun `terminal follows new output only while its viewport remains at the bottom`() {
        assertTrue(
            shouldAutoScrollTerminal(lastIndex = 20, lastVisibleIndex = 19, userScrollInProgress = false),
        )
        assertFalse(
            shouldAutoScrollTerminal(lastIndex = 20, lastVisibleIndex = 12, userScrollInProgress = false),
        )
        assertFalse(
            shouldAutoScrollTerminal(lastIndex = 20, lastVisibleIndex = 20, userScrollInProgress = true),
        )
        assertTrue(
            shouldAutoScrollTerminal(lastIndex = 0, lastVisibleIndex = null, userScrollInProgress = false),
        )

        val app = cli("CliApp.kt")
        val terminal = cli("home/CliHomeTerminal.kt")
        assertTrue(app.contains("val terminalListState = rememberLazyListState()"))
        assertTrue(app.contains("var terminalFollowsOutput by rememberSaveable"))
        assertFalse(terminal.contains("val listState = rememberLazyListState()"))
        assertTrue(terminal.contains("snapshotFlow"))
        assertEquals(12, terminalOutputBottomIndex(lineCount = 12, hasVisibleProgress = false))
        assertEquals(13, terminalOutputBottomIndex(lineCount = 12, hasVisibleProgress = true))
        assertEquals(-796, terminalBottomScrollOffset(0, 800, 4))
        assertEquals(0, terminalBottomScrollOffset(0, 0, 4))
        assertTrue(terminal.contains("terminal-bottom-anchor"))
        assertTrue(terminal.contains("scrollOffset = terminalBottomScrollOffset("))
        assertTrue(terminal.contains("outputLayoutRevision"))
        assertTrue(terminal.contains(".onSizeChanged { size ->"))
        assertTrue(terminal.contains("onOutputHeightChanged()"))
        assertTrue(terminal.contains("TERMINAL_PROMPT_VISUAL_OFFSET"))
        assertTrue(terminal.contains("TERMINAL_PROMPT_VISUAL_OFFSET_MODERN = 7.dp"))
        assertTrue(terminal.contains("TERMINAL_PROMPT_MARKER_OFFSET_MODERN = 1.dp"))
        assertTrue(terminal.contains("if (plainStyle) TERMINAL_PROMPT_MARKER_OFFSET_MODERN else 0.dp"))
        assertTrue(terminal.contains("TERMINAL_PROMPT_CURSOR_OFFSET_MODERN = 1.dp"))
        assertTrue(terminal.contains("if (plainStyle) TERMINAL_PROMPT_CURSOR_OFFSET_MODERN else 0.dp"))
        assertFalse(terminal.contains("Text(text = \"fhg > \""))
    }

    @Test
    fun `Guard brand uses the canonical blue neon on home and onboarding`() {
        val app = cli("CliApp.kt")
        val terminal = cli("home/CliHomeTerminal.kt")
        val homeBrand = terminal.substringAfter("private fun CliBrandTitle")
        val wizardBrand = cli("onboarding/CliOnboardingWizard.kt").substringAfter("private fun WizardHeader")

        assertTrue(homeBrand.contains("text = \"Guard\""))
        assertTrue(homeBrand.contains("color = colors.info"))
        val connectedStatus = terminal
            .substringAfter("val line = buildAnnotatedString")
            .substringBefore("CliStatusDot(")
        assertTrue(connectedStatus.contains("SpanStyle(color = colors.info)"))
        assertFalse(connectedStatus.contains("SpanStyle(color = colors.ok)"))
        assertTrue(terminal.contains("CliStatusDot("))
        assertTrue(terminal.contains("fontSize = statusStyle.fontSize"))
        assertTrue(terminal.contains("pulsing = false"))
        assertTrue(terminal.contains("CliShimmerText("))
        assertTrue(terminal.contains("Modifier.offset(y = STATUS_DOT_VERTICAL_OFFSET * statusScale)"))
        assertTrue(terminal.contains("STATUS_DOT_VERTICAL_OFFSET = (-2).dp"))
        assertTrue(terminal.contains("PIXEL_CAP_HEIGHT_RATIO"))
        assertTrue(wizardBrand.contains("pushStyle(SpanStyle(color = colors.info))"))
        assertEquals(Color(0xFF2FD9F2), CliNeonBlue)
        assertEquals(Color(0xFF58A6FF), CliNoteBlue)
        assertEquals(CliNeonBlue, CliColors().info)
        assertEquals(CliNoteBlue, CliColors().note)
        assertEquals(CliNeonBlue, CliColors().firewall)
        assertFalse(CliColors().info == CliColors().ok)
        assertFalse(CliColors().note == CliColors().info)
        assertTrue(app.contains("FoxholeBannerTone.SUCCESS -> CliLineTone.INFO"))
        assertFalse(app.contains("FoxholeBannerTone.SUCCESS -> CliLineTone.OK"))
    }

    @Test
    fun `statistics plot fills its allocated lane and recent sparse buckets stay right aligned`() {
        val sparkline = cli("stats/CliStatsSparkline.kt")

        assertTrue(sparkline.contains("Row(modifier = modifier.fillMaxWidth())"))
        assertTrue(sparkline.contains("Canvas(modifier = Modifier.fillMaxWidth().height(SPARKLINE_HEIGHT))"))
        assertTrue(sparkline.contains("val slotX = cliStatsSlotLeftPx(index, size.width, bucketCount, gap)"))
        assertTrue(sparkline.contains("cliStatsSlotCenterPx(index, rowWidth.toFloat(), axis.bucketCount, gapPx)"))
        assertFalse(sparkline.contains("index * (slotWidth + gap)"))
    }

    @Test
    fun `help titles marquee only inside their clipped title slot`() {
        val card = cli("settings/CliHelpSubScreen.kt").substringAfter("private fun CliHelpCard")
        assertTrue(card.contains("overflow = TextOverflow.Clip"))
        assertTrue(card.contains("basicMarquee("))
        assertTrue(card.contains("initialDelayMillis = HELP_MARQUEE_INITIAL_DELAY_MS"))
        assertTrue(card.contains("private const val HELP_MARQUEE_INITIAL_DELAY_MS = 1_200"))
    }

    @Test
    fun `info notes use the info glyph help uses a question mark and profile actions need no nudges`() {
        val text = cli("components/CliText.kt")
        val help = cli("settings/CliHelpSubScreen.kt")
        val profiles = cli("profiles/CliProfilesScreen.kt")

        assertTrue(text.contains("private fun CliInfoLine("))
        assertTrue(text.contains("id = R.drawable.pix_info"))
        assertTrue(text.contains("Modifier.offset(y = if (plain) 2.dp else (-1).dp)"))
        assertTrue(text.contains("CliInfoLine("))
        assertFalse(text.contains("text = \"⎿ "))
        assertTrue(help.contains("iconGlyph = \"?\""))
        assertFalse(profiles.contains("iconOffsetX"))
        assertFalse(profiles.contains("PROFILE_HEADER_ICON_NUDGE"))
    }

    @Test
    fun `terminal clear uses the canonical confirm sheet`() {
        val screen = cli("home/CliHomeScreen.kt")
        assertTrue(screen.contains("CliConfirmSheet("))
        assertTrue(screen.contains("terminal.clearHistory()"))
        assertTrue(screen.contains("R.string.cli_terminal_clear_question"))
    }

    @Test
    fun `terminal clear keeps version and the current connection state`() {
        val state = cli("home/CliTerminalState.kt")

        val clear = state.substringAfter("fun clearHistory()").substringBefore("fun onConnection")
        assertTrue(clear.contains("welcomeVersionName?.let"))
        assertTrue(clear.contains("append(welcomeLine(versionName), CliLineTone.ACCENT)"))
        assertTrue(state.contains("WELCOME_LINE_PREFIX = \"FoxHole Guard "))
        assertTrue(state.contains("private fun welcomeLine(versionName: String)"))
        assertTrue(state.contains("\${WELCOME_LINE_PREFIX}v\$versionName"))
        assertTrue(cli("home/CliHomeTerminal.kt").contains("R.drawable.ic_qs_tile"))
        assertTrue(clear.contains("showCurrentStatusNotice()"))
        assertTrue(state.contains("text = currentStatusText"))
        assertFalse(clear.contains("append(strings.bootReady"))
    }

    @Test
    fun `cold start uses the theme specific live progress row`() {
        val state = cli("home/CliTerminalState.kt")
        val terminal = cli("home/CliHomeTerminal.kt")

        assertTrue(state.contains("var bootProgress: CliTerminalProgress?"))
        assertTrue(state.contains("bootProgress =\n                CliTerminalProgress("))
        assertTrue(terminal.contains("terminal.bootProgress ?: terminal.progress"))
        assertTrue(terminal.contains("LocalCliVisualStyle.current == VisualStyle.PLAIN"))
        assertTrue(terminal.contains("PROGRESS_SPINNER_FRAMES[frame]"))
    }

    @Test
    fun `about uses centred dashed tables with the animated widget fox left of version facts`() {
        val about = cli("settings/CliAboutSubScreen.kt")
        val widget =
            listOf(
                File("src/main/kotlin/com/foxhole/guard/widget/FoxStatusWidget.kt"),
                File("app/src/main/kotlin/com/foxhole/guard/widget/FoxStatusWidget.kt"),
                File("../app/src/main/kotlin/com/foxhole/guard/widget/FoxStatusWidget.kt"),
            ).first(File::isFile).readText()

        assertEquals(4, Regex("CliAboutDashedBlock\\(").findAll(about).count())
        assertTrue(about.contains("infoText = infoText"))
        assertTrue(about.contains("private fun CliAboutAnimatedFox()"))
        assertTrue(about.contains("FOX_STATUS_ANIMATION_FRAMES[frameIndex]"))
        assertTrue(about.contains("delay(FOX_STATUS_FRAME_DURATION_MS)"))
        assertTrue(widget.contains("internal val FOX_STATUS_ANIMATION_FRAMES"))
        assertTrue(widget.contains("internal const val FOX_STATUS_FRAME_DURATION_MS"))
        assertTrue(about.indexOf("CliAboutAnimatedFox()") < about.indexOf("CliAboutVersionTable("))
        assertFalse(about.contains("CliFoxHero"))
        assertTrue(about.contains("modifier = Modifier.weight(1f)"))
        assertTrue(about.contains("private val ABOUT_FOX_SIZE = 72.dp"))
        assertTrue(about.contains("CliAboutVersionTable("))
        assertTrue(about.contains("BuildConfig.FOXCORE_SOURCE_VERSION"))
        assertTrue(about.contains("BuildConfig.ARTI_VERSION"))
        assertTrue(about.contains("ABOUT_LICENSES.forEachIndexed"))
        assertTrue(about.contains("ABOUT_LINKS.forEachIndexed"))
        assertTrue(about.contains("if (index > 0) CliRowDivider()"))
        assertFalse(about.contains("cliMarchingBorder"))
    }

    @Test
    fun `update source settings use the settings gear consistently`() {
        val source = cli("settings/CliUpdateSourcesSheet.kt")
        assertEquals(2, Regex("R\\.drawable\\.pix_settings").findAll(source).count())
        assertFalse(source.contains("R.drawable.pix_link"))
        assertFalse(source.contains("R.drawable.pix_update"))
    }

    @Test
    fun `quick start is the same icon checklist in first run and help`() {
        val firstRun = cli("onboarding/CliQuickStartSheet.kt")
        val help = cli("settings/CliHelpSubScreen.kt")
        assertTrue(firstRun.contains("CliQuickStartItems(body = body, framed = true)"))
        assertFalse(firstRun.contains("CliFoxHero"))
        assertTrue(firstRun.contains("sheetGesturesEnabled = false"))
        assertTrue(help.contains("CliQuickStartItems(body = body, framed = false)"))

        val sample = quickStartItems("first sentence.\n\nsecond sentence.\nthird sentence.")
        assertEquals(listOf("first sentence.", "second sentence.", "third sentence."), sample.map { it.text })
        assertTrue(sample.all { item -> item.icon != 0 })
    }

    @Test
    fun `section headers and contextual help share the canonical pixel grammar`() {
        val header = cli("components/CliScreenHeader.kt")
        val contextHelp = cli("components/CliContextHelp.kt")
        val icon = header.indexOf("if (icon != null)")
        val brand = header.indexOf("text = brandText", startIndex = icon)
        val title = header.indexOf("text = label", startIndex = brand)

        assertTrue(icon >= 0)
        assertTrue(brand > icon)
        assertTrue(title > brand)
        assertTrue(contextHelp.contains("requiredSize(CliContextHelpButtonSize)"))
        assertTrue(contextHelp.contains("id = R.drawable.pix_info"))
        assertTrue(contextHelp.contains("size = iconSize"))
        assertTrue(contextHelp.contains("CliModernTopBarHelpIconSize = 20.dp"))
        assertTrue(contextHelp.contains("CliContextHelpButtonSize = CliHeaderControlSlotHeight"))
        assertTrue(contextHelp.contains("contentDescription = helpDescription"))
        assertTrue(contextHelp.contains("CliDashedInfoNote("))
        assertTrue(contextHelp.contains("centeredIconFirstLine = true"))
    }

    @Test
    fun `routing scenarios keep semantic coloured icons in menu and selected value`() {
        val dropdown = cli("components/CliDropdownOption.kt")
        val lanes = cli("settings/CliRoutingAppLanesSection.kt")
        val note = cli("components/CliText.kt")

        assertTrue(dropdown.contains("val iconTint: Color? = null"))
        assertTrue(dropdown.contains("showSelectedOptionIcon: Boolean = false"))
        assertTrue(dropdown.contains("tint = option.iconTint ?:"))
        assertTrue(lanes.contains("iconTint = appLaneColor(candidate, colors)"))
        assertTrue(lanes.contains("showSelectedOptionIcon = true"))
        assertTrue(lanes.contains("AppTunnelLane.TOR -> colors.tor"))
        assertTrue(lanes.contains("AppTunnelLane.VPN -> colors.vpn"))
        assertTrue(lanes.contains("AppTunnelLane.BLOCK -> colors.err"))
        assertTrue(lanes.contains("AppTunnelLane.EXCLUDE -> colors.dim"))
        assertTrue(
            lanes.contains(
                "centeredIconLeading = true",
            ),
        )
        assertTrue(lanes.contains("centeredIconFirstLine = true"))
        val sites = cli("settings/CliRoutingSiteRulesSection.kt")
        assertTrue(sites.contains("centeredIconFirstLine = true"))
        assertTrue(note.contains("id = R.drawable.pix_info"))
        val dashed = note
            .substringAfter("internal fun CliDashedInfoNote(")
            .substringBefore("private fun CliInfoLine(")
        assertTrue(dashed.contains("if (centered)"))
        assertTrue(dashed.contains("iconLeading = centeredIconLeading"))
        assertTrue(dashed.contains("iconFirstLine = centeredIconFirstLine"))
        assertTrue(dashed.contains("horizontalArrangement = Arrangement.Center"))
        assertTrue(dashed.contains("if (iconFirstLine) Alignment.Top else Alignment.CenterVertically"))
        assertTrue(dashed.contains("Row(verticalAlignment = Alignment.Top)"))
    }

    @Test
    fun `site rules keep remove at the bottom of the shared dropdown`() {
        val sites = cli("settings/CliRoutingSiteRulesSection.kt")
        val row = sites
            .substringAfter("private fun CliSiteRuleRow(")
            .substringBefore("private fun siteRuleTokens(")

        assertTrue(row.contains("options = siteLaneOptions(colors) + CliDropdownOption("))
        assertTrue(row.contains("id = SITE_OPT_REMOVE"))
        assertTrue(row.contains("R.string.cli_route_remove"))
        assertTrue(row.contains("R.drawable.pix_cross"))
        assertTrue(row.contains("if (id == SITE_OPT_REMOVE) onRemove()"))
        assertFalse(row.contains("Text(text = \"[x]\""))
        assertFalse(row.contains("defaultMinSize"))
    }

    @Test
    fun `beta and every help article use the same icon row grammar`() {
        val beta = cli("onboarding/CliBetaNoticeSheet.kt")
        val help = cli("settings/CliHelpSubScreen.kt")
        val items = betaNoticeItems("first\nsecond", "dns limit")
        val fullItems = betaNoticeItems("one\ntwo\nthree\nfour\nfive\ndonation", "dns limit")

        assertEquals(listOf("first", "second", "dns limit"), items.map { item -> item.text })
        assertEquals(
            listOf("donation", "one", "two", "three", "four", "five", "dns limit"),
            fullItems.map { item -> item.text },
        )
        assertTrue(items.all { item -> item.icon != 0 })
        assertTrue(beta.contains("CliIconTextItems("))
        assertTrue(beta.contains("framed = true"))
        assertTrue(beta.contains(".cliMarchingBorder(colors.accent)"))
        assertTrue(beta.contains("items = items.drop(1)"))
        assertTrue(help.contains("CliHelpBody(body = body, icon = section.icon)"))
        assertTrue(help.contains("id = icon"))
        assertTrue(help.contains("modifier = Modifier.weight(1f)"))

        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")
        assertTrue(english.contains("cli_beta_notice_dns_protocols"))
        assertTrue(russian.contains("cli_beta_notice_dns_protocols"))
        assertTrue(english.contains("WireGuard uses the profile’s DNS server without filtering"))
        assertTrue(russian.contains("WireGuard использует DNS-сервер профиля без фильтрации"))
        assertTrue(english.contains(">web address exclusions</string>"))
        assertTrue(russian.contains(">исключения по веб-адресам</string>"))
    }

    @Test
    fun `first run wizard keeps chrome fixed and uses safe FoxHole DB defaults`() {
        val wizard = cli("onboarding/CliOnboardingWizard.kt")
        assertTrue(wizard.indexOf("WizardHeader()") < wizard.indexOf("AnimatedContent("))
        assertTrue(wizard.contains("cliPanelSwap(forward = targetState > initialState, plain = plainStyle)"))
        assertTrue(wizard.contains("Box(modifier = modifier, contentAlignment = Alignment.TopStart)"))
        assertTrue(wizard.contains("modifier = Modifier.fillMaxSize().clipToBounds()"))
        assertFalse(wizard.contains("slideInVertically"))
        assertFalse(wizard.contains("slideOutVertically"))
        val license = wizard
            .substringAfter("private fun WizardLicenseStep")
            .substringBefore("private fun WizardComponentsStep")
        assertTrue(license.contains(".cliDashedBorder(colors.note)"))
        assertTrue(license.contains("R.drawable.pix_info"))
        assertTrue(license.contains("textAlign = TextAlign.Center"))
        assertTrue(license.contains("if (validationError)"))
        assertTrue(license.contains(".cliDashedBorder(colors.err)"))
        assertTrue(license.contains("R.string.cli_wizard_license_required"))
        assertTrue(wizard.contains("if (choices.licenseAccepted)"))
        assertTrue(wizard.contains("licenseValidationError = true"))
        assertTrue(wizard.contains("if (next.licenseAccepted) licenseValidationError = false"))
        val footer = wizard
            .substringAfter("private fun WizardFooter")
            .substringBefore("private data class OnboardingWizardChoices")
        val licenseButtons = footer.substringAfter("if (step == LICENSE_STEP)").substringBefore("} else if")
        assertTrue(licenseButtons.contains("R.string.cli_wizard_skip"))
        assertTrue(licenseButtons.contains("Modifier.weight(1f)"))
        assertTrue(licenseButtons.contains("enabled = true"))
        assertTrue(licenseButtons.contains("dimWhenDisabled = false"))
        assertTrue(footer.contains("enabled = step == LICENSE_STEP || canContinue"))
        assertTrue(footer.contains("filled = canContinue && step != LICENSE_STEP"))
        assertTrue(footer.contains("dimWhenDisabled = step != LICENSE_STEP"))
        assertTrue(footer.contains(".height(WIZARD_SECOND_ACTION_HEIGHT)"))
        assertTrue(footer.contains("if (step == DATA_STEP)"))
        assertTrue(wizard.contains("WIZARD_SECOND_ACTION_HEIGHT = 48.dp"))
        assertTrue(wizard.contains("val geoDownload: Boolean = true"))
        assertTrue(wizard.contains("val dnsDownload: Boolean = false"))
        assertTrue(wizard.contains("val torBridgesDownload: Boolean = false"))
        assertTrue(wizard.contains("val threatIntelDownload: Boolean = false"))
        assertTrue(wizard.contains("val autoUpdate: Boolean = true"))
        assertTrue(wizard.contains("OnboardingDownload.THREAT_INTEL"))

        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")
        assertTrue(english.contains("Bugs are possible in complex proxy scenarios"))
        assertTrue(russian.contains("возможны баги в сложных прокси-сценариях"))
        assertTrue(english.contains("Accept the license agreement first"))
        assertTrue(russian.contains("Сначала примите лицензионное соглашение"))
    }

    @Test
    fun `profile import uses full width qr over equal file and clipboard buttons at 320dp`() {
        val transfer = cli("profiles/CliProfileTransfer.kt")
        val importBlock = transfer
            .substringAfter("private fun CliProfileTransferButtons")
            .substringAfter("if (!exportMode)")
            .substringBefore("return")
        val qrRow = importBlock.substringBefore("Row(")
        assertTrue(qrRow.contains("cli_prof_imp_qr"))
        assertTrue(qrRow.contains("modifier = Modifier.fillMaxWidth()"))
        assertEquals(2, Regex("modifier = Modifier\\.weight\\(1f\\)").findAll(importBlock).count())
        assertTrue(importBlock.contains("horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)"))
        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")
        assertTrue(english.contains(">Scan QR code</string>"))
        assertTrue(english.contains(">File</string>"))
        assertTrue(english.contains(">Clipboard</string>"))
        assertTrue(russian.contains(">Сканировать QR-код</string>"))
        assertTrue(russian.contains(">Файл</string>"))
        assertTrue(russian.contains(">Буфер обмена</string>"))

        val confirm = cli("profiles/CliProfileDialogs.kt").substringBefore("formatExpiryDate")
        assertFalse(confirm.contains("CliChip("))
        assertTrue(confirm.contains("dashed = true"))
        assertTrue(confirm.contains("modifier = Modifier.fillMaxWidth()"))
    }

    @Test
    fun `profile share keeps full width qr over equal file and clipboard buttons at 320dp`() {
        val transfer = cli("profiles/CliProfileTransfer.kt")
        val buttons = transfer
            .substringAfter("private fun CliProfileTransferButtons(")
            .substringBefore("private fun CliProfileTransferOverlays")
        val exportBlock =
            buttons.substringAfterLast("Column(verticalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {")
        val qrRow = exportBlock.substringBefore("Row(")

        assertTrue(qrRow.contains("cli_prof_exp_qr"))
        assertTrue(buttons.contains("val qrReady = selectedKeyCount == 1"))
        assertTrue(qrRow.contains("R.string.cli_prof_exp_qr_pick_one"))
        assertTrue(qrRow.contains("enabled = !busy && qrReady"))
        assertFalse(qrRow.contains("dimWhenDisabled = false"))
        assertTrue(qrRow.contains("modifier = Modifier.fillMaxWidth()"))
        assertEquals(2, Regex("modifier = Modifier\\.weight\\(1f\\)").findAll(exportBlock).count())
        assertTrue(exportBlock.contains("cli_prof_exp_file"))
        assertTrue(exportBlock.contains("cli_prof_exp_clip"))

        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")
        assertTrue(english.contains(">Share as QR code</string>"))
        assertTrue(english.contains(">Save to file</string>"))
        assertTrue(english.contains(">Copy to clipboard</string>"))
        assertTrue(russian.contains(">Поделиться QR-кодом</string>"))
        assertTrue(russian.contains(">Сохранить в файл</string>"))
        assertTrue(russian.contains(">Скопировать в буфер</string>"))
    }

    @Test
    fun `profile import and export keep one stable dashed geometry`() {
        val transfer = cli("profiles/CliProfileTransfer.kt")
        val controls = transfer
            .substringAfter("private fun CliProfileTransferControls")
            .substringBefore("private fun CliProfileTransferButtons")

        assertEquals(1, Regex("cliDashedBorder\\(").findAll(controls).count())
        assertTrue(controls.contains("if (exportMode) colors.info else colors.border"))
        assertFalse(controls.contains("R.drawable.pix_info"))
        assertFalse(controls.contains("cli_prof_export_hint"))
        assertFalse(transfer.contains("rememberInfiniteTransition"))

        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")
        assertTrue(english.contains(">select one VPN profile</string>"))
        assertTrue(russian.contains(">Выберите один профиль впн</string>"))
    }

    @Test
    fun `map app strip shares the centred arrow anchor without moving the lane`() {
        val route = cli("map/CliRouteScheme.kt")
        val lane = route.substringAfter("private fun CliRouteLane(").substringBefore("private fun CliRouteAppStrip(")
        val arrow = route.substringAfter("private fun CliRouteArrow(").substringBefore("private fun cliRouteLanes")

        assertTrue(lane.contains("index == lane.appSegmentIndex"))
        assertTrue(lane.contains("CliRouteArrow("))
        assertFalse(lane.contains("padding(bottom"))
        assertFalse(lane.contains("Alignment.BottomCenter"))
        assertTrue(arrow.contains("appStrip: CliRouteAppStripPresentation?"))
        assertTrue(arrow.contains("Box(modifier = Modifier.height(ARROW_NOTE_SLOT_HEIGHT)"))
        assertTrue(arrow.contains("CliRouteAppStrip(presentation = appStrip, color = appColor)"))
        assertTrue(route.contains("private const val MAX_ROUTE_APP_ICONS = 6"))
        assertTrue(route.contains("val torSegment = CliRouteSegmentModel(colors.tor, detail ="))
        val splitLane = route
            .substringAfter("private fun CliRouteParts.splitLanes")
            .substringBefore("private fun CliRouteParts.directBranchLane(")
        assertTrue(splitLane.contains("appSegmentIndex = 1"))
        val proxyTorLane = route
            .substringAfter("private fun CliRouteParts.proxyTorLanes")
            .substringBefore("private fun CliRouteParts.splitLanes")
        assertTrue(proxyTorLane.contains("appSegmentIndex = 1"))
    }

    @Test
    fun `settings primary sections use quiet spacing without root dashed dividers`() {
        val settings = cli("settings/CliSettingsScreen.kt")
        val root =
            settings
                .substringAfter("private fun CliSettingsRootColumn")
                .substringBefore("private fun CliCfgSubScreen")

        assertFalse(root.contains("CliSettingsSectionDivider"))
        assertFalse(root.contains("PathEffect.dashPathEffect"))
        val primarySections =
            root
                .substringAfter("CliNetworkSection(")
                .substringBefore("CliApplicationSection(")
        val rootSectionSpacers =
            Regex("Spacer\\(modifier = Modifier\\.height\\(CliSpacing\\.sm\\)\\)")
                .findAll(primarySections)
                .count()
        assertEquals(4, rootSectionSpacers)

        assertTrue(settings.contains("CliRowDivider()"))
        val module = cli("settings/CliModuleBlock.kt")
        assertFalse(module.contains("cliDashedBorder"))
        assertFalse(module.contains("CliRowDivider("))
        assertTrue(module.contains("CliModuleSettingsConnector(color = settingsActionColor)"))
        assertTrue(module.contains("PathEffect.dashPathEffect("))
        assertTrue(module.contains("actionColor = settingsActionColor"))
        assertTrue(module.contains("val settingsActionColor = colors.accent"))
        assertTrue(module.contains("labelColor = colors.dim"))
    }

    @Test
    fun `map neutral chrome reuses brand blue without recoloring semantic route lanes`() {
        val pixelMap = cli("map/CliPixelMap.kt")
        val screen = cli("map/CliMapScreen.kt")
        val route = cli("map/CliRouteScheme.kt")

        assertTrue(pixelMap.contains("lerp(colors.panel, colors.info, 0.10f)"))
        assertTrue(pixelMap.contains("lerp(colors.panel, colors.info, if (plain) 0.45f else 0.22f)"))
        assertTrue(pixelMap.contains("if (blinkOn) colors.info else colors.info.copy(alpha = 0.35f)"))
        assertTrue(pixelMap.contains("drawMarkerHalo(cell, colors.info"))
        assertFalse(pixelMap.contains("lerp(colors.panel, colors.vpn"))
        assertFalse(pixelMap.contains("colors.accent"))
        assertFalse(screen.contains("colors.accent"))
        assertTrue(route.substringAfter("private fun internetNode").contains("colors.info"))

        assertTrue(route.contains("colors.vpn"))
        assertTrue(route.contains("colors.tor"))
        assertTrue(route.contains("colors.i2p"))
        assertTrue(route.contains("colors.firewall"))
    }

    @Test
    fun `device dns protection copy is exact and localized`() {
        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")

        assertTrue(english.contains(">Device DNS protection</string>"))
        assertTrue(
            english.contains(">Replaces the system DNS and enables DNS filtering without an active mode.</string>"),
        )
        assertTrue(russian.contains(">Защита DNS устройства</string>"))
        assertTrue(
            russian.contains(">Заменяет системный DNS и запускает фильтрацию DNS без активного режима.</string>"),
        )
    }

    @Test
    fun `localized quick start has twelve explicit items and documents both holds`() {
        listOf("values/strings.xml", "values-ru/strings.xml").forEach { path ->
            val xml = resource(path)
            val body =
                Regex("""<string name="cli_help_start_body">(.*?)</string>""")
                    .find(xml)
                    ?.groupValues
                    ?.get(1)
                    ?: error("missing cli_help_start_body in $path")
            assertEquals("wrong quick-start item count in $path", 12, body.split("\\n").size)
            assertTrue(
                "terminal hold missing in $path",
                body.contains("terminal", ignoreCase = true) || body.contains("терминал", ignoreCase = true),
            )
            assertTrue("STATUS hold missing in $path", body.contains("STATUS") || body.contains("СТАТУС"))
            assertTrue("v2raytun refresh missing in $path", body.contains("v2raytun", ignoreCase = true))
            assertTrue("START hold missing in $path", body.contains("START") || body.contains("СТАРТ"))
        }
        val russian = resource("values-ru/strings.xml")
        assertTrue(russian.contains("\\nДетект новых приложений"))
    }

    @Test
    fun `the dock pill is derived from the item padding so the bar height stays adjustable`() {
        val dock = cli("components/CliHintBar.kt")

        assertTrue(dock.contains("private val MODERN_DOCK_DROP_TOP =\n"))
        assertTrue(dock.contains("MODERN_DOCK_ITEM_PADDING - (MODERN_DOCK_DROP_HEIGHT - MODERN_DOCK_ICON_SIZE) / 2"))
        assertTrue(
            dock.contains(
                "MODERN_DOCK_ITEM_PADDING_COMPACT - (MODERN_DOCK_DROP_HEIGHT_COMPACT - MODERN_DOCK_ICON_SIZE_COMPACT) / 2",
            ),
        )
        assertTrue(dock.contains("if (compact) MODERN_DOCK_ITEM_PADDING_COMPACT else MODERN_DOCK_ITEM_PADDING"))
        assertTrue(dock.contains("if (compact) MODERN_DOCK_ICON_SIZE_COMPACT else MODERN_DOCK_ICON_SIZE"))
        val minHeight = Regex("""MODERN_DOCK_MIN_HEIGHT = (\d+)\.dp""").find(dock)!!.groupValues[1].toInt()
        assertTrue("dock below the 48dp touch floor", minHeight >= 48)
        val compactMinHeight =
            Regex("""MODERN_DOCK_MIN_HEIGHT_COMPACT = (\d+)\.dp""").find(dock)!!.groupValues[1].toInt()
        assertTrue("compact dock below the 48dp touch floor", compactMinHeight >= 48)
    }

    @Test
    fun `every more-settings row is separated by the shared divider`() {
        val settings = cli("settings/CliSettingsScreen.kt")
        val more = settings
            .substringAfter("private fun CliMoreSection(")
            .substringBefore("private fun CliTerminalClearRows(")

        val rows = Regex("""CliActionRow\(""").findAll(more).count()
        val dividers = Regex("""CliRowDivider\(\)""").findAll(more).count()
        assertEquals(4, rows)
        assertEquals("every adjacent pair needs one divider", rows - 1, dividers)
    }

    @Test
    fun `the terminal flag stays on the single right aligned value row`() {
        val terminal = cli("home/CliHomeTerminal.kt")
        val columns = terminal
            .substringAfter("private fun RowScope.CliTerminalKeyValueColumns(")
            .substringBefore("private fun RowScope.CliTerminalValueText(")
        val valueText = terminal
            .substringAfter("private fun RowScope.CliTerminalValueText(")
            .substringBefore("private fun CliTerminalAppIcon(")

        assertTrue(columns.contains("FLAGGED_VALUE_COLUMN_WEIGHT"))
        assertTrue(valueText.contains("horizontalArrangement = Arrangement.End"))
        assertTrue(valueText.contains("maxLines = 1"))
        assertTrue(valueText.contains("softWrap = false"))
        assertTrue(valueText.contains("CliFlagIcon(countryCode = country, style = CliType.small)"))
        assertFalse(valueText.contains("appendInlineContent(VALUE_FLAG_SLOT"))
    }

    @Test
    fun `profile action glyphs share the row and carry no cancel button`() {
        val profiles = cli("profiles/CliProfileListItem.kt")
        val actions = profiles
            .substringAfter("private fun CliProfileActionsRow(")
            .substringBefore("private fun CliProfileActionGlyph(")

        val glyphs = Regex("""CliProfileActionGlyph\(""").findAll(actions).count()
        assertEquals(4, glyphs)
        assertEquals(
            "each glyph takes an equal share",
            glyphs,
            Regex("""Modifier\.weight\(1f\)""").findAll(actions).count(),
        )
        assertFalse("cancel is carried by tapping away, not by a sixth glyph", actions.contains("pix_cross"))
        assertFalse(actions.contains("cli_common_no_cancel"))
    }

    @Test
    fun `one shared clock drives the text sweep, the route arrows and the accent edge`() {
        val shimmer = cli("components/CliShimmerText.kt")
        val frame = cli("components/CliModalFrame.kt")
        val route = cli("map/CliRouteScheme.kt")
        val clock = cli("components/CliMotionClock.kt")

        assertTrue(shimmer.contains("internal const val CLI_SHIMMER_CYCLE_MS"))
        assertTrue(clock.contains("internal fun cliMotionPhase("))
        assertTrue(clock.contains("cycleMs: Int = CLI_SHIMMER_CYCLE_MS"))
        assertTrue(clock.contains("withInfiniteAnimationFrameNanos"))
        listOf(shimmer, frame, route).forEach { source ->
            assertTrue("every continuous animation reads the shared clock", source.contains("cliMotionPhase()"))
            assertFalse(
                "a private transition is a second clock and a second phase",
                source.contains("rememberInfiniteTransition"),
            )
        }
        assertFalse("a second sweep duration is a second animation", frame.contains("ACCENT_SWEEP_DURATION_MS"))
    }

    @Test
    fun `typed text shares one character cadence`() {
        val clock = cli("components/CliMotionClock.kt")
        val shimmer = cli("components/CliShimmerText.kt")
        val typewriter = cli("components/CliTypewriterText.kt")

        assertTrue(clock.contains("internal const val CLI_TYPE_STEP_MS"))
        assertTrue(clock.contains("internal const val CLI_ERASE_STEP_MS"))
        listOf(shimmer, typewriter).forEach { source ->
            assertTrue(source.contains("delay(CLI_TYPE_STEP_MS)"))
            assertTrue(source.contains("delay(CLI_ERASE_STEP_MS)"))
        }
        assertFalse("the shimmer typed at its own speed", shimmer.contains("TYPE_STEP_MS = "))
        assertFalse("the typewriter typed at its own speed", typewriter.contains("TYPE_CHAR_DELAY_MS"))
    }

    @Test
    fun `panel headers give the title every pixel the trailing controls leave`() {
        val panel = cli("components/CliPanel.kt")

        assertFalse(panel.contains("Spacer(modifier = Modifier.weight(1f))"))
        assertTrue(panel.contains("private fun CliPanelTitleGroup("))
        val plainHeader = panel
            .substringAfter("internal fun CliPanel(")
            .substringBefore("private fun CliPanelCollapsibleHeader(")
        val collapsibleHeader = panel
            .substringAfter("private fun CliPanelCollapsibleHeader(")
            .substringBefore("private fun CliPanelTitleGroup(")
        assertTrue(plainHeader.contains("CliPanelTitleGroup("))
        assertTrue(collapsibleHeader.contains("CliPanelTitleGroup("))
    }

    @Test
    fun `every value entry modal opens under an icon`() {
        val callSites = listOf(
            "components/CliRetentionRow.kt",
            "settings/CliLanProxySubScreen.kt",
            "settings/CliRoutingSiteRulesSection.kt",
            "settings/CliSettingsDnsSection.kt",
            "settings/CliSettingsScreen.kt",
        ).flatMap { path ->
            cli(path).split("CliInputModal(").drop(1).map { path to it.take(SHEET_HEADER_SCAN_CHARS) }
        }

        assertEquals(7, callSites.size)
        callSites.forEach { (path, head) ->
            assertTrue("CliInputModal in $path opens without an icon", head.contains("icon = R.drawable."))
        }
        assertTrue(cli("components/CliInputModal.kt").contains("@DrawableRes icon: Int? = null"))
    }

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
        const val SHEET_HEADER_SCAN_CHARS = 220
    }
}
