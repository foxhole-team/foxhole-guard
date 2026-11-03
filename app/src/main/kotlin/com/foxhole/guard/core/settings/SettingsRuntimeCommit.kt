package com.foxhole.guard.core.settings

internal fun interface SettingsRuntimeCommit {
    suspend fun committed()
}
