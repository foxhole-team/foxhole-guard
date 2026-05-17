package com.foxhole.beta.ui

import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.settings.preferredLastKnownGoodOptionId
import com.foxhole.beta.core.settings.rememberedSmartProfileDownOptionIds
import com.foxhole.beta.core.settings.rememberedSmartProfileDownOptionIdsByProfileId
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
    val activeProfileDownOptionIds =
        state.activeProfile
            ?.let { activeProfile ->
                val rememberedDownOptionIds =
                    state.settings
                        .smartProfilePreference(activeProfile.id)
                        ?.rememberedSmartProfileDownOptionIds(currentNetworkFingerprintKey)
                        .orEmpty()
                val liveDownOptionIds =
                    protocolMetrics.downOptionIds
                        .filter { key -> key.profileId == activeProfile.id }
                        .map(ProfileOptionLatencyKey::optionId)
                        .toSet()
                rememberedDownOptionIds + liveDownOptionIds
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
                fullSmartRefreshUpdatedAtByOptionId(
                    profile = activeProfile,
                    settings = state.settings,
                )
            }.orEmpty()
    val selectedLatencyOptionId = resolveDashboardLatencyOptionId(state.activeProfile, state.connection)
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
                    ?.filterKeys { optionId -> optionId !in activeProfileLatencyUnavailable }
            }.orEmpty()
    val activeKnownLatenciesByOptionId = smartStartRememberedLatenciesByOptionId + activeProfileLatencies
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
    val activeFavoriteProtocolOptionId =
        state.activeProfile
            ?.let { activeProfile ->
                fastestProtocolOptionId(activeKnownLatenciesByOptionId)
                    ?: state.settings
                        .smartProfilePreference(activeProfile.id)
                        ?.preferredLastKnownGoodOptionId(currentNetworkFingerprintKey)
            }
    val autoConnectRefreshingActiveProfile = autoConnect.running && state.activeProfile != null
    return state.toHomeRouteUiState(
        autoConnect = autoConnect,
        selectedProtocolLatencyMs = selectedProtocolLatencyMs,
        protocolLatenciesByOptionId = activeProfileLatencies,
        protocolDownOptionIds = activeProfileDownOptionIds,
        selectedProtocolLatencyUnavailable = selectedProtocolLatencyUnavailable,
        protocolLatencyUnavailableOptionIds = activeProfileLatencyUnavailable,
        protocolServerPingsByOptionId = activeProfileServerPings,
        protocolServerPingUnavailableOptionIds = activeProfileServerPingUnavailable,
        protocolMetricsUpdatedAtByOptionId = activeProfileMetricsUpdatedAt,
        protocolMetricsRefreshing =
            state.activeProfile?.id in protocolMetrics.refreshingProfileIds ||
                autoConnectRefreshingActiveProfile,
        protocolMetricsRefreshingOptionId =
            state.activeProfile?.id?.let(protocolMetrics.refreshingOptionIdByProfileId::get)
                ?: autoConnect.currentOptionId.takeIf { autoConnectRefreshingActiveProfile },
        recommendedProtocolOptionId =
            protocolMetrics.recommendation
                ?.takeIf { recommendation -> recommendation.profileId == state.activeProfile?.id }
                ?.optionId
                ?: activeRecommendedProtocolOptionIds.firstOrNull(),
        recommendedProtocolOptionIds = activeRecommendedProtocolOptionIds,
        favoriteProtocolOptionId = activeFavoriteProtocolOptionId,
        smartStartRememberedLatenciesByOptionId = smartStartRememberedLatenciesByOptionId,
    )
}

