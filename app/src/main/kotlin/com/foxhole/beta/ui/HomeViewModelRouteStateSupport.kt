package com.foxhole.beta.ui

import com.foxhole.beta.core.settings.rememberedSmartProfileMetricsUpdatedAtByOptionId
import com.foxhole.beta.core.settings.rememberedSmartProfileMetricsUpdatedAtByProfileId
import com.foxhole.beta.core.settings.rememberedSmartProfileServerPingByOptionId
import com.foxhole.beta.core.settings.rememberedSmartProfileServerPingByProfileId
import com.foxhole.beta.core.settings.rememberedSmartStartLatencyByOptionId
import com.foxhole.beta.core.settings.rememberedSmartStartLatencyByProfileId
import com.foxhole.beta.core.settings.smartProfilePreference

internal fun buildHomeRouteUiState(
    state: HomeUiState,
    autoConnect: AutoConnectUiState,
    profileOptionLatencies: Map<ProfileOptionLatencyKey, Long>,
    profileOptionLatencyUnavailable: Set<ProfileOptionLatencyKey>,
    protocolMetrics: ProtocolMetricsUiState,
    currentNetworkFingerprintKey: String?,
): HomeRouteUiState {
    val activeProfileLatencies =
        state.activeProfile
            ?.let { activeProfile ->
                profileOptionLatencies
                    .filterKeys { key -> key.profileId == activeProfile.id }
                    .mapKeys { (key, _) -> key.optionId }
            }.orEmpty()
    val activeProfileLatencyUnavailable =
        state.activeProfile
            ?.let { activeProfile ->
                profileOptionLatencyUnavailable
                    .filter { key -> key.profileId == activeProfile.id }
                    .map(ProfileOptionLatencyKey::optionId)
                    .toSet()
            }.orEmpty()
    val activeProfileServerPings =
        state.activeProfile
            ?.let { activeProfile ->
                val rememberedServerPings =
                    state.settings
                        .smartProfilePreference(activeProfile.id)
                        ?.rememberedSmartProfileServerPingByOptionId(currentNetworkFingerprintKey)
                        .orEmpty()
                val liveServerPings =
                    protocolMetrics.serverPings
                        .filterKeys { key -> key.profileId == activeProfile.id }
                        .mapNotNull { (key, value) -> value.pingMs?.let { key.optionId to it } }
                        .toMap()
                rememberedServerPings + liveServerPings
            }.orEmpty()
    val activeProfileServerPingUnavailable =
        state.activeProfile
            ?.let { activeProfile ->
                protocolMetrics.serverPings
                    .filter { (key, value) -> key.profileId == activeProfile.id && value.unavailable }
                    .map { (key, _) -> key.optionId }
                    .filterNot(activeProfileServerPings::containsKey)
                    .toSet()
            }.orEmpty()
    val activeProfileMetricsUpdatedAt =
        state.activeProfile
            ?.let { activeProfile ->
                val rememberedUpdatedAt =
                    state.settings
                        .smartProfilePreference(activeProfile.id)
                        ?.rememberedSmartProfileMetricsUpdatedAtByOptionId(currentNetworkFingerprintKey)
                        .orEmpty()
                val liveUpdatedAt =
                    protocolMetrics.updatedAt
                        .filterKeys { key -> key.profileId == activeProfile.id }
                        .mapKeys { (key, _) -> key.optionId }
                (rememberedUpdatedAt.keys + liveUpdatedAt.keys)
                    .associateWith { optionId ->
                        listOfNotNull(rememberedUpdatedAt[optionId], liveUpdatedAt[optionId]).maxOrNull() ?: 0L
                    }.filterValues { updatedAt -> updatedAt > 0L }
            }.orEmpty()
    val selectedLatencyOptionId = resolveDashboardLatencyOptionId(state.activeProfile)
    val selectedProtocolLatencyMs = selectedLatencyOptionId?.let(activeProfileLatencies::get)
    val selectedProtocolLatencyUnavailable =
        selectedLatencyOptionId != null &&
            selectedProtocolLatencyMs == null &&
            selectedLatencyOptionId in activeProfileLatencyUnavailable
    val smartStartRememberedLatenciesByOptionId =
        state.activeProfile
            ?.let { activeProfile ->
                state.settings
                    .smartProfilePreference(activeProfile.id)
                    ?.rememberedSmartStartLatencyByOptionId(currentNetworkFingerprintKey)
            }.orEmpty()
    val activeRecommendedProtocolOptionIds =
        state.activeProfile
            ?.let { activeProfile ->
                val baseline =
                    state.settings
                        .smartProfilePreference(activeProfile.id)
                        ?.recommendedProtocolIds
                        .orEmpty()
                val transient =
                    protocolMetrics.recommendation
                        ?.takeIf { recommendation -> recommendation.profileId == activeProfile.id }
                        ?.optionId
                (baseline + listOfNotNull(transient)).toSet()
            }.orEmpty()
    return state.toHomeRouteUiState(
        autoConnect = autoConnect,
        selectedProtocolLatencyMs = selectedProtocolLatencyMs,
        protocolLatenciesByOptionId = activeProfileLatencies,
        selectedProtocolLatencyUnavailable = selectedProtocolLatencyUnavailable,
        protocolLatencyUnavailableOptionIds = activeProfileLatencyUnavailable,
        protocolServerPingsByOptionId = activeProfileServerPings,
        protocolServerPingUnavailableOptionIds = activeProfileServerPingUnavailable,
        protocolMetricsUpdatedAtByOptionId = activeProfileMetricsUpdatedAt,
        protocolMetricsRefreshing = state.activeProfile?.id in protocolMetrics.refreshingProfileIds,
        recommendedProtocolOptionId =
            protocolMetrics.recommendation
                ?.takeIf { recommendation -> recommendation.profileId == state.activeProfile?.id }
                ?.optionId
                ?: activeRecommendedProtocolOptionIds.firstOrNull(),
        recommendedProtocolOptionIds = activeRecommendedProtocolOptionIds,
        smartStartRememberedLatenciesByOptionId = smartStartRememberedLatenciesByOptionId,
    )
}

