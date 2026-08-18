package com.foxhole.guard.ui.cli.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliSettingsStructureContractTest {

    @Test
    fun `widget defaults live on their own sub-screen`() {
        val root = cli("settings/CliSettingsScreen.kt")

        assertTrue(root.contains("""private const val SUB_WIDGETS = "widgets""""))
        assertTrue(root.contains("SUB_WIDGETS -> CliWidgetsSubScreen(viewModel, modifier)"))
        assertTrue(root.contains("onTap = { onOpenSub(SUB_WIDGETS) }"))
        assertFalse(root.contains("R.string.cli_cfg_widget_bg"))
        assertFalse(root.contains("R.string.cli_cfg_widget_alpha"))
    }

    @Test
    fun `the widgets sub-screen carries per-kind panels with honest previews`() {
        val screen = cli("settings/CliWidgetsSubScreen.kt")

        assertTrue(screen.contains("CliScreenHeader("))
        assertTrue(screen.contains("CliPanel("))
        assertTrue(screen.contains("viewModel.onWidgetBlackBackgroundChanged(kind"))
        assertTrue(screen.contains("viewModel.onWidgetAlphaPercentChanged(kind"))
        assertTrue(screen.contains("viewModel.onWidgetOutlineChanged(kind"))
        assertTrue(screen.contains("onFoxWidgetAnimationChanged"))
        assertTrue(screen.contains("HomeWidgetKind.CONNECTION"))
        assertTrue(screen.contains("HomeWidgetKind.WEB_APPS"))
        assertTrue(screen.contains("HomeWidgetKind.STATUS"))
        assertEquals(2, Regex("viewModel\\.onAddHomeWidget\\(").findAll(screen).count())
        assertTrue(screen.contains("CliStatusWidgetPreview"))
        assertTrue(screen.contains("CliWebAppsWidgetPreview"))
        assertTrue(screen.contains("connected = connected"))
    }

    @Test
    fun `a module block cannot be declared without a leading icon`() {
        val block = cli("settings/CliModuleBlock.kt")

        assertTrue(block.contains("@DrawableRes icon: Int,"))
        assertTrue(block.contains("icon = icon,"))
        assertTrue(block.contains("icon = R.drawable.pix_settings,"))
    }

    @Test
    fun `every module block passes an icon`() {
        val callSites =
            listOf("settings/CliExtrasSection.kt", "settings/CliModulesSection.kt")
                .flatMap { relative ->
                    cli(relative).split("CliModuleBlock(").drop(1).map { relative to it }
                }

        assertEquals(6, callSites.size)
        callSites.forEach { (relative, call) ->
            val head = call.lineSequence().take(MODULE_BLOCK_HEAD_LINES).joinToString("\n")
            assertTrue("$relative: CliModuleBlock has no icon", head.contains("icon = R.drawable.pix_"))
        }
    }

    @Test
    fun `the extras rows name their own glyphs`() {
        val extras = cli("settings/CliExtrasSection.kt")

        assertTrue(extras.contains("icon = R.drawable.pix_webapps"))
        assertTrue(extras.contains("icon = R.drawable.pix_device"))
    }

    private fun cli(relative: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
        ).first(File::isFile).readText()
}

private const val MODULE_BLOCK_HEAD_LINES = 4
