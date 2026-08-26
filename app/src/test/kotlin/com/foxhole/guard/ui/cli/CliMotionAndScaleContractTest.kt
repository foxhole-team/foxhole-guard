package com.foxhole.guard.ui.cli

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.guard.ui.cli.components.CLI_BUTTON_LEADING_ICON_DROP
import com.foxhole.guard.ui.cli.components.CLI_BUTTON_LEADING_ICON_SIZE
import com.foxhole.guard.ui.cli.components.cliStatusDotAnimates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal class CliMotionAndScaleContractTest {
    private fun cli(path: String): String =
        File("src/main/kotlin/com/foxhole/guard/ui/cli/$path").readText()

    @Test
    fun `the motion tokens are the Material 3 easing and duration values`() {
        val theme = cli("CliTheme.kt")

        assertEquals(100, CliMotion.DurationShort)
        assertEquals(200, CliMotion.DurationQuick)
        assertEquals(300, CliMotion.DurationMedium)
        assertEquals(400, CliMotion.DurationEmphasis)

        assertTrue(theme.contains("CubicBezierEasing(0.2f, 0f, 0f, 1f)"))
        assertTrue(theme.contains("CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)"))
        assertTrue(theme.contains("CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)"))
    }

    @Test
    fun `enter decelerates, exit accelerates and is shorter than the entrance`() {
        val enterHead = CliMotion.EasingEnter.transform(0.02f)
        val exitHead = CliMotion.EasingExit.transform(0.02f)
        assertTrue("enter must lead the clock, not lag it", enterHead > 0.02f)
        assertTrue("exit must start slower than linear", exitHead < 0.02f)
        assertTrue(CliMotion.DurationShort < CliMotion.DurationMedium)
    }

    @Test
    fun `press, settle and emphasis exist as springs so an interruption bends the motion`() {
        val theme = cli("CliTheme.kt")
        val specs = theme.substringAfter("object CliMotion {").substringBefore("\n}")

        assertTrue(specs.contains("fun <T> press(): SpringSpec<T>"))
        assertTrue(specs.contains("fun <T> settle(): SpringSpec<T>"))
        assertTrue(specs.contains("fun <T> emphasis(): SpringSpec<T>"))
        val bouncy = "spring(dampingRatio = Spring.DampingRatioLowBouncy"
        val damped = "spring(dampingRatio = Spring.DampingRatioNoBouncy"
        assertTrue(specs.substringAfter("fun <T> emphasis()").contains(bouncy))
        assertTrue(specs.substringAfter("fun <T> settle()").substringBefore("fun <T> emphasis()").contains(damped))
        assertTrue(specs.substringAfter("fun <T> press()").substringBefore("fun <T> settle()").contains(damped))
    }

    @Test
    fun `interactive components read the tokens instead of carrying their own timings`() {
        val button = cli("components/CliButton.kt")
        val dock = cli("components/CliHintBar.kt")
        val swap = cli("CliMotion.kt")

        listOf(button, dock).forEach { source ->
            assertTrue("every animated surface reads the tokens", source.contains("CliMotion."))
        }
        assertFalse("a local press duration is a second motion law", button.contains("PLAIN_PRESS_MS"))
        assertTrue(button.contains("targetValue = if (pressed) PRESS_SCALE else 1f"))
        assertFalse(button.contains("plainPress && pressed"))
        assertFalse(button.contains("animationSpec = tween("))
        assertFalse(dock.contains("animationSpec = spring("))
        assertFalse(swap.contains("fadeIn(tween("))
        assertTrue(swap.contains("spring(stiffness = Spring.StiffnessLow)"))
    }

    @Test
    fun `text button icons share a larger cap-height slot with a slight optical lift`() {
        val button = cli("components/CliButton.kt")

        assertEquals(18.dp, CLI_BUTTON_LEADING_ICON_SIZE)
        assertEquals((-1).dp, CLI_BUTTON_LEADING_ICON_DROP)
        assertTrue(button.contains(".size(CLI_BUTTON_LEADING_ICON_SIZE)"))
        assertTrue(button.contains(".offset(y = CLI_BUTTON_LEADING_ICON_DROP)"))
        assertTrue(button.contains("iconContent != null -> Box("))
        assertTrue(button.contains("scaleX = CLI_ICON_DRAW_SCALE"))
        assertTrue(button.contains("scaleY = CLI_ICON_DRAW_SCALE"))
    }

    @Test
    fun `the interface uses one restrained Android navigation law`() {
        val motion = cli("CliMotion.kt")
        val app = cli("CliApp.kt")

        assertTrue(motion.contains("NAVIGATION_SHIFT_DIVISOR"))
        assertTrue(motion.contains("full * direction / NAVIGATION_SHIFT_DIVISOR"))
        assertTrue(motion.contains("internal fun cliSlide("))
        assertFalse(motion.contains("cliPanelSwap"))
        assertFalse(motion.contains("plain: Boolean"))
        assertFalse(motion.contains("initialOffsetX = { full -> full * direction }"))
        assertTrue(app.contains("BackHandler(enabled = screen != CliScreen.HOME)"))
        assertFalse(app.contains("PredictiveBackHandler"))
        assertFalse(app.contains("backPeek"))
    }

    @Test
    fun `terminal typing and placed rows share one clock`() {
        val terminal = cli("home/CliHomeTerminal.kt")
        val promptEffect = terminal
            .substringAfter("LaunchedEffect(prompt)")
            .substringBefore("Row(modifier = modifier")
        val placedEntrance = terminal
            .substringAfter("private fun Modifier.cliPlacedRowEntrance")
            .substringBefore("private fun RowScope.CliTerminalKeyValueColumns")

        assertTrue(promptEffect.contains("PROMPT_TYPE_STEP_MS"))
        assertFalse(promptEffect.contains("plainStyle"))
        assertFalse(promptEffect.contains("delay(40L)"))
        assertTrue(placedEntrance.contains("CliMotion.enter"))
        assertFalse(placedEntrance.contains("VisualStyle"))
    }

    @Test
    fun `collapsible panels and sheet steps use the shared restrained motion`() {
        val panel = cli("components/CliPanel.kt")
        val help = cli("settings/CliHelpSubScreen.kt")
        val dataset = cli("settings/CliDatasetActivationSheet.kt")

        listOf(panel, help).forEach { source ->
            assertTrue(source.contains("enter = cliVerticalEnter()"))
            assertTrue(source.contains("exit = cliVerticalExit()"))
            assertFalse(source.contains("expandVertically("))
            assertFalse(source.contains("shrinkVertically("))
        }
        assertTrue(dataset.contains("cliSlide(forward = targetState.ordinal > initialState.ordinal)"))
        assertFalse(dataset.contains("slideInHorizontally { width -> width }"))
    }

    @Test
    fun `selection emphasis is the theme accent and never a literal colour`() {
        val emphasis = cli("components/CliEmphasis.kt")

        assertTrue(emphasis.contains("fun Modifier.cliSelectionEmphasis("))
        assertTrue(emphasis.contains("colors.accent"))
        assertFalse("emphasis must follow the palette", emphasis.contains("Color(0xFF"))
        assertTrue(emphasis.contains("graphicsLayer"))
    }

    @Test
    fun `the rejection gesture uses the platform haptic and converges to rest`() {
        val emphasis = cli("components/CliEmphasis.kt")

        assertTrue("the platform's own rejection affordance", emphasis.contains("HapticFeedbackConstants.REJECT"))
        assertTrue(emphasis.contains("Build.VERSION_CODES.R"))
        assertTrue(emphasis.contains("HapticFeedbackConstants.LONG_PRESS"))
        assertFalse(emphasis.contains("infiniteRepeatable"))
        assertTrue(emphasis.contains("0f at REJECT_SHAKE_MS"))
    }

    @Test
    fun `one unified reference ladder has one bilingual pixel heading face`() {
        val type = cliTypography()
        assertEquals(referenceSp(15f), type.body.fontSize)
        assertEquals(referenceSp(13f), type.small.fontSize)
        assertEquals(referenceSp(14f), type.title.fontSize)
        assertEquals(referenceSp(16f), type.display.fontSize)
        assertEquals(pixelHeadingSp(15f), type.button.fontSize)
        assertEquals((-2).dp, cliHeadingOpticalOffsetFor("Settings"))
        assertEquals((-2).dp, cliHeadingOpticalOffsetFor("Настройки"))
        assertEquals((-2).dp, cliPanelHeadingOpticalOffsetFor("Настройки"))
        assertEquals((-1).dp, cliHeadingGlyphOpticalOffsetFor("Настройки"))
        assertEquals((-1).dp, cliHeadingGlyphOpticalOffsetFor("Settings", pixelArtEnabled = false))
        assertEquals((-4).dp, cliScreenHeadingOpticalOffsetFor("Настройки"))
        assertEquals((-2).dp, cliHeadingOpticalOffsetFor("Settings", pixelArtEnabled = false))
        assertEquals((-2).dp, cliPanelHeadingOpticalOffsetFor("Settings", pixelArtEnabled = false))
        assertEquals((-4).dp, cliScreenHeadingOpticalOffsetFor("Настройки", pixelArtEnabled = false))
        assertEquals(pixelHeadingSp(16f), cliDisplayStyleFor("FoxHole Guard").fontSize)
        assertEquals(pixelHeadingSp(16f), cliScreenTitleStyleFor("Settings").fontSize)
        assertEquals(
            referenceSp(16f),
            cliScreenTitleStyleFor("Settings", pixelArtEnabled = false).fontSize,
        )
        assertEquals(
            cliDisplayStyleFor("Settings").lineHeight,
            cliScreenTitleStyleFor("Settings").lineHeight,
        )
        assertEquals(pixelHeadingSp(14f), cliTitleStyleFor("Settings").fontSize)
        assertEquals(
            cliTitleStyleFor("Settings").lineHeight,
            cliTitleStyleFor("Settings", pixelArtEnabled = false).lineHeight,
        )
        assertEquals(
            cliTitleStyleFor("Settings").fontSize.value * CLI_PIXEL_FONT_CAP_HEIGHT_RATIO,
            cliTitleStyleFor("Settings", pixelArtEnabled = false).fontSize.value *
                CLI_MONO_FONT_CAP_HEIGHT_RATIO,
            0.001f,
        )
        assertEquals(
            cliDisplayStyleFor("Settings").fontSize.value * CLI_PIXEL_FONT_CAP_HEIGHT_RATIO,
            cliDisplayStyleFor("Settings", pixelArtEnabled = false).fontSize.value *
                CLI_MONO_FONT_CAP_HEIGHT_RATIO,
            0.001f,
        )
        assertEquals(cliTitleStyleFor("Settings").fontFamily, cliTitleStyleFor("Настройки").fontFamily)

        val theme = cli("CliTheme.kt")
        assertTrue(theme.contains("private val Tiny5Family"))
        assertTrue(theme.contains("fontFamily = Tiny5Family"))
        assertTrue(theme.contains("fontFamily = JetBrainsMonoBoldFamily"))
        assertFalse(theme.contains("InterFamily"))
        assertFalse(theme.contains("withWholeStringCyrillicFallback"))

        val icon = cli("components/CliIcon.kt")
        assertTrue(icon.contains("val drawnSize = size * CLI_ICON_DRAW_SCALE"))
        assertTrue(icon.contains("painterResource(id)"))
        assertFalse(icon.contains("cliLinIconRes"))
        assertTrue(icon.contains("modifier = modifier.size(size).then(a11y)"))
        assertTrue(icon.contains("modifier = Modifier.size(drawnSize)"))

        val dock = cli("components/CliHintBar.kt")
        assertTrue(dock.contains("CliType.button.copy("))
        assertTrue(dock.contains("fontSize = cliFontSizeForMode("))
        assertTrue(dock.contains("cliScaledSp(if (compact) 10f else 11f)"))
        assertTrue(dock.contains("semanticLabel.uppercase()"))
    }

    @Test
    fun `home brand status and terminal footnotes use shared semantic roles`() {
        val terminal = cli("home/CliHomeTerminal.kt")

        assertTrue(terminal.contains("style = cliDisplayStyle(\"FOXHOLE GUARD\").copy("))
        assertTrue(
            terminal.contains("val baseStatusStyle = cliTypography(pixelArtEnabled = false).button.copy("),
        )
        assertFalse(terminal.contains("if (statusScale < 1f)"))
        assertTrue(terminal.contains("fontSize = baseStatusStyle.fontSize * statusScale"))
        assertTrue(terminal.contains("val style = CliType.small"))
        assertTrue(terminal.contains("style = CliType.small"))
        assertFalse(terminal.contains("TERMINAL_FOOTNOTE_FONT_SP"))
        assertFalse(terminal.contains("STATUS_FONT_SIZE"))
        assertFalse(terminal.contains("CliType.display.copy(fontSize"))
    }

    @Test
    fun `buttons use uppercase pixel labels without legacy brackets`() {
        val button = cli("components/CliButton.kt")

        assertTrue(button.contains("val shownLabel = cliHeadingText(label)"))
        assertTrue(button.contains("text = shownLabel"))
        assertTrue(button.contains("style = CliType.button"))
        assertFalse(button.contains("[${'$'}{cliLabelText(label)}]"))
    }

    @Test
    fun `shared geometry follows one compact radius cascade`() {
        assertEquals(1.dp, CliRadius.hairline)
        assertEquals(2.dp, CliRadius.pixel)
        assertEquals(4.dp, CliRadius.indicator)
        assertEquals(6.dp, CliRadius.control)
        assertEquals(8.dp, CliRadius.panel)
        assertEquals(12.dp, CliRadius.modal)
        assertEquals(24.dp, CliRadius.sheet)

        val consumers = listOf(
            "components/CliButton.kt" to "CliRadius.control",
            "components/CliPanel.kt" to "CliRadius.panel",
            "components/CliInputModal.kt" to "CliRadius.modal",
            "components/CliBottomSheet.kt" to "CliRadius.sheet",
            "components/CliStageProgress.kt" to "CliRadius.indicator",
        )
        consumers.forEach { (path, token) ->
            assertTrue("$path must use $token", cli(path).contains(token))
        }
    }

    @Test
    fun `manual text and continuous clocks honor disabled system motion`() {
        val clock = cli("components/CliMotionClock.kt")
        val typewriter = cli("components/CliTypewriterText.kt")
        val shimmer = cli("components/CliShimmerText.kt")
        val terminal = cli("home/CliHomeTerminal.kt")

        assertTrue(clock.contains("currentCoroutineContext()[MotionDurationScale]"))
        assertTrue(clock.contains("phase.floatValue = 0f"))
        listOf(typewriter, shimmer, terminal).forEach { source ->
            assertTrue(source.contains("cliSystemMotionEnabled()"))
        }
        assertTrue(terminal.contains("terminal.promptTypedCount = prompt.length"))
        assertTrue(terminal.contains("visibleChars = length"))
    }

    @Test
    fun `outline icons share one optical alignment`() {
        assertEquals(0.dp, CLI_ICON_OPTICAL_OFFSET)

        val icon = cli("components/CliIcon.kt")
        val flag = cli("components/CliFlagIcon.kt")
        assertFalse(icon.contains("VisualStyle"))
        assertFalse(flag.contains("VisualStyle"))
    }

    @Test
    fun `table headers keep the small step`() {
        listOf(
            "logs/CliLogsScreen.kt" to "private fun CliJournalTableHeader",
            "stats/CliStatsVpnTablePanels.kt" to "private fun CliStatsTableHeader",
            "settings/CliFirewallSubScreen.kt" to "private fun CliQuarantineTableHeader",
        ).forEach { (path, marker) ->
            val header = cli(path).substringAfter(marker).take(HEADER_SCAN_CHARS)
            assertTrue("$path header must use the small step", header.contains("CliType.small"))
            assertFalse("$path header must not use the enlarged caption", header.contains("cliCaptionTextStyle()"))
        }
    }

    @Test
    fun `only transition status dots animate on the attention clock`() {
        val dot = cli("components/CliStatusDot.kt")

        assertTrue(dot.contains("internal const val CLI_ATTENTION_PULSE_MS = 900"))
        assertEquals(1, Regex("CLI_ATTENTION_PULSE_MS,").findAll(dot).count())
        assertEquals(2, Regex("\\.clip\\(CircleShape\\)").findAll(dot).count())
        assertTrue(dot.contains("RepeatMode.Reverse"))
        assertTrue(dot.contains("PIXEL_CAP_HEIGHT_RATIO"))
        assertFalse(dot.contains("RepeatMode.Restart"))
        assertTrue(dot.contains("private fun cliAttentionPulse(label: String): Float"))
        assertFalse(cliStatusDotAnimates(pulsing = false))
        assertTrue(cliStatusDotAnimates(pulsing = true))
    }

    private fun referenceSp(base: Float) = (base * 1.1f * CLI_UNIFIED_METRIC_SCALE).sp

    private fun pixelHeadingSp(base: Float) =
        cliPixelFontSizeForMonoSp(base * 1.1f * CLI_UNIFIED_METRIC_SCALE)

    @Test
    fun `selected vpn profile and protocol markers stay static`() {
        val rows = cli("components/CliRows.kt")
        val panel = cli("components/CliPanel.kt")
        val homeSelector = cli("home/CliProfileQuickSelector.kt")
        val protocolEditor = cli("profiles/CliProfileEditorProtocolForm.kt")
        val activeDot = rows
            .substringAfter("internal fun CliActiveDot(")
            .substringBefore("internal fun CliColumnRule")

        assertTrue(activeDot.contains("if (active)"))
        assertTrue(activeDot.contains(".background(colors.accent)"))
        assertFalse(activeDot.contains("rememberInfiniteTransition"))
        assertFalse(activeDot.contains("animateFloat"))
        assertFalse(activeDot.contains("graphicsLayer"))
        assertFalse(activeDot.contains("CLI_ATTENTION_PULSE_MS"))
        assertTrue(cli("profiles/CliProfileListItem.kt").contains("CliActiveDot(active = true)"))
        assertTrue(cli("profiles/CliProtocolDropdown.kt").contains("CliActiveDot(active = true)"))
        assertTrue(homeSelector.contains("CliActiveDot(active = active)"))
        val profileRow = cli("profiles/CliProfileListItem.kt")
            .substringAfter("private fun CliProfileRow(")
            .substringBefore("private fun ProfileTableLeadingCell(")
        assertTrue(profileRow.contains(".selectedProfileDecoration(selected, colors.accent)"))
        assertFalse(profileRow.contains("cliAccentSweepBorder"))
        assertTrue(panel.contains("Modifier.border(1.dp, accentBorderColor.copy(alpha = 0.65f), shape)"))
        assertTrue(panel.contains(".then(accentEdge)"))
        listOf(homeSelector, protocolEditor).forEach { source ->
            assertTrue(source.contains("accentBorderColor = colors.accent"))
            assertFalse(source.contains("cliAccentSweepBorder"))
        }
    }

    @Test
    fun `journals and statistics open on a centred preloader`() {
        val spinner = cli("components/CliSpinner.kt")
        val logs = cli("logs/CliLogsScreen.kt")
        val stats = cli("stats/CliStatsScreen.kt")

        assertTrue(spinner.contains("internal fun CliSectionPreloader("))
        assertTrue(spinner.contains("contentAlignment = Alignment.Center"))
        assertTrue(spinner.contains("modifier.fillMaxSize()"))
        listOf(logs, stats).forEach { screen ->
            assertTrue("the section gates on a preloader", screen.contains("CliSectionPreloader("))
        }
        val statsGate = stats.substringAfter("CliScreenHeader(").substringBefore("verticalScroll")
        assertTrue(statsGate.contains("CliSectionPreloader("))
    }

    @Test
    fun `info sheets use the shared help frame with only the icon accented`() {
        val sheet = cli("components/CliInfoSheet.kt")
        val help = cli("components/CliContextHelp.kt")

        assertTrue(sheet.contains("icon = R.drawable.lin_info.takeIf"))
        assertTrue(sheet.contains("LocalCliInfoSheetBodyIconVisible.current"))
        assertFalse(sheet.contains("CliDashedInfoNote("))
        assertTrue(help.contains("tint = colors.info"))
        assertTrue(help.contains("color = colors.fg"))
    }

    @Test
    fun `an unsatisfiable proxy scenario refuses instead of applying`() {
        val screen = cli("settings/CliRoutingScreen.kt")
        val section = cli("settings/CliRoutingModeSection.kt")
        val lanes = cli("settings/CliRoutingAppLanesSection.kt")

        assertTrue(screen.contains("rememberCliRejectFeedback()"))
        assertTrue(screen.contains("missingAppsFeedback.play()"))
        assertTrue(screen.contains("if (rejectionAttempt == attempt) missingAppsTarget = null"))
        assertTrue(section.contains("onMissingAppsRejected(CliMissingAppsTarget.VPN)"))
        assertTrue(section.contains("onMissingAppsRejected(CliMissingAppsTarget.TOR)"))
        assertTrue(lanes.contains("if (missingAppsTarget != null) colors.err else colors.firewall"))
        assertTrue(lanes.contains("missingAppsTarget == CliMissingAppsTarget.VPN"))
        assertTrue(lanes.contains("missingAppsTarget == CliMissingAppsTarget.TOR"))
        assertTrue(lanes.contains("Modifier.cliRejectShake(missingAppsFeedback)"))
        val onSelect = section.substringAfter("onSelect = { id ->").substringBefore("},")
        assertTrue(onSelect.contains("} else {"))
        assertTrue(onSelect.contains("applyVpnConn("))
    }

    @Test
    fun `one badge composable serves every badge and always takes its colour from the caller`() {
        val badge = cli("components/CliBadge.kt")
        val sources = cli("settings/CliUpdateSourcesSheet.kt")

        assertTrue(badge.contains("internal fun CliBadge("))
        assertTrue(badge.contains("internal fun CliBadgedText("))
        assertTrue(badge.contains("offset(y = CLI_BADGE_VERTICAL_OFFSET)"))
        assertTrue(badge.contains("CLI_BADGE_VERTICAL_OFFSET = (-1).dp"))
        assertFalse("a badge colour is always semantic", badge.contains("Color(0xFF"))
        assertTrue(badge.contains("RoundedCornerShape(BADGE_CORNER)"))

        assertTrue(sources.contains("CliBadge(text = officialLabel, color = colors.ok)"))
        assertTrue(sources.contains("verticalAlignment = Alignment.Top"))
        assertTrue(sources.contains("iconSize = cliModalHeaderIconSizeFor(CliSheetHeaderIconRole.DEFAULT)"))
        assertFalse(sources.contains("CliElbowLine(text = stringResource(R.string.cli_updates_sources_note))"))
    }

    @Test
    fun `a stored proxy secret has persistent safe trailing actions`() {
        val secret = cli("components/CliSecretRow.kt")
        val routing = cli("settings/CliRoutingModeSection.kt")
        val lan = cli("settings/CliLanProxySubScreen.kt")

        assertTrue(secret.contains("rememberSaveable(prompt, value.isBlank())"))
        assertTrue(secret.contains("revealed = !revealed"))
        assertTrue(secret.contains("password = !revealed"))
        assertTrue(secret.contains("android.content.extra.IS_SENSITIVE"))
        assertFalse(secret.contains("emitInfo"))
        assertFalse(secret.contains("recordStructured"))
        assertTrue(secret.contains("enabled = hasValue"))
        assertTrue(secret.contains("SECRET_ACTION_SIZE = 48.dp"))
        assertTrue(secret.contains("R.string.cli_secret_action_show"))
        assertTrue(secret.contains("R.string.cli_secret_action_hide"))
        assertTrue(secret.contains("R.drawable.lin_edit"))
        assertTrue(secret.contains("R.drawable.lin_copy"))

        assertTrue("the proxy password uses it", routing.contains("CliSecretRow("))
        assertTrue("the LAN proxy password uses it", lan.contains("CliSecretRow("))
    }

    @Test
    fun `the i2p module keeps exactly the two switches the carrier machine reads`() {
        val screen = cli("settings/CliI2pSubScreen.kt")
        val carrierSwitches =
            screen
                .substringAfter("private fun CliI2pRuntimePanel")
                .substringBefore(
                    "CliToggleRow(\n" +
                        "                label = stringResource(R.string.i2p_relay_transit_title)",
                )

        assertTrue(carrierSwitches.contains("R.string.cli_i2p_auto_reconnect"))
        assertTrue(carrierSwitches.contains("R.string.cli_i2p_allow_outside_tunnel"))
        assertFalse(carrierSwitches.contains("R.string.cli_i2p_runtime"))
        assertFalse(screen.contains("onI2pEngagedChanged"))
        assertEquals(2, Regex("CliToggleRow\\(").findAll(carrierSwitches).count())
        assertEquals(2, Regex("infoText = ").findAll(carrierSwitches).count())
    }

    private companion object {
        const val HEADER_SCAN_CHARS = 1600
    }
}
