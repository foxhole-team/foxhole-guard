package com.foxhole.guard.core.data

import com.foxhole.core.model.ParsedSubscriptionProfile
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret

internal data class SubscriptionGroupMember(
    val entity: ProfileEntity,
    val storedSecret: StoredProfileSecret,
)

internal data class AppliedSubscriptionProfile(
    val id: Long,
    val secretRef: String,
    val importedProfile: ParsedSubscriptionProfile,
    val previousProfileId: Long?,
    val wasActive: Boolean,
    val previousSelectedProtocolOptionId: String?,
)

internal data class PreparedLocalImportProfile(
    val entity: ProfileEntity,
    val importedProfile: ParsedSubscriptionProfile,
    val previousSelectedProtocolOptionId: String?,
    val stagedSecretWrite: StagedProfileSecretWrite,
)

internal data class PreparedSubscriptionRefreshProfile(
    val existingEntity: ProfileEntity?,
    val resolvedName: String,
    val stagedSecretWrite: StagedProfileSecretWrite,
    val importedProfile: ParsedSubscriptionProfile,
    val previousSelectedProtocolOptionId: String?,

    val reusesExistingSecret: Boolean = false,
)

internal data class LoadedResolvedConfig(
    val secret: StoredProfileSecret,
    val settings: Settings,
    val selectedOption: StoredProfileProtocolOption?,
    val resolvedConfig: String,
)

internal data class RepairedResolvedConfig(
    val runtimeConfig: String,
    val legacyRawConfigRepaired: Boolean,
)

internal data class CommittedSubscriptionRefresh(
    val appliedProfiles: List<AppliedSubscriptionProfile>,
    val replacementActiveId: Long?,
)

internal data class ProfileRefreshResult(
    val profile: Profile,
    val protocolChanges: ProfileRefreshProtocolChanges,
)

internal data class ProfileRefreshProtocolChanges(
    val availableProtocolLabels: List<String>,
    val addedProtocolLabels: List<String>,
    val removedProtocolLabels: List<String>,
    val unavailableProtocolLabels: List<String>,
) {
    val hasChanges: Boolean
        get() =
            addedProtocolLabels.isNotEmpty() ||
                removedProtocolLabels.isNotEmpty() ||
                unavailableProtocolLabels.isNotEmpty()
}

internal fun profileRefreshProtocolChanges(
    before: Set<ProtocolHint>,
    after: Set<ProtocolHint>,
    unavailableProtocolLabels: List<String> = emptyList(),
): ProfileRefreshProtocolChanges =
    ProfileRefreshProtocolChanges(
        availableProtocolLabels = after.map(::refreshProtocolLabel).sorted(),
        addedProtocolLabels = (after - before).map(::refreshProtocolLabel).sorted(),
        removedProtocolLabels = (before - after).map(::refreshProtocolLabel).sorted(),
        unavailableProtocolLabels =
        unavailableProtocolLabels
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted(),
    )

private fun refreshProtocolLabel(protocolHint: ProtocolHint): String =
    protocolHint.name.replace('_', '-')
