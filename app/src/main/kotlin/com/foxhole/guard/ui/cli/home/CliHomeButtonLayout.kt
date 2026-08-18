package com.foxhole.guard.ui.cli.home

import androidx.compose.runtime.Immutable

internal enum class CliHomeButton { MAIN, MODE, RESTART, STATUS, I2P }

@Immutable
internal data class CliHomeButtonRow(val buttons: List<CliHomeButton>) {
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