internal fun buildProfilesRouteUiState(
    state: HomeUiState,
    protocolMetrics: ProtocolMetricsUiState,
    networkFingerprintKey: String?,
): ProfilesRouteUiState {
    val rememberedServerPingsByProfileId =
        state.settings.rememberedSmartProfileServerPingByProfileId(
            networkFingerprint = networkFingerprintKey,
        )
    val rememberedMetricsUpdatedAtByProfileId =
        state.settings.rememberedSmartProfileMetricsUpdatedAtByProfileId(
            networkFingerprint = networkFingerprintKey,
        )
    val liveServerPingsByProfileId =
        protocolMetrics.serverPings
            .mapNotNull { (key, value) -> value.pingMs?.let { key.profileId to (key.optionId to it) } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.toMap() }
    val mergedServerPingsByProfileId =
        (rememberedServerPingsByProfileId.keys + liveServerPingsByProfileId.keys)
            .associateWith { profileId ->
                rememberedServerPingsByProfileId[profileId].orEmpty() +
                    liveServerPingsByProfileId[profileId].orEmpty()
            }
    val liveMetricsUpdatedAtByProfileId =
        protocolMetrics.updatedAt
            .map { (key, value) -> key.profileId to (key.optionId to value) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.toMap() }
    val mergedMetricsUpdatedAtByProfileId =
        (rememberedMetricsUpdatedAtByProfileId.keys + liveMetricsUpdatedAtByProfileId.keys)
            .associateWith { profileId ->
                val rememberedUpdatedAt = rememberedMetricsUpdatedAtByProfileId[profileId].orEmpty()
                val liveUpdatedAt = liveMetricsUpdatedAtByProfileId[profileId].orEmpty()
                (rememberedUpdatedAt.keys + liveUpdatedAt.keys)
                    .associateWith { optionId ->
                        listOfNotNull(rememberedUpdatedAt[optionId], liveUpdatedAt[optionId]).maxOrNull() ?: 0L
                    }.filterValues { updatedAt -> updatedAt > 0L }
            }
    return state.toProfilesRouteUiState(
        smartStartRememberedLatenciesByProfileId =
            state.settings.rememberedSmartStartLatencyByProfileId(
                networkFingerprint = networkFingerprintKey,
            ),
        smartProfileServerPingsByProfileId = mergedServerPingsByProfileId,
        smartProfileServerPingUnavailableByProfileId =
            protocolMetrics.serverPings
                .filter { (_, value) -> value.unavailable }
                .keys
                .groupBy(ProfileOptionLatencyKey::profileId, ProfileOptionLatencyKey::optionId)
                .mapValues { (profileId, values) ->
                    values.filterNot(mergedServerPingsByProfileId[profileId].orEmpty()::containsKey).toSet()
                },
        smartProfileMetricsUpdatedAtByProfileId = mergedMetricsUpdatedAtByProfileId,
        smartProfileMetricsRefreshingProfileIds = protocolMetrics.refreshingProfileIds,
        recommendedProtocolOptionByProfileId =
            state.settings.smartProfilePreferences
                .mapNotNull { preference ->
                    preference.recommendedProtocolIds.firstOrNull()?.let { optionId ->
                        preference.profileId to optionId
                    }
                }.toMap() +
                protocolMetrics.recommendation
                    ?.let { recommendation -> mapOf(recommendation.profileId to recommendation.optionId) }
                    .orEmpty(),
        recommendedProtocolOptionsByProfileId =
            state.settings.smartProfilePreferences
                .associate { preference ->
                    preference.profileId to preference.recommendedProtocolIds.toSet()
                } +
                protocolMetrics.recommendation
                    ?.let { recommendation -> mapOf(recommendation.profileId to setOf(recommendation.optionId)) }
                    .orEmpty(),
    )
}
