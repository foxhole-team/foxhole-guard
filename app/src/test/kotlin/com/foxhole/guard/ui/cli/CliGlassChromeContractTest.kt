package com.foxhole.guard.ui.cli

import androidx.compose.ui.graphics.Color
import com.foxhole.core.model.PanelAppearance
import com.foxhole.guard.ui.cli.components.cliGlassFallbackColor
import com.foxhole.guard.ui.cli.components.cliGlassTintColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliGlassChromeContractTest {

    @Test
    fun `glass chrome follows semantic panels except for legacy true black`() {
        val panel = Color(0xFF334455)

        assertEquals(panel, cliGlassFallbackColor(PanelAppearance.AUTO, panel))
        assertEquals(panel, cliGlassFallbackColor(PanelAppearance.STANDARD, panel))
        assertEquals(panel, cliGlassFallbackColor(PanelAppearance.LIGHT, panel))
        assertEquals(Color.Black, cliGlassFallbackColor(PanelAppearance.DARK, panel))
        assertEquals(panel.copy(alpha = 0.68f), cliGlassTintColor(PanelAppearance.AUTO, panel))
        assertEquals(panel.copy(alpha = 0.68f), cliGlassTintColor(PanelAppearance.STANDARD, panel))
        assertEquals(panel.copy(alpha = 0.60f), cliGlassTintColor(PanelAppearance.LIGHT, panel))
        assertEquals(Color.Black.copy(alpha = 0.68f), cliGlassTintColor(PanelAppearance.DARK, panel))
    }

    @Test
    fun `glass surfaces sample a backdrop and fall back to a solid fill`() {
        val glass = cli("components/CliGlass.kt")

        assertTrue(glass.contains("internal fun CliGlassSurface("))
        assertTrue(glass.contains("cliGlassSupported()"))
        assertTrue(glass.contains("Build.VERSION_CODES.S"))
        assertTrue(glass.contains(".background(cliGlassFallback())"))
        assertTrue(glass.contains("LocalCliGlassBlurEnabled = staticCompositionLocalOf { false }"))
    }

    @Test
    fun `screen headers use the exact semantic home background without backdrop work`() {
        val glass = cli("components/CliGlass.kt")
        val headerScreen = glass
            .substringAfter("internal fun CliGlassHeaderScreen(")

        assertTrue(headerScreen.contains("val colors = LocalCliColors.current"))
        assertTrue(headerScreen.contains(".background(colors.bg)"))
        assertFalse(headerScreen.contains("rememberCliBackdrop()"))
        assertFalse(headerScreen.contains("cliBackdropSource("))
        assertFalse(headerScreen.contains("CliGlassSurface("))
        assertTrue(headerScreen.contains(".statusBarsPadding()"))
        assertTrue(headerScreen.contains("content(topInset)"))
        val app = cli("CliApp.kt")
        val pagerHost = app.substringAfter(".background(colors.bg)").substringBefore("CliTabPager(")
        assertFalse(pagerHost.contains(".statusBarsPadding()"))
    }

    @Test
    fun `the blur sample bleeds past the surface and the tint is painted over it`() {
        val glass = cli("components/CliGlass.kt")

        assertTrue(glass.contains("GLASS_EDGE_BLEED"))
        val effectBox = glass
            .substringAfter("val glassLayer = rememberGraphicsLayer()")
            .substringBefore("@android.annotation.TargetApi")
        assertTrue(effectBox.contains("glassLayer.renderEffect = glassRenderEffect"))
        assertTrue(effectBox.contains("glassLayer.record(size = sample)"))
        assertTrue(effectBox.contains("translate(backdropOffset.x + bleed, backdropOffset.y + bleed)"))
        assertTrue(effectBox.contains("translate(-bleed, -bleed)"))
        assertTrue(effectBox.contains("drawLayer(glassLayer)"))
        assertTrue(effectBox.contains("drawRect(color = tint)"))
    }

    @Test
    fun `the dock floats over content with blur dormant and screens spend the published clearance`() {
        val app = cli("CliApp.kt")
        val dock = cli("components/CliHintBar.kt")

        assertTrue(app.contains("LocalCliGlassBlurEnabled provides false"))
        assertFalse(app.contains("rememberCliBackdrop()"))
        assertFalse(app.contains("cliBackdropSource("))
        assertTrue(app.contains(".align(Alignment.BottomCenter)"))
        assertTrue(app.contains(".navigationBarsPadding()"))
        assertTrue(app.contains("val LocalCliBottomChromeClearance"))
        assertFalse(
            app.contains("navigationBarsPadding(),\n        ) {\n            // Keep the dock in the layout flow")
        )
        assertTrue(dock.contains("CliGlassSurface("))
        assertTrue(dock.contains("private val CLI_DOCK_LIFT_TOP = 0.dp"))
        assertTrue(dock.contains("private val CLI_DOCK_LIFT_BOTTOM = 0.dp"))
        assertTrue(dock.contains("private val CLI_DOCK_VISUAL_DROP = 2.dp"))
        assertTrue(dock.contains(".cliDockVisualDrop()"))
        assertTrue(dock.contains("translate(top = CLI_DOCK_VISUAL_DROP.toPx())"))
        assertFalse(dock.contains(".background(dockFill)"))
    }

    @Test
    fun `every scrolling screen reserves the chrome clearance inside its own scroll`() {
        val tail = cli("components/CliGlass.kt")
        assertTrue(tail.contains("internal fun CliChromeTailSpacer("))
        assertTrue(tail.contains("LocalCliBottomChromeClearance.current"))

        val consumers = listOf(
            "settings/CliSettingsScreen.kt",
            "settings/CliRoutingScreen.kt",
            "map/CliMapScreen.kt",
            "webapps/CliWebAppsScreen.kt",
            "stats/CliStatsScreen.kt",
            "profiles/CliProfilesScreen.kt",
            "home/CliHomeScreen.kt",
            "logs/CliLogsScreen.kt",
        )
        consumers.forEach { path ->
            assertTrue("$path must spend the chrome clearance", cli(path).contains("CliChromeTailSpacer("))
        }
    }

    @Test
    fun `the dormant blur option is hidden but remains localised for a later release`() {
        val settings = cli("settings/CliSettingsScreen.kt")
        assertFalse(settings.contains("R.string.cli_cfg_blur_effects"))
        assertFalse(settings.contains("settings.ui.blurEffectsEnabled"))
        assertFalse(settings.contains("onBlurEffectsEnabledChanged"))

        val english = resource("values/strings.xml")
        val russian = resource("values-ru/strings.xml")
        assertTrue(english.contains("<string name=\"cli_cfg_blur_effects\">"))
        assertTrue(english.contains("<string name=\"cli_cfg_blur_effects_note\">"))
        assertTrue(russian.contains("<string name=\"cli_cfg_blur_effects\">Эффект размытия</string>"))
        assertTrue(russian.contains("<string name=\"cli_cfg_blur_effects_note\">"))
    }

    @Test
    fun `the glass header screens all share the one scaffold`() {
        val screens = listOf(
            "settings/CliSettingsScreen.kt",
            "settings/CliRoutingScreen.kt",
            "map/CliMapScreen.kt",
            "webapps/CliWebAppsScreen.kt",
            "stats/CliStatsScreen.kt",
            "profiles/CliProfilesScreen.kt",
        )
        screens.forEach { path ->
            val source = cli(path)
            assertTrue("$path must use the glass header scaffold", source.contains("CliGlassHeaderScreen("))
            val scrollingBody = source.substringAfter("CliGlassHeaderScreen(")
                .substringAfter(".verticalScroll(rememberScrollState())")
            assertTrue(
                "$path must spend the header inset inside the scrolling body",
                scrollingBody.contains("Spacer(modifier = Modifier.height(topInset))"),
            )
        }
    }

    @Test
    fun `status commands print localised with a footnote in both languages`() {
        val home = cli("home/CliHomeScreen.kt")
        assertTrue(home.contains("R.string.cli_cmd_status"))
        assertTrue(home.contains("R.string.cli_cmd_status_full"))
        assertTrue(home.contains("terminal.footnote(statusNote)"))
        assertTrue(home.contains("terminal.footnote(statusFullNote)"))

        val russian = resource("values-ru/strings.xml")
        assertTrue(russian.contains("<string name=\"cli_cmd_status\">статус</string>"))
        assertTrue(russian.contains("<string name=\"cli_cmd_status_full\">расширенный статус</string>"))
        val english = resource("values/strings.xml")
        assertTrue(english.contains("<string name=\"cli_cmd_status\">status</string>"))
        assertTrue(english.contains("<string name=\"cli_cmd_status_full\">extended status</string>"))
    }

    @Test
    fun `the dark palette meets its own documented contrast lines`() {
        assertTrue(contrastOnBlack(0x8B7A5E) >= 4.5)
        assertTrue(contrastOnBlack(0x806548) >= 3.0)
        val theme = cli("CliTheme.kt")
        assertTrue(theme.contains("Color(0xFF8B7A5E)"))
        assertTrue(theme.contains("Color(0xFF806548)"))
    }

    private fun contrastOnBlack(rgb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((rgb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val luminance = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        return (luminance + 0.05) / 0.05
    }

    private fun cli(path: String): String = projectFile("src/main/kotlin/com/foxhole/guard/ui/cli/$path").readText()

    private fun resource(path: String): String = projectFile("src/main/res/$path").readText()

    private fun projectFile(relative: String): File {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val candidate = File(root, relative)
        if (candidate.isFile) return candidate
        return File(root, "app/$relative")
    }
}
