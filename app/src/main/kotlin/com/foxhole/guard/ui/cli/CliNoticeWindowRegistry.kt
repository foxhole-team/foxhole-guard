package com.foxhole.guard.ui.cli

import androidx.compose.runtime.mutableStateListOf

internal class CliNoticeWindowRegistry {
    private val windows = mutableStateListOf<Any>()

    fun add(owner: Any) {
        if (owner !in windows) windows.add(owner)
    }

    fun remove(owner: Any) {
        windows.remove(owner)
    }

    fun isForemost(owner: Any): Boolean = windows.lastOrNull() === owner
}
