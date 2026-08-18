package com.foxhole.guard.ui.cli.components

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

internal val CliStringSetSaver: Saver<Set<String>, Any> = listSaver(
    save = { it.toList() },
    restore = { it.toSet() },
)
