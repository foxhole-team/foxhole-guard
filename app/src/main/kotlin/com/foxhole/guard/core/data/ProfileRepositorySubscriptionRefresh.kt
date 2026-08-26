package com.foxhole.guard.core.data

import androidx.room.withTransaction
import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.SubscriptionEntryStatus
import com.foxhole.core.model.storedProtocolHintOrNull
import com.foxhole.core.network.ensurePublicUrl
import com.foxhole.core.network.requirePublicUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

suspend fun ProfileRepository.refreshProfile(
    profileId: Long,
    excludeInsecureTlsOptions: Boolean = false,
    allowInsecureTlsForProfile: Boolean = false,
    callTimeoutMs: Long? = null,
): Profile =
    profileImportMutex.withLock {
        refreshProfileWithReportLocked(
            profileId = profileId,
            excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            allowInsecureTlsForProfile = allowInsecureTlsForProfile,
            callTimeoutMs = callTimeoutMs,
        ).profile
    }

internal suspend fun ProfileRepository.refreshProfileWithReport(
    profileId: Long,
    excludeInsecureTlsOptions: Boolean = false,
    allowInsecureTlsForProfile: Boolean = false,
    callTimeoutMs: Long? = null,
): ProfileRefreshResult =
    profileImportMutex.withLock {
        refreshProfileWithReportLocked(
            profileId = profileId,
            excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            allowInsecureTlsForProfile = allowInsecureTlsForProfile,
            callTimeoutMs = callTimeoutMs,
        )
    }

internal suspend fun ProfileRepository.refreshProfileWithReportLocked(
    profileId: Long,
    excludeInsecureTlsOptions: Boolean = false,
    allowInsecureTlsForProfile: Boolean = false,
    callTimeoutMs: Long? = null,
): ProfileRefreshResult {
    val entity = dao.getById(profileId) ?: error("profile not found")
    require(entity.sourceType == ProfileSourceType.SUBSCRIPTION_URL.name) { "profile is not refreshable" }
    val secret = secretStore.read(entity.secretRef) ?: error("profile secret is missing")
    val sourceUrl = secret.subscriptionUrl ?: error("subscription url is missing")
    diagnosticsLogger.record(
        "profile",
        "subscription refresh started profileId=$profileId etagPresent=${!entity.lastEtag.isNullOrBlank()}",
    )
    val settings = settingsRepository.current()
    val safeUrl =
        withContext(Dispatchers.IO) {
            sourceUrl
                .ensurePublicUrl(allowHttp = false)
                .requirePublicUrl(
                    allowHttp = false,
                    resolveHost = true,
                )
        }
    val response =
        runCatching {
            subscriptionFetchUseCase.fetchSubscriptionResponse(
                sourceUrl = sourceUrl,
                safeUrl = safeUrl,
                lastEtag = entity.lastEtag,
                allowHttp = false,
                callTimeoutMs = callTimeoutMs,
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            throw IllegalStateException(describeSubscriptionTransportFailure(sourceUrl, error), error)
        }
    diagnosticsLogger.record(
        "profile",
        "subscription transport finished notModified=${response.notModified} bodyBytes=${response.body.orEmpty().toByteArray(
            Charsets.UTF_8
        ).size} etagPresent=${!response.etag.isNullOrBlank()} metadataTitlePresent=${!response.metadataTitle.isNullOrBlank()} expirationPresent=${response.subscriptionExpiresAt != null}",
    )
    diagnosticsLogger.record("profile", response.metadata.redactedSummary())

    val subscriptionGroup = loadSubscriptionGroup(sourceUrl)
    val protocolsBefore = subscriptionGroup.subscriptionProtocolHints()
    if (response.notModified) {
        diagnosticsLogger.record("profile", "subscription not modified")
        return ProfileRefreshResult(
            profile = resolveDomainProfile(entity),
            protocolChanges = profileRefreshProtocolChanges(protocolsBefore, protocolsBefore),
        )
    }

    val parsed =
        parseFetchedSubscriptionProfiles(
            rawBody = response.body.orEmpty(),
            fallbackName = entity.name,
            settings = settings,
            allowInsecureTlsForProfile = allowInsecureTlsForProfile,
            excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            subscriptionGroup = subscriptionGroup,
        )
    val refreshedProfile = applyFetchedSubscriptionProfiles(
        sourceUrl = sourceUrl,
        parsed = parsed,
        response = response,
        targetProfileId = profileId,
        activateFirstIfNoProfiles = false,
        subscriptionGroup = subscriptionGroup,
        grantInsecureTlsConsent = allowInsecureTlsForProfile,
    )
    val protocolsAfter = loadSubscriptionGroup(sourceUrl).subscriptionProtocolHints()
    val changes =
        profileRefreshProtocolChanges(
            before = protocolsBefore,
            after = protocolsAfter,
            unavailableProtocolLabels =
            parsed.entryReports
                .filter { report -> report.status == SubscriptionEntryStatus.IGNORED_UNSUPPORTED }
                .map { report -> report.protocolLabel },
        )
    diagnosticsLogger.record(
        "profile",
        "subscription protocols available=${changes.availableProtocolLabels.size} " +
            "added=${changes.addedProtocolLabels.joinToString().ifBlank { "none" }} " +
            "removed=${changes.removedProtocolLabels.joinToString().ifBlank { "none" }} " +
            "unavailable=${changes.unavailableProtocolLabels.joinToString().ifBlank { "none" }}",
    )
    return ProfileRefreshResult(profile = refreshedProfile, protocolChanges = changes)
}

