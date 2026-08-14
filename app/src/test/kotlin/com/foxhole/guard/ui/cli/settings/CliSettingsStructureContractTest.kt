package com.foxhole.guard.ui.cli.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Структура экрана настроек: настройки виджетов — отдельный подэкран (как бекап), а каждый
 * модуль-блок несёт свою иконку перед именем.
 */
class CliSettingsStructureContractTest {

    @Test
    fun `widget defaults live on their own sub-screen`() {
        val root = cli("settings/CliSettingsScreen.kt")

        // Ключ подэкрана заведён, разведён в when и открывается строкой действия.
        assertTrue(root.contains("""private const val SUB_WIDGETS = "widgets""""))
        assertTrue(root.contains("SUB_WIDGETS -> CliWidgetsSubScreen(viewModel, modifier)"))
        assertTrue(root.contains("onTap = { onOpenSub(SUB_WIDGETS) }"))
        // И самих рядов в корне больше нет — иначе настройка живёт в двух местах сразу.
        assertFalse(root.contains("R.string.cli_cfg_widget_bg"))
        assertFalse(root.contains("R.string.cli_cfg_widget_alpha"))
    }

    @Test
    fun `the widgets sub-screen carries both defaults in the backup screen's shape`() {
        val screen = cli("settings/CliWidgetsSubScreen.kt")

        assertTrue(screen.contains("CliScreenHeader("))
        assertTrue(screen.contains("CliPanel("))
        assertTrue(screen.contains("R.string.cli_cfg_widget_bg"))
        assertTrue(screen.contains("R.string.cli_cfg_widget_alpha"))
        assertTrue(screen.contains("viewModel.onWidgetBlackBackgroundChanged"))
        assertTrue(screen.contains("viewModel.onWidgetAlphaPercentChanged"))
        assertTrue(screen.contains("HomeWidgetKind.CONNECTION"))
        assertTrue(screen.contains("HomeWidgetKind.WEB_APPS"))
        assertTrue(screen.contains("HomeWidgetKind.STATUS"))
        assertEquals(3, Regex("viewModel\\.onAddHomeWidget\\(").findAll(screen).count())
    }

    @Test
    fun `a module block cannot be declared without a leading icon`() {
        val block = cli("settings/CliModuleBlock.kt")

        // Не nullable и без значения по умолчанию: иконка — часть контракта блока.
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

        // Два дополнения и четыре модуля: Tor, I2P, фаервол, детекция аномалий.
        assertEquals(6, callSites.size)
        callSites.forEach { (relative, call) ->
            // Иконка — второй именованный аргумент, так что окна в несколько строк хватает.
            val head = call.lineSequence().take(MODULE_BLOCK_HEAD_LINES).joinToString("\n")
            assertTrue("$relative: у CliModuleBlock нет иконки", head.contains("icon = R.drawable.pix_"))
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
