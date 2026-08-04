@file:Suppress("ReturnCount")

package com.foxhole.guard.core.settings

import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.NETWORK_FINGERPRINT_SCHEMA_CURRENT
import com.foxhole.core.model.ProfileTrafficTotal
import com.foxhole.core.model.Settings
import com.foxhole.core.model.SmartProfileNetworkMemory
import com.foxhole.core.model.SmartProfilePreference
import com.foxhole.core.model.SmartProfileProtocolMemory
import java.security.MessageDigest

internal fun Settings.smartProfilePreference(profileId: Long): SmartProfilePreference? =
    smartProfilePreferences.firstOrNull { preference -> preference.profileId == profileId }

internal fun SmartProfilePreference.networkMemory(networkFingerprint: String?): SmartProfileNetworkMemory? {
    val normalizedNetworkFingerprint = networkFingerprint?.trim()?.takeIf(String::isNotBlank) ?: return null
    return networkMemories.firstOrNull { memory ->
        memory.networkFingerprint == normalizedNetworkFingerprint &&
            memory.networkFingerprintSchema == NETWORK_FINGERPRINT_SCHEMA_CURRENT
    }
}

internal fun SmartProfilePreference.preferredLastKnownGoodOptionId(networkFingerprint: String?): String? =
    networkMemory(networkFingerprint)?.lastKnownGoodOptionId ?: lastKnownGoodOptionId

internal const val SMART_START_RECOMMENDED_LIMIT = 3
internal const val SMART_START_REMEMBERED_LATENCY_RETENTION_MS = 21L * 24L * 60L * 60L * 1000L
internal const val SMART_START_MEMORY_RETENTION_MS = SMART_START_REMEMBERED_LATENCY_RETENTION_MS

