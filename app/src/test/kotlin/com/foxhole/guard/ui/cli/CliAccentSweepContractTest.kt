package com.foxhole.guard.ui.cli

import com.foxhole.guard.ui.cli.components.cliAccentSweepStartX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliAccentSweepContractTest {
    @Test
    fun `accent sweep starts fully outside each edge`() {
        val width = 300f
        val length = 146f

        val start = cliAccentSweepStartX(width, length, phase = 0f)
        val end = cliAccentSweepStartX(width, length, phase = 1f)

        assertEquals(-length, start, 0f)
        assertEquals(0f, start + length, 0f)
        assertEquals(width, end, 0f)
    }

    @Test
    fun `panel accent borders stay static while selections fade without changing padding`() {
        val panel = cli("components/CliPanel.kt")
        val profiles = cli("profiles/CliProfileListItem.kt")
        val panelAccent = panel
            .substringAfter("val accentEdge = if (accentBorderColor != Color.Unspecified)")
            .substringBefore("Column(")
        val decoration = profiles
            .substringAfter("private fun Modifier.selectedProfileDecoration(")
            .substringBefore("private fun profileSelectionMarker(")

        assertTrue(panelAccent.contains("Modifier.border(1.dp, accentBorderColor.copy(alpha = 0.65f), shape)"))
        assertTrue(!panelAccent.contains("cliAccentSweepBorder"))
        assertTrue(decoration.contains("label = \"profileSelection\""))
        assertTrue(decoration.contains("animateFloatAsState("))
        assertTrue(!decoration.contains("padding("))
    }

    private fun cli(relative: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
        ).first(File::isFile).readText()
}
