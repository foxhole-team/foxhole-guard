package com.foxhole.guard.ui.cli.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Что где живёт в настройках — продуктовое решение, а не следствие того, в каком порядке
 * дописывались ряды.
 *
 * Фаервол и детекция аномалий переехали из группы «безопасность» в раздел модулей: у каждого свой
 * тумблер и своя поверхность, а это и есть определение модуля. В «безопасности» остались шифрование
 * данных и блокировка скриншотов — именно в этом порядке. Проверяется по исходнику, как и остальные
 * структурные контракты CLI-настроек.
 */
class CliSecuritySectionOrderContractTest {

    @Test
    fun `the security group keeps the product order`() {
        val section = cli("settings/CliLockSection.kt")

        val encryption = section.indexOf("R.string.cli_lock_password")
        val screenshots = section.indexOf("R.string.cli_cfg_block_screenshots")

        assertTrue("шифрование данных не найдено", encryption >= 0)
        assertTrue("блокировка скриншотов идёт не последней", encryption < screenshots)
    }

    /** Настройка живёт в одном месте: иначе один и тот же тумблер стоит на двух экранах. */
    @Test
    fun `the firewall and anomaly detection left the security group`() {
        val section = cli("settings/CliLockSection.kt")

        assertFalse("фаервол остался в группе безопасности", section.contains("R.string.cli_cfg_firewall"))
        assertFalse("аномалии остались в группе безопасности", section.contains("R.string.cli_cfg_more_anomaly"))
    }

    /**
     * Все четыре модуля читаются одинаково — тумблер и всегда доступный вход в свои настройки, —
     * и разделяются ровно одной более яркой линией между собой.
     */
    @Test
    fun `the modules section holds four module blocks separated by one rule each`() {
        val section = cli("settings/CliModulesSection.kt")

        assertEquals(4, section.split("CliModuleBlock(").size - 1)
        assertEquals(3, section.split("CliDivider(").size - 1)
    }

    /** Вход в настройки модуля не закрыт тумблером: выключить модуль — не значит спрятать его. */
    @Test
    fun `a module's settings entry is not gated on its switch`() {
        val block = cli("settings/CliModuleBlock.kt")

        val entry = block.substringAfter("CliToggleRow(").substringBefore("CliActionRow(")
        assertFalse("вход в настройки модуля закрыт условием", entry.contains("if ("))
    }

    private fun cli(relative: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
        ).first(File::isFile).readText()
}