internal fun buildProfilesRouteUiState(
    state: HomeUiState,
    autoConnect: AutoConnectUiState,
    protocolMetrics: ProtocolMetricsUiState,
    profileOptionLatencies: Map<ProfileOptionLatencyKey, Long> = emptyMap(),
    profileOptionLatencyUnavailable: Set<ProfileOptionLatencyKey> = emptySet(),
    networkFingerprintKey: String?,
): ProfilesRouteUiState {
    val rememberedServerPingsByProfileId =
        state.settings.rememberedSmartProfileServerPingByProfileId(
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
    val serverPingUnavailableByProfileId =
        smartProfileServerPingUnavailableByProfileId(
            serverPings = protocolMetrics.serverPings,
            mergedServerPingsByProfileId = mergedServerPingsByProfileId,
        )
    val fullRefreshUpdatedAtByProfileId =
        state.profiles
            .mapNotNull { profile ->
                fullSmartRefreshUpdatedAtByOptionId(
                    profile = profile,
                    settings = state.settings,
                ).takeIf(Map<String, Long>::isNotEmpty)
                    ?.let { updatedAtByOptionId -> profile.id to updatedAtByOptionId }
            }.toMap()
    val rememberedDownOptionIdsByProfileId =
        state.settings.rememberedSmartProfileDownOptionIdsByProfileId(
            networkFingerprint = networkFingerprintKey,
        )
    val downOptionIdsByProfileId =
        smartProfileDownOptionIdsByProfileId(
            rememberedDownOptionIdsByProfileId = rememberedDownOptionIdsByProfileId,
            liveDownOptionIds = protocolMetrics.downOptionIds,
        )
    val dashboardRefreshingProfileId = state.activeProfile?.id?.takeIf { state.dashboardConnectionMetricsLoading }
    val dashboardRefreshingOptionIdByProfileId =
        dashboardRefreshingProfileId
            ?.let { profileId ->
                resolveDashboardLatencyOptionId(state.activeProfile, state.connection)?.let { optionId ->
                    mapOf(profileId to optionId)
                }
            }.orEmpty()
    val autoConnectRefreshingProfileId = state.activeProfile?.id?.takeIf { autoConnect.running }
    val refreshingProfileIds =
        protocolMetrics.refreshingProfileIds +
            listOfNotNull(dashboardRefreshingProfileId, autoConnectRefreshingProfileId)
    val autoConnectRefreshingOptionIdByProfileId =
        autoConnect.currentOptionId
            ?.takeIf { autoConnect.running }
            ?.let { refreshingOptionId ->
                refreshingProfileIds.associateWith { refreshingOptionId }
            }.orEmpty()
    val refreshingOptionIdByProfileId =
        (
            autoConnectRefreshingOptionIdByProfileId +
                dashboardRefreshingOptionIdByProfileId +
                protocolMetrics.refreshingOptionIdByProfileId.filterKeys(refreshingProfileIds::contains)
        )
            .filterKeys(refreshingProfileIds::contains)
    val rememberedLatenciesByProfileId =
        state.settings.rememberedSmartStartLatencyByProfileId(
            networkFingerprint = networkFingerprintKey,
        )
    val liveLatenciesByProfileId =
        liveSmartProfileLatenciesByProfileId(profileOptionLatencies)
    val latencyUnavailableByProfileId =
        liveSmartProfileLatencyUnavailableByProfileId(
            profileOptionLatencyUnavailable = profileOptionLatencyUnavailable,
            liveLatenciesByProfileId = liveLatenciesByProfileId,
        )
    val mergedLatenciesByProfileId =
        mergedSmartProfileLatenciesByProfileId(
            rememberedLatenciesByProfileId = rememberedLatenciesByProfileId,
            liveLatenciesByProfileId = liveLatenciesByProfileId,
            latencyUnavailableByProfileId = latencyUnavailableByProfileId,
        )
    val recommendedProtocolOptionByProfileId =
        smartProfileRecommendedOptionByProfileId(state.settings, protocolMetrics.recommendation)
    val recommendedProtocolOptionsByProfileId =
        smartProfileRecommendedOptionsByProfileId(state.settings, protocolMetrics.recommendation)
    val favoriteProtocolOptionByProfileId =
        smartProfileFavoriteOptionByProfileId(
            settings = state.settings,
            latenciesByProfileId = mergedLatenciesByProfileId,
            networkFingerprintKey = networkFingerprintKey,
        )
    return state.toProfilesRouteUiState(
        smartStartRememberedLatenciesByProfileId = mergedLatenciesByProfileId,
        smartProfileDownOptionIdsByProfileId = downOptionIdsByProfileId,
        smartProfileLatencyUnavailableByProfileId = latencyUnavailableByProfileId,
        smartProfileServerPingsByProfileId = mergedServerPingsByProfileId,
        smartProfileServerPingUnavailableByProfileId = serverPingUnavailableByProfileId,
        smartProfileMetricsUpdatedAtByProfileId = fullRefreshUpdatedAtByProfileId,
        smartProfileMetricsRefreshingProfileIds = refreshingProfileIds,
        smartProfileMetricsRefreshingOptionIdByProfileId = refreshingOptionIdByProfileId,
        recommendedProtocolOptionByProfileId = recommendedProtocolOptionByProfileId,
        recommendedProtocolOptionsByProfileId = recommendedProtocolOptionsByProfileId,
        favoriteProtocolOptionByProfileId = favoriteProtocolOptionByProfileId,
    )
}

private fun smartProfileRecommendedOptionByProfileId(
    settings: Settings,
    recommendation: ProtocolRecommendationState?,
): Map<Long, String> =
    settings.smartProfilePreferences
        .mapNotNull { preference ->
            preference.recommendedProtocolIds.firstOrNull()?.let { optionId ->
                preference.profileId to optionId
            }
        }.toMap() +
        recommendation
            ?.let { state -> mapOf(state.profileId to state.optionId) }
            .orEmpty()

private fun smartProfileRecommendedOptionsByProfileId(
    settings: Settings,
    recommendation: ProtocolRecommendationState?,
): Map<Long, Set<String>> =
    settings.smartProfilePreferences
        .associate { preference ->
            preference.profileId to preference.recommendedProtocolIds.toSet()
        } +
        recommendation
            ?.let { state -> mapOf(state.profileId to setOf(state.optionId)) }
            .orEmpty()

private fun smartProfileFavoriteOptionByProfileId(
    settings: Settings,
    latenciesByProfileId: Map<Long, Map<String, Long>>,
    networkFingerprintKey: String?,
): Map<Long, String> =
    settings.smartProfilePreferences
        .mapNotNull { preference ->
            (
                fastestProtocolOptionId(latenciesByProfileId[preference.profileId].orEmpty())
                    ?: preference.preferredLastKnownGoodOptionId(networkFingerprintKey)
            )?.let { optionId ->
                preference.profileId to optionId
            }
        }.toMap()

private fun smartProfileServerPingUnavailableByProfileId(
    serverPings: Map<ProfileOptionLatencyKey, ProfileOptionServerPingState>,
    mergedServerPingsByProfileId: Map<Long, Map<String, Long>>,
): Map<Long, Set<String>> =
    serverPings
        .filter { (_, value) -> value.unavailable }
        .keys
        .groupBy(ProfileOptionLatencyKey::profileId, ProfileOptionLatencyKey::optionId)
        .mapValues { (profileId, values) ->
            values.filterNot(mergedServerPingsByProfileId[profileId].orEmpty()::containsKey).toSet()
        }

private fun smartProfileDownOptionIdsByProfileId(
    rememberedDownOptionIdsByProfileId: Map<Long, Set<String>>,
    liveDownOptionIds: Set<ProfileOptionLatencyKey>,
): Map<Long, Set<String>> {
    val liveDownOptionIdsByProfileId =
        liveDownOptionIds
            .groupBy(ProfileOptionLatencyKey::profileId, ProfileOptionLatencyKey::optionId)
            .mapValues { (_, values) -> values.toSet() }
    return (rememberedDownOptionIdsByProfileId.keys + liveDownOptionIdsByProfileId.keys)
        .associateWith { profileId ->
            rememberedDownOptionIdsByProfileId[profileId].orEmpty() +
                liveDownOptionIdsByProfileId[profileId].orEmpty()
        }
}

private fun liveSmartProfileLatenciesByProfileId(
    profileOptionLatencies: Map<ProfileOptionLatencyKey, Long>,
): Map<Long, Map<String, Long>> =
    profileOptionLatencies
        .map { (key, latencyMs) -> key.profileId to (key.optionId to latencyMs) }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.toMap() }

