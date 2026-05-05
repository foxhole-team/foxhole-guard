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
    val activeFavoriteProtocolOptionId =
        state.activeProfile
            ?.let { activeProfile ->
                state.settings
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
    val liveDownOptionIdsByProfileId =
        protocolMetrics.downOptionIds
            .groupBy(ProfileOptionLatencyKey::profileId, ProfileOptionLatencyKey::optionId)
            .mapValues { (_, values) -> values.toSet() }
    val downOptionIdsByProfileId =
        (rememberedDownOptionIdsByProfileId.keys + liveDownOptionIdsByProfileId.keys)
            .associateWith { profileId ->
                rememberedDownOptionIdsByProfileId[profileId].orEmpty() +
                    liveDownOptionIdsByProfileId[profileId].orEmpty()
            }
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
    return state.toProfilesRouteUiState(
        smartStartRememberedLatenciesByProfileId =
            state.settings.rememberedSmartStartLatencyByProfileId(
                networkFingerprint = networkFingerprintKey,
            ),
        smartProfileDownOptionIdsByProfileId = downOptionIdsByProfileId,
        smartProfileServerPingsByProfileId = mergedServerPingsByProfileId,
        smartProfileServerPingUnavailableByProfileId =
            protocolMetrics.serverPings
                .filter { (_, value) -> value.unavailable }
                .keys
                .groupBy(ProfileOptionLatencyKey::profileId, ProfileOptionLatencyKey::optionId)
                .mapValues { (profileId, values) ->
                    values.filterNot(mergedServerPingsByProfileId[profileId].orEmpty()::containsKey).toSet()
                },
        smartProfileMetricsUpdatedAtByProfileId = fullRefreshUpdatedAtByProfileId,
        smartProfileMetricsRefreshingProfileIds = refreshingProfileIds,
        smartProfileMetricsRefreshingOptionIdByProfileId = refreshingOptionIdByProfileId,
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
        favoriteProtocolOptionByProfileId =
            state.settings.smartProfilePreferences
                .mapNotNull { preference ->
                    preference.preferredLastKnownGoodOptionId(networkFingerprintKey)?.let { optionId ->
                        preference.profileId to optionId
                    }
                }.toMap(),
    )
}

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
