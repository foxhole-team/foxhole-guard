package com.foxhole.guard.ui.cli.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliSecuritySectionOrderContractTest {

    @Test
    fun `the security group keeps the product order`() {
        val section = cli("settings/CliLockSection.kt")

        val encryption = section.indexOf("R.string.cli_lock_password")
        val screenshots = section.indexOf("R.string.cli_cfg_block_screenshots")

        assertTrue("data encryption row not found", encryption >= 0)
        assertTrue("screenshot blocking is not last", encryption < screenshots)
    }

    @Test
    fun `the firewall and anomaly detection left the security group`() {
        val section = cli("settings/CliLockSection.kt")

        assertFalse("the firewall is still in the security group", section.contains("R.string.cli_cfg_firewall"))
        assertFalse(
            "anomaly detection is still in the security group",
            section.contains("R.string.cli_cfg_more_anomaly"),
        )
    }

    @Test
    fun `the modules section holds four module blocks separated by one rule each`() {
        val section = cli("settings/CliModulesSection.kt")

        assertEquals(4, section.split("CliModuleBlock(").size - 1)
        assertEquals(3, section.split("CliDivider(").size - 1)
    }

    @Test
    fun `a module's settings entry is not gated on its switch`() {
        val block = cli("settings/CliModuleBlock.kt")

        val entry = block.substringAfter("CliToggleRow(").substringBefore("CliActionRow(")
        assertFalse("the module settings entry is gated by a condition", entry.contains("if ("))
    }

    private fun cli(relative: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
        ).first(File::isFile).readText()
}
