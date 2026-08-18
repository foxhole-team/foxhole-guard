package com.foxhole.guard.ui.cli

import com.foxhole.core.model.VisualStyle
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

        listOf(button, dock, swap).forEach { source ->
            assertTrue("every animated surface reads the tokens", source.contains("CliMotion."))
        }
        assertFalse("a local press duration is a second motion law", button.contains("PLAIN_PRESS_MS"))
        assertFalse(button.contains("animationSpec = tween("))
        assertFalse(dock.contains("animationSpec = spring("))
        assertFalse(swap.contains("fadeIn(tween("))
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
    fun `modern renders one notch smaller and the dock opts out`() {
        assertEquals(1f, cliMetricScaleFor(VisualStyle.PIXEL), 0f)
        assertEquals(CLI_MODERN_METRIC_SCALE, cliMetricScaleFor(VisualStyle.PLAIN), 0f)
        assertTrue("modern must be smaller than retro, not larger", CLI_MODERN_METRIC_SCALE < 1f)
        assertTrue("a notch, not a redesign", CLI_MODERN_METRIC_SCALE > 0.85f)

        val theme = cli("CliTheme.kt")
        val plainSet = theme.substringAfter("private val CliPlainTypography").substringBefore("\n)")
        listOf("body", "small", "title", "display", "button").forEach { step ->
            assertTrue("$step must take the notch", plainSet.contains("plainSize"))
        }
        assertFalse(plainSet.contains("= CliTypeBody.size"))
        assertFalse(plainSet.contains("= CliTypeSmall.size"))

        val icon = cli("components/CliPixIcon.kt")
        assertTrue(icon.contains("val drawnSize = size * LocalCliMetricScale.current"))
        assertFalse(icon.contains("modifier.size(size)"))

        val dock = cli("components/CliHintBar.kt")
        assertTrue(dock.contains("CompositionLocalProvider(LocalCliMetricScale provides CLI_DOCK_METRIC_SCALE)"))
        assertTrue(dock.contains("private const val CLI_DOCK_METRIC_SCALE = 1f"))
    }

    @Test
    fun `table headers keep the small step in both styles`() {
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
        assertEquals(2, Regex("CLI_ATTENTION_PULSE_MS,").findAll(dot).count())
        assertTrue("modern draws a circle, not the pixel disc", dot.contains("Modifier.clip(CircleShape)"))
        assertTrue(dot.contains("RepeatMode.Reverse"))
        assertTrue(dot.contains("STATUS_DOT_GRID"))
        assertTrue(dot.contains("RepeatMode.Restart"))
        assertTrue(dot.contains("if (!cliStatusDotAnimates(pulsing)) {"))
        assertFalse(cliStatusDotAnimates(pulsing = false))
        assertTrue(cliStatusDotAnimates(pulsing = true))
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
    fun `expanded info blocks use the centred blue first-line frame`() {
        val sheet = cli("components/CliInfoSheet.kt")

        assertTrue(sheet.contains("CliDashedInfoNote("))
        assertTrue(sheet.contains("centered = true"))
        assertTrue(sheet.contains("centeredIconLeading = true"))
        assertTrue(sheet.contains("centeredIconFirstLine = true"))
        assertTrue(sheet.contains("color = colors.accent"))
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
        assertTrue(lanes.contains("if (missingAppsTarget != null) colors.err else colors.note"))
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
        assertTrue("raised like an exponent", badge.contains("private val BADGE_LIFT = (-3).dp"))
        assertTrue(badge.contains("offset(y = BADGE_LIFT)"))
        assertFalse("a badge colour is always semantic", badge.contains("Color(0xFF"))
        assertTrue(badge.contains("if (round) BADGE_CORNER else 0.dp"))

        assertTrue(sources.contains("CliBadge(text = officialLabel, color = colors.ok)"))
        assertTrue(sources.contains("verticalAlignment = Alignment.Top"))
        assertTrue(sources.contains("trailing = { CliRowInfoGlyph(onTap = { noteOpen = true }) }"))
        assertFalse(sources.contains("CliElbowLine(text = stringResource(R.string.cli_updates_sources_note))"))
    }

    @Test
    fun `a stored secret is revealed only while held and copied without leaving a record`() {
        val secret = cli("components/CliSecretRow.kt")
        val routing = cli("settings/CliRoutingModeSection.kt")

        assertTrue(secret.contains("collectIsPressedAsState()"))
        assertFalse("a toggled reveal can be left on", secret.contains("revealed = !revealed"))
        assertTrue(secret.contains("password = !revealed"))
        assertTrue(secret.contains("android.content.extra.IS_SENSITIVE"))
        assertFalse(secret.contains("emitInfo"))
        assertFalse(secret.contains("recordStructured"))
        assertTrue(secret.contains("R.drawable.pix_edit"))
        assertTrue(secret.contains("R.drawable.pix_copy"))

        assertTrue("the proxy password uses it", routing.contains("CliSecretRow("))
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
