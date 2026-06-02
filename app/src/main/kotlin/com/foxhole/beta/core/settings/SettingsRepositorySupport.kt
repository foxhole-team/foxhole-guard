package com.foxhole.beta.core.settings

import android.content.Context
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.SmartProfileNetworkMemory
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import com.foxhole.beta.core.model.ThemeMode

internal fun Settings.withSmartProfilePreference(preference: SmartProfilePreference): Settings {
    val updatedPreferences =
        smartProfilePreferences
            .filterNot { currentPreference -> currentPreference.profileId == preference.profileId } +
            preference
    return copy(smartProfilePreferences = updatedPreferences)
}

internal fun SmartProfilePreference.clearedSmartStartRuntimeData(): SmartProfilePreference =
    SmartProfilePreference(
        profileId = profileId,
        excludedProtocolOptionIds = excludedProtocolOptionIds,
    )

internal fun SmartProfileProtocolMemory.normalized(): SmartProfileProtocolMemory? {
    val normalizedOptionId = optionId.trim().takeIf(String::isNotBlank) ?: return null
    return copy(
        optionId = normalizedOptionId,
        lastSuccessAt = lastSuccessAt?.takeIf { it > 0L },
        lastFailureAt = lastFailureAt?.takeIf { it > 0L },
        lastLatencyMs = lastLatencyMs?.takeIf { it > 0L },
        lastServerPingMs = lastServerPingMs?.takeIf { it > 0L },
        lastServerPingAt = lastServerPingAt?.takeIf { it > 0L },
        failureStreak = failureStreak.coerceAtLeast(0),
        validationFailureCount = validationFailureCount.coerceAtLeast(0),
        successCount = successCount.coerceAtLeast(0),
        failureCount = failureCount.coerceAtLeast(0),
        lastConnectDurationMs = lastConnectDurationMs?.takeIf { it > 0L },
        lastValidatedAt = lastValidatedAt?.takeIf { it > 0L },
        lastTrafficAt = lastTrafficAt?.takeIf { it > 0L },
        cooldownUntilAt = cooldownUntilAt?.takeIf { it > 0L },
    )
}

internal fun SmartProfileNetworkMemory.normalized(): SmartProfileNetworkMemory? {
    val normalizedNetworkFingerprint = networkFingerprint.trim().takeIf(String::isNotBlank) ?: return null
    val normalizedProtocolMemories =
        protocolMemories
            .mapNotNull(SmartProfileProtocolMemory::normalized)
            .distinctBy(SmartProfileProtocolMemory::optionId)
            .sortedBy(SmartProfileProtocolMemory::optionId)
    val normalizedLastKnownGoodOptionId = lastKnownGoodOptionId?.trim()?.takeIf(String::isNotBlank)
    return copy(
        networkFingerprint = normalizedNetworkFingerprint,
        networkFingerprintSchema = networkFingerprintSchema.coerceAtLeast(1),
        lastKnownGoodOptionId = normalizedLastKnownGoodOptionId,
        lastKnownGoodLatencyMs = lastKnownGoodLatencyMs?.takeIf { it > 0L },
        lastKnownGoodAt = lastKnownGoodAt?.takeIf { it > 0L },
        protocolMemories = normalizedProtocolMemories,
    ).takeIf {
        normalizedLastKnownGoodOptionId != null || normalizedProtocolMemories.isNotEmpty()
    }
}

internal data class SmartProfileMemoryUpdate(
    val lastKnownGoodOptionId: String?,
    val lastKnownGoodLatencyMs: Long?,
    val lastKnownGoodAt: Long?,
    val protocolMemories: List<SmartProfileProtocolMemory>,
)

