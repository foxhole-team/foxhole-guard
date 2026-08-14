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
        assertEquals(3, Regex("CliTerminalTimestamp\\(").findAll(terminal).count())
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
    fun `home profile facts replace values immediately without typewriter motion`() {
        val keyValue = cli("components/CliText.kt")
        val typewriter = cli("components/CliTypewriterText.kt")
        val facts = cli("home/CliHomeFacts.kt")
        val identity = cli("home/CliRouteIdentityRows.kt")
        val buttons = cli("home/CliHomeButtons.kt")

        assertFalse(keyValue.contains("animateValue"))
        assertFalse(typewriter.contains("deleteAllOnChange"))
        assertFalse(facts.contains("animateValue"))
        assertFalse(identity.contains("animateValue"))
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
            .substringBefore("// The status is a fixed single line")
        assertTrue(connectedStatus.contains("SpanStyle(color = colors.info)"))
        assertFalse(connectedStatus.contains("SpanStyle(color = colors.ok)"))
        assertTrue(terminal.contains("CliStatusDot("))
        assertTrue(terminal.contains("fontSize = statusStyle.fontSize"))
        assertTrue(terminal.contains("pulsing = home.connection.isRouteTransition()"))
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
        assertTrue(sparkline.contains("val slotX = index * (slotWidth + gap)"))
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
        assertTrue(text.contains("Modifier.offset(y = (-1).dp)"))
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
    fun `terminal clear keeps version and connection readiness in both locales`() {
        val state = cli("home/CliTerminalState.kt")
        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")

        val clear = state.substringAfter("fun clearHistory()").substringBefore("fun onConnection")
        assertTrue(clear.contains("welcomeVersionName?.let"))
        assertTrue(clear.contains("append(\"✻ FoxHole Guard · \$versionName\""))
        assertTrue(clear.contains("append(strings.bootReady, CliLineTone.OK)"))
        assertTrue(english.contains(">ready to connect</string>"))
        assertTrue(russian.contains(">готово к подключению</string>"))
        assertFalse(english.contains("application ready to start"))
        assertFalse(russian.contains("приложение готово к запуску"))
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
        assertEquals(1, Regex("\\.cliDashedBorder\\(colors\\.border\\)").findAll(about).count())
        assertTrue(about.contains("horizontalAlignment = Alignment.CenterHorizontally"))
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
        assertTrue(contextHelp.contains("text = \"?\""))
        assertTrue(contextHelp.contains("requiredSize(CliContextHelpButtonSize)"))
        assertTrue(contextHelp.contains(".border(HELP_CIRCLE_STROKE, colors.accent, CircleShape)"))
        assertTrue(contextHelp.contains("HELP_CIRCLE_SIZE = 16.dp"))
        assertTrue(contextHelp.contains("contentDescription = helpDescription"))
        assertTrue(contextHelp.contains("CliQuickStartItems(body = stringResource(bodyRes), framed = false)"))
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
        assertTrue(lanes.contains("CliDashedInfoNote(text = stringResource(R.string.cli_route_apps_empty))"))
        assertTrue(note.contains("id = R.drawable.pix_info"))
        assertTrue(note.contains("horizontalAlignment = Alignment.CenterHorizontally"))
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
        assertTrue(wizard.contains("transitionSpec = { cliSlide(forward = targetState > initialState) }"))
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
        val exportBlock = transfer
            .substringAfter("// Share mirrors import at narrow widths")
            .substringBefore("private fun CliProfileTransferOverlays")
        val qrRow = exportBlock.substringBefore("Row(")

        assertTrue(qrRow.contains("cli_prof_exp_qr"))
        assertTrue(qrRow.contains("enabled = !busy && selectedKeyCount == 1"))
        assertTrue(qrRow.contains("dimWhenDisabled = false"))
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
        assertTrue(controls.contains("if (exportMode) R.string.cli_prof_export_hint"))
        assertTrue(controls.contains("horizontalArrangement = Arrangement.Center"))
        assertTrue(controls.contains("R.drawable.pix_info"))
        assertFalse(transfer.contains("rememberInfiniteTransition"))

        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")
        assertTrue(english.contains(">to export a VPN profile use the buttons below</string>"))
        assertTrue(russian.contains(">для экспорта профиля VPN воспользуйтесь кнопками ниже</string>"))
    }

    @Test
    fun `map app strip shares the centred arrow anchor without moving the lane`() {
        val route = cli("map/CliRouteScheme.kt")
        val lane = route.substringAfter("private fun CliRouteLane(").substringBefore("/** One canonical")
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
            .substringBefore("// The direct branch")
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

        // Expanded panels own their quiet row stitches; the root itself still has no global rule.
        assertTrue(settings.contains("CliRowDivider()"))
        val module = cli("settings/CliModuleBlock.kt")
        assertFalse(module.contains("cliDashedBorder"))
        assertFalse(module.contains("CliRowDivider("))
        assertTrue(module.contains("CliModuleSettingsConnector(color = settingsActionColor)"))
        assertTrue(module.contains("PathEffect.dashPathEffect("))
        assertTrue(module.contains("actionColor = settingsActionColor"))
        assertTrue(module.contains("if (checked) colors.ok else colors.accent"))
        assertTrue(module.contains("labelColor = colors.dim"))
    }

    @Test
    fun `map neutral chrome reuses brand blue without recoloring semantic route lanes`() {
        val pixelMap = cli("map/CliPixelMap.kt")
        val screen = cli("map/CliMapScreen.kt")
        val route = cli("map/CliRouteScheme.kt")

        assertTrue(pixelMap.contains("lerp(colors.panel, colors.info, 0.10f)"))
        assertTrue(pixelMap.contains("lerp(colors.panel, colors.info, 0.22f)"))
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
}
