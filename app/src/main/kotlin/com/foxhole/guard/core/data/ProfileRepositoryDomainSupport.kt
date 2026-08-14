package com.foxhole.guard.core.data

import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.model.ParsedSubscriptionProfile
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileSecret
import kotlinx.serialization.json.Json
import java.net.URI

internal fun ProfileEntity.toResolvedDomainProfile(
    secret: StoredProfileSecret?,
    json: Json,
): Profile =
    toDomain().let { profile ->
        profile.copy(
            subscriptionExpiresAt = secret?.subscriptionExpiresAt,
            protocolOptions = secret?.profileProtocolOptions(json).orEmpty(),
            selectedProtocolOptionId = secret?.selectedProtocolOptionId,
            requiresInsecureTls = secret?.requiresInsecureTlsForDomain(json) == true,
            insecureTlsConsentGranted = secret?.hasInsecureTlsConsent() == true,
        )
    }

internal fun subscriptionDefaultName(sourceUrl: String): String = runCatching {
    URI(sourceUrl).host
}.getOrNull().orEmpty().ifBlank { "subscription" }

internal fun resolveSubscriptionProfileName(
    importedProfile: ParsedSubscriptionProfile,
    fallbackName: String,
    metadataTitle: String?,
    defaultImportedName: String,
    existingName: String?,
    profileCount: Int,
    profileIndex: Int,
): String {
    val importedName =
        importedProfile.displayName.ifBlank {
            if (profileCount == 1) {
                fallbackName
            } else {
                "$fallbackName ${profileIndex + 1}"
            }
        }
    val singleProfileResolvedName =
        importedName
            .takeUnless { it.isBlank() || it == defaultImportedName }
            ?: metadataTitle
            ?: importedName
    return if (profileCount == 1) {
        if (existingName == null || existingName.shouldReplaceSubscriptionName(defaultImportedName)) {
            singleProfileResolvedName
        } else {
            existingName
        }
    } else {
        importedName
    }
}

internal fun resolveImportedProfileName(
    importedProfile: ParsedSubscriptionProfile,
    fallbackName: String,
    profileCount: Int,
    profileIndex: Int,
): String =
    importedProfile.displayName.ifBlank {
        if (profileCount == 1) {
            fallbackName
        } else {
            "$fallbackName ${profileIndex + 1}"
        }
    }

private fun String.shouldReplaceSubscriptionName(defaultImportedName: String): Boolean =
    isBlank() || this == "subscription" || this == defaultImportedName

internal fun StoredProfileSecret.profileProtocolOptions(json: Json): List<ProfileProtocolOption> =
    protocolOptions.map { option ->
        ProfileProtocolOption(
            id = option.id,
            displayName = option.displayName,
            protocolHint = option.protocolHint,
            requiresInsecureTls = option.requiresInsecureTls || option.normalizedConfigJson.requiresInsecureTls(json),
            isSelected = option.id == selectedProtocolOptionId ||
                (selectedProtocolOptionId == null && option.id == protocolOptions.firstOrNull()?.id),
            enabled = option.enabled,
        )
    }

internal fun StoredProfileSecret.requiresInsecureTlsForDomain(json: Json): Boolean =
    requiresInsecureTls ||
        resolvedConfigJson?.requiresInsecureTls(json) == true ||
        protocolOptions.any { option -> option.normalizedConfigJson.requiresInsecureTls(json) }

internal fun ParsedSubscriptionProfile.resolveProtocolHint(selectedProtocolOptionId: String?): ProtocolHint =
    protocolOptions.firstOrNull { option -> option.id == selectedProtocolOptionId }?.protocolHint ?: protocolHint

internal fun ParsedSubscriptionProfile.resolveNormalizedConfigJson(selectedProtocolOptionId: String?): String =
    protocolOptions.firstOrNull { option -> option.id == selectedProtocolOptionId }?.normalizedConfigJson ?: normalizedConfigJson

internal fun ParsedSubscriptionImport.protocolSummary(): String =
    profiles
        .flatMap { profile ->
            profile
                .protocolOptions
                .map { option -> option.protocolHint }
                .ifEmpty { listOf(profile.protocolHint) }
        }
        .distinct()
        .joinToString(separator = ",") { hint -> hint.name.lowercase() }

internal fun ParsedSubscriptionImport.planRefreshAgainst(
    subscriptionGroup: List<SubscriptionGroupMember>,
): SubscriptionRefreshPlan =
    planSubscriptionRefresh(
        existingProfiles = subscriptionGroup.map(SubscriptionGroupMember::toExistingSubscriptionProfile),
        importedProfiles = profiles.map(ParsedSubscriptionProfile::toImportedSubscriptionProfile),
    )

private fun SubscriptionGroupMember.toExistingSubscriptionProfile(): ExistingSubscriptionProfile {
    val domainProfile = entity.toDomain()
    return ExistingSubscriptionProfile(
        id = entity.id,
        name = entity.name,
        protocolHint = domainProfile.protocolHint,
        stableFingerprint =
        stableSubscriptionProfileFingerprint(
            protocolHint = domainProfile.protocolHint,
            normalizedConfigJson = storedSecret.resolvedConfigJson,
            protocolOptions = storedSecret.protocolOptions,
        ),
    )
}

private fun ParsedSubscriptionProfile.toImportedSubscriptionProfile(): ImportedSubscriptionProfile =
    ImportedSubscriptionProfile(
        displayName = displayName,
        protocolHint = protocolHint,
        stableFingerprint =
        stableSubscriptionProfileFingerprint(
            protocolHint = protocolHint,
            normalizedConfigJson = normalizedConfigJson,
            protocolOptions = protocolOptions,
        ),
    )

internal fun ParsedSubscriptionImport.hasUnconsentedInsecureTlsProfiles(
    subscriptionGroup: List<SubscriptionGroupMember>,
    json: Json,
): Boolean {
    if (!requiresInsecureTls(json)) {
        return false
    }
    val subscriptionGroupById = subscriptionGroup.associateBy { member -> member.entity.id }
    val refreshPlan = planRefreshAgainst(subscriptionGroup)
    return refreshPlan.assignments.withIndex().any { (index, assignment) ->
        val importedProfile = profiles[index]
        importedProfile.requiresInsecureTls(json) &&
            assignment.existingProfileId
                ?.let(subscriptionGroupById::get)
                ?.storedSecret
                ?.hasInsecureTlsConsent() != true
    }
}
