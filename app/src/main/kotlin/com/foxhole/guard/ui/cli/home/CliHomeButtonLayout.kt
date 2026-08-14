package com.foxhole.guard.ui.cli.home

import androidx.compose.runtime.Immutable

/**
 * Which buttons the home control block actually carries right now.
 *
 * The rule is the same one the status block follows: only what is in force. A control that cannot
 * do anything in the current state is not dimmed and not padded around — it is absent, and the
 * buttons that remain divide the whole row between them. That is why this is a function of state
 * and not a set of `enabled` flags.
 *
 * - MODE cycles VPN / VPN+TOR / TOR, so it exists only while the TOR module is switched on.
 *   With the module off there is nothing to cycle to and the primary row is the start/stop button
 *   alone, full width.
 * - RESTART (or RECONNECT, the same slot) acts on a live tunnel and appears only with one.
 * - I2P is the module's own switch and appears only while that module is on — alone in the row
 *   with STATUS, or beside RESTART when a tunnel is up.
 */
internal enum class CliHomeButton { MAIN, MODE, RESTART, STATUS, I2P }

@Immutable
internal data class CliHomeButtonRow(val buttons: List<CliHomeButton>) {
    /** Equal share of the full row width: no gaps, no placeholders — so it is exactly 1/n. */
    val share: Float
        get() = if (buttons.isEmpty()) 0f else 1f / buttons.size

    operator fun contains(button: CliHomeButton): Boolean = button in buttons
}

@Immutable
internal data class CliHomeButtonLayout(
    val primary: CliHomeButtonRow,
    val secondary: CliHomeButtonRow,
)

internal fun cliHomeButtonLayout(
    torModuleEnabled: Boolean,
    i2pModuleEnabled: Boolean,
    connected: Boolean,
): CliHomeButtonLayout =
    CliHomeButtonLayout(
        primary = CliHomeButtonRow(
            listOfNotNull(
                CliHomeButton.MAIN,
                CliHomeButton.MODE.takeIf { torModuleEnabled },
            ),
        ),
        secondary = CliHomeButtonRow(
            listOfNotNull(
                CliHomeButton.RESTART.takeIf { connected },
                CliHomeButton.STATUS,
                CliHomeButton.I2P.takeIf { i2pModuleEnabled },
            ),
        ),
    )
