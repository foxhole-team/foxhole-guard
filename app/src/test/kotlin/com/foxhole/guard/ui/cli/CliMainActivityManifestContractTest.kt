package com.foxhole.guard.ui.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * История терминала — это журнал сессии (причины реконнектов, цепочки Tor), и живёт она в
 * композиции: `CliApp` держит `CliTerminalState` в обычном `remember`. Любое пересоздание
 * активности стирает журнал до загрузочных строк, а поворот экрана — самый частый его повод:
 * человек лёг с телефоном, и лога нет.
 *
 * Пока состояние не сохраняемое, ориентация активности — часть контракта, а не оформление,
 * поэтому она прибита здесь.
 */
class CliMainActivityManifestContractTest {

    @Test
    fun `the launcher activity does not rotate`() {
        val activity = cliMainActivityBlock()

        assertTrue(
            "CliMainActivity must fix its orientation: a rotation re-creates the activity and " +
                "wipes the terminal journal, which lives in remember { CliTerminalState(...) }.",
            activity.contains("""android:screenOrientation="userPortrait""""),
        )
    }

    /**
     * Именно `userPortrait`: жёсткий `portrait` игнорирует принудительную ориентацию, выставленную
     * системными настройками доступности, а `sensorPortrait`/`fullSensor` возвращают поворот и
     * вместе с ним потерю журнала.
     */
    @Test
    fun `the fixed orientation still honours a user-forced orientation`() {
        val orientation = Regex("""android:screenOrientation="([^"]+)"""")
            .find(cliMainActivityBlock())
            ?.groupValues
            ?.get(1)

        assertEquals("userPortrait", orientation)
    }

    private fun cliMainActivityBlock(): String {
        val manifest = manifestFile().readText()
        val start = manifest.indexOf(""".ui.cli.CliMainActivity"""")
        assertTrue("CliMainActivity is missing from the manifest", start > 0)
        val declaration = manifest.lastIndexOf("<activity", start)
        val end = manifest.indexOf("</activity>", start)
        return manifest.substring(declaration, end)
    }

    private fun manifestFile(): File =
        repoRoot().resolve("app/src/main/AndroidManifest.xml").also { file ->
            check(file.isFile) { "Missing manifest: $file" }
        }

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { dir -> dir.resolve("settings.gradle.kts").isFile }
}
