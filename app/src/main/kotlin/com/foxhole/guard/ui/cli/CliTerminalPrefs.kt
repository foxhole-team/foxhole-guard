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

    /**
     * The one place a retention value is made safe. Zero, a negative number and a value larger than
     * the ceiling are all impossible to store: the journal's cutoff is computed from this, so a 0
     * would expire every line the moment it was written and a negative one would expire lines from
     * the future.
     */
    fun normalizedHours(hours: Int): Int = hours.coerceIn(MIN_HOURS, MAX_HOURS)

    /**
     * A typed custom value in [unit], or null when the entry is not a usable retention — empty,
     * non-numeric, or not positive. Null means "do not accept": the input modal stays open rather
     * than silently storing something the user did not ask for.
     *
     * A number far above the ceiling is not rejected but clamped: the user asked for "as long as
     * possible", and refusing the entry would read as a broken field.
     */
    fun hoursFromInput(raw: String, unit: CliRetentionUnit): Int? {
        val entered = raw.trim().toLongOrNull() ?: return null
        if (entered <= 0L) {
            return null
        }
        val hours = when (unit) {
            CliRetentionUnit.HOURS -> entered
            // Long arithmetic before the clamp: 999 days in Int hours is fine, but the guard here
            // is against any future ceiling change, not against this particular literal.
            CliRetentionUnit.DAYS -> entered * HOURS_PER_DAY
        }
        return hours.coerceIn(MIN_HOURS.toLong(), MAX_HOURS.toLong()).toInt()
    }

    /**
     * `48h` or `3d`: whole days read as days. Deliberately unit letters rather than words — the
     * terminal writes every duration this way, and it needs no translation.
     */
    fun retentionLabel(hours: Int): String {
        val normalized = normalizedHours(hours)
        return if (normalized >= HOURS_PER_DAY && normalized % HOURS_PER_DAY == 0) {
            "${normalized / HOURS_PER_DAY}d"
        } else {
            "${normalized}h"
        }
    }
}

/** Which unit a custom retention value is typed in. */
internal enum class CliRetentionUnit { HOURS, DAYS }