internal fun recordProbeResultIntoMemory(
    lastKnownGoodOptionId: String?,
    lastKnownGoodLatencyMs: Long?,
    lastKnownGoodAt: Long?,
    protocolMemories: List<SmartProfileProtocolMemory>,
    optionId: String,
    latencyMs: Long?,
    success: Boolean,
    reasonCode: AutoConnectReasonCode?,
    markAsLastKnownGood: Boolean,
    recordedAt: Long,
    connectDurationMs: Long? = null,
    validatedAt: Long? = null,
    trafficAt: Long? = null,
    countTowardOutcomeHistory: Boolean = true,
    affectsFailureRankingMemory: Boolean = true,
): SmartProfileMemoryUpdate {
    val existingMemories =
        protocolMemories
            .filter { memory -> memory.hasFreshSmartStartEvidence(recordedAt) }
            .associateBy(SmartProfileProtocolMemory::optionId)
            .toMutableMap()
    val previous = existingMemories[optionId]
    if (!success && !affectsFailureRankingMemory) {
        return SmartProfileMemoryUpdate(
            lastKnownGoodOptionId = lastKnownGoodOptionId,
            lastKnownGoodLatencyMs = lastKnownGoodLatencyMs,
            lastKnownGoodAt = lastKnownGoodAt,
            protocolMemories = existingMemories.values.sortedBy(SmartProfileProtocolMemory::optionId),
        )
    }
    val nextFailureStreak =
        when {
            success -> 0
            countTowardOutcomeHistory -> (previous?.failureStreak ?: 0) + 1
            else -> previous?.failureStreak ?: 0
        }
    val nextCooldownUntilAt =
        when {
            success -> null
            else -> resolveAdaptiveProtocolCooldownUntil(
                recordedAt = recordedAt,
                failureStreak = nextFailureStreak,
                reasonCode = reasonCode,
            ) ?: previous?.cooldownUntilAt
        }
    existingMemories[optionId] =
        if (success) {
            SmartProfileProtocolMemory(
                optionId = optionId,
                lastSuccessAt = recordedAt,
                lastFailureAt = previous?.lastFailureAt,
                lastLatencyMs = latencyMs ?: previous?.lastLatencyMs,
                lastServerPingMs = previous?.lastServerPingMs,
                lastServerPingAt = previous?.lastServerPingAt,
                lastReasonCode = reasonCode,
                failureStreak = 0,
                validationFailureCount = previous?.validationFailureCount ?: 0,
                successCount = (previous?.successCount ?: 0) + if (countTowardOutcomeHistory) 1 else 0,
                failureCount = previous?.failureCount ?: 0,
                lastConnectDurationMs = connectDurationMs ?: previous?.lastConnectDurationMs,
                lastValidatedAt = validatedAt ?: recordedAt,
                lastTrafficAt = trafficAt ?: recordedAt,
                cooldownUntilAt = null,
            )
        } else {
            SmartProfileProtocolMemory(
                optionId = optionId,
                lastSuccessAt = previous?.lastSuccessAt,
                lastFailureAt = recordedAt,
                lastLatencyMs = previous?.lastLatencyMs,
                lastServerPingMs = previous?.lastServerPingMs,
                lastServerPingAt = previous?.lastServerPingAt,
                lastReasonCode = reasonCode,
                failureStreak = nextFailureStreak,
                validationFailureCount =
                    (previous?.validationFailureCount ?: 0) +
                        if (countTowardOutcomeHistory && reasonCode.isTunnelValidationFailure()) 1 else 0,
                successCount = previous?.successCount ?: 0,
                failureCount = (previous?.failureCount ?: 0) + if (countTowardOutcomeHistory) 1 else 0,
                lastConnectDurationMs = connectDurationMs ?: previous?.lastConnectDurationMs,
                lastValidatedAt = previous?.lastValidatedAt,
                lastTrafficAt = previous?.lastTrafficAt,
                cooldownUntilAt = nextCooldownUntilAt,
            )
        }
    val shouldRefreshLastKnownGood = success && (markAsLastKnownGood || lastKnownGoodOptionId == optionId)
    return SmartProfileMemoryUpdate(
        lastKnownGoodOptionId =
            when {
                markAsLastKnownGood -> optionId
                else -> lastKnownGoodOptionId
            },
        lastKnownGoodLatencyMs =
            when {
                shouldRefreshLastKnownGood -> latencyMs ?: lastKnownGoodLatencyMs
                else -> lastKnownGoodLatencyMs
            },
        lastKnownGoodAt =
            when {
                shouldRefreshLastKnownGood -> recordedAt
                else -> lastKnownGoodAt
            },
        protocolMemories = existingMemories.values.sortedBy(SmartProfileProtocolMemory::optionId),
    )
}

