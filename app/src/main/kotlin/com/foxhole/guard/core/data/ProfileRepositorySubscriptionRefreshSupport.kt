package com.foxhole.guard.core.data

import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.model.StoredProfileSecret
import kotlinx.serialization.json.Json
import java.util.UUID

// The write/cleanup split of the secret-first mutation: a profile that reuses its stored secret
// stages no write, and its ref must never reach the cleanup list — it is still live.
internal fun List<PreparedSubscriptionRefreshProfile>.stagedSecretWrites(): List<StagedProfileSecretWrite> =
    filterNot(PreparedSubscriptionRefreshProfile::reusesExistingSecret)
        .map(PreparedSubscriptionRefreshProfile::stagedSecretWrite)

internal fun List<PreparedSubscriptionRefreshProfile>.replacedSecretRefs(): List<String> =
    filterNot(PreparedSubscriptionRefreshProfile::reusesExistingSecret)
        .mapNotNull { prepared -> prepared.existingEntity?.secretRef }

internal fun prepareSubscriptionRefreshProfiles(
    sourceUrl: String,
    parsed: ParsedSubscriptionImport,
    response: SubscriptionResponse,
    refreshPlan: SubscriptionRefreshPlan,
    subscriptionGroupById: Map<Long, SubscriptionGroupMember>,
    grantInsecureTlsConsent: Boolean,
    json: Json,
): List<PreparedSubscriptionRefreshProfile> {
    val defaultImportedName = subscriptionDefaultName(sourceUrl)
    return refreshPlan.assignments.mapIndexed { index, assignment ->
        val importedProfile = parsed.profiles[index]
        val matchedProfile = assignment.existingProfileId?.let(subscriptionGroupById::get)
        val selectedProtocolOptionId =
            importedProfile.resolveRefreshSelectedProtocolOptionId(
                previousSecret = matchedProfile?.storedSecret,
            )
        val importedRequiresInsecureTls = importedProfile.requiresInsecureTls(json)
        val previousInsecureTlsConsentGranted = matchedProfile?.storedSecret?.hasInsecureTlsConsent() == true
        val refreshedSecret =
            StoredProfileSecret(
                rawInput = sourceUrl,
                subscriptionUrl = sourceUrl,
                resolvedConfigJson = importedProfile.resolveNormalizedConfigJson(selectedProtocolOptionId),
                subscriptionExpiresAt =
                importedProfile.subscriptionExpiresAt
                    ?: parsed.subscriptionExpiresAt
                    ?: response.subscriptionExpiresAt,
                protocolOptions = importedProfile.protocolOptions,
                selectedProtocolOptionId = selectedProtocolOptionId,
            ).withInsecureTlsMarkers(
                json = json,
                forceRequiresInsecureTls = importedRequiresInsecureTls,
                grantInsecureTlsConsent =
                importedRequiresInsecureTls &&
                    (grantInsecureTlsConsent || previousInsecureTlsConsentGranted),
            )
        // Deterministic node tags make an unchanged subscription reproduce the stored secret
        // byte-for-byte; reusing the stored ref then skips the re-encryption and the secret-file
        // rewrite entirely (zero-churn refresh).
        val reusesExistingSecret = matchedProfile != null && matchedProfile.storedSecret == refreshedSecret
        PreparedSubscriptionRefreshProfile(
            existingEntity = matchedProfile?.entity,
            resolvedName =
            resolveSubscriptionProfileName(
                importedProfile = importedProfile,
                fallbackName = parsed.displayName,
                metadataTitle = response.metadataTitle,
                defaultImportedName = defaultImportedName,
                existingName = matchedProfile?.entity?.name,
                profileCount = parsed.nodesCount,
                profileIndex = index,
            ),
            stagedSecretWrite =
            StagedProfileSecretWrite(
                secretRef =
                if (reusesExistingSecret) {
                    matchedProfile.entity.secretRef
                } else {
                    UUID.randomUUID().toString()
                },
                value = refreshedSecret,
            ),
            importedProfile = importedProfile,
            previousSelectedProtocolOptionId = matchedProfile?.storedSecret?.selectedProtocolOptionId,
            reusesExistingSecret = reusesExistingSecret,
        )
    }
}
