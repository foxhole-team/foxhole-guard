package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.ProtocolHint
import java.util.Locale

internal data class ExistingSubscriptionProfile(
    val id: Long,
    val name: String,
    val protocolHint: ProtocolHint,
)

internal data class ImportedSubscriptionProfile(
    val displayName: String,
    val protocolHint: ProtocolHint,
)

internal data class SubscriptionRefreshAssignment(
    val existingProfileId: Long?,
    val importedProfile: ImportedSubscriptionProfile,
)

internal data class SubscriptionRefreshPlan(
    val assignments: List<SubscriptionRefreshAssignment>,
    val deletedProfileIds: List<Long>,
)

internal fun planSubscriptionRefresh(
    existingProfiles: List<ExistingSubscriptionProfile>,
    importedProfiles: List<ImportedSubscriptionProfile>,
): SubscriptionRefreshPlan {
    require(importedProfiles.isNotEmpty()) { "importedProfiles must not be empty" }

    val orderedExisting = existingProfiles.sortedBy(ExistingSubscriptionProfile::id)
    val unmatchedExisting = orderedExisting.toMutableList()
    val existingByKey =
        orderedExisting
            .groupBy { it.matchKey() }
            .mapValues { (_, profiles) -> profiles.toMutableList() }
            .toMutableMap()

    val assignments =
        importedProfiles.map { imported ->
            val exactMatches = existingByKey[imported.matchKey()]
            val matched =
                when {
                    !exactMatches.isNullOrEmpty() -> exactMatches.removeAt(0)
                    unmatchedExisting.isNotEmpty() -> unmatchedExisting.removeAt(0)
                    else -> null
                }
            if (matched != null) {
                unmatchedExisting.remove(matched)
            }
            SubscriptionRefreshAssignment(
                existingProfileId = matched?.id,
                importedProfile = imported,
            )
        }

    return SubscriptionRefreshPlan(
        assignments = assignments,
        deletedProfileIds = unmatchedExisting.map(ExistingSubscriptionProfile::id),
    )
}

private data class SubscriptionProfileMatchKey(
    val normalizedName: String,
    val protocolHint: ProtocolHint,
)

private fun ExistingSubscriptionProfile.matchKey(): SubscriptionProfileMatchKey =
    SubscriptionProfileMatchKey(
        normalizedName = name.trim().lowercase(Locale.ROOT),
        protocolHint = protocolHint,
    )

private fun ImportedSubscriptionProfile.matchKey(): SubscriptionProfileMatchKey =
    SubscriptionProfileMatchKey(
        normalizedName = displayName.trim().lowercase(Locale.ROOT),
        protocolHint = protocolHint,
    )