private fun SmartProfileProtocolMemory.hasFreshSmartStartEvidence(now: Long): Boolean {
    val newestEvidenceAt =
        listOfNotNull(
            lastSuccessAt,
            lastFailureAt,
            lastServerPingAt,
            lastValidatedAt,
            lastTrafficAt,
            cooldownUntilAt?.takeIf { cooldownUntil -> cooldownUntil > now },
        ).maxOrNull() ?: return false
    return now - newestEvidenceAt <= SMART_START_MEMORY_RETENTION_MS
}

internal fun AutoConnectReasonCode?.isTunnelValidationFailure(): Boolean =
    this == AutoConnectReasonCode.VALIDATION_TIMEOUT || this == AutoConnectReasonCode.DNS_FAILURE

internal fun resolveAdaptiveProtocolCooldownUntil(
    recordedAt: Long,
    failureStreak: Int,
    reasonCode: AutoConnectReasonCode?,
): Long? {
    val baseCooldownMs =
        when (reasonCode) {
            AutoConnectReasonCode.VALIDATION_TIMEOUT -> 4L * 60L * 1000L
            AutoConnectReasonCode.DNS_FAILURE -> 3L * 60L * 1000L
            AutoConnectReasonCode.HANDSHAKE_TIMEOUT -> 2L * 60L * 1000L
            AutoConnectReasonCode.CONNECT_ERROR -> 90L * 1000L
            AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED,
            AutoConnectReasonCode.RESTORED_LAST_GOOD,
            null,
            -> 0L
        }
    if (baseCooldownMs <= 0L) {
        return null
    }
    val multiplier = 1L shl (failureStreak.coerceAtLeast(1) - 1).coerceAtMost(3)
    return recordedAt + minOf(baseCooldownMs * multiplier, 30L * 60L * 1000L)
}

internal fun parseStoredThemeMode(value: String?): ThemeMode {
    return parseOptionalStoredThemeMode(value) ?: ThemeMode.SYSTEM
}

internal fun parseOptionalStoredThemeMode(value: String?): ThemeMode? {
    val normalized = value?.trim()?.uppercase().orEmpty()
    return when {
        normalized.isBlank() -> null
        else -> runCatching { ThemeMode.valueOf(normalized) }.getOrNull()
    }
}

internal fun parseOptionalStoredAppLocale(value: String?): AppLocale? {
    val normalized = value?.trim()?.uppercase().orEmpty()
    return when {
        normalized.isBlank() -> null
        else -> runCatching { AppLocale.valueOf(normalized) }.getOrNull()
    }
}

internal fun readFastStoredAppLocale(context: Context): AppLocale {
    val prefs = context.applicationContext.getSharedPreferences(FAST_UI_PREFERENCES_NAME, Context.MODE_PRIVATE)
    return parseOptionalStoredAppLocale(prefs.getString(FAST_LOCALE_KEY, null)) ?: AppLocale.SYSTEM
}

internal fun readFastStoredThemeMode(context: Context): ThemeMode {
    val prefs = context.applicationContext.getSharedPreferences(FAST_UI_PREFERENCES_NAME, Context.MODE_PRIVATE)
    return parseOptionalStoredThemeMode(prefs.getString(FAST_THEME_MODE_KEY, null)) ?: ThemeMode.SYSTEM
}

internal fun sanitizeStoredThemeModePayload(payload: String): String =
    STORED_THEME_MODE_REGEX.replace(payload) { match ->
        val storedValue = match.groupValues[2]
        val normalizedValue = parseOptionalStoredThemeMode(storedValue)?.name ?: ThemeMode.SYSTEM.name
        "${match.groupValues[1]}$normalizedValue${match.groupValues[3]}"
    }

private val STORED_THEME_MODE_REGEX = Regex("""("themeMode"\s*:\s*")([^"]+)(")""")
