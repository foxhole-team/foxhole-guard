package com.foxhole.guard.core.settings

import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.NETWORK_FINGERPRINT_SCHEMA_CURRENT
import com.foxhole.core.model.ProfileTrafficTotal
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.SmartProfileNetworkMemory
import com.foxhole.core.model.SmartProfilePreference
import com.foxhole.core.model.SmartProfileProtocolMemory
import com.foxhole.core.model.TransportProtocol

suspend fun SettingsRepository.removeSmartProfilePreference(profileId: Long) = update { current ->
    current.copy(
        smartProfilePreferences = current.smartProfilePreferences.filterNot { it.profileId == profileId },
    )
}

suspend fun SettingsRepository.recordSmartProfileProbeResult(
    profileId: Long,
    optionId: String,
    latencyMs: Long?,
    success: Boolean,
    reasonCode: AutoConnectReasonCode? = null,
    markAsLastKnownGood: Boolean = false,
    networkFingerprint: String? = null,
    recordedAt: Long = System.currentTimeMillis(),
    connectDurationMs: Long? = null,
    validatedAt: Long? = null,
    trafficObservedAt: Long? = null,
    countTowardOutcomeHistory: Boolean = true,
    affectsFailureRankingMemory: Boolean = true,
) = update { current ->
    val normalizedOptionId = optionId.trim().takeIf(String::isNotBlank) ?: return@update current
    val normalizedLatencyMs = latencyMs?.coerceAtLeast(1L)
    val normalizedNetworkFingerprint = networkFingerprint?.trim()?.takeIf(String::isNotBlank)
    val existing = current.smartProfilePreference(profileId) ?: SmartProfilePreference(profileId = profileId)
    val globalMemoryUpdate =
        recordProbeResultIntoMemory(
            lastKnownGoodOptionId = existing.lastKnownGoodOptionId,
            lastKnownGoodLatencyMs = existing.lastKnownGoodLatencyMs,
            lastKnownGoodAt = existing.lastKnownGoodAt,
            protocolMemories = existing.protocolMemories,
            optionId = normalizedOptionId,
            latencyMs = normalizedLatencyMs,
            success = success,
            reasonCode = reasonCode,
            markAsLastKnownGood = markAsLastKnownGood,
            recordedAt = recordedAt,
            connectDurationMs = connectDurationMs,
            validatedAt = validatedAt,
            trafficAt = trafficObservedAt,
            countTowardOutcomeHistory = countTowardOutcomeHistory,
            affectsFailureRankingMemory = affectsFailureRankingMemory,
        )
    val updatedNetworkMemories =
        normalizedNetworkFingerprint?.let { fingerprint ->
            val currentNetworkMemories = existing.networkMemories.associateBy(SmartProfileNetworkMemory::networkFingerprint).toMutableMap()
            val previousNetworkMemory =
                currentNetworkMemories[fingerprint]
                    ?.takeIf { memory -> memory.networkFingerprintSchema == NETWORK_FINGERPRINT_SCHEMA_CURRENT }
                    ?: SmartProfileNetworkMemory(
                        networkFingerprint = fingerprint,
                        networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
                    )
            val scopedMemoryUpdate =
                recordProbeResultIntoMemory(
                    lastKnownGoodOptionId = previousNetworkMemory.lastKnownGoodOptionId,
                    lastKnownGoodLatencyMs = previousNetworkMemory.lastKnownGoodLatencyMs,
                    lastKnownGoodAt = previousNetworkMemory.lastKnownGoodAt,
                    protocolMemories = previousNetworkMemory.protocolMemories,
                    optionId = normalizedOptionId,
                    latencyMs = normalizedLatencyMs,
                    success = success,
                    reasonCode = reasonCode,
                    markAsLastKnownGood = markAsLastKnownGood,
                    recordedAt = recordedAt,
                    connectDurationMs = connectDurationMs,
                    validatedAt = validatedAt,
                    trafficAt = trafficObservedAt,
                    countTowardOutcomeHistory = countTowardOutcomeHistory,
                    affectsFailureRankingMemory = affectsFailureRankingMemory,
                )
            currentNetworkMemories[fingerprint] =
                previousNetworkMemory.copy(
                    lastKnownGoodOptionId = scopedMemoryUpdate.lastKnownGoodOptionId,
                    lastKnownGoodLatencyMs = scopedMemoryUpdate.lastKnownGoodLatencyMs,
                    lastKnownGoodAt = scopedMemoryUpdate.lastKnownGoodAt,
                    protocolMemories = scopedMemoryUpdate.protocolMemories,
                )
            currentNetworkMemories.values.sortedBy(SmartProfileNetworkMemory::networkFingerprint)
        } ?: existing.networkMemories
    current.withSmartProfilePreference(
        existing.copy(
            lastKnownGoodOptionId = globalMemoryUpdate.lastKnownGoodOptionId,
            lastKnownGoodLatencyMs = globalMemoryUpdate.lastKnownGoodLatencyMs,
            lastKnownGoodAt = globalMemoryUpdate.lastKnownGoodAt,
            protocolMemories = globalMemoryUpdate.protocolMemories,
            networkMemories = updatedNetworkMemories,
        ),
    )
}

