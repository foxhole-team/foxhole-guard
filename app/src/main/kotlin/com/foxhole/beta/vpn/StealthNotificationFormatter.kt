package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.NotificationSnapshot
import java.util.Locale

internal object StealthNotificationFormatter {
    fun subtext(snapshot: NotificationSnapshot): String? {
        if (snapshot.isRedacted) {
            return null
        }
        return countryEmoji(snapshot.countryCode)
    }

    fun countryEmoji(countryCode: String?): String? {
        val normalized = countryCode?.trim()?.uppercase(Locale.ROOT)
        if (normalized.isNullOrBlank() || normalized.length != 2 || !normalized.all(Char::isLetter)) {
            return null
        }
        return normalized.map { 0x1F1A5 + it.code }.joinToString(separator = "") { codePoint ->
            String(Character.toChars(codePoint))
        }
    }
}
