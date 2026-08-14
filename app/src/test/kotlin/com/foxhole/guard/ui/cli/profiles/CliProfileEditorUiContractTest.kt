package com.foxhole.guard.ui.cli.profiles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliProfileEditorUiContractTest {
    @Test
    fun `profile help belongs to the profiles screen only`() {
        val profiles =
            projectFile(
                "src/main/kotlin/com/foxhole/guard/ui/cli/profiles/CliProfilesScreen.kt",
            ).readText()
        val editor =
            projectFile(
                "src/main/kotlin/com/foxhole/guard/ui/cli/profiles/CliProfileEditorScreen.kt",
            ).readText()

        assertTrue(profiles.contains("bodyRes = R.string.cli_help_editor_body"))
        assertFalse(editor.contains("CliContextHelpButton"))
    }

    @Test
    fun `blank profile creation hides the second add protocol action`() {
        val profiles =
            projectFile(
                "src/main/kotlin/com/foxhole/guard/ui/cli/profiles/CliProfilesScreen.kt",
            ).readText()
        val editor =
            projectFile(
                "src/main/kotlin/com/foxhole/guard/ui/cli/profiles/CliProfileEditorScreen.kt",
            ).readText()

        assertTrue(profiles.contains("allowAddProtocol = false"))
        assertTrue(editor.contains("allowAddProtocol: Boolean = true"))
        assertTrue(editor.contains("if (onAddProtocol != null)"))
    }

    @Test
    fun `smart export expands into individually selectable protocol rows`() {
        val item =
            projectFile(
                "src/main/kotlin/com/foxhole/guard/ui/cli/profiles/CliProfileListItem.kt",
            ).readText()

        assertTrue(item.contains("CliSmartExportChoiceRows("))
        assertTrue(item.contains("selection.toggleSmartProfileExpanded(profile.id)"))
        assertTrue(item.contains("selection.toggleSmartProfileChoice(profile, selectionKey)"))
        assertTrue(item.contains("selectedKeys = selection.selectedKeys(profile.id)"))
    }

    @Test
    fun `manual editor is full height and saves any supported config text`() {
        val editor =
            projectFile(
                "src/main/kotlin/com/foxhole/guard/ui/cli/profiles/CliProfileEditorScreen.kt",
            ).readText()
        val manual =
            projectFile(
                "src/main/kotlin/com/foxhole/guard/ui/cli/profiles/CliManualProfileEditor.kt",
            ).readText()
        val support =
            projectFile(
                "src/main/kotlin/com/foxhole/guard/ui/HomeViewModelProfileEditorSupport.kt",
            ).readText()
        val footer = editor.substringAfter("private fun CliProfileEditorFooter(")
            .substringBefore("private fun CliDisketteIcon")

        assertTrue(footer.indexOf("cli_common_no_cancel") < footer.indexOf("cli_prof_edit_raw"))
        assertTrue(footer.contains("if (dirty)"))
        assertTrue(footer.contains("color = colors.ok"))
        assertTrue(footer.contains("iconContent = { tint -> CliDisketteIcon"))
        assertFalse(footer.contains("filled = true"))
        assertTrue(editor.contains("CliManualProfileEditor("))
        assertTrue(manual.contains(".fillMaxSize()"))
        assertTrue(manual.indexOf("cli_common_no_cancel") < manual.indexOf("cli_prof_config_save"))
        assertTrue(support.contains("parser.sanitizeResolvedConfig("))
        assertTrue(support.contains("parser.parseUserInput("))
        assertTrue(support.contains("ProfileSourceType.SUBSCRIPTION_URL"))
        assertFalse(manual.contains("CliBottomSheet("))
    }

    @Test
    fun `view model submits one editor batch instead of saving protocols one by one`() {
        val support =
            projectFile(
                "src/main/kotlin/com/foxhole/guard/ui/HomeViewModelProfileEditorSupport.kt",
            ).readText()
        val batchSave =
            support.substringAfter("internal fun HomeViewModel.saveProfileEditorChanges(")
                .substringBefore("private suspend fun HomeViewModel.loadEditorProtocolConfig")

        assertTrue(batchSave.contains("profileRepository.updateProfileEditor("))
        assertFalse(batchSave.contains("profileRepository.updateResolvedConfig("))
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("app/$path"), File("../app/$path"))
            .first { file -> file.exists() }
}
