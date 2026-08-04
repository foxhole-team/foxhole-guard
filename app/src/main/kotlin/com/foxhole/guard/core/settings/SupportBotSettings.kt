package com.foxhole.guard.core.settings

import com.foxhole.guard.BuildConfig

private val SUPPORT_BOT_HANDLE_REGEX = Regex("""^@[A-Za-z0-9_]{4,64}_bot$""")

internal fun normalizeSupportBotHandle(raw: String?): String? {
    val normalized = raw?.trim().orEmpty()
    return normalized.takeIf { it.matches(SUPPORT_BOT_HANDLE_REGEX) }
}

internal fun storedSupportBotHandleOverride(raw: String?): String? =
    normalizeSupportBotHandle(raw)
        ?.takeUnless { it in LEGACY_SUPPORT_BOT_HANDLES }
        ?.takeUnless { it.equals(BuildConfig.DEFAULT_SUPPORT_BOT_HANDLE, ignoreCase = true) }

private val LEGACY_SUPPORT_BOT_HANDLES =
    setOf(
        "@foxhole_app_support_bot",
    )
