package com.foxhole.guard.ui.cli

import java.util.Locale
import java.util.concurrent.TimeUnit

/** Terminal-style value formatting shared by the CLI screens. */
internal object CliFormat {

    fun bytes(value: Long): String {
        if (value < 1024) return "${value}B"
        val kb = value / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        if (gb < 1024) return String.format(Locale.US, "%.2fGB", gb)
        return String.format(Locale.US, "%.2fTB", gb / 1024.0)
    }

    fun rate(bytesPerSec: Long): String = bytes(bytesPerSec) + "/s"

    fun latency(ms: Long?): String = ms?.let { "${it}ms" } ?: "—"

    fun clock(timestampMs: Long): String {
        if (timestampMs <= 0L) return "--:--:--"
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestampMs
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
        )
    }

    fun uptime(sinceMs: Long, nowMs: Long): String {
        if (sinceMs <= 0L || nowMs <= sinceMs) return "0s"
        val total = nowMs - sinceMs
        val h = TimeUnit.MILLISECONDS.toHours(total)
        val m = TimeUnit.MILLISECONDS.toMinutes(total) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(total) % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh%02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm%02ds", m, s)
            else -> "${s}s"
        }
    }

    fun percent(ratio: Float): String = String.format(Locale.US, "%.0f%%", ratio * 100f)
}