internal fun smartStartEnabledProtocolSetHash(optionIds: Collection<String>): String {
    val payload =
        optionIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .joinToString(separator = "\n")
    val digest = MessageDigest.getInstance("SHA-256").digest(payload.encodeToByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

// UI-facing remembered-memory readers deliberately ignore per-network memories: the writers always
// record every probe/ping into the global [SmartProfilePreference.protocolMemories], so the global
// entry IS the latest measurement regardless of which network it was taken on. Reading scoped-first
// here used to let a stale per-network snapshot shadow a fresher global one (latency icon stuck on
// an old color after the fingerprint changed). Network-scoped memories remain in use where they
// belong: auto-connect ranking and last-known-good selection.
internal fun Settings.rememberedSmartStartLatencyByProfileId(
    now: Long = System.currentTimeMillis(),
): Map<Long, Map<String, Long>> =
    smartProfilePreferences
        .mapNotNull { preference ->
            preference
                .rememberedSmartStartLatencyByOptionId(now = now)
                .takeIf(Map<String, Long>::isNotEmpty)
                ?.let { preference.profileId to it }
        }.toMap()

internal fun Settings.rememberedSmartProfileDownOptionIdsByProfileId(
    now: Long = System.currentTimeMillis(),
): Map<Long, Set<String>> =
    smartProfilePreferences
        .mapNotNull { preference ->
            preference
                .rememberedSmartProfileDownOptionIds(now = now)
                .takeIf(Set<String>::isNotEmpty)
                ?.let { downOptionIds -> preference.profileId to downOptionIds }
        }.toMap()

internal fun Settings.rememberedSmartProfileLatencyUnavailableByProfileId(
    now: Long = System.currentTimeMillis(),
): Map<Long, Set<String>> =
    smartProfilePreferences
        .mapNotNull { preference ->
            preference
                .rememberedSmartProfileLatencyUnavailableOptionIds(now = now)
                .takeIf(Set<String>::isNotEmpty)
                ?.let { unavailableOptionIds -> preference.profileId to unavailableOptionIds }
        }.toMap()

internal fun SmartProfilePreference.rememberedSmartProfileDownOptionIds(
    now: Long = System.currentTimeMillis(),
): Set<String> =
    protocolMemories
        .filter { memory -> memory.freshRememberedDown(now) }
        .map(SmartProfileProtocolMemory::optionId)
        .toSet()

internal fun SmartProfilePreference.rememberedSmartProfileLatencyUnavailableOptionIds(
    now: Long = System.currentTimeMillis(),
): Set<String> =
    protocolMemories
        .filter { memory -> memory.freshRememberedLatencyUnavailable(now) }
        .map(SmartProfileProtocolMemory::optionId)
        .toSet()

internal fun SmartProfilePreference.rememberedSmartStartLatencyByOptionId(
    now: Long = System.currentTimeMillis(),
): Map<String, Long> =
    buildMap {
        protocolMemories
            .sortedBy(SmartProfileProtocolMemory::optionId)
            .forEach { memory ->
                memory.freshRememberedLatency(now)?.let { latencyMs -> put(memory.optionId, latencyMs) }
            }
    }

internal fun Settings.rememberedSmartProfileServerPingByProfileId(
    now: Long = System.currentTimeMillis(),
): Map<Long, Map<String, Long>> =
    smartProfilePreferences
        .mapNotNull { preference ->
            preference
                .rememberedSmartProfileServerPingByOptionId(now = now)
                .takeIf(Map<String, Long>::isNotEmpty)
                ?.let { preference.profileId to it }
        }.toMap()

internal fun Settings.rememberedSmartProfileConnectDurationByProfileId(
    now: Long = System.currentTimeMillis(),
): Map<Long, Map<String, Long>> =
    smartProfilePreferences
        .mapNotNull { preference ->
            preference
                .rememberedSmartProfileConnectDurationByOptionId(now)
                .takeIf(Map<String, Long>::isNotEmpty)
                ?.let { preference.profileId to it }
        }.toMap()

private fun SmartProfilePreference.rememberedSmartProfileConnectDurationByOptionId(
    now: Long,
): Map<String, Long> =
    buildMap {
        protocolMemories.forEach { memory ->
            val duration = memory.lastConnectDurationMs?.takeIf { it > 0L }
            val measuredAt = memory.lastSuccessAt?.takeIf { it > 0L }
            if (duration != null && measuredAt != null && now - measuredAt <= SMART_START_MEMORY_RETENTION_MS) {
                put(memory.optionId, duration)
            }
        }
    }

internal fun SmartProfilePreference.rememberedSmartProfileServerPingByOptionId(
    now: Long = System.currentTimeMillis(),
): Map<String, Long> =
    buildMap {
        protocolMemories
            .sortedBy(SmartProfileProtocolMemory::optionId)
            .forEach { memory ->
                memory.freshRememberedServerPing(now)?.let { pingMs -> put(memory.optionId, pingMs) }
            }
    }

private fun SmartProfileProtocolMemory?.freshRememberedLatency(
    now: Long,
    retentionMs: Long = SMART_START_REMEMBERED_LATENCY_RETENTION_MS,
): Long? {
    val memory = this ?: return null
    val latencyMs = memory.lastLatencyMs?.takeIf { it > 0L } ?: return null
    val successAt = memory.lastSuccessAt?.takeIf { it > 0L } ?: return null
    return if (now - successAt <= retentionMs) latencyMs else null
}

private fun SmartProfileProtocolMemory?.freshRememberedDown(
    now: Long,
    retentionMs: Long = SMART_START_REMEMBERED_LATENCY_RETENTION_MS,
): Boolean {
    val memory = this ?: return false
    val failureAt = memory.lastFailureAt?.takeIf { it > 0L } ?: return false
    val successAt = memory.lastSuccessAt?.takeIf { it > 0L }
    val failureIsLatest = successAt == null || failureAt >= successAt
    val cooldownActive = memory.cooldownUntilAt?.let { cooldownUntil -> cooldownUntil > now } == true
    return failureIsLatest && (cooldownActive || now - failureAt <= retentionMs)
}

private fun SmartProfileProtocolMemory?.freshRememberedLatencyUnavailable(
    now: Long,
    retentionMs: Long = SMART_START_REMEMBERED_LATENCY_RETENTION_MS,
): Boolean {
    val memory = this
    val successAt = memory?.lastSuccessAt?.takeIf { it > 0L }
    val failureAt = memory?.lastFailureAt?.takeIf { it > 0L }
    return memory != null &&
        memory.lastLatencyMs?.takeIf { it > 0L } == null &&
        memory.lastReasonCode == AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED &&
        successAt != null &&
        (failureAt == null || successAt >= failureAt) &&
        now - successAt <= retentionMs
}

private fun SmartProfileProtocolMemory?.freshRememberedServerPing(
    now: Long,
    retentionMs: Long = SMART_START_REMEMBERED_LATENCY_RETENTION_MS,
): Long? {
    val memory = this ?: return null
    val serverPingMs = memory.lastServerPingMs?.takeIf { it > 0L } ?: return null
    val serverPingAt = memory.lastServerPingAt?.takeIf { it > 0L } ?: return null
    return if (now - serverPingAt <= retentionMs) serverPingMs else null
}

internal data class ProfileTrafficKey(
    val profileId: Long,
    val protocolOptionId: String?,
)

internal fun ProfileTrafficTotal.trafficKey(): ProfileTrafficKey =
    ProfileTrafficKey(
        profileId = profileId,
        protocolOptionId = protocolOptionId.normalizedProfileTrafficProtocolOptionId(),
    )

internal fun String?.normalizedProfileTrafficProtocolOptionId(): String? =
    this?.trim()?.takeIf(String::isNotBlank)

internal fun normalizeSmartProfilePreferences(
    preferences: List<SmartProfilePreference>,
): List<SmartProfilePreference> =
    preferences
        .mapNotNull { preference ->
            val normalizedLastKnownGoodOptionId = preference.lastKnownGoodOptionId?.trim()?.takeIf(String::isNotBlank)
            val normalizedRecommendedProtocolIds =
                preference.recommendedProtocolIds
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(SMART_START_RECOMMENDED_LIMIT)
            val normalizedProtocolMemories =
                preference.protocolMemories
                    .mapNotNull(SmartProfileProtocolMemory::normalized)
                    .distinctBy(SmartProfileProtocolMemory::optionId)
                    .sortedBy(SmartProfileProtocolMemory::optionId)
            val normalizedNetworkMemories =
                preference.networkMemories
                    .mapNotNull(SmartProfileNetworkMemory::normalized)
                    .distinctBy(SmartProfileNetworkMemory::networkFingerprint)
                    .sortedBy(SmartProfileNetworkMemory::networkFingerprint)
            val normalizedPreference =
                preference.copy(
                    lastKnownGoodOptionId = normalizedLastKnownGoodOptionId,
                    lastKnownGoodLatencyMs = preference.lastKnownGoodLatencyMs?.takeIf { it > 0L },
                    lastKnownGoodAt = preference.lastKnownGoodAt?.takeIf { it > 0L },
                    lastFullSmartRefreshAt = preference.lastFullSmartRefreshAt?.takeIf { it > 0L },
                    recommendedProtocolIds = normalizedRecommendedProtocolIds,
                    enabledProtocolSetHash = preference.enabledProtocolSetHash?.trim()?.takeIf(String::isNotBlank),
                    networkFingerprintSchema = preference.networkFingerprintSchema.coerceAtLeast(1),
                    protocolMemories = normalizedProtocolMemories,
                    networkMemories = normalizedNetworkMemories,
                )
            normalizedPreference.takeIf {
                normalizedLastKnownGoodOptionId != null ||
                    normalizedPreference.lastFullSmartRefreshAt != null ||
                    normalizedPreference.recommendedProtocolIds.isNotEmpty() ||
                    normalizedProtocolMemories.isNotEmpty() ||
                    normalizedNetworkMemories.isNotEmpty()
            }
        }.distinctBy(SmartProfilePreference::profileId)
        .sortedBy(SmartProfilePreference::profileId)