private fun liveSmartProfileLatencyUnavailableByProfileId(
    profileOptionLatencyUnavailable: Set<ProfileOptionLatencyKey>,
    liveLatenciesByProfileId: Map<Long, Map<String, Long>>,
): Map<Long, Set<String>> =
    profileOptionLatencyUnavailable
        .groupBy(ProfileOptionLatencyKey::profileId, ProfileOptionLatencyKey::optionId)
        .mapValues { (profileId, values) ->
            values
                .filterNot(liveLatenciesByProfileId[profileId].orEmpty()::containsKey)
                .toSet()
        }

private fun mergedSmartProfileLatenciesByProfileId(
    rememberedLatenciesByProfileId: Map<Long, Map<String, Long>>,
    liveLatenciesByProfileId: Map<Long, Map<String, Long>>,
    latencyUnavailableByProfileId: Map<Long, Set<String>>,
): Map<Long, Map<String, Long>> =
    (rememberedLatenciesByProfileId.keys + liveLatenciesByProfileId.keys + latencyUnavailableByProfileId.keys)
        .associateWith { profileId ->
            (rememberedLatenciesByProfileId[profileId].orEmpty() + liveLatenciesByProfileId[profileId].orEmpty())
                .filterKeys { optionId -> optionId !in latencyUnavailableByProfileId[profileId].orEmpty() }
        }

private fun fastestProtocolOptionId(latenciesByOptionId: Map<String, Long>): String? =
    latenciesByOptionId
        .asSequence()
        .filter { (_, latencyMs) -> latencyMs > 0L }
        .minWithOrNull(
            compareBy<Map.Entry<String, Long>> { (_, latencyMs) -> latencyMs }
                .thenBy { (optionId, _) -> optionId },
        )?.key

private fun fullSmartRefreshUpdatedAtByOptionId(
    profile: Profile,
    settings: Settings,
): Map<String, Long> {
    val refreshedAt =
        settings
            .smartProfilePreference(profile.id)
            ?.lastFullSmartRefreshAt
            ?.takeIf { updatedAt -> updatedAt > 0L }
            ?: return emptyMap()
    return profile.protocolOptions
        .map(ProfileProtocolOption::id)
        .filter(String::isNotBlank)
        .distinct()
        .associateWith { refreshedAt }
}
