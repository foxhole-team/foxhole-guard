package com.foxhole.guard.ui.cli.components

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

// rememberSaveable cannot store a Set directly, so keys travel in the Bundle as a flat list. The
// shared Saver for expanded sections.
internal val CliStringSetSaver: Saver<Set<String>, Any> = listSaver(
    save = { it.toList() },
    restore = { it.toSet() },
)
