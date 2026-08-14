package com.foxhole.guard.guardian

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuardEventUserTextContractTest {
    @Test
    fun `every persisted guard event has an explicit localized label`() {
        val source = projectFile(
            "app/src/main/kotlin/com/foxhole/guard/guardian/GuardEventUserText.kt",
        ).readText()

        GuardEventType.entries.forEach { type ->
            assertTrue("missing user label for $type", source.contains("GuardEventType.${type.name} ->"))
        }
    }

    @Test
    fun `security surfaces share localized anomaly text instead of raw reason`() {
        val logs = projectFile(
            "app/src/main/kotlin/com/foxhole/guard/ui/cli/logs/CliLogsScreen.kt",
        ).readText()
        val status = projectFile(
            "app/src/main/kotlin/com/foxhole/guard/ui/cli/home/CliStatusExtendedRows.kt",
        ).readText()

        assertTrue(logs.contains("event.userFacingMessage(context)"))
        assertTrue(status.contains("event.userFacingMessage(context)"))
        assertFalse(logs.contains("event.reason.takeIf"))
        assertFalse(status.contains("event.reason.takeIf"))
    }

    private fun projectFile(path: String): File =
        listOf(File(path), File("../$path"))
            .first(File::isFile)
}
