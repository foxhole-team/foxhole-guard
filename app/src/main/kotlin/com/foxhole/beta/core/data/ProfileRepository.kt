package com.foxhole.beta.core.data

import android.util.Log
import androidx.room.withTransaction
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.diagnostics.DiagnosticSanitizer
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.importer.ProfileImportParser
import com.foxhole.beta.core.importer.SubscriptionMetadataParser
import com.foxhole.beta.core.model.CachedActiveProfile
import com.foxhole.beta.core.model.ParsedImport
import com.foxhole.beta.core.model.ParsedSubscriptionImport
import com.foxhole.beta.core.model.ParsedSubscriptionProfile
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.StoredProfileProtocolOption
import com.foxhole.beta.core.model.StoredProfileSecret
import com.foxhole.beta.core.model.VpnSession
import com.foxhole.beta.core.network.ensurePublicUrl
import com.foxhole.beta.core.network.requirePublicUrl
import com.foxhole.beta.core.settings.SettingsRepository
import com.foxhole.beta.vpn.PrivateDnsMode
import com.foxhole.beta.vpn.RuntimeConfigAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

class ProfileRepository(
    private val database: ProfileDatabase,
    private val secretStore: ProfileSecretStore,
    private val parser: ProfileImportParser,
    private val httpClient: OkHttpClient,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val settingsRepository: SettingsRepository,
    private val routingRepository: RoutingRepository,
    private val runtimeConfigAssembler: RuntimeConfigAssembler,
    private val json: Json,
) {
    private data class SubscriptionResponse(
        val body: String? = null,
        val etag: String? = null,
        val metadataTitle: String? = null,
        val subscriptionExpiresAt: Long? = null,
        val notModified: Boolean = false,
    )

    private data class SubscriptionGroupMember(
        val entity: ProfileEntity,
        val storedSecret: StoredProfileSecret,
    )

    private data class AppliedSubscriptionProfile(
        val id: Long,
        val secretRef: String,
        val importedProfile: ParsedSubscriptionProfile,
        val previousProfileId: Long?,
        val wasActive: Boolean,
        val previousSelectedProtocolOptionId: String?,
    )

    private data class PreparedLocalImportProfile(
        val entity: ProfileEntity,
        val importedProfile: ParsedSubscriptionProfile,
        val previousSelectedProtocolOptionId: String?,
        val stagedSecretWrite: StagedProfileSecretWrite,
    )

    private data class PreparedSubscriptionRefreshProfile(
        val existingEntity: ProfileEntity?,
        val resolvedName: String,
        val stagedSecretWrite: StagedProfileSecretWrite,
        val importedProfile: ParsedSubscriptionProfile,
        val previousSelectedProtocolOptionId: String?,
    )

    private data class CommittedSubscriptionRefresh(
        val appliedProfiles: List<AppliedSubscriptionProfile>,
        val replacementActiveId: Long?,
    )

    private val dao = database.profileDao()
    private val subscriptionHttpClient: OkHttpClient by lazy {
        httpClient.withBoundedRemoteFetchTimeouts()
    }

    val profiles: Flow<List<Profile>> = dao.observeProfiles().map { list -> list.map { entity -> resolveDomainProfile(entity) } }
    val activeProfile: Flow<Profile?> =
        combine(dao.observeProfiles(), dao.observeActiveProfile()) { profiles, active ->
            val resolvedProfiles = profiles.map { entity -> resolveDomainProfile(entity) }
            active?.id?.let { activeId -> resolvedProfiles.firstOrNull { it.id == activeId } } ?: resolvedProfiles.firstOrNull()
        }

    suspend fun rawInputRequiresInsecureTls(rawInput: String): Boolean {
        return rawInputInsecureTlsWarning(rawInput) != null
    }

    suspend fun rawInputInsecureTlsWarning(rawInput: String): InsecureTlsImportWarning? {
        val settings = settingsRepository.current()
        val requiresConsent = rawImportRequiresInsecureTls(
            parser = parser,
            rawInput = rawInput,
            allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
            allowHttpSubscriptionUrls = settings.expert.allowHttpConfigImports,
        )
        if (!requiresConsent) {
            return null
        }
        return withContext(Dispatchers.IO) {
            val parsed =
                parser.parseUserInput(
                    input = rawInput,
                    allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                    allowHttpSubscriptionUrls = settings.expert.allowHttpConfigImports,
                    allowInsecureTls = true,
                )
            val localParsedProfiles =
                if (parsed.sourceType == ProfileSourceType.SUBSCRIPTION_URL) {
                    null
                } else {
                    runCatching {
                        parser.parseSubscriptionProfiles(
                            rawContent = rawInput,
                            fallbackName = parsed.displayName,
                            allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                            allowHttpSubscriptionUrls = settings.expert.allowHttpConfigImports,
                            allowInsecureTls = true,
                        )
                    }.getOrNull()
                }
            localParsedProfiles?.insecureTlsImportWarning(json) ?: parsed.insecureTlsImportWarning(json)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    suspend fun importProfile(
        rawInput: String,
        preferredName: String? = null,
        allowInsecureTlsForProfile: Boolean = false,
        excludeInsecureTlsOptions: Boolean = false,
    ): Profile {
        val settings = settingsRepository.current()
        val effectiveAllowInsecureTls = settings.expert.allowInsecureTls || allowInsecureTlsForProfile || excludeInsecureTlsOptions
        val resolvedPreferredName = preferredName?.trim().takeUnless { it.isNullOrBlank() }
        diagnosticsLogger.record(
            "profile",
            "import parse started bytes=${rawInput.toByteArray(Charsets.UTF_8).size}",
        )
        val parsedRaw =
            withContext(Dispatchers.IO) {
                runCatching {
                    parser.parseUserInput(
                        input = rawInput,
                        allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                        allowHttpSubscriptionUrls = settings.expert.allowHttpConfigImports,
                        allowInsecureTls = effectiveAllowInsecureTls,
                    )
                }
            }.onSuccess { result ->
                diagnosticsLogger.record(
                    "profile",
                    "import parse finished sourceType=${result.sourceType.name.lowercase()} protocol=${result.protocolHint.name.lowercase()} options=${result.protocolOptions.size}",
                )
            }.onFailure { error ->
                diagnosticsLogger.record(
                    "profile",
                    "import parse failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
            }.getOrThrow()
        val parsed =
            if (excludeInsecureTlsOptions) {
                parsedRaw.withoutInsecureTlsOptions(json)
            } else {
                parsedRaw
            }
        if (parsed.sourceType == ProfileSourceType.SUBSCRIPTION_URL) {
            return importSubscriptionUrl(
                parsed = parsed,
                resolvedPreferredName = resolvedPreferredName,
                allowInsecureTlsForProfile = allowInsecureTlsForProfile,
                excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            )
        }
        val importPlan =
            resolveImportProfilePlan(
                parsed = parsed,
                localParsedProfiles =
                    run {
                        val parsedProfiles = withContext(Dispatchers.IO) {
                            runCatching {
                                parser.parseSubscriptionProfiles(
                                    rawContent = rawInput,
                                    fallbackName = resolvedPreferredName ?: parsed.displayName,
                                    allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                                    allowHttpSubscriptionUrls = settings.expert.allowHttpConfigImports,
                                    allowInsecureTls = effectiveAllowInsecureTls,
                                )
                            }
                        }.onSuccess { result ->
                            diagnosticsLogger.record(
                                "profile",
                                "local config group parse finished profiles=${result.profiles.size} nodes=${result.nodesCount} protocols=${result.protocolSummary()}",
                            )
                        }.onFailure { error ->
                            diagnosticsLogger.record(
                                "profile",
                                "local config group parse failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                            )
                        }.getOrNull()
                        if (excludeInsecureTlsOptions) {
                            parsedProfiles?.withoutInsecureTlsOptions(json)
                        } else {
                            parsedProfiles
                        }
                    },
            )
        if (importPlan is ImportProfilePlan.Multi) {
            return importLocalProfileGroup(
                rawInput = rawInput,
                sourceType = parsed.sourceType,
                parsed = importPlan.parsed,
                forceRequiresInsecureTls = allowInsecureTlsForProfile || importPlan.parsed.requiresInsecureTls(json),
            )
        }
        val secretRef = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val count = dao.count()
        val entity =
            ProfileEntity(
                name = resolvedPreferredName ?: parsed.displayName,
                sourceType = parsed.sourceType.name,
                secretRef = secretRef,
                protocolHint = parsed.protocolHint.name,
                lastUpdatedAt = now,
                lastEtag = null,
                isActive = count == 0,
            )
        val stagedSecretWrite =
            StagedProfileSecretWrite(
                secretRef = secretRef,
                value =
                    StoredProfileSecret(
                        rawInput = rawInput.trim(),
                        subscriptionUrl = parsed.sourceUrl,
                        resolvedConfigJson = parsed.normalizedConfigJson,
                        subscriptionExpiresAt = parsed.subscriptionExpiresAt,
                        protocolOptions = parsed.protocolOptions,
                        selectedProtocolOptionId = parsed.selectedProtocolOptionId,
                    ).withInsecureTlsMarkers(
                        json = json,
                        forceRequiresInsecureTls = allowInsecureTlsForProfile || parsed.requiresInsecureTls(json),
                    ),
            )
        val id =
            executeSecretFirstMutation(
                secretStore = secretStore,
                stagedWrites = listOf(stagedSecretWrite),
                onCleanupFailure = ::recordSecretCleanupFailure,
            ) {
                database.withTransaction {
                    if (entity.isActive) {
                        dao.clearActive()
                    }
                    dao.insert(entity)
                }
            }
        val saved = requireProfile(id)
        if (saved.isActive) {
            persistCachedActiveProfile(saved)
        }
        diagnosticsLogger.record("profile", "profile imported")
        return saved
    }

    private suspend fun importSubscriptionUrl(
        parsed: ParsedImport,
        resolvedPreferredName: String?,
        allowInsecureTlsForProfile: Boolean,
        excludeInsecureTlsOptions: Boolean,
    ): Profile {
        val sourceUrl = parsed.sourceUrl ?: error("subscription url is missing")
        val settings = settingsRepository.current()
        diagnosticsLogger.record("profile", "subscription import started")
        val safeUrl =
            withContext(Dispatchers.IO) {
                sourceUrl
                    .ensurePublicUrl(allowHttp = settings.expert.allowHttpConfigImports)
                    .requirePublicUrl(
                        allowHttp = settings.expert.allowHttpConfigImports,
                        resolveHost = true,
                    )
            }
        val response =
            runCatching {
                fetchSubscriptionResponse(
                    sourceUrl = sourceUrl,
                    safeUrl = safeUrl,
                    lastEtag = null,
                    allowHttp = settings.expert.allowHttpConfigImports,
                )
            }.getOrElse { error ->
                throw IllegalStateException(describeSubscriptionTransportFailure(sourceUrl, error), error)
            }
        diagnosticsLogger.record(
            "profile",
            "subscription transport finished notModified=${response.notModified} bodyBytes=${response.body.orEmpty().toByteArray(Charsets.UTF_8).size} etagPresent=${!response.etag.isNullOrBlank()} metadataTitlePresent=${!response.metadataTitle.isNullOrBlank()} expirationPresent=${response.subscriptionExpiresAt != null}",
        )
        require(!response.notModified) { "subscription did not return a config payload" }
        val parsedSubscription =
            parseFetchedSubscriptionProfiles(
                rawBody = response.body.orEmpty(),
                fallbackName = resolvedPreferredName ?: parsed.displayName,
                settings = settings,
                profileInsecureTlsConsentGranted = allowInsecureTlsForProfile,
                excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            )
        return applyFetchedSubscriptionProfiles(
            sourceUrl = sourceUrl,
            parsed = parsedSubscription,
            response = response,
            targetProfileId = null,
            activateFirstIfNoProfiles = dao.count() == 0,
        )
    }

    private suspend fun importLocalProfileGroup(
        rawInput: String,
        sourceType: ProfileSourceType,
        parsed: ParsedSubscriptionImport,
        forceRequiresInsecureTls: Boolean,
    ): Profile {
        val now = System.currentTimeMillis()
        val count = dao.count()
        val trimmedRawInput = rawInput.trim()
        val preparedProfiles =
            parsed.profiles.mapIndexed { index, importedProfile ->
                val secretRef = UUID.randomUUID().toString()
                val selectedProtocolOptionId = importedProfile.resolveSelectedProtocolOptionId(previousSelectedProtocolOptionId = null)
                PreparedLocalImportProfile(
                    entity =
                        ProfileEntity(
                            name =
                                resolveImportedProfileName(
                                    importedProfile = importedProfile,
                                    fallbackName = parsed.displayName,
                                    profileCount = parsed.nodesCount,
                                    profileIndex = index,
                                ),
                            sourceType = sourceType.name,
                            secretRef = secretRef,
                            protocolHint = importedProfile.resolveProtocolHint(selectedProtocolOptionId).name,
                            lastUpdatedAt = now,
                            lastEtag = null,
                            isActive = count == 0 && index == 0,
                        ),
                    importedProfile = importedProfile,
                    previousSelectedProtocolOptionId = null,
                    stagedSecretWrite =
                        StagedProfileSecretWrite(
                            secretRef = secretRef,
                            value =
                                StoredProfileSecret(
                                    rawInput = trimmedRawInput,
                                    resolvedConfigJson = importedProfile.resolveNormalizedConfigJson(selectedProtocolOptionId),
                                    subscriptionExpiresAt = importedProfile.subscriptionExpiresAt ?: parsed.subscriptionExpiresAt,
                                    protocolOptions = importedProfile.protocolOptions,
                                    selectedProtocolOptionId = selectedProtocolOptionId,
                                ).withInsecureTlsMarkers(
                                    json = json,
                                    forceRequiresInsecureTls =
                                        forceRequiresInsecureTls && importedProfile.requiresInsecureTls(json),
                                ),
                        ),
                )
            }
        val appliedProfiles =
            executeSecretFirstMutation(
                secretStore = secretStore,
                stagedWrites = preparedProfiles.map(PreparedLocalImportProfile::stagedSecretWrite),
                onCleanupFailure = ::recordSecretCleanupFailure,
            ) {
                val committedProfiles = mutableListOf<AppliedSubscriptionProfile>()
                database.withTransaction {
                    preparedProfiles.forEach { prepared ->
                        val insertedId = dao.insert(prepared.entity)
                        committedProfiles +=
                            AppliedSubscriptionProfile(
                                id = insertedId,
                                secretRef = prepared.stagedSecretWrite.secretRef,
                                importedProfile = prepared.importedProfile,
                                previousProfileId = null,
                                wasActive = prepared.entity.isActive,
                                previousSelectedProtocolOptionId = prepared.previousSelectedProtocolOptionId,
                            )
                    }
                }
                committedProfiles
            }
        val saved = requireProfile(appliedProfiles.first().id)
        if (saved.isActive) {
            persistCachedActiveProfile(saved)
        }
        diagnosticsLogger.record("profile", "profile imported: ${parsed.profiles.size} profiles")
        return saved
    }

    suspend fun deleteProfile(profileId: Long) {
        val entity = dao.getById(profileId) ?: return
        var nextActiveId: Long? = null
        database.withTransaction {
            dao.delete(profileId)
            if (entity.isActive) {
                val replacementId = dao.getMostRecentProfileId()
                if (replacementId != null) {
                    dao.setActive(replacementId)
                    nextActiveId = replacementId
                }
            }
        }
        if (!secretStore.delete(entity.secretRef)) {
            diagnosticsLogger.record("profile", "profile secret cleanup failed")
        }
        settingsRepository.updateSmartProfileExcludedProtocolOptionIds(profileId, emptySet())
        persistCachedActiveProfile(if (nextActiveId != null) requireProfile(nextActiveId!!) else null)
        diagnosticsLogger.record("profile", "profile deleted")
    }

    suspend fun setActiveProfile(profileId: Long) {
        database.withTransaction {
            dao.clearActive()
            dao.setActive(profileId)
        }
        persistCachedActiveProfile(requireProfile(profileId))
        diagnosticsLogger.record("profile", "active profile changed")
    }

    suspend fun selectProfileProtocolOption(
        profileId: Long,
        optionId: String,
    ): Profile {
        val entity = dao.getById(profileId) ?: error("profile not found")
        val secret = secretStore.read(entity.secretRef) ?: error("profile secret is missing")
        val selected =
            secret.protocolOptions.firstOrNull { option ->
                option.id == optionId && option.normalizedConfigJson.isNotBlank()
            } ?: error("protocol option not found")
        secretStore.write(
            secretRef = entity.secretRef,
            value = secret.copy(selectedProtocolOptionId = selected.id),
        )
        dao.updateProtocolHint(profileId, selected.protocolHint.name)
        val updated = requireProfile(profileId)
        if (updated.isActive) {
            persistCachedActiveProfile(updated)
        }
        diagnosticsLogger.record("profile", "profile protocol option changed")
        return updated
    }

    suspend fun getActiveProfile(): Profile? {
        ensureActiveProfileInvariant()
        val activeEntity = dao.getActiveProfile()
        val active =
            if (activeEntity != null) {
                resolveDomainProfile(activeEntity)
            } else {
                dao.getMostRecentProfileId()?.let { profileId -> requireProfile(profileId) }
            }
        persistCachedActiveProfile(active)
        return active
    }

    suspend fun ensureActiveProfileInvariant() {
        var recovered = false
        database.withTransaction {
            if (dao.getActiveProfile() != null) {
                return@withTransaction
            }
            val replacementId = dao.getMostRecentProfileId() ?: return@withTransaction
            dao.clearActive()
            dao.setActive(replacementId)
            recovered = true
        }
        if (recovered) {
            persistCachedActiveProfile(dao.getActiveProfile()?.let { entity -> resolveDomainProfile(entity) })
            diagnosticsLogger.record("profile", "active profile recovered")
        }
    }

    suspend fun getProfile(profileId: Long): Profile? = dao.getById(profileId)?.let { entity -> resolveDomainProfile(entity) }

    suspend fun getResolvedConfig(
        profileId: Long,
        protocolOptionIdOverride: String? = null,
    ): String {
        val profile = requireProfile(profileId)
        val secret = secretStore.read(profile.secretRef) ?: error("profile secret is missing")
        val settings = settingsRepository.current()
        val selectedOption = secret.selectedStoredProtocolOption(protocolOptionIdOverride)
        val baseConfig =
            selectedOption?.normalizedConfigJson ?: secret.resolvedConfigJson
        val resolvedConfig = baseConfig ?: error("profile has no resolved config")
        val legacyRawConfigRepair =
            parser.normalizeLegacyRawResolvedConfig(
                raw = resolvedConfig,
                settings = settings,
                allowInsecureTls =
                    settings.expert.allowInsecureTls ||
                        secret.requiresInsecureTls ||
                        selectedOption?.requiresInsecureTls == true,
            )
        val runtimeConfig = legacyRawConfigRepair ?: resolvedConfig
        require(runtimeConfig.trimStart().startsWith("{")) { "stored profile config is not valid JSON" }
        val effectiveAllowInsecureTls =
            settings.expert.allowInsecureTls ||
                secret.requiresInsecureTls ||
                selectedOption?.requiresInsecureTls == true ||
                selectedOption?.normalizedConfigJson?.requiresInsecureTls(json) == true ||
                runtimeConfig.requiresInsecureTls(json)
        val sanitized =
            withContext(Dispatchers.IO) {
                parser.sanitizeResolvedConfig(
                    raw = runtimeConfig,
                    allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                    allowInsecureTls = effectiveAllowInsecureTls,
                )
            }
        if (legacyRawConfigRepair != null || sanitized != resolvedConfig) {
            diagnosticsLogger.record(
                "profile",
                if (legacyRawConfigRepair != null) {
                    "legacy raw resolved config normalized for runtime"
                } else {
                    "resolved config sanitized for runtime"
                },
            )
        }
        return sanitized
    }

    suspend fun updateResolvedConfig(
        profileId: Long,
        editedJson: String,
        protocolOptionIdOverride: String? = null,
    ) {
        val entity = dao.getById(profileId) ?: error("profile not found")
        val secret = secretStore.read(entity.secretRef) ?: error("profile secret is missing")
        val settings = settingsRepository.current()
        val selectedOption = secret.selectedStoredProtocolOption(protocolOptionIdOverride)
        val effectiveAllowInsecureTls =
            settings.expert.allowInsecureTls ||
                secret.requiresInsecureTls ||
                selectedOption?.requiresInsecureTls == true ||
                selectedOption?.normalizedConfigJson?.requiresInsecureTls(json) == true
        val sanitized =
            withContext(Dispatchers.IO) {
                parser.sanitizeResolvedConfig(
                    raw = editedJson,
                    allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                    allowInsecureTls = effectiveAllowInsecureTls,
                )
            }
        secretStore.write(
            secretRef = entity.secretRef,
            value =
                secret
                    .withUpdatedResolvedConfigJson(
                        sanitized = sanitized,
                        protocolOptionIdOverride = protocolOptionIdOverride,
                    )
                    .withInsecureTlsMarkers(json),
        )
        diagnosticsLogger.record("profile", "resolved config updated")
    }

    suspend fun getSession(
        profileId: Long,
        protocolOptionIdOverride: String? = null,
        privateDnsMode: PrivateDnsMode? = null,
    ): VpnSession {
        val profile = requireProfile(profileId)
        val secret = secretStore.read(profile.secretRef) ?: error("profile secret is missing")
        val selectedOption = secret.selectedStoredProtocolOption(protocolOptionIdOverride)
        val correlationId = newRuntimeCorrelationId()
        val assembled =
            runCatching {
                runtimeConfigAssembler.assemble(
                    baseConfigJson = getResolvedConfig(profileId, protocolOptionIdOverride),
                    settings = settingsRepository.current(),
                    activePreset = routingRepository.currentPresetForRuntime(),
                    privateDnsMode = privateDnsMode,
                )
            }.onFailure { error ->
                diagnosticsLogger.record(
                    "profile",
                    "session build failed sessionId=$correlationId error=${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
                val logMessage = "session build failed sessionId=$correlationId error=${error.javaClass.simpleName}"
                if (BuildConfig.DEBUG) {
                    Log.e(LOG_TAG, logMessage, error)
                } else {
                    Log.e(LOG_TAG, DiagnosticSanitizer.sanitizeForExport(logMessage))
                }
            }.getOrThrow()
        return VpnSession(
            profileId = profile.id,
            profileName = profile.name,
            protocolHint = selectedOption?.protocolHint ?: profile.protocolHint,
            protocolOptionId = selectedOption?.id,
            configJson = assembled,
            correlationId = correlationId,
        )
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun refreshProfile(
        profileId: Long,
        excludeInsecureTlsOptions: Boolean = false,
    ): Profile {
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
                    .ensurePublicUrl(allowHttp = settings.expert.allowHttpConfigImports)
                    .requirePublicUrl(
                        allowHttp = settings.expert.allowHttpConfigImports,
                        resolveHost = true,
                    )
            }
        val response =
            runCatching {
                fetchSubscriptionResponse(
                    sourceUrl = sourceUrl,
                    safeUrl = safeUrl,
                    lastEtag = entity.lastEtag,
                    allowHttp = settings.expert.allowHttpConfigImports,
                )
            }.getOrElse { error ->
                throw IllegalStateException(describeSubscriptionTransportFailure(sourceUrl, error), error)
            }
        diagnosticsLogger.record(
            "profile",
            "subscription transport finished notModified=${response.notModified} bodyBytes=${response.body.orEmpty().toByteArray(Charsets.UTF_8).size} etagPresent=${!response.etag.isNullOrBlank()} metadataTitlePresent=${!response.metadataTitle.isNullOrBlank()} expirationPresent=${response.subscriptionExpiresAt != null}",
        )

        if (response.notModified) {
            diagnosticsLogger.record("profile", "subscription not modified")
            return resolveDomainProfile(entity)
        }

        val profileInsecureTlsConsentGranted = secret.hasInsecureTlsConsent(json)
        val parsed =
            parseFetchedSubscriptionProfiles(
                rawBody = response.body.orEmpty(),
                fallbackName = entity.name,
                settings = settings,
                profileInsecureTlsConsentGranted = profileInsecureTlsConsentGranted,
                excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            )
        return applyFetchedSubscriptionProfiles(
            sourceUrl = sourceUrl,
            parsed = parsed,
            response = response,
            targetProfileId = profileId,
            activateFirstIfNoProfiles = false,
        )
    }

    private suspend fun parseFetchedSubscriptionProfiles(
        rawBody: String,
        fallbackName: String,
        settings: Settings,
        profileInsecureTlsConsentGranted: Boolean,
        excludeInsecureTlsOptions: Boolean,
    ): ParsedSubscriptionImport {
        diagnosticsLogger.record(
            "profile",
            "subscription parse started bodyBytes=${rawBody.toByteArray(Charsets.UTF_8).size}",
        )
        val strictParseFailedForInsecureTls =
            withContext(Dispatchers.IO) {
                runCatching {
                    parser.parseSubscriptionProfiles(
                        rawContent = rawBody,
                        fallbackName = fallbackName,
                        allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                        allowHttpSubscriptionUrls = settings.expert.allowHttpConfigImports,
                        allowInsecureTls = false,
                    )
                }.exceptionOrNull()
                    ?.isInsecureTlsPolicyFailure() == true
            }
        val subscriptionRequiresInsecureTlsConsent =
            shouldRequireInsecureTlsRefreshConsent(
                allowInsecureTlsGlobally = settings.expert.allowInsecureTls,
                profileInsecureTlsConsentGranted = profileInsecureTlsConsentGranted,
                strictParseFailedForInsecureTls = strictParseFailedForInsecureTls,
            )
        if (subscriptionRequiresInsecureTlsConsent && !excludeInsecureTlsOptions) {
            val warning =
                withContext(Dispatchers.IO) {
                    runCatching {
                        parser.parseSubscriptionProfiles(
                            rawContent = rawBody,
                            fallbackName = fallbackName,
                            allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                            allowHttpSubscriptionUrls = settings.expert.allowHttpConfigImports,
                            allowInsecureTls = true,
                        ).insecureTlsImportWarning(json)
                    }.getOrNull()
                }
            diagnosticsLogger.record("profile", "subscription requires INSECURE TLS consent")
            throw InsecureTlsProfileConsentRequiredException(warning = warning)
        }
        val effectiveAllowInsecureTls = settings.expert.allowInsecureTls || profileInsecureTlsConsentGranted || excludeInsecureTlsOptions
        val parsedRaw =
            withContext(Dispatchers.IO) {
                runCatching {
                    parser.parseSubscriptionProfiles(
                        rawContent = rawBody,
                        fallbackName = fallbackName,
                        allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                        allowHttpSubscriptionUrls = settings.expert.allowHttpConfigImports,
                        allowInsecureTls = effectiveAllowInsecureTls,
                    )
                }
            }.onSuccess { result ->
                diagnosticsLogger.record(
                    "profile",
                    "subscription parse finished profiles=${result.profiles.size} nodes=${result.nodesCount} protocols=${result.protocolSummary()}",
                )
            }.onFailure { error ->
                diagnosticsLogger.record(
                    "profile",
                    "subscription parse failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
            }.getOrThrow()
        return if (excludeInsecureTlsOptions) {
            parsedRaw.withoutInsecureTlsOptions(json)
        } else {
            parsedRaw
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun applyFetchedSubscriptionProfiles(
        sourceUrl: String,
        parsed: ParsedSubscriptionImport,
        response: SubscriptionResponse,
        targetProfileId: Long?,
        activateFirstIfNoProfiles: Boolean,
    ): Profile {
        val subscriptionGroup = loadSubscriptionGroup(sourceUrl)
        val refreshPlan =
            planSubscriptionRefresh(
                existingProfiles =
                    subscriptionGroup.map { member ->
                        ExistingSubscriptionProfile(
                            id = member.entity.id,
                            name = member.entity.name,
                            protocolHint = member.entity.toDomain().protocolHint,
                        )
                    },
                importedProfiles =
                    parsed.profiles.map { imported ->
                        ImportedSubscriptionProfile(
                            displayName = imported.displayName,
                            protocolHint = imported.protocolHint,
                        )
                    },
            )
        val matchedProfiles = refreshPlan.assignments.count { assignment -> assignment.existingProfileId != null }
        val insertedProfiles = refreshPlan.assignments.size - matchedProfiles
        diagnosticsLogger.record(
            "profile",
            "subscription apply plan prepared matched=$matchedProfiles inserted=$insertedProfiles deleted=${refreshPlan.deletedProfileIds.size}",
        )
        val subscriptionGroupById = subscriptionGroup.associateBy { it.entity.id }
        val defaultImportedName = subscriptionDefaultName(sourceUrl)
        val now = System.currentTimeMillis()
        val removedProfiles = refreshPlan.deletedProfileIds.mapNotNull(subscriptionGroupById::get)
        val preparedProfiles =
            refreshPlan.assignments.mapIndexed { index, assignment ->
                val importedProfile = parsed.profiles[index]
                val matchedProfile = assignment.existingProfileId?.let(subscriptionGroupById::get)
                val resolvedName =
                    resolveSubscriptionProfileName(
                        importedProfile = importedProfile,
                        fallbackName = parsed.displayName,
                        metadataTitle = response.metadataTitle,
                        defaultImportedName = defaultImportedName,
                        existingName = matchedProfile?.entity?.name,
                        profileCount = parsed.nodesCount,
                        profileIndex = index,
                    )
                val nextSecretRef = UUID.randomUUID().toString()
                val selectedProtocolOptionId =
                    importedProfile.resolveSelectedProtocolOptionId(matchedProfile?.storedSecret?.selectedProtocolOptionId)
                PreparedSubscriptionRefreshProfile(
                    existingEntity = matchedProfile?.entity,
                    resolvedName = resolvedName,
                    stagedSecretWrite =
                        StagedProfileSecretWrite(
                            secretRef = nextSecretRef,
                            value =
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
                                    forceRequiresInsecureTls = importedProfile.requiresInsecureTls(json),
                                ),
                        ),
                    importedProfile = importedProfile,
                    previousSelectedProtocolOptionId = matchedProfile?.storedSecret?.selectedProtocolOptionId,
                )
            }
        val committedRefresh =
            executeSecretFirstMutation(
                secretStore = secretStore,
                stagedWrites = preparedProfiles.map(PreparedSubscriptionRefreshProfile::stagedSecretWrite),
                cleanupSecretRefsAfterSuccess =
                    preparedProfiles.mapNotNull { prepared -> prepared.existingEntity?.secretRef } +
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
                                            prepared.importedProfile.resolveSelectedProtocolOptionId(prepared.previousSelectedProtocolOptionId),
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
                                                    prepared.importedProfile.resolveSelectedProtocolOptionId(
                                                        prepared.previousSelectedProtocolOptionId,
                                                    ),
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
                    val groupHadActive = subscriptionGroup.any { it.entity.isActive }
                    val preservedActive = committedProfiles.any(AppliedSubscriptionProfile::wasActive)
                    val shouldPromoteFirstProfile =
                        (groupHadActive && !preservedActive) || activateFirstIfNoProfiles
                    if (shouldPromoteFirstProfile && committedProfiles.isNotEmpty()) {
                        replacementActiveId = committedProfiles.first().id
                        dao.clearActive()
                        dao.setActive(replacementActiveId!!)
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

        val refreshedProfileId =
            targetProfileId?.let { requestedId -> appliedProfiles.firstOrNull { it.previousProfileId == requestedId }?.id }
                ?: committedRefresh.replacementActiveId
                ?: appliedProfiles.firstOrNull()?.id
                ?: targetProfileId
                ?: error("subscription did not produce profiles")
        return requireProfile(refreshedProfileId)
    }

    private suspend fun fetchSubscriptionResponse(
        sourceUrl: String,
        safeUrl: HttpUrl,
        lastEtag: String?,
        allowHttp: Boolean,
    ): SubscriptionResponse =
        withContext(Dispatchers.IO) {
            executeSubscriptionRequest(
                client = subscriptionHttpClient,
                safeUrl = safeUrl,
                sourceUrl = sourceUrl,
                lastEtag = lastEtag,
                allowHttp = allowHttp,
            )
        }

    private fun buildSubscriptionRequest(
        safeUrl: HttpUrl,
        lastEtag: String?,
    ): Request =
        Request.Builder()
            .url(safeUrl)
            .get()
            .header("User-Agent", "foxhole/0.1.0")
            .apply {
                lastEtag?.takeIf(String::isNotBlank)?.let { header("If-None-Match", it) }
            }.build()

    private fun executeSubscriptionRequest(
        client: OkHttpClient,
        safeUrl: HttpUrl,
        sourceUrl: String,
        lastEtag: String?,
        allowHttp: Boolean,
    ): SubscriptionResponse {
        val response =
            executeBoundedPublicGet(
                client = client,
                initialUrl = safeUrl,
                allowHttp = allowHttp,
                maxBytes = MAX_SUBSCRIPTION_BYTES,
            ) { url ->
                buildSubscriptionRequest(safeUrl = url, lastEtag = lastEtag)
            }
        if (response.code == 304) {
            return SubscriptionResponse(notModified = true)
        }
        if (!response.isSuccessful) {
            error(
                describeSubscriptionHttpFailure(
                    code = response.code,
                    serverHeader = response.headers["Server"],
                    responseBody = response.body.orEmpty(),
                ),
            )
        }
        return SubscriptionResponse(
            body = response.body.orEmpty(),
            etag = response.headers["ETag"],
            metadataTitle = response.headers["profile-title"].decodeSubscriptionMetadataHeader(),
            subscriptionExpiresAt =
                SubscriptionMetadataParser
                    .expirationFromSubscriptionUserinfo(response.headers["subscription-userinfo"])
                    ?: SubscriptionMetadataParser.expirationFromSubscriptionUrl(sourceUrl),
        )
    }

    private suspend fun persistCachedActiveProfile(profile: Profile?) {
        settingsRepository.updateLastActiveProfile(
            profile?.let {
                CachedActiveProfile(
                    id = it.id,
                    name = it.name,
                    sourceType = it.sourceType,
                    protocolHint = it.protocolHint,
                )
            },
        )
    }

    private suspend fun resolveDomainProfile(entity: ProfileEntity): Profile =
        entity.toDomain().let { profile ->
            val secret = secretStore.read(entity.secretRef)
            profile.copy(
                subscriptionExpiresAt = secret?.subscriptionExpiresAt,
                protocolOptions = secret?.profileProtocolOptions().orEmpty(),
                selectedProtocolOptionId = secret?.selectedProtocolOptionId,
                requiresInsecureTls =
                    secret?.requiresInsecureTls == true ||
                        secret?.resolvedConfigJson?.requiresInsecureTls(json) == true ||
                        secret?.protocolOptions.orEmpty().any { option -> option.normalizedConfigJson.requiresInsecureTls(json) },
            )
        }

    private suspend fun requireProfile(profileId: Long): Profile =
        dao.getById(profileId)?.let { entity -> resolveDomainProfile(entity) } ?: error("profile not found")

    private fun subscriptionDefaultName(sourceUrl: String): String = runCatching { URI(sourceUrl).host }.getOrNull().orEmpty().ifBlank { "subscription" }

    private fun recordSecretCleanupFailure(
        secretRef: String,
        error: Throwable,
    ) {
        diagnosticsLogger.record(
            "profile",
            "profile secret cleanup failed for $secretRef: ${error.message ?: error.javaClass.simpleName}",
        )
    }

    private suspend fun loadSubscriptionGroup(sourceUrl: String): List<SubscriptionGroupMember> {
        val members = mutableListOf<SubscriptionGroupMember>()
        for (entity in dao.getAllProfiles().filter { it.sourceType == ProfileSourceType.SUBSCRIPTION_URL.name }.sortedBy(ProfileEntity::id)) {
            val storedSecret = secretStore.read(entity.secretRef) ?: continue
            if (storedSecret.subscriptionUrl != sourceUrl) {
                continue
            }
            members += SubscriptionGroupMember(entity = entity, storedSecret = storedSecret)
        }
        return members
    }

    private fun resolveSubscriptionProfileName(
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

    private fun resolveImportedProfileName(
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

    private fun String?.decodeSubscriptionMetadataHeader(): String? {
        val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val value =
            if (raw.startsWith("base64:", ignoreCase = true)) {
                runCatching {
                    String(
                        Base64.getDecoder().decode(raw.removePrefix("base64:").removePrefix("BASE64:")),
                        StandardCharsets.UTF_8,
                    )
                }.getOrNull()
            } else {
                raw
            }
        return value?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun String.shouldReplaceSubscriptionName(defaultImportedName: String): Boolean =
        isBlank() || this == "subscription" || this == defaultImportedName

    private fun StoredProfileSecret.selectedStoredProtocolOption(
        overrideOptionId: String? = null,
    ): StoredProfileProtocolOption? {
        if (protocolOptions.isEmpty()) {
            return null
        }
        val resolvedOptionId = overrideOptionId?.takeIf(String::isNotBlank) ?: selectedProtocolOptionId
        return protocolOptions.firstOrNull { it.id == resolvedOptionId }
            ?: protocolOptions.firstOrNull()
    }

    private fun newRuntimeCorrelationId(): String =
        "s-" + UUID.randomUUID().toString().replace("-", "").take(12)

    private fun StoredProfileSecret.profileProtocolOptions(): List<ProfileProtocolOption> =
        protocolOptions.map { option ->
            ProfileProtocolOption(
                id = option.id,
                displayName = option.displayName,
                protocolHint = option.protocolHint,
                requiresInsecureTls = option.requiresInsecureTls || option.normalizedConfigJson.requiresInsecureTls(json),
                isSelected = option.id == selectedProtocolOptionId ||
                    (selectedProtocolOptionId == null && option.id == protocolOptions.firstOrNull()?.id),
            )
        }

    private fun ParsedSubscriptionProfile.resolveSelectedProtocolOptionId(previousSelectedProtocolOptionId: String?): String? =
        previousSelectedProtocolOptionId?.takeIf { selectedId -> protocolOptions.any { option -> option.id == selectedId } }
            ?: selectedProtocolOptionId

    private fun ParsedSubscriptionProfile.resolveProtocolHint(selectedProtocolOptionId: String?): com.foxhole.beta.core.model.ProtocolHint =
        protocolOptions.firstOrNull { option -> option.id == selectedProtocolOptionId }?.protocolHint ?: protocolHint

    private fun ParsedSubscriptionProfile.resolveNormalizedConfigJson(selectedProtocolOptionId: String?): String =
        protocolOptions.firstOrNull { option -> option.id == selectedProtocolOptionId }?.normalizedConfigJson ?: normalizedConfigJson

    private fun ParsedSubscriptionImport.protocolSummary(): String =
        profiles
            .map(ParsedSubscriptionProfile::protocolHint)
            .distinct()
            .joinToString(separator = ",") { hint -> hint.name.lowercase() }

    private companion object {
        private const val LOG_TAG = "FoxholeProfileRepo"
    }
}

internal fun StoredProfileSecret.withUpdatedResolvedConfigJson(
    sanitized: String,
    protocolOptionIdOverride: String? = null,
): StoredProfileSecret {
    if (protocolOptions.isEmpty()) {
        return copy(resolvedConfigJson = sanitized)
    }
    val targetOption =
        protocolOptionIdOverride
            ?.takeIf(String::isNotBlank)
            ?.let { overrideId -> protocolOptions.firstOrNull { option -> option.id == overrideId } }
            ?: selectedProtocolOptionId
                ?.takeIf(String::isNotBlank)
                ?.let { selectedId -> protocolOptions.firstOrNull { option -> option.id == selectedId } }
            ?: protocolOptions.firstOrNull()
            ?: return copy(resolvedConfigJson = sanitized)
    val shouldMirrorTopLevel =
        selectedProtocolOptionId == targetOption.id ||
            (selectedProtocolOptionId == null && protocolOptions.firstOrNull()?.id == targetOption.id) ||
            resolvedConfigJson == targetOption.normalizedConfigJson
    return copy(
        resolvedConfigJson = if (shouldMirrorTopLevel) sanitized else resolvedConfigJson,
        protocolOptions =
            protocolOptions.map { option ->
                if (option.id == targetOption.id) {
                    option.copy(normalizedConfigJson = sanitized)
                } else {
                    option
                }
            },
    )
}

internal fun ProfileImportParser.normalizeLegacyRawResolvedConfig(
    raw: String,
    settings: Settings,
    allowInsecureTls: Boolean,
): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank() || trimmed.startsWith("{")) {
        return null
    }
    return runCatching {
        parseUserInput(
            input = trimmed,
            allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
            allowHttpSubscriptionUrls = settings.expert.allowHttpConfigImports,
            allowInsecureTls = allowInsecureTls,
        ).normalizedConfigJson
    }.getOrNull()?.takeIf(String::isNotBlank)
}
