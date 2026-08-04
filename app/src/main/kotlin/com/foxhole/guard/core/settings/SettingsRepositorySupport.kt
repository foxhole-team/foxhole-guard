package com.foxhole.guard.core.settings

import android.content.Context
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.SmartProfileNetworkMemory
import com.foxhole.core.model.SmartProfilePreference
import com.foxhole.core.model.SmartProfileProtocolMemory
import com.foxhole.core.model.ThemeMode

internal fun Settings.withSmartProfilePreference(preference: SmartProfilePreference): Settings {
    val updatedPreferences =
        smartProfilePreferences
            .filterNot { currentPreference -> currentPreference.profileId == preference.profileId } +
            preference
    return copy(smartProfilePreferences = updatedPreferences)
}

internal fun Settings.canAccumulateProfileTraffic(
    rxBytes: Long,
    txBytes: Long,
): Boolean =
    statistics.enabled &&
        statistics.profileTrafficEnabled &&
        (rxBytes > 0L || txBytes > 0L)

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
    existingMemories[optionId] =
        if (success) {
            previous.toSuccessProtocolMemory(
                optionId = optionId,
                latencyMs = latencyMs,
                reasonCode = reasonCode,
                recordedAt = recordedAt,
                connectDurationMs = connectDurationMs,
                validatedAt = validatedAt,
                trafficAt = trafficAt,
                countTowardOutcomeHistory = countTowardOutcomeHistory,
            )
        } else {
            previous.toFailureProtocolMemory(
                optionId = optionId,
                reasonCode = reasonCode,
                recordedAt = recordedAt,
                connectDurationMs = connectDurationMs,
                countTowardOutcomeHistory = countTowardOutcomeHistory,
            )
        }
    return resolveMemoryUpdate(
        success = success,
        markAsLastKnownGood = markAsLastKnownGood,
        optionId = optionId,
        latencyMs = latencyMs,
        recordedAt = recordedAt,
        lastKnownGoodOptionId = lastKnownGoodOptionId,
        lastKnownGoodLatencyMs = lastKnownGoodLatencyMs,
        lastKnownGoodAt = lastKnownGoodAt,
        protocolMemories = existingMemories.values.sortedBy(SmartProfileProtocolMemory::optionId),
    )
}

private fun resolveMemoryUpdate(
    success: Boolean,
    markAsLastKnownGood: Boolean,
    optionId: String,
    latencyMs: Long?,
    recordedAt: Long,
    lastKnownGoodOptionId: String?,
    lastKnownGoodLatencyMs: Long?,
    lastKnownGoodAt: Long?,
    protocolMemories: List<SmartProfileProtocolMemory>,
): SmartProfileMemoryUpdate {
    val shouldRefreshLastKnownGood = success && (markAsLastKnownGood || lastKnownGoodOptionId == optionId)
    return SmartProfileMemoryUpdate(
        lastKnownGoodOptionId = if (markAsLastKnownGood) optionId else lastKnownGoodOptionId,
        lastKnownGoodLatencyMs =
        if (shouldRefreshLastKnownGood) latencyMs ?: lastKnownGoodLatencyMs else lastKnownGoodLatencyMs,
        lastKnownGoodAt = if (shouldRefreshLastKnownGood) recordedAt else lastKnownGoodAt,
        protocolMemories = protocolMemories,
    )
}

private fun SmartProfileProtocolMemory?.toSuccessProtocolMemory(
    optionId: String,
    latencyMs: Long?,
    reasonCode: AutoConnectReasonCode?,
    recordedAt: Long,
    connectDurationMs: Long?,
    validatedAt: Long?,
    trafficAt: Long?,
    countTowardOutcomeHistory: Boolean,
): SmartProfileProtocolMemory =
    SmartProfileProtocolMemory(
        optionId = optionId,
        lastSuccessAt = recordedAt,
        lastFailureAt = this?.lastFailureAt,
        lastLatencyMs = latencyMs ?: this?.lastLatencyMs,
        lastServerPingMs = this?.lastServerPingMs,
        lastServerPingAt = this?.lastServerPingAt,
        lastReasonCode = reasonCode,
        failureStreak = 0,
        validationFailureCount = this?.validationFailureCount ?: 0,
        successCount = (this?.successCount ?: 0) + if (countTowardOutcomeHistory) 1 else 0,
        failureCount = this?.failureCount ?: 0,
        lastConnectDurationMs = connectDurationMs ?: this?.lastConnectDurationMs,
        lastValidatedAt = validatedAt ?: recordedAt,
        lastTrafficAt = trafficAt ?: recordedAt,
        cooldownUntilAt = null,
    )

