package com.foxhole.guard.ui.cli

import android.content.Context
import androidx.core.content.edit
import com.foxhole.guard.ui.cli.home.CliTerminalState

/** Device-local terminal housekeeping: how long log lines live (hours). */
internal object CliTerminalPrefs {
    private const val PREFS_NAME = "cli_ui_prefs"
    private const val KEY_RETENTION_HOURS = "terminal_retention_hours"

    // CliTerminalState owns the default; this is only a reference so the two cannot drift.
    const val DEFAULT_HOURS = CliTerminalState.DEFAULT_RETENTION_HOURS
    const val MIN_HOURS = 1
    const val MAX_HOURS = 720

    fun readRetentionHours(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_RETENTION_HOURS, DEFAULT_HOURS)
            .coerceIn(MIN_HOURS, MAX_HOURS)

    fun writeRetentionHours(context: Context, hours: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_RETENTION_HOURS, hours.coerceIn(MIN_HOURS, MAX_HOURS))
        }
    }
}
