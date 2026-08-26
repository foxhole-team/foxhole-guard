package com.foxhole.guard.core.data

import androidx.room.withTransaction
import com.foxhole.core.importer.ProfileImportParser
import com.foxhole.core.model.CachedActiveProfile
import com.foxhole.core.model.ParsedImport
import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.ensurePublicUrl
import com.foxhole.core.network.requirePublicUrl
import com.foxhole.core.runtime.I2pdManager
import com.foxhole.core.runtime.RuntimeConfigAssembler
import com.foxhole.core.runtime.TorRuntimeInstaller
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.core.settings.removeSmartProfilePreference
import com.foxhole.guard.runtime.DnsFilterAssetInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.UUID
import javax.net.SocketFactory

class ProfileRepository(
    databaseProvider: () -> ProfileDatabase,
    internal val secretStore: ProfileSecretStore,
    internal val parser: ProfileImportParser,
    private val httpClient: OkHttpClient,
    internal val diagnosticsLogger: DiagnosticsLogger,
    internal val settingsRepository: SettingsRepository,
    private val routingRepository: RoutingRepository,
    private val runtimeConfigAssembler: RuntimeConfigAssembler,
    private val torRuntimeInstaller: TorRuntimeInstaller,
    private val i2pdManager: I2pdManager,
    private val dnsFilterAssetInstaller: DnsFilterAssetInstaller,
    internal val json: Json,
    private val remoteHostResolver: RemoteHostResolver? = null,
    private val underlyingSocketFactory: () -> SocketFactory? = { null },

    private val awaitDatabaseReady: suspend () -> Unit = {},
) {
    internal val database: ProfileDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED, databaseProvider)

    internal val dao by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { database.profileDao() }
    internal val secretMutationMutex = Mutex()
    internal val profileImportMutex = Mutex()
    internal val subscriptionFetchUseCase =
        SubscriptionFetchUseCase(
            httpClient = httpClient,
            resolver = remoteHostResolver,
            underlyingSocketFactory = underlyingSocketFactory,
        )
    internal val sessionFactory by lazy {
        ProfileSessionFactory(
            settingsRepository = settingsRepository,
            routingRepository = routingRepository,
            runtimeConfigAssembler = runtimeConfigAssembler,
            torRuntimeInstaller = torRuntimeInstaller,
            i2pdManager = i2pdManager,
            dnsFilterAssetInstaller = dnsFilterAssetInstaller,
            diagnosticsLogger = diagnosticsLogger,
            profileProvider = ::requireProfile,
            secretProvider = secretStore::read,
            resolvedConfigProvider = ::getResolvedConfig,
        )
    }

    val profiles: Flow<List<Profile>> =
        flow {
            awaitDatabaseReady()
            emitAll(dao.observeProfiles().map { list -> list.map { entity -> resolveDomainProfile(entity) } })
        }.flowOn(Dispatchers.IO)
    val activeProfile: Flow<Profile?> =
        flow {
            awaitDatabaseReady()
            emitAll(
                combine(dao.observeProfiles(), dao.observeActiveProfile()) { profiles, active ->
                    val resolvedProfiles = profiles.map { entity -> resolveDomainProfile(entity) }
                    active?.id?.let { activeId -> resolvedProfiles.firstOrNull { it.id == activeId } }
                        ?: resolvedProfiles.firstOrNull()
                },
            )
        }.flowOn(Dispatchers.IO)

    suspend fun importProfile(
        rawInput: String,
        preferredName: String? = null,
        allowInsecureTlsForProfile: Boolean = false,
        excludeInsecureTlsOptions: Boolean = false,
    ): Profile =
        profileImportMutex.withLock {
            importProfileLocked(
                rawInput = rawInput,
                preferredName = preferredName,
                allowInsecureTlsForProfile = allowInsecureTlsForProfile,
                excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            )
        }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun importProfileLocked(
        rawInput: String,
        preferredName: String?,
        allowInsecureTlsForProfile: Boolean,
        excludeInsecureTlsOptions: Boolean,
    ): Profile {
        val settings = settingsRepository.current()
        val effectiveAllowInsecureTls = settings.expert.allowInsecureTls || allowInsecureTlsForProfile || excludeInsecureTlsOptions
        val resolvedPreferredName = preferredName?.trim().takeUnless { it.isNullOrBlank() }
        val rawInputSizeBytes = localProfileImportByteCount(rawInput)
        if (rawInputSizeBytes > MAX_LOCAL_PROFILE_IMPORT_BYTES) {
            throw ProfileImportPayloadTooLargeException()
        }

        findProfileMatchingRawImport(rawInput)?.let { existing ->
            if (existing.sourceType == ProfileSourceType.SUBSCRIPTION_URL) {
                diagnosticsLogger.record("profile", "duplicate subscription import resolved to refresh")
                return refreshProfileWithReportLocked(
                    profileId = existing.id,
                    excludeInsecureTlsOptions = excludeInsecureTlsOptions,
                    allowInsecureTlsForProfile = allowInsecureTlsForProfile,
                ).profile
            }
            diagnosticsLogger.record("profile", "import resolved to existing profile, no duplicate stored")
            setActiveProfile(existing.id)
            return getProfile(existing.id) ?: existing
        }
        diagnosticsLogger.record(
            "profile",
            "import parse started bytes=$rawInputSizeBytes",
        )
        val parsedRaw =
            withContext(Dispatchers.IO) {
                runCatching {
                    parser.parseUserInput(
                        input = rawInput,
                        allowPrivateOutboundHosts = settings.expert.allowPrivateOutboundHosts,
                        allowInsecureTls = effectiveAllowInsecureTls,
                    )
                }
            }.onSuccess { result ->
                diagnosticsLogger.record(
                    "profile",
                    "import parse finished sourceType=${result.sourceType.name.lowercase()} protocol=${result.protocolHint.name.lowercase()} options=${result.protocolOptions.size}",
                )
            }.onFailure { error ->
                diagnosticsLogger.recordFailure(
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
                                allowInsecureTls = effectiveAllowInsecureTls,
                            )
                        }
                    }.onSuccess { result ->
                        diagnosticsLogger.record(
                            "profile",
                            "local config group parse finished profiles=${result.profiles.size} nodes=${result.nodesCount} protocols=${result.protocolSummary()}",
                        )
                    }.onFailure { error ->
                        diagnosticsLogger.recordFailure(
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
                grantInsecureTlsConsent = allowInsecureTlsForProfile,
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
                    grantInsecureTlsConsent = allowInsecureTlsForProfile && parsed.requiresInsecureTls(json),
                ),
            )
        val id =
            executeSerializedSecretFirstMutation(
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
                    lastEtag = null,
                    allowHttp = false,
                )
            }.getOrElse { error ->
                throw IllegalStateException(describeSubscriptionTransportFailure(sourceUrl, error), error)
            }
        diagnosticsLogger.record(
            "profile",
            "subscription transport finished notModified=${response.notModified} bodyBytes=${response.body.orEmpty().toByteArray(
                Charsets.UTF_8
            ).size} etagPresent=${!response.etag.isNullOrBlank()} metadataTitlePresent=${!response.metadataTitle.isNullOrBlank()} expirationPresent=${response.subscriptionExpiresAt != null}",
        )
        require(!response.notModified) { "subscription did not return a config payload" }
        val parsedSubscription =
            parseFetchedSubscriptionProfiles(
                rawBody = response.body.orEmpty(),
                fallbackName = resolvedPreferredName ?: parsed.displayName,
                settings = settings,
                allowInsecureTlsForProfile = allowInsecureTlsForProfile,
                excludeInsecureTlsOptions = excludeInsecureTlsOptions,
                subscriptionGroup = emptyList(),
            )
        return applyFetchedSubscriptionProfiles(
            sourceUrl = sourceUrl,
            parsed = parsedSubscription,
            response = response,
            targetProfileId = null,
            activateFirstIfNoProfiles = dao.count() == 0,
            subscriptionGroup = emptyList(),
            grantInsecureTlsConsent = allowInsecureTlsForProfile,
        )
    }

    private suspend fun importLocalProfileGroup(
        rawInput: String,
        sourceType: ProfileSourceType,
        parsed: ParsedSubscriptionImport,
        forceRequiresInsecureTls: Boolean,
        grantInsecureTlsConsent: Boolean,
    ): Profile {
        val now = System.currentTimeMillis()
        val count = dao.count()
        val trimmedRawInput = rawInput.trim()
        val preparedProfiles =
            parsed.profiles.mapIndexed { index, importedProfile ->
                val secretRef = UUID.randomUUID().toString()
                val selectedProtocolOptionId =
                    importedProfile.resolveRefreshSelectedProtocolOptionId(
                        previousSecret = null,
                    )
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
                            resolvedConfigJson = importedProfile.resolveNormalizedConfigJson(
                                selectedProtocolOptionId
                            ),
                            subscriptionExpiresAt = importedProfile.subscriptionExpiresAt ?: parsed.subscriptionExpiresAt,
                            protocolOptions = importedProfile.protocolOptions,
                            selectedProtocolOptionId = selectedProtocolOptionId,
                        ).withInsecureTlsMarkers(
                            json = json,
                            forceRequiresInsecureTls =
                            forceRequiresInsecureTls && importedProfile.requiresInsecureTls(json),
                            grantInsecureTlsConsent =
                            grantInsecureTlsConsent && importedProfile.requiresInsecureTls(json),
                        ),
                    ),
                )
            }
        val appliedProfiles =
            executeSerializedSecretFirstMutation(
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
        cleanupProfileSecret(entity.secretRef)
        settingsRepository.removeSmartProfilePreference(profileId)
        persistCachedActiveProfile(nextActiveId?.let { replacementId -> requireProfile(replacementId) })
        diagnosticsLogger.record("profile", "profile deleted")
    }

    internal suspend fun <T> executeSerializedSecretFirstMutation(
        secretStore: ProfileSecretStore,
        stagedWrites: List<StagedProfileSecretWrite>,
        cleanupSecretRefsAfterSuccess: List<String> = emptyList(),
        onCleanupFailure: (secretRef: String, error: Throwable) -> Unit = { _, _ -> },
        mutation: suspend () -> T,
    ): T =
        secretMutationMutex.withLock {
            executeSecretFirstMutation(
                secretStore = secretStore,
                stagedWrites = stagedWrites,
                cleanupSecretRefsAfterSuccess = cleanupSecretRefsAfterSuccess,
                onCleanupFailure = onCleanupFailure,
                mutation = mutation,
            )
        }

    suspend fun setActiveProfile(profileId: Long) {
        database.withTransaction {
            dao.setActiveProfileIfPresent(profileId)
        }
        persistCachedActiveProfile(requireProfile(profileId))
        diagnosticsLogger.record("profile", "active profile changed")
    }

    suspend fun renameProfile(
        profileId: Long,
        name: String,
    ): Profile {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "profile name is blank" }
        dao.updateName(profileId, trimmed)
        val updated = requireProfile(profileId)
        if (updated.isActive) {
            persistCachedActiveProfile(updated)
        }
        diagnosticsLogger.record("profile", "profile renamed")
        return updated
    }

    suspend fun selectProfileProtocolOption(
        profileId: Long,
        optionId: String,
    ): Profile {
        val entity = dao.getById(profileId) ?: error("profile not found")
        val secret = secretStore.read(entity.secretRef) ?: error("profile secret is missing")
        val selected = secret.selectableProtocolOptionOrNull(optionId) ?: error("protocol option not found")
        requireSelectableInsecureTls(secret = secret, option = selected)
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

    suspend fun setProfileProtocolOptionEnabled(
        profileId: Long,
        optionId: String,
        enabled: Boolean,
    ): Profile {
        val entity = dao.getById(profileId) ?: error("profile not found")
        val secret = secretStore.read(entity.secretRef) ?: error("profile secret is missing")
        val allowInsecureTls = allowsInsecureTlsForProfileRuntime(secret)
        val update = secret.protocolOptionEnabledUpdate(optionId, enabled, json, allowInsecureTls)
        secretStore.write(secretRef = entity.secretRef, value = update.secret)
        dao.updateProtocolHint(profileId, update.reselectedOption?.protocolHint?.name ?: entity.protocolHint)
        val updated = requireProfile(profileId)
        if (updated.isActive) {
            persistCachedActiveProfile(updated)
        }
        diagnosticsLogger.record("profile", "profile protocol option enabled toggled")
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

    suspend fun getProfile(profileId: Long): Profile? = dao.getById(
        profileId
    )?.let { entity -> resolveDomainProfile(entity) }

    suspend fun getResolvedConfig(
        profileId: Long,
        protocolOptionIdOverride: String? = null,
    ): String {
        val loaded = loadResolvedConfig(profileId, protocolOptionIdOverride)
        val repaired = repairResolvedConfigIfNeeded(parser = parser, loaded = loaded)
        val effectiveAllowInsecureTls =
            allowsInsecureTlsForStoredProfileRuntime(
                allowInsecureTlsGlobally = loaded.settings.expert.allowInsecureTls,
                secret = loaded.secret,
            )
        val sanitized =
            withContext(Dispatchers.IO) {
                parser.sanitizeResolvedConfig(
                    raw = repaired.runtimeConfig,
                    allowPrivateOutboundHosts = loaded.settings.expert.allowPrivateOutboundHosts,
                    allowInsecureTls = effectiveAllowInsecureTls,
                )
            }
        if (repaired.legacyRawConfigRepaired || sanitized != loaded.resolvedConfig) {
            diagnosticsLogger.record(
                "profile",
                if (repaired.legacyRawConfigRepaired) {
                    "legacy raw resolved config normalized for runtime"
                } else {
                    "resolved config sanitized for runtime"
                },
            )
        }
        return sanitized
    }

    private suspend fun loadResolvedConfig(
        profileId: Long,
        protocolOptionIdOverride: String?,
    ): LoadedResolvedConfig {
        val profile = requireProfile(profileId)
        val secret = secretStore.read(profile.secretRef) ?: error("profile secret is missing")
        val settings = settingsRepository.current()
        val selectedOption = secret.selectedStoredProtocolOptionForRuntime(protocolOptionIdOverride)
        val resolvedConfig =
            selectedOption?.normalizedConfigJson
                ?: secret.resolvedConfigJson
                ?: missingResolvedConfig(profile)
        return LoadedResolvedConfig(
            secret = secret,
            settings = settings,
            selectedOption = selectedOption,
            resolvedConfig = resolvedConfig,
        )
    }

    suspend fun updateResolvedConfig(
        profileId: Long,
        editedJson: String,
        protocolOptionIdOverride: String? = null,
    ) {
        val entity = dao.getById(profileId) ?: error("profile not found")
        val secret = secretStore.read(entity.secretRef) ?: error("profile secret is missing")
        val settings = settingsRepository.current()
        val effectiveAllowInsecureTls =
            allowsInsecureTlsForStoredProfileRuntime(
                allowInsecureTlsGlobally = settings.expert.allowInsecureTls,
                secret = secret,
            )
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

    @Suppress("CyclomaticComplexMethod")
    internal suspend fun persistCachedActiveProfile(profile: Profile?) {
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

    internal suspend fun resolveDomainProfile(entity: ProfileEntity): Profile =
        entity.toResolvedDomainProfile(secret = secretStore.read(entity.secretRef), json = json)

    internal suspend fun requireProfile(profileId: Long): Profile =
        dao.getById(profileId)?.let { entity -> resolveDomainProfile(entity) } ?: error("profile not found")
}

private suspend fun ProfileRepository.allowsInsecureTlsForProfileRuntime(secret: StoredProfileSecret): Boolean =
    allowsInsecureTlsForStoredProfileRuntime(
        allowInsecureTlsGlobally = settingsRepository.current().expert.allowInsecureTls,
        secret = secret,
    )

private suspend fun ProfileRepository.requireSelectableInsecureTls(
    secret: StoredProfileSecret,
    option: StoredProfileProtocolOption,
) {
    if (option.requiresInsecureTlsForRuntime(json) && !allowsInsecureTlsForProfileRuntime(secret)) {
        throw InsecureTlsProfileConsentRequiredException()
    }
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
            allowInsecureTls = allowInsecureTls,
        ).normalizedConfigJson
    }.getOrNull()?.takeIf(String::isNotBlank)
}
