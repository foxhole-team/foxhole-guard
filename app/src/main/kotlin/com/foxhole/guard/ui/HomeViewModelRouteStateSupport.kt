package com.foxhole.guard.ui
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.Settings
import com.foxhole.guard.core.settings.preferredLastKnownGoodOptionId
import com.foxhole.guard.core.settings.rememberedSmartProfileConnectDurationByProfileId
import com.foxhole.guard.core.settings.rememberedSmartProfileDownOptionIds
import com.foxhole.guard.core.settings.rememberedSmartProfileDownOptionIdsByProfileId
import com.foxhole.guard.core.settings.rememberedSmartProfileLatencyUnavailableByProfileId
import com.foxhole.guard.core.settings.rememberedSmartProfileLatencyUnavailableOptionIds
import com.foxhole.guard.core.settings.rememberedSmartProfileServerPingByOptionId
import com.foxhole.guard.core.settings.rememberedSmartProfileServerPingByProfileId
import com.foxhole.guard.core.settings.rememberedSmartStartLatencyByOptionId
import com.foxhole.guard.core.settings.rememberedSmartStartLatencyByProfileId
import com.foxhole.guard.core.settings.smartProfilePreference

internal fun buildHomeRouteUiState(
    state: HomeUiState,
    autoConnect: AutoConnectUiState,
    profileOptionLatencies: Map<ProfileOptionLatencyKey, Long>,
    profileOptionLatencyUnavailable: Set<ProfileOptionLatencyKey>,
    protocolMetrics: ProtocolMetricsUiState,
    currentNetworkFingerprintKey: String?,
): HomeRouteUiState {
    val latencyInputs =
        activeProfileLatencyInputs(
            state = state,
            profileOptionLatencies = profileOptionLatencies,
            profileOptionLatencyUnavailable = profileOptionLatencyUnavailable,
            protocolMetrics = protocolMetrics,
        )
    val pingInputs =
        activeProfilePingInputs(
            state = state,
            protocolMetrics = protocolMetrics,
        )
    val selection =
        activeProfileProtocolSelection(
            state = state,
            protocolMetrics = protocolMetrics,
            latencyInputs = latencyInputs,
            currentNetworkFingerprintKey = currentNetworkFingerprintKey,
        )
    val autoConnectRefreshingActiveProfile = autoConnect.running && state.activeProfile != null
    return state.toHomeRouteUiState(
        autoConnect = autoConnect,
        protocolMetricsInputs =
        HomeRouteProtocolMetricsInputs(
            selectedProtocolLatencyMs = selection.selectedLatencyMs,
            selectedProtocolLatencyUnavailable = selection.selectedLatencyUnavailable,
            protocolLatenciesByOptionId = latencyInputs.latencies,
            protocolDownOptionIds = latencyInputs.downOptionIds,
            protocolLatencyUnavailableOptionIds = latencyInputs.latencyUnavailable,
            protocolServerPingsByOptionId = pingInputs.serverPings,
            protocolServerPingUnavailableOptionIds = pingInputs.serverPingUnavailable,
            protocolTunnelPingsByOptionId = pingInputs.tunnelPings,
            protocolTunnelPingUnavailableOptionIds = pingInputs.tunnelPingUnavailable,
            protocolMetricsUpdatedAtByOptionId = pingInputs.metricsUpdatedAt,
            protocolMetricsRefreshing =
            state.activeProfile?.id in protocolMetrics.refreshingProfileIds ||
                autoConnectRefreshingActiveProfile,
            protocolMetricsRefreshingOptionId =
            state.activeProfile?.id?.let(protocolMetrics.refreshingOptionIdByProfileId::get)
                ?: autoConnect.currentOptionId.takeIf { autoConnectRefreshingActiveProfile },
            recommendedProtocolOptionId = selection.recommendedOptionIds.firstOrNull(),
            recommendedProtocolOptionIds = selection.recommendedOptionIds,
            favoriteProtocolOptionId = selection.favoriteOptionId,
            smartStartRememberedLatenciesByOptionId = selection.smartStartRememberedLatencies,
        ),
        includeLiveTraffic = false,
    )
}

private data class ActiveProfileLatencyInputs(
    val latencies: Map<String, Long>,
    val latencyUnavailable: Set<String>,
    val downOptionIds: Set<String>,
)