internal data class PreparedProfileConnection(
    val profile: Profile,
    val protocolOptionId: String?,
)

internal class SubscriptionProtocolOptionUnavailableException :
    IllegalStateException("requested subscription protocol is no longer available")

/** Refreshes one subscription group and resolves the exact post-refresh target atomically. */
internal suspend fun ProfileRepository.prepareProfileForConnection(
    profileId: Long,
    requestedProtocolOptionId: String?,
    refreshSubscription: Boolean,
): PreparedProfileConnection =
    profileImportMutex.withLock {
        val entityBefore = dao.getById(profileId) ?: error("profile not found")
        val profileBefore = resolveDomainProfile(entityBefore)
        if (profileBefore.sourceType != ProfileSourceType.SUBSCRIPTION_URL) {
            return@withLock PreparedProfileConnection(profileBefore, requestedProtocolOptionId)
        }
        val secretBefore = secretStore.read(entityBefore.secretRef) ?: error("profile secret is missing")
        val requestedId = requestedProtocolOptionId?.trim()?.takeIf(String::isNotBlank)
        val requestedFingerprint =
            requestedId
                ?.let { id -> secretBefore.protocolOptions.firstOrNull { option -> option.id == id } }
                ?.stableProtocolOptionFingerprint()
        val refreshedProfile =
            if (refreshSubscription) {
                refreshProfileWithReportLocked(
                    profileId = profileId,

                    allowInsecureTlsForProfile = false,
                ).profile
            } else {
                profileBefore
            }
        val refreshedEntity = dao.getById(refreshedProfile.id) ?: error("profile not found after refresh")
        val refreshedSecret = secretStore.read(refreshedEntity.secretRef) ?: error("profile secret is missing")
        val resolvedRequestedId =
            requestedId?.let { id ->
                refreshedSecret.protocolOptions.firstOrNull { option -> option.id == id }?.id
                    ?: requestedFingerprint
                        ?.let { fingerprint ->
                            refreshedSecret.protocolOptions
                                .filter { option -> option.stableProtocolOptionFingerprint() == fingerprint }
                                .singleOrNull()
                                ?.id
                        }
                    ?: throw SubscriptionProtocolOptionUnavailableException()
            }
        PreparedProfileConnection(
            profile = resolveDomainProfile(refreshedEntity),
            protocolOptionId = resolvedRequestedId,
        )
    }

internal suspend fun ProfileRepository.subscriptionRefreshRepresentatives(): List<Profile> =
    profileImportMutex.withLock {
        val seenSources = linkedSetOf<String>()
        buildList {
            dao.getAllProfiles()
                .filter { entity -> entity.sourceType == ProfileSourceType.SUBSCRIPTION_URL.name }
                .forEach { entity ->
                    val secret = secretStore.read(entity.secretRef) ?: return@forEach
                    val source = secret.subscriptionUrl?.trim()?.takeIf(String::isNotBlank) ?: return@forEach
                    if (seenSources.add(source)) add(resolveDomainProfile(entity))
                }
        }
    }

