package com.foxhole.guard.ui.cli

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliProgressLayoutContractTest {
    @Test
    fun `modern stage progress uses animated text while retro keeps the pixel slot`() {
        val spinner = source("components/CliSpinner.kt")
        val stageProgress = source("components/CliStageProgress.kt")

        assertTrue(spinner.contains("modifier = modifier.size(cliSpinnerSlotSize)"))
        assertTrue(spinner.contains("contentAlignment = Alignment.Center"))
        assertTrue(spinner.contains("if (visible)"))
        assertTrue(stageProgress.contains("LocalCliVisualStyle.current == VisualStyle.PLAIN"))
        assertTrue(stageProgress.contains("CliShimmerText("))
        assertTrue(stageProgress.contains("CliPixelProgressSegments("))
        assertTrue(stageProgress.contains("if (running)"))
    }

    @Test
    fun `updates database note uses the canonical dashed info surface`() {
        val updates = source("settings/CliUpdatesSubScreen.kt")

        assertTrue(updates.contains("infoText = stringResource(R.string.cli_foxdb_note)"))
        assertFalse(updates.contains("CliElbowLine(text = stringResource(R.string.cli_foxdb_note))"))
        assertFalse(updates.contains("CliDashedInfoNote(text = stringResource(R.string.cli_foxdb_note))"))
    }

    private fun source(relative: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
        ).first(File::isFile).readText()
}
