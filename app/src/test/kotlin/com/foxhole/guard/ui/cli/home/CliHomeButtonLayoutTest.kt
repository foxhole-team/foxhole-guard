package com.foxhole.guard.ui.cli.home

import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliHomeButtonLayoutTest {

    private fun layout(
        tor: Boolean = true,
        i2p: Boolean = false,
        connected: Boolean = false,
    ) = cliHomeButtonLayout(torModuleEnabled = tor, i2pModuleEnabled = i2p, connected = connected)

    @Test
    fun `mode button exists only while the tor module is on`() {
        assertTrue(CliHomeButton.MODE in layout(tor = true).primary)
        assertFalse(CliHomeButton.MODE in layout(tor = false).primary)
    }

    @Test
    fun `without the tor module the start button takes the whole row`() {
        val primary = layout(tor = false).primary

        assertEquals(listOf(CliHomeButton.MAIN), primary.buttons)
        assertEquals(1f, primary.share, 0f)
    }

    @Test
    fun `with the tor module the row is split in half`() {
        val primary = layout(tor = true).primary

        assertEquals(listOf(CliHomeButton.MAIN, CliHomeButton.MODE), primary.buttons)
        assertEquals(0.5f, primary.share, 0f)
    }

    @Test
    fun `restart appears only with a live tunnel`() {
        assertTrue(CliHomeButton.RESTART in layout(connected = true).secondary)
        assertFalse(CliHomeButton.RESTART in layout(connected = false).secondary)
    }

    @Test
    fun `i2p appears only while its module is on`() {
        assertTrue(CliHomeButton.I2P in layout(i2p = true).secondary)
        assertFalse(CliHomeButton.I2P in layout(i2p = false).secondary)
    }

    @Test
    fun `a live tunnel with i2p splits the row three ways`() {
        val secondary = layout(i2p = true, connected = true).secondary

        assertEquals(
            listOf(CliHomeButton.RESTART, CliHomeButton.STATUS, CliHomeButton.I2P),
            secondary.buttons,
        )
        assertEquals(1f / 3f, secondary.share, 0f)
    }

    @Test
    fun `without a tunnel the row drops restart and splits in half`() {
        val secondary = layout(i2p = true, connected = false).secondary

        assertEquals(listOf(CliHomeButton.STATUS, CliHomeButton.I2P), secondary.buttons)
        assertEquals(0.5f, secondary.share, 0f)
    }

    @Test
    fun `with neither tunnel nor i2p status takes the whole row`() {
        val secondary = layout(i2p = false, connected = false).secondary

        assertEquals(listOf(CliHomeButton.STATUS), secondary.buttons)
        assertEquals(1f, secondary.share, 0f)
    }

    @Test
    fun `start is outlined green while stop and cancel keep their own actions`() {
        val start = mainButtonAction(connected = false, busy = false)
        val stop = mainButtonAction(connected = true, busy = false)
        val cancel = mainButtonAction(connected = false, busy = true)

        assertEquals(R.string.cli_home_btn_connect, start.labelRes)
        assertNull(start.command)
        assertFalse(start.filled)
        assertEquals(CliMainActionTone.START, start.tone)

        assertEquals(R.string.cli_home_btn_disconnect, stop.labelRes)
        assertEquals(CliCommands.STOP, stop.command)
        assertFalse(stop.filled)
        assertEquals(CliMainActionTone.STOP, stop.tone)

        assertEquals(R.string.cli_home_btn_cancel, cancel.labelRes)
        assertEquals(CliCommands.CANCEL, cancel.command)
        assertFalse(cancel.filled)
        assertEquals(CliMainActionTone.STOP, cancel.tone)
    }

    @Test
    fun `no state leaves an empty row`() {
        listOf(true, false).forEach { tor ->
            listOf(true, false).forEach { i2p ->
                listOf(true, false).forEach { connected ->
                    val current = layout(tor = tor, i2p = i2p, connected = connected)
                    assertTrue(current.primary.buttons.isNotEmpty())
                    assertTrue(current.secondary.buttons.isNotEmpty())
                    assertEquals(1f, current.primary.share * current.primary.buttons.size, 0.0001f)
                    assertEquals(1f, current.secondary.share * current.secondary.buttons.size, 0.0001f)
                }
            }
        }
    }

    @Test
    fun `home buttons add no extra gap before the dock clearance`() {
        val source = listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/home/CliHomeScreen.kt"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/home/CliHomeScreen.kt"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/home/CliHomeScreen.kt"),
        ).first(File::isFile).readText()
        val buttonsTail = source
            .substringAfter("CliHomeButtonsArea(")
            .substringBefore("@Composable\nprivate fun CliHomeAdditionalInfoSlot(")

        assertTrue(buttonsTail.contains("CliChromeTailSpacer(extraGap = 0.dp)"))
    }

    @Test
    fun `button count changes morph widths instead of replacing the whole row`() {
        val source = listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/home/CliHomeButtons.kt"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/home/CliHomeButtons.kt"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/home/CliHomeButtons.kt"),
        ).first(File::isFile).readText()

        assertTrue(source.contains("private fun CliMorphingActionRow("))
        assertTrue(source.contains("updateTransition(targetState = row"))
        assertTrue(source.contains(".weight(weight)"))
        assertTrue(source.contains("CliMotion.settle()"))
        assertFalse(source.contains("cliHomeActionRowSwap"))
        assertFalse(source.contains("AnimatedContent("))
    }

    @Test
    fun `home never duplicates reconnect as two warning buttons`() {
        val source = listOf(
            File("src/main/kotlin/com/foxhole/guard/ui/cli/home/CliHomeButtons.kt"),
            File("app/src/main/kotlin/com/foxhole/guard/ui/cli/home/CliHomeButtons.kt"),
            File("../app/src/main/kotlin/com/foxhole/guard/ui/cli/home/CliHomeButtons.kt"),
        ).first(File::isFile).readText()

        assertFalse(source.contains("R.string.cli_home_btn_reconnect"))
        assertFalse(source.contains("CliCommands.RECONNECT"))
        assertFalse(source.contains("CliMainActionTone.WARN"))
        assertTrue(source.contains("label = stringResource(R.string.cli_home_btn_restart)"))
        assertTrue(source.contains("terminal.command(CliCommands.RESTART)"))
    }
}
