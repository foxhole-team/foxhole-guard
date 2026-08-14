package com.foxhole.guard.ui.cli.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ряд кнопок главного экрана: в нём нет пустых мест и нет кнопок, которые в текущем состоянии
 * ничего не делают. Кнопка, которой нечего сделать, не «серая» — её просто нет, а ширину делят
 * между собой те, что остались.
 */
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

    /** Владелец: без модуля TOR START занимает освободившуюся ширину целиком. */
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

    /** Перезапуск действует на живой туннель — без туннеля кнопки нет вовсе. */
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

    /** Туннель поднят и I2P включён — три кнопки делят ряд поровну. */
    @Test
    fun `a live tunnel with i2p splits the row three ways`() {
        val secondary = layout(i2p = true, connected = true).secondary

        assertEquals(
            listOf(CliHomeButton.RESTART, CliHomeButton.STATUS, CliHomeButton.I2P),
            secondary.buttons,
        )
        assertEquals(1f / 3f, secondary.share, 0f)
    }

    /** Туннель выключен: перезапускать нечего, ряд делят STATUS и I2P пополам. */
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

    /** Ни одно состояние не оставляет пустой ряд и не делит ширину на ноль. */
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
}
