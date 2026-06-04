package com.foxhole.beta.ui

import java.util.Locale
import kotlin.math.roundToInt

internal fun formatTrafficMapLegendBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var scaled = value
    var unitIndex = 0
    while (scaled >= 1024.0 && unitIndex < units.lastIndex) {
        scaled /= 1024.0
        unitIndex += 1
    }
    return when {
        unitIndex == 0 -> "${scaled.toLong()} ${units[unitIndex]}"
        scaled >= 100.0 -> "${scaled.roundToInt()} ${units[unitIndex]}"
        else -> String.format(Locale.US, "%.1f %s", scaled, units[unitIndex])
    }
}
