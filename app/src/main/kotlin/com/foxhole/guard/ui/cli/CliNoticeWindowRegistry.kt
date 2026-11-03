package com.foxhole.guard.ui.cli

import androidx.compose.runtime.mutableStateListOf

internal class CliNoticeWindowRegistry {
    private val windows = mutableStateListOf<Any>()
    private val leaving = mutableStateListOf<Any>()

    fun add(owner: Any) {
        if (owner !in windows) windows.add(owner)
    }

    fun remove(owner: Any) {
        windows.remove(owner)
        leaving.remove(owner)
    }

    fun handoff(owner: Any, closing: Boolean) {
        if (closing && owner in windows && owner !in leaving) leaving.add(owner)
        else if (!closing) leaving.remove(owner)
    }
    fun draws(owner: Any): Boolean = isForemost(owner) || windows.lastOrNull() in leaving
    fun isForemost(owner: Any): Boolean = windows.lastOrNull() === owner
}