private fun activeProfileLatencyInputs(
    state: HomeUiState,
    profileOptionLatencies: Map<ProfileOptionLatencyKey, Long>,
    profileOptionLatencyUnavailable: Set<ProfileOptionLatencyKey>,
    protocolMetrics: ProtocolMetricsUiState,
): ActiveProfileLatencyInputs {
    val activeProfile =
        state.activeProfile
            ?: return ActiveProfileLatencyInputs(emptyMap(), emptySet(), emptySet())
    val latencies =
        profileOptionLatencies
            .filterKeys { key -> key.profileId == activeProfile.id }
            .mapKeys { (key, _) -> key.optionId }
    val liveUnavailable =
        profileOptionLatencyUnavailable
            .filter { key -> key.profileId == activeProfile.id }
            .map(ProfileOptionLatencyKey::optionId)
            .toSet()
    val rememberedUnavailable =
        state.settings
            .smartProfilePreference(activeProfile.id)
            ?.rememberedSmartProfileLatencyUnavailableOptionIds()
            .orEmpty()
    val rememberedDownOptionIds =
        state.settings
            .smartProfilePreference(activeProfile.id)
            ?.rememberedSmartProfileDownOptionIds()
            .orEmpty()
    val liveDownOptionIds =
        protocolMetrics.downOptionIds
            .filter { key -> key.profileId == activeProfile.id }
            .map(ProfileOptionLatencyKey::optionId)
            .toSet()
    return ActiveProfileLatencyInputs(
        latencies = latencies,
        latencyUnavailable = liveUnavailable + rememberedUnavailable,
        downOptionIds = rememberedDownOptionIds + liveDownOptionIds,
    )
}

private data class ActiveProfilePingInputs(
    val serverPings: Map<String, Long>,
    val serverPingUnavailable: Set<String>,
    val tunnelPings: Map<String, Long>,
    val tunnelPingUnavailable: Set<String>,
    val metricsUpdatedAt: Map<String, Long>,
)

private fun activeProfilePingInputs(
    state: HomeUiState,
    protocolMetrics: ProtocolMetricsUiState,
): ActiveProfilePingInputs {
    val activeProfile =
        state.activeProfile
            ?: return ActiveProfilePingInputs(emptyMap(), emptySet(), emptyMap(), emptySet(), emptyMap())
    val liveServerPingStates =
        protocolMetrics.serverPings
            .filterKeys { key -> key.profileId == activeProfile.id }
    val liveServerPingUnavailableOptionIds =
        liveServerPingStates
            .filter { (_, value) -> value.unavailable }
            .keys
            .map(ProfileOptionLatencyKey::optionId)
            .toSet()
    val liveServerPings =
        liveServerPingStates
            .mapNotNull { (key, value) -> value.pingMs?.let { key.optionId to it } }
            .toMap()
    val activeConnectedOptionId =
        resolveDashboardLatencyOptionId(activeProfile, state.connection)
            ?.takeIf {
                state.connection.profileId == activeProfile.id &&
                    state.connection.state in ACTIVE_CONNECTION_STATES
            }
    val rememberedServerPings =
        state.settings
            .smartProfilePreference(activeProfile.id)
            ?.rememberedSmartProfileServerPingByOptionId()
            ?.filterKeys { optionId ->
                optionId !in liveServerPingUnavailableOptionIds &&
                    (
                        optionId != activeConnectedOptionId ||
                            optionId in liveServerPings
                        )
            }
            .orEmpty()
    val serverPings = rememberedServerPings + liveServerPings
    val serverPingUnavailable =
        liveServerPingStates
            .filter { (_, value) -> value.unavailable }
            .map { (key, _) -> key.optionId }
            .filterNot(serverPings::containsKey)
            .toSet()
    val tunnelPings =
        protocolMetrics.tunnelPings
            .filterKeys { key -> key.profileId == activeProfile.id }
            .mapNotNull { (key, value) -> value.pingMs?.let { key.optionId to it } }
            .toMap()
    val tunnelPingUnavailable =
        protocolMetrics.tunnelPings
            .filter { (key, value) -> key.profileId == activeProfile.id && value.unavailable }
            .map { (key, _) -> key.optionId }
            .filterNot(tunnelPings::containsKey)
            .toSet()
    return ActiveProfilePingInputs(
        serverPings = serverPings,
        serverPingUnavailable = serverPingUnavailable,
        tunnelPings = tunnelPings,
        tunnelPingUnavailable = tunnelPingUnavailable,
        metricsUpdatedAt =
        fullSmartRefreshUpdatedAtByOptionId(
            profile = activeProfile,
            settings = state.settings,
        ),
    )
}

