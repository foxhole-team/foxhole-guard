package com.foxhole.guard.core.data

import com.foxhole.core.model.ParsedImport
import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.Settings
import com.foxhole.core.network.ensurePublicSubscriptionUrl
import com.foxhole.core.network.requirePublicUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ProfileImportPolicy(val privateHosts: Boolean, val insecureTls: Boolean)

internal fun Settings.importPolicy() = ProfileImportPolicy(expert.allowPrivateOutboundHosts, expert.allowInsecureTls)

internal data class PreparedProfileImport(
    val rawInput: String,
    val preferredName: String?,
    val policy: ProfileImportPolicy,
    val parsed: ParsedImport?,
    val group: ParsedSubscriptionImport?,
    val sourceUrl: String?,
    val response: SubscriptionResponse?,
    val expectedGroup: List<SubscriptionGroupMember>?,
    val existingLocal: Profile?,
    val grantInsecureTlsConsent: Boolean,
)

internal class PreparedProfileImportChangedException :
    IllegalStateException("profile or import policy changed; retry the import")

internal suspend fun ProfileRepository.prepareProfileImport(
    rawInput: String,
    preferredName: String? = null,
    allowInsecureTlsForProfile: Boolean = false,
    excludeInsecureTlsOptions: Boolean = false,
): PreparedProfileImport {
    val settings = settingsRepository.current()
    val policy = settings.importPolicy()
    val preferred = preferredName?.trim().takeUnless { it.isNullOrBlank() }
    if (localProfileImportByteCount(rawInput) > MAX_LOCAL_PROFILE_IMPORT_BYTES) {
        throw ProfileImportPayloadTooLargeException()
    }
    val existing = mutationCoordinator.readCommitted { findProfileMatchingRawImport(rawInput) }
    if (existing != null && existing.sourceType != ProfileSourceType.SUBSCRIPTION_URL) {
        return PreparedProfileImport(rawInput, preferred, policy, null, null, null, null, null, existing, allowInsecureTlsForProfile)
    }
    val effectiveAllow = settings.expert.allowInsecureTls || allowInsecureTlsForProfile || excludeInsecureTlsOptions
    val rawParsed = withContext(Dispatchers.IO) {
        parser.parseUserInput(rawInput, settings.expert.allowPrivateOutboundHosts, effectiveAllow)
    }
    val parsed = if (excludeInsecureTlsOptions) rawParsed.withoutInsecureTlsOptions(json) else rawParsed
    if (parsed.sourceType == ProfileSourceType.SUBSCRIPTION_URL) {
        return prepareSubscriptionImport(
            rawInput,
            preferred,
            settings,
            parsed,
            existing,
            allowInsecureTlsForProfile,
            excludeInsecureTlsOptions,
        )
    }
    val localGroup = try {
        withContext(Dispatchers.IO) {
            parser.parseSubscriptionProfiles(
                rawContent = rawInput,
                fallbackName = preferred ?: parsed.displayName,
                allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                allowInsecureTls = effectiveAllow,
            )
        }.let { if (excludeInsecureTlsOptions) it.withoutInsecureTlsOptions(json) else it }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    val plan = resolveImportProfilePlan(parsed, localGroup)
    return PreparedProfileImport(
        rawInput, preferred, policy, parsed, (plan as? ImportProfilePlan.Multi)?.parsed,
        null, null, null, null, allowInsecureTlsForProfile,
    )
}

private suspend fun ProfileRepository.prepareSubscriptionImport(
    rawInput: String,
    preferred: String?,
    settings: Settings,
    parsed: ParsedImport,
    existing: Profile?,
    grantConsent: Boolean,
    excludeInsecureTlsOptions: Boolean,
): PreparedProfileImport {
    val source = parsed.sourceUrl ?: error("subscription url is missing")
    val snapshot = mutationCoordinator.readCommitted {
        loadSubscriptionGroup(source)
    }
    val target = existing?.let { profile -> snapshot.firstOrNull { it.entity.id == profile.id } }
    val safeUrl = withContext(Dispatchers.IO) {
        source.ensurePublicSubscriptionUrl().requirePublicUrl(allowHttp = false, resolveHost = true)
    }
    val response = try {
        subscriptionFetchUseCase.fetchSubscriptionResponse(
            sourceUrl = source,
            safeUrl = safeUrl,
            lastEtag = target?.entity?.lastEtag,
            allowHttp = false,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw IllegalStateException(describeSubscriptionTransportFailure(source, error), error)
    }
    val fetched = if (response.notModified) {
        null
    } else {
        parseFetchedSubscriptionProfiles(
            rawBody = response.body.orEmpty(),
            fallbackName = target?.entity?.name ?: preferred ?: parsed.displayName,
            settings = settings,
            allowInsecureTlsForProfile = grantConsent,
            excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            subscriptionGroup = snapshot,
        )
    }
    check(!response.notModified || target != null) { "subscription did not return a config payload" }
    return PreparedProfileImport(
        rawInput, preferred, settings.importPolicy(), parsed, fetched, source, response, snapshot,
        existing, grantConsent,
    )
}

internal suspend fun ProfileRepository.validatePreparedImport(
    prepared: PreparedProfileImport,
    owner: ProfileMutationLease,
) {
    owner.requireOwner(mutationCoordinator)
    requirePreparedImportCurrent(owner.currentSettings(settingsRepository).importPolicy() == prepared.policy)
    val expected = prepared.expectedGroup
    requirePreparedImportCurrent(
        expected == null || loadSubscriptionGroup(requireNotNull(prepared.sourceUrl)) == expected
    )
    if (prepared.sourceUrl == null && prepared.existingLocal != null) {
        val existing = findProfileMatchingRawImport(prepared.rawInput)
        requirePreparedImportCurrent(
            existing != null && existing.id == prepared.existingLocal.id && existing.secretRef == prepared.existingLocal.secretRef
        )
    }
}

internal class ValidatedProfileImport private constructor(val prepared: PreparedProfileImport, val owner: ProfileMutationLease) {
    companion object {
        suspend fun validate(
            repository: ProfileRepository,
            prepared: PreparedProfileImport,
            owner: ProfileMutationLease
        ): ValidatedProfileImport {
            repository.validatePreparedImport(prepared, owner)
            return ValidatedProfileImport(prepared, owner)
        }
    }
}

internal suspend fun ProfileRepository.validateImportForCommit(
    prepared: PreparedProfileImport,
    owner: ProfileMutationLease,
): ValidatedProfileImport {
    return ValidatedProfileImport.validate(this, prepared, owner)
}

internal suspend fun ProfileRepository.applyPreparedImport(
    validated: ValidatedProfileImport,
): Profile {
    val owner = validated.owner
    val prepared = validated.prepared
    owner.requireOwner(mutationCoordinator)
    val existing = findProfileMatchingRawImport(prepared.rawInput)
    if (prepared.sourceUrl == null && existing != null) {
        setActiveProfileLocked(existing.id, owner)
        return requireProfile(existing.id)
    }
    if (prepared.sourceUrl != null) {
        val group = loadSubscriptionGroup(prepared.sourceUrl)
        val response = requireNotNull(prepared.response)
        if (response.notModified) return requireProfile(requireNotNull(prepared.existingLocal).id)
        return applyFetchedSubscriptionProfiles(
            sourceUrl = prepared.sourceUrl,
            parsed = requireNotNull(prepared.group),
            response = response,
            targetProfileId = existing?.id,
            activateFirstIfNoProfiles = dao.count() == 0,
            subscriptionGroup = group,
            grantInsecureTlsConsent = prepared.grantInsecureTlsConsent,
            owner = owner,
        )
    }
    val group = prepared.group
    return if (group != null) {
        importLocalProfileGroupLocked(
            rawInput = prepared.rawInput,
            sourceType = requireNotNull(prepared.parsed).sourceType,
            parsed = group,
            forceRequiresInsecureTls = prepared.grantInsecureTlsConsent || group.requiresInsecureTls(json),
            grantInsecureTlsConsent = prepared.grantInsecureTlsConsent,
            owner = owner,
        )
    } else {
        importPreparedSingleLocked(prepared, owner)
    }
}

internal fun requirePreparedImportCurrent(unchanged: Boolean) {
    if (!unchanged) throw PreparedProfileImportChangedException()
}