suspend fun SettingsRepository.recordSmartProfileServerPing(
    profileId: Long,
    optionId: String,
    serverPingMs: Long,
    networkFingerprint: String? = null,
    recordedAt: Long = System.currentTimeMillis(),
) = update { current ->
    val normalizedOptionId = optionId.trim().takeIf(String::isNotBlank) ?: return@update current
    val normalizedServerPingMs = serverPingMs.coerceAtLeast(1L)
    val normalizedNetworkFingerprint = networkFingerprint?.trim()?.takeIf(String::isNotBlank)
    val existing = current.smartProfilePreference(profileId) ?: SmartProfilePreference(profileId = profileId)
    fun recordInto(memories: List<SmartProfileProtocolMemory>): List<SmartProfileProtocolMemory> {
        val currentMemories = memories.associateBy(SmartProfileProtocolMemory::optionId).toMutableMap()
        val previous = currentMemories[normalizedOptionId]
        currentMemories[normalizedOptionId] =
            (previous ?: SmartProfileProtocolMemory(optionId = normalizedOptionId)).copy(
                lastServerPingMs = normalizedServerPingMs,
                lastServerPingAt = recordedAt.takeIf { it > 0L },
            )
        return currentMemories.values.sortedBy(SmartProfileProtocolMemory::optionId)
    }
    val updatedNetworkMemories =
        normalizedNetworkFingerprint?.let { fingerprint ->
            val currentNetworkMemories = existing.networkMemories.associateBy(SmartProfileNetworkMemory::networkFingerprint).toMutableMap()
            val previousNetworkMemory =
                currentNetworkMemories[fingerprint]
                    ?.takeIf { memory -> memory.networkFingerprintSchema == NETWORK_FINGERPRINT_SCHEMA_CURRENT }
                    ?: SmartProfileNetworkMemory(
                        networkFingerprint = fingerprint,
                        networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
                    )
            currentNetworkMemories[fingerprint] =
                previousNetworkMemory.copy(
                    protocolMemories = recordInto(previousNetworkMemory.protocolMemories),
                )
            currentNetworkMemories.values.sortedBy(SmartProfileNetworkMemory::networkFingerprint)
        } ?: existing.networkMemories
    current.withSmartProfilePreference(
        existing.copy(
            protocolMemories = recordInto(existing.protocolMemories),
            networkMemories = updatedNetworkMemories,
        ),
    )
}

suspend fun SettingsRepository.recordSmartProfileBaseline(
    profileId: Long,
    recommendedProtocolIds: List<String>,
    enabledProtocolSetHash: String,
    refreshedAt: Long = System.currentTimeMillis(),
) = update { current ->
    val normalizedRecommendedIds =
        recommendedProtocolIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(SMART_START_RECOMMENDED_LIMIT)
    val normalizedHash = enabledProtocolSetHash.trim().takeIf(String::isNotBlank) ?: return@update current
    val existing = current.smartProfilePreference(profileId) ?: SmartProfilePreference(profileId = profileId)
    current.withSmartProfilePreference(
        existing.copy(
            lastFullSmartRefreshAt = refreshedAt.takeIf { it > 0L } ?: existing.lastFullSmartRefreshAt,
            recommendedProtocolIds = normalizedRecommendedIds,
            enabledProtocolSetHash = normalizedHash,
            networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
        ),
    )
}

suspend fun SettingsRepository.accumulateProfileTraffic(
    profileId: Long,
    profileName: String,
    protocolHint: ProtocolHint,
    protocolOptionId: String? = null,
    transport: TransportProtocol = TransportProtocol.UNKNOWN,
    rxBytes: Long,
    txBytes: Long,
    updatedAt: Long = System.currentTimeMillis(),
) = update { current ->
    if (!current.canAccumulateProfileTraffic(rxBytes = rxBytes, txBytes = txBytes)) {
        current
    } else {
        val normalizedProtocolOptionId = protocolOptionId.normalizedProfileTrafficProtocolOptionId()
        val trafficKey = ProfileTrafficKey(profileId, normalizedProtocolOptionId)
        val existing = current.profileTrafficTotals.associateBy(ProfileTrafficTotal::trafficKey).toMutableMap()
        val previous = existing[trafficKey]
        existing[trafficKey] =
            ProfileTrafficTotal(
                profileId = profileId,
                profileName = profileName,
                protocolHint = protocolHint,
                protocolOptionId = normalizedProtocolOptionId,
                transport = transport,
                rxTotalBytes = (previous?.rxTotalBytes ?: 0L) + rxBytes.coerceAtLeast(0L),
                txTotalBytes = (previous?.txTotalBytes ?: 0L) + txBytes.coerceAtLeast(0L),
                updatedAt = updatedAt,
            )
        current.copy(profileTrafficTotals = existing.values.sortedByDescending(ProfileTrafficTotal::updatedAt))
    }
}