private fun SmartProfileProtocolMemory?.toFailureProtocolMemory(
    optionId: String,
    reasonCode: AutoConnectReasonCode?,
    recordedAt: Long,
    connectDurationMs: Long?,
    countTowardOutcomeHistory: Boolean,
): SmartProfileProtocolMemory {
    val failureStreak = (this?.failureStreak ?: 0) + if (countTowardOutcomeHistory) 1 else 0
    val cooldownUntilAt =
        resolveAdaptiveProtocolCooldownUntil(
            recordedAt = recordedAt,
            failureStreak = failureStreak,
            reasonCode = reasonCode,
        ) ?: this?.cooldownUntilAt
    return SmartProfileProtocolMemory(
        optionId = optionId,
        lastSuccessAt = this?.lastSuccessAt,
        lastFailureAt = recordedAt,
        lastLatencyMs = this?.lastLatencyMs,
        lastServerPingMs = this?.lastServerPingMs,
        lastServerPingAt = this?.lastServerPingAt,
        lastReasonCode = reasonCode,
        failureStreak = failureStreak,
        validationFailureCount =
        (this?.validationFailureCount ?: 0) +
            if (countTowardOutcomeHistory && reasonCode.isTunnelValidationFailure()) 1 else 0,
        successCount = this?.successCount ?: 0,
        failureCount = (this?.failureCount ?: 0) + if (countTowardOutcomeHistory) 1 else 0,
        lastConnectDurationMs = connectDurationMs ?: this?.lastConnectDurationMs,
        lastValidatedAt = this?.lastValidatedAt,
        lastTrafficAt = this?.lastTrafficAt,
        cooldownUntilAt = cooldownUntilAt,
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

internal fun parseOptionalStoredAppLockMode(value: String?): AppLockMode? {
    val normalized = value?.trim()?.uppercase().orEmpty()
    return when {
        normalized.isBlank() -> null
        else -> runCatching { AppLockMode.valueOf(normalized) }.getOrNull()
    }
}

internal fun readFastStoredAppLockMode(context: Context): AppLockMode {
    val prefs = context.applicationContext.getSharedPreferences(FAST_UI_PREFERENCES_NAME, Context.MODE_PRIVATE)
    return parseOptionalStoredAppLockMode(prefs.getString(FAST_APP_LOCK_MODE_KEY, null)) ?: AppLockMode.OFF
}

internal fun readFastStoredAppLockBuiltInPad(context: Context): Boolean {
    val prefs = context.applicationContext.getSharedPreferences(FAST_UI_PREFERENCES_NAME, Context.MODE_PRIVATE)
    return prefs.getBoolean(FAST_APP_LOCK_BUILTIN_PAD_KEY, false)
}

internal fun readFastStoredAppLockScramble(context: Context): Boolean {
    val prefs = context.applicationContext.getSharedPreferences(FAST_UI_PREFERENCES_NAME, Context.MODE_PRIVATE)
    return prefs.getBoolean(FAST_APP_LOCK_SCRAMBLE_KEY, true)
}

internal fun sanitizeStoredThemeModePayload(payload: String): String =
    STORED_THEME_MODE_REGEX.replace(payload) { match ->
        val storedValue = match.groupValues[2]
        val normalizedValue = parseOptionalStoredThemeMode(storedValue)?.name ?: ThemeMode.SYSTEM.name
        "${match.groupValues[1]}$normalizedValue${match.groupValues[3]}"
    }

private val STORED_THEME_MODE_REGEX = Regex("""("themeMode"\s*:\s*")([^"]+)(")""")
