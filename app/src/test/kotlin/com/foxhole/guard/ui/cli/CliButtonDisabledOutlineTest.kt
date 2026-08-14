package com.foxhole.guard.ui.cli

import com.foxhole.guard.ui.cli.components.cliButtonEmphasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Release contract for action buttons that temporarily disable while work is in progress. */
class CliButtonDisabledOutlineTest {
    @Test
    fun `disabled action dims its fill and content but never its outline`() {
        val emphasis = cliButtonEmphasis(enabled = false, dimWhenDisabled = true)

        assertEquals(1f, emphasis.borderAlpha)
        assertTrue(emphasis.fillAlpha in 0f..1f && emphasis.fillAlpha < 1f)
        assertTrue(emphasis.contentAlpha in 0f..1f && emphasis.contentAlpha < 1f)
    }

    @Test
    fun `enabled and stable-disabled actions keep full emphasis`() {
        assertEquals(
            listOf(1f, 1f, 1f),
            cliButtonEmphasis(enabled = true, dimWhenDisabled = true).asList(),
        )
        assertEquals(
            listOf(1f, 1f, 1f),
            cliButtonEmphasis(enabled = false, dimWhenDisabled = false).asList(),
        )
    }

    @Test
    fun `profile add editor and transfer actions share the outlined button primitive`() {
        assertSharedButton(
            source = source("ui/cli/profiles/CliProfileDialogs.kt"),
            anchor = "label = stringResource(R.string.cli_prof_import_yes_add)",
        )
        assertSharedButton(
            source = source("ui/cli/profiles/CliProfileEditorScreen.kt"),
            anchor = "label = stringResource(R.string.cli_prof_edit_add)",
        )
        assertSharedButton(
            source = source("ui/cli/profiles/CliProfileTransfer.kt"),
            anchor = "label = stringResource(R.string.cli_prof_imp_qr)",
        )
    }

    private fun assertSharedButton(source: String, anchor: String) {
        val anchorIndex = source.indexOf(anchor)
        assertTrue("Missing action: $anchor", anchorIndex >= 0)
        val callStart = source.lastIndexOf("CliButton(", startIndex = anchorIndex)
        assertTrue("Action must use CliButton: $anchor", callStart >= 0)
        val call = source.balancedCallAt(callStart)
        assertTrue("Action must not replace shared background: $anchor", ".background(" !in call)
        assertTrue("Action must not replace shared border: $anchor", ".border(" !in call)
        assertTrue("Action must not apply local alpha: $anchor", ".alpha(" !in call)
    }

    private fun String.balancedCallAt(start: Int): String {
        val opening = indexOf('(', startIndex = start)
        var depth = 0
        for (index in opening until length) {
            when (this[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return substring(start, index + 1)
                }
            }
        }
        error("Unclosed call at $start")
    }

    private fun source(relativePath: String): String =
        repoRoot()
            .resolve("app/src/main/kotlin/com/foxhole/guard/$relativePath")
            .also { file -> check(file.isFile) { "Missing source: $file" } }
            .readText()

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { directory -> directory.parentFile }
            .first { directory -> directory.resolve("settings.gradle.kts").isFile }

    private fun com.foxhole.guard.ui.cli.components.CliButtonEmphasis.asList(): List<Float> =
        listOf(fillAlpha, borderAlpha, contentAlpha)
}
