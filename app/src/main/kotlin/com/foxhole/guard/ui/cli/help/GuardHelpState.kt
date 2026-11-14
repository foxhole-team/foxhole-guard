package com.foxhole.guard.ui.cli.help

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.foxhole.guard.ui.cli.components.CliStringSetSaver

internal class GuardHelpState(val expanded: MutableState<Set<String>>, val scroll: ScrollState)


@Composable
internal fun rememberGuardHelpState(): GuardHelpState {
    val expanded = rememberSaveable(stateSaver = CliStringSetSaver) { mutableStateOf(emptySet<String>()) }
    val scroll = rememberScrollState()
    return remember(expanded, scroll) { GuardHelpState(expanded, scroll) }
}