private data class ActiveProfileProtocolSelection(
    val selectedLatencyMs: Long?,
    val selectedLatencyUnavailable: Boolean,
    val smartStartRememberedLatencies: Map<String, Long>,
    val recommendedOptionIds: Set<String>,
    val favoriteOptionId: String?,
)

private fun activeProfileProtocolSelection(
    state: HomeUiState,
    protocolMetrics: ProtocolMetricsUiState,
    latencyInputs: ActiveProfileLatencyInputs,
    currentNetworkFingerprintKey: String?,
): ActiveProfileProtocolSelection {
    val selectedLatencyOptionId = resolveDashboardLatencyOptionId(state.activeProfile, state.connection)
    val selectedLatencyMs = selectedLatencyOptionId?.let(latencyInputs.latencies::get)
    val selectedLatencyUnavailable =
        selectedLatencyOptionId != null &&
            selectedLatencyMs == null &&
            selectedLatencyOptionId in latencyInputs.latencyUnavailable
    val activeProfile =
        state.activeProfile
            ?: return ActiveProfileProtocolSelection(
                selectedLatencyMs = selectedLatencyMs,
                selectedLatencyUnavailable = selectedLatencyUnavailable,
                smartStartRememberedLatencies = emptyMap(),
                recommendedOptionIds = emptySet(),
                favoriteOptionId = null,
            )
    val activeConnectedLatencyOptionId =
        selectedLatencyOptionId
            ?.takeIf {
                state.connection.profileId == activeProfile.id &&
                    state.connection.state in ACTIVE_CONNECTION_STATES
            }
    val smartStartRememberedLatencies =
        state.settings
            .smartProfilePreference(activeProfile.id)
            ?.rememberedSmartStartLatencyByOptionId()
            ?.filterKeys { optionId ->
                optionId !in latencyInputs.latencyUnavailable &&
                    (
                        optionId != activeConnectedLatencyOptionId ||
                            optionId in latencyInputs.latencies
                        )
            }
            .orEmpty()
    val knownLatenciesByOptionId = smartStartRememberedLatencies + latencyInputs.latencies
    val evidenceOptionIds = knownLatenciesByOptionId.keys
    val baselineRecommended =
        state.settings
            .smartProfilePreference(activeProfile.id)
            ?.recommendedProtocolIds
            ?.filter(evidenceOptionIds::contains)
            .orEmpty()
    val transientRecommended =
        protocolMetrics.recommendation
            ?.takeIf { recommendation -> recommendation.profileId == activeProfile.id }
            ?.takeIf { recommendation -> recommendation.optionId in evidenceOptionIds }
            ?.optionId
    val favoriteOptionId =
        fastestProtocolOptionId(knownLatenciesByOptionId)
            ?: state.settings
                .smartProfilePreference(activeProfile.id)
                ?.preferredLastKnownGoodOptionId(currentNetworkFingerprintKey)
    return ActiveProfileProtocolSelection(
        selectedLatencyMs = selectedLatencyMs,
        selectedLatencyUnavailable = selectedLatencyUnavailable,
        smartStartRememberedLatencies = smartStartRememberedLatencies,
        recommendedOptionIds = (baselineRecommended + listOfNotNull(transientRecommended)).toSet(),
        favoriteOptionId = favoriteOptionId,
    )
}

