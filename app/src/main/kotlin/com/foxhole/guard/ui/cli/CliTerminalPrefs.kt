package com.foxhole.guard.ui.cli

import android.content.Context
import androidx.core.content.edit
import com.foxhole.guard.ui.cli.home.CliTerminalState

internal object CliTerminalPrefs {
    private const val PREFS_NAME = "cli_ui_prefs"
    private const val KEY_RETENTION_HOURS = "terminal_retention_hours"

    const val DEFAULT_HOURS = CliTerminalState.DEFAULT_RETENTION_HOURS
    const val MIN_HOURS = 1
    const val MAX_HOURS = 720
    const val HOURS_PER_DAY = 24
    const val MAX_DAYS = MAX_HOURS / HOURS_PER_DAY

    fun readRetentionHours(context: Context): Int =
        normalizedHours(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_RETENTION_HOURS, DEFAULT_HOURS),
        )

    fun writeRetentionHours(context: Context, hours: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_RETENTION_HOURS, normalizedHours(hours))
        }
    }

    fun normalizedHours(hours: Int): Int = hours.coerceIn(MIN_HOURS, MAX_HOURS)

    fun hoursFromInput(raw: String, unit: CliRetentionUnit): Int? {
        val entered = raw.trim().toLongOrNull() ?: return null
        if (entered <= 0L) {
            return null
        }
        val hours = when (unit) {
            CliRetentionUnit.HOURS -> entered
            CliRetentionUnit.DAYS -> entered * HOURS_PER_DAY
        }
        return hours.coerceIn(MIN_HOURS.toLong(), MAX_HOURS.toLong()).toInt()
    }

    fun retentionLabel(hours: Int): String {
        val normalized = normalizedHours(hours)
        return if (normalized >= HOURS_PER_DAY && normalized % HOURS_PER_DAY == 0) {
            "${normalized / HOURS_PER_DAY}d"
        } else {
            "${normalized}h"
        }
    }
}

internal enum class CliRetentionUnit { HOURS, DAYS }
