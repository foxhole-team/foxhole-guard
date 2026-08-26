package com.foxhole.guard.ui.cli

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliProgressLayoutContractTest {
    @Test
    fun `unified stage progress uses animated text and stable spinner slots`() {
        val spinner = source("components/CliSpinner.kt")
        val stageProgress = source("components/CliStageProgress.kt")

        assertTrue(spinner.contains("modifier = modifier.size(cliSpinnerSlotSize)"))
        assertTrue(spinner.contains("contentAlignment = Alignment.Center"))
        assertTrue(spinner.contains("if (visible)"))
        assertTrue(stageProgress.contains("CliShimmerText("))
        assertTrue(stageProgress.contains("CliPixelProgressBar("))
        assertTrue(stageProgress.contains("if (running)"))
        assertFalse(stageProgress.contains("VisualStyle"))
    }

    @Test
    fun `updates database panel omits the redundant single database info block`() {
        val updates = source("settings/CliUpdatesSubScreen.kt")

        assertFalse(updates.contains("R.string.cli_foxdb_note"))
    }

    private fun source(relative: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/$relative"),
        ).first(File::isFile).readText()
}