@Suppress("LongMethod")
internal fun buildProfilesRouteUiState(
    state: HomeUiState,
    autoConnect: AutoConnectUiState,
    protocolMetrics: ProtocolMetricsUiState,
    profileOptionLatencies: Map<ProfileOptionLatencyKey, Long> = emptyMap(),
    profileOptionLatencyUnavailable: Set<ProfileOptionLatencyKey> = emptySet(),
    networkFingerprintKey: String?,
): ProfilesRouteUiState {
    val rememberedServerPingsByProfileId =
        state.settings.rememberedSmartProfileServerPingByProfileId()
    val rememberedConnectDurationsByProfileId =
        state.settings.rememberedSmartProfileConnectDurationByProfileId()
    val liveServerPingsByProfileId =
        protocolMetrics.serverPings
            .mapNotNull { (key, value) -> value.pingMs?.let { key.profileId to (key.optionId to it) } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.toMap() }
    val liveUnavailableServerPingOptionIdsByProfileId =
        protocolMetrics.serverPings
            .filter { (_, value) -> value.unavailable }
            .keys
            .groupBy(ProfileOptionLatencyKey::profileId, ProfileOptionLatencyKey::optionId)
            .mapValues { (_, values) -> values.toSet() }
    val mergedServerPingsByProfileId =
        (rememberedServerPingsByProfileId.keys + liveServerPingsByProfileId.keys)
            .associateWith { profileId ->
                rememberedServerPingsByProfileId[profileId]
                    .orEmpty()
                    .filterKeys { optionId ->
                        optionId !in liveUnavailableServerPingOptionIdsByProfileId[profileId].orEmpty()
                    } +
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
        state.settings.rememberedSmartProfileDownOptionIdsByProfileId()
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
        state.settings.rememberedSmartStartLatencyByProfileId()
    val liveLatenciesByProfileId =
        liveSmartProfileLatenciesByProfileId(profileOptionLatencies)
    val latencyUnavailableByProfileId =
        smartProfileLatencyUnavailableByProfileId(
            rememberedLatencyUnavailableByProfileId =
            state.settings.rememberedSmartProfileLatencyUnavailableByProfileId(),
            liveLatencyUnavailableByProfileId =
            liveSmartProfileLatencyUnavailableByProfileId(
                profileOptionLatencyUnavailable = profileOptionLatencyUnavailable,
                liveLatenciesByProfileId = liveLatenciesByProfileId,
            ),
            liveLatenciesByProfileId = liveLatenciesByProfileId,
        )
    val mergedLatenciesByProfileId =
        mergedSmartProfileLatenciesByProfileId(
            rememberedLatenciesByProfileId = rememberedLatenciesByProfileId,
            liveLatenciesByProfileId = liveLatenciesByProfileId,
            latencyUnavailableByProfileId = latencyUnavailableByProfileId,
        )
    val recommendedProtocolOptionByProfileId =
        smartProfileRecommendedOptionByProfileId(
            settings = state.settings,
            recommendation = protocolMetrics.recommendation,
            latenciesByProfileId = mergedLatenciesByProfileId,
        )
    val recommendedProtocolOptionsByProfileId =
        smartProfileRecommendedOptionsByProfileId(
            settings = state.settings,
            recommendation = protocolMetrics.recommendation,
            latenciesByProfileId = mergedLatenciesByProfileId,
        )
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
        smartProfileConnectDurationsByProfileId = rememberedConnectDurationsByProfileId,
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
    latenciesByProfileId: Map<Long, Map<String, Long>>,
): Map<Long, String> =
    settings.smartProfilePreferences
        .mapNotNull { preference ->
            val evidenceOptionIds = latenciesByProfileId[preference.profileId].orEmpty().keys
            preference.recommendedProtocolIds.firstOrNull(evidenceOptionIds::contains)?.let { optionId ->
                preference.profileId to optionId
            }
        }.toMap() +
        recommendation
            ?.takeIf { state -> state.optionId in latenciesByProfileId[state.profileId].orEmpty() }
            ?.let { state -> mapOf(state.profileId to state.optionId) }
            .orEmpty()

private fun smartProfileRecommendedOptionsByProfileId(
    settings: Settings,
    recommendation: ProtocolRecommendationState?,
    latenciesByProfileId: Map<Long, Map<String, Long>>,
): Map<Long, Set<String>> =
    settings.smartProfilePreferences
        .associate { preference ->
            val evidenceOptionIds = latenciesByProfileId[preference.profileId].orEmpty().keys
            preference.profileId to preference.recommendedProtocolIds.filter(evidenceOptionIds::contains).toSet()
        } +
        recommendation
            ?.takeIf { state -> state.optionId in latenciesByProfileId[state.profileId].orEmpty() }
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

private fun smartProfileLatencyUnavailableByProfileId(
    rememberedLatencyUnavailableByProfileId: Map<Long, Set<String>>,
    liveLatencyUnavailableByProfileId: Map<Long, Set<String>>,
    liveLatenciesByProfileId: Map<Long, Map<String, Long>>,
): Map<Long, Set<String>> =
    (rememberedLatencyUnavailableByProfileId.keys + liveLatencyUnavailableByProfileId.keys)
        .associateWith { profileId ->
            (rememberedLatencyUnavailableByProfileId[profileId].orEmpty() + liveLatencyUnavailableByProfileId[profileId].orEmpty())
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