internal suspend fun ProfileRepository.parseFetchedSubscriptionProfiles(
    rawBody: String,
    fallbackName: String,
    settings: Settings,
    allowInsecureTlsForProfile: Boolean,
    excludeInsecureTlsOptions: Boolean,
    subscriptionGroup: List<SubscriptionGroupMember>,
): ParsedSubscriptionImport {
    diagnosticsLogger.record(
        "profile",
        "subscription parse started bodyBytes=${rawBody.toByteArray(Charsets.UTF_8).size}",
    )
    diagnosticsLogger.record("profile", redactedSubscriptionPayloadShape(rawBody))

    val parsedRelaxed =
        withContext(Dispatchers.IO) {
            runCatching {
                parser.parseSubscriptionProfiles(
                    rawContent = rawBody,
                    fallbackName = fallbackName,
                    allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                    allowInsecureTls = true,
                    groupCompatibleSingleServerMultiProtocol = true,
                )
            }
        }.onSuccess { result ->
            diagnosticsLogger.record(
                "profile",
                "subscription parse finished profiles=${result.profiles.size} nodes=${result.nodesCount} protocols=${result.protocolSummary()}",
            )
        }.onFailure { error ->
            diagnosticsLogger.recordFailure(
                "profile",
                "subscription parse failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )
        }.getOrThrow()

    val strictParseFailedForInsecureTls = parsedRelaxed.requiresInsecureTls(json)
    val refreshInsecureTlsConsentResolved =
        settings.expert.allowInsecureTls ||
            allowInsecureTlsForProfile ||
            excludeInsecureTlsOptions
    if (strictParseFailedForInsecureTls &&
        !refreshInsecureTlsConsentResolved &&
        parsedRelaxed.hasUnconsentedInsecureTlsProfiles(subscriptionGroup, json)
    ) {
        val warning = parsedRelaxed.insecureTlsImportWarning(json)
        diagnosticsLogger.record("profile", "subscription requires INSECURE TLS consent")
        throw InsecureTlsProfileConsentRequiredException(warning = warning)
    }
    return if (excludeInsecureTlsOptions) {
        parsedRelaxed.withoutInsecureTlsOptions(json)
    } else {
        parsedRelaxed
    }
}

@Suppress("CyclomaticComplexMethod")
internal suspend fun ProfileRepository.applyFetchedSubscriptionProfiles(
    sourceUrl: String,
    parsed: ParsedSubscriptionImport,
    response: SubscriptionResponse,
    targetProfileId: Long?,
    activateFirstIfNoProfiles: Boolean,
    subscriptionGroup: List<SubscriptionGroupMember>? = null,
    grantInsecureTlsConsent: Boolean = false,
): Profile {
    val resolvedSubscriptionGroup = subscriptionGroup ?: loadSubscriptionGroup(sourceUrl)
    val refreshPlan = parsed.planRefreshAgainst(resolvedSubscriptionGroup)
    val matchedProfiles = refreshPlan.assignments.count { assignment -> assignment.existingProfileId != null }
    val insertedProfiles = refreshPlan.assignments.size - matchedProfiles
    diagnosticsLogger.record(
        "profile",
        "subscription apply plan prepared matched=$matchedProfiles inserted=$insertedProfiles deleted=${refreshPlan.deletedProfileIds.size}",
    )
    val subscriptionGroupById = resolvedSubscriptionGroup.associateBy { it.entity.id }
    val now = System.currentTimeMillis()
    val removedProfiles = refreshPlan.deletedProfileIds.mapNotNull(subscriptionGroupById::get)
    val preparedProfiles =
        prepareSubscriptionRefreshProfiles(
            sourceUrl = sourceUrl,
            parsed = parsed,
            response = response,
            refreshPlan = refreshPlan,
            subscriptionGroupById = subscriptionGroupById,
            grantInsecureTlsConsent = grantInsecureTlsConsent,
            json = json,
        )
    val committedRefresh =
        executeSerializedSecretFirstMutation(
            secretStore = secretStore,
            stagedWrites = preparedProfiles.stagedSecretWrites(),
            cleanupSecretRefsAfterSuccess =
            preparedProfiles.replacedSecretRefs() +
                removedProfiles.map { member -> member.entity.secretRef },
            onCleanupFailure = ::recordSecretCleanupFailure,
        ) {
            val committedProfiles = mutableListOf<AppliedSubscriptionProfile>()
            var replacementActiveId: Long? = null
            database.withTransaction {
                preparedProfiles.forEach { prepared ->
                    val existingEntity = prepared.existingEntity
                    if (existingEntity != null) {
                        dao.updateMetadataAndSecretRef(
                            id = existingEntity.id,
                            name = prepared.resolvedName,
                            secretRef = prepared.stagedSecretWrite.secretRef,
                            protocolHint =
                            prepared.importedProfile
                                .resolveProtocolHint(
                                    prepared.stagedSecretWrite.value.selectedProtocolOptionId,
                                ).name,
                            lastUpdatedAt = now,
                            lastEtag = response.etag,
                        )
                        committedProfiles +=
                            AppliedSubscriptionProfile(
                                id = existingEntity.id,
                                secretRef = prepared.stagedSecretWrite.secretRef,
                                importedProfile = prepared.importedProfile,
                                previousProfileId = existingEntity.id,
                                wasActive = existingEntity.isActive,
                                previousSelectedProtocolOptionId = prepared.previousSelectedProtocolOptionId,
                            )
                    } else {
                        val insertedId =
                            dao.insert(
                                ProfileEntity(
                                    name = prepared.resolvedName,
                                    sourceType = ProfileSourceType.SUBSCRIPTION_URL.name,
                                    secretRef = prepared.stagedSecretWrite.secretRef,
                                    protocolHint =
                                    prepared.importedProfile
                                        .resolveProtocolHint(
                                            prepared.stagedSecretWrite.value.selectedProtocolOptionId,
                                        ).name,
                                    lastUpdatedAt = now,
                                    lastEtag = response.etag,
                                    isActive = false,
                                ),
                            )
                        committedProfiles +=
                            AppliedSubscriptionProfile(
                                id = insertedId,
                                secretRef = prepared.stagedSecretWrite.secretRef,
                                importedProfile = prepared.importedProfile,
                                previousProfileId = null,
                                wasActive = false,
                                previousSelectedProtocolOptionId = prepared.previousSelectedProtocolOptionId,
                            )
                    }
                }
                removedProfiles.forEach { member ->
                    dao.delete(member.entity.id)
                }
                val groupHadActive = resolvedSubscriptionGroup.any { it.entity.isActive }
                val preservedActive = committedProfiles.any(AppliedSubscriptionProfile::wasActive)
                val shouldPromoteFirstProfile =
                    (groupHadActive && !preservedActive) || activateFirstIfNoProfiles
                if (shouldPromoteFirstProfile && committedProfiles.isNotEmpty()) {
                    val promotedProfileId = committedProfiles.first().id
                    replacementActiveId = promotedProfileId
                    dao.clearActive()
                    dao.setActive(promotedProfileId)
                }
            }
            CommittedSubscriptionRefresh(
                appliedProfiles = committedProfiles,
                replacementActiveId = replacementActiveId,
            )
        }
    val appliedProfiles = committedRefresh.appliedProfiles
    dao.getActiveProfile()?.let { entity -> persistCachedActiveProfile(resolveDomainProfile(entity)) }
    diagnosticsLogger.record(
        "profile",
        "subscription apply finished updated=$matchedProfiles inserted=$insertedProfiles deleted=${removedProfiles.size} replacementActive=${committedRefresh.replacementActiveId != null}",
    )
    diagnosticsLogger.record("profile", "subscription refreshed: ${parsed.nodesCount} profiles")

    return requireProfile(
        refreshedSubscriptionProfileId(
            targetProfileId = targetProfileId,
            committedRefresh = committedRefresh,
        ),
    )
}

private fun refreshedSubscriptionProfileId(
    targetProfileId: Long?,
    committedRefresh: CommittedSubscriptionRefresh,
): Long =
    targetProfileId
        ?.let { requestedId ->
            committedRefresh.appliedProfiles
                .firstOrNull { applied -> applied.previousProfileId == requestedId }
                ?.id
        }
        ?: committedRefresh.replacementActiveId
        ?: committedRefresh.appliedProfiles.firstOrNull()?.id
        ?: targetProfileId
        ?: error("subscription did not produce profiles")

internal suspend fun ProfileRepository.loadSubscriptionGroup(sourceUrl: String): List<SubscriptionGroupMember> {
    return dao.getAllProfiles()
        .filter { entity -> entity.sourceType == ProfileSourceType.SUBSCRIPTION_URL.name }
        .sortedBy(ProfileEntity::id)
        .mapNotNull { entity ->
            val storedSecret = secretStore.read(entity.secretRef) ?: return@mapNotNull null
            if (storedSecret.subscriptionUrl == sourceUrl) {
                SubscriptionGroupMember(entity = entity, storedSecret = storedSecret)
            } else {
                null
            }
        }
        .toList()
}

private fun List<SubscriptionGroupMember>.subscriptionProtocolHints(): Set<ProtocolHint> =
    flatMapTo(linkedSetOf()) { member ->
        member.storedSecret.protocolOptions
            .map { option -> option.protocolHint }
            .ifEmpty {
                listOfNotNull(
                    storedProtocolHintOrNull(member.entity.protocolHint),
                )
            }
    }.filterTo(linkedSetOf()) { protocolHint -> protocolHint != ProtocolHint.UNKNOWN }
