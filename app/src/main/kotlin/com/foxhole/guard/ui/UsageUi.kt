package com.foxhole.guard.ui

import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileTrafficTotal
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.model.Settings as FoxholeSettings

internal fun visibleProfileTrafficTotals(state: HomeRouteUiState): List<ProfileTrafficTotal> =
    visibleProfileTrafficTotals(state = state, traffic = state.traffic)

internal fun visibleProfileTrafficTotals(
    state: HomeRouteUiState,
    traffic: TrafficSnapshot,
): List<ProfileTrafficTotal> =
    visibleProfileTrafficTotals(
        settings = state.settings,
        activeProfile = state.activeProfile,
        traffic = traffic,
        currentProtocolOptionId = state.connection.protocolOptionId,
        currentProtocolHint = state.connection.protocolHint,
    )

private fun visibleProfileTrafficTotals(
    settings: FoxholeSettings,
    activeProfile: Profile?,
    traffic: TrafficSnapshot,
    currentProtocolOptionId: String? = null,
    currentProtocolHint: com.foxhole.core.model.ProtocolHint? = null,
): List<ProfileTrafficTotal> {
    val totals = settings.profileTrafficTotals.associateBy(ProfileTrafficTotal::trafficKey).toMutableMap()
    if (activeProfile != null && (traffic.rxTotalBytes > 0L || traffic.txTotalBytes > 0L)) {
        val activeOptionId =
            currentProtocolOptionId
                ?.takeIf(String::isNotBlank)
                ?.takeIf { optionId -> activeProfile.protocolOptions.any { option -> option.id == optionId } }
                ?: activeProfile.selectedProtocolOptionId?.takeIf(String::isNotBlank)
        val activeOption = activeProfile.protocolOptions.firstOrNull { option -> option.id == activeOptionId }
        val trafficKey = ProfileTrafficKey(activeProfile.id, activeOptionId)
        val stored = totals[trafficKey]
        totals[trafficKey] =
            ProfileTrafficTotal(
                profileId = activeProfile.id,
                profileName = activeProfile.name,
                protocolHint = activeOption?.protocolHint ?: currentProtocolHint ?: activeProfile.protocolHint,
                protocolOptionId = activeOptionId,
                rxTotalBytes = (stored?.rxTotalBytes ?: 0L) + traffic.rxTotalBytes,
                txTotalBytes = (stored?.txTotalBytes ?: 0L) + traffic.txTotalBytes,
                updatedAt = maxOf(stored?.updatedAt ?: 0L, traffic.sampledAt),
            )
    }
    return totals.values.sortedByDescending(ProfileTrafficTotal::updatedAt)
}

private data class ProfileTrafficKey(
    val profileId: Long,
    val protocolOptionId: String?,
)

private val ProfileTrafficTotal.trafficKey: ProfileTrafficKey
    get() = ProfileTrafficKey(profileId, protocolOptionId?.takeIf(String::isNotBlank))

internal fun protocolLabel(raw: String): String = raw.lowercase().replace('_', '-')
