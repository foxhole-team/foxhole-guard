package com.foxhole.guard.ui.cli.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliDataScreenContractTest {
    @Test
    fun `current cli data screen keeps every destructive action behind confirmation`() {
        val source = sourceFile()
        val destructivePanel =
            source.substringAfter("title = stringResource(R.string.cli_data_clear_title)")
                .substringBefore("private fun CliDestructiveRow")

        assertEquals(3, Regex("""CliDestructiveRow\(""").findAll(destructivePanel).count())
        listOf(
            "viewModel::resetApplicationSettingsToDefaults",
            "viewModel::clearProfilesAndSecretsLocalData",
            "viewModel::factoryResetLocalData",
        ).forEach { action ->
            assertTrue("missing destructive action $action", destructivePanel.contains(action))
        }

        // Сам ряд поднят в components/CliRows.kt — контракт arm-then-модалка проверяется там.
        val destructiveRow = componentsRowsFile().substringAfter("internal fun CliDestructiveRow")
        assertTrue(destructiveRow.contains("if (confirmAction == key)"))
        assertTrue(destructiveRow.contains("CliConfirmSheet("))
        assertTrue(destructiveRow.contains("onArm(null)"))
        assertTrue(destructiveRow.contains("onConfirm()"))
    }

    @Test
    fun `backup restore is previewed and confirmed before it is applied`() {
        val source = sourceFile()

        assertTrue(source.contains("pendingRestore?.let { document ->"))
        assertTrue(source.contains("R.string.cli_data_restore_preview"))
        assertTrue(source.contains("R.string.cli_data_restore_confirm"))
        assertTrue(source.contains("viewModel.restoreBackup(document)"))
        // Отказ и применение гасят оба состояния: документ и saveable-uri, из которого
        // превью перечитывается после пересоздания экрана.
        assertTrue(source.contains("pendingRestore = null"))
        assertTrue(source.contains("pendingRestoreUri = null"))
    }

    private fun sourceFile(): String = readSource("settings/CliDataSubScreen.kt")

    private fun componentsRowsFile(): String = readSource("components/CliRows.kt")

    private fun readSource(relative: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
        ).first(File::isFile).readText()
}
