package com.foxhole.beta.core.settings

import com.foxhole.beta.BuildConfig

private val SUPPORT_BOT_HANDLE_REGEX = Regex("""^@[A-Za-z0-9_]{4,64}_bot$""")

internal fun normalizeSupportBotHandle(raw: String?): String? {
    val normalized = raw?.trim().orEmpty()
    return normalized.takeIf { it.matches(SUPPORT_BOT_HANDLE_REGEX) }
}

internal fun storedSupportBotHandleOverride(raw: String?): String? =
    normalizeSupportBotHandle(raw)
        ?.takeUnless { it.equals(BuildConfig.DEFAULT_SUPPORT_BOT_HANDLE, ignoreCase = true) }

internal fun effectiveSupportBotHandle(override: String?): String =
    normalizeSupportBotHandle(override) ?: BuildConfig.DEFAULT_SUPPORT_BOT_HANDLE

internal fun supportBotUsername(handle: String): String = handle.removePrefix("@")
