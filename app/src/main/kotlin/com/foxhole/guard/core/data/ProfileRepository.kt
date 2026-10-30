package com.foxhole.guard.core.data

import com.foxhole.core.importer.ProfileImportParser
import com.foxhole.core.model.CachedActiveProfile
import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.runtime.I2pdManager
import com.foxhole.core.runtime.RuntimeConfigAssembler
import com.foxhole.core.runtime.TorRuntimeInstaller
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.core.settings.ensureNewAppQuarantineBaseline
import com.foxhole.guard.runtime.DnsFilterAssetInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    internal val secretMutationMutex get() = mutationCoordinator.secretMutex
    internal val mutationCoordinator = settingsRepository.profileMutationCoordinator
    internal val profileImportMutex get() = mutationCoordinator.mutex
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
            profileProvider = { id -> getProfile(id) ?: error("profile not found") },
            secretProvider = secretStore::read,
            resolvedConfigProvider = ::getResolvedConfig,
        )
    }

    val profiles: Flow<List<Profile>> =
        flow {
            awaitDatabaseReady()
            emitAll(
                dao.observeProfiles().map {
                    mutationCoordinator.readCommitted {
                        dao.getAllProfiles().map { entity -> resolveDomainProfile(entity) }
                    }
                }
            )
        }.flowOn(Dispatchers.IO)
    val activeProfile: Flow<Profile?> =
        flow {
            awaitDatabaseReady()
            emitAll(
                combine(dao.observeProfiles(), dao.observeActiveProfile()) { _, _ ->
                    mutationCoordinator.readCommitted {
                        val resolvedProfiles = dao.getAllProfiles().map { entity -> resolveDomainProfile(entity) }
                        val active = dao.getActiveProfile()
                        active?.id?.let { activeId -> resolvedProfiles.firstOrNull { it.id == activeId } }
                            ?: resolvedProfiles.firstOrNull()
                    }
                },
            )
        }.flowOn(Dispatchers.IO)

    internal suspend fun <T> withProfileMutation(block: suspend (ProfileMutationLease) -> T): T =
        mutationCoordinator.write(
            onEffectFailure = { error ->
                diagnosticsLogger.recordFailure(
                    "profile",
                    "post-commit effect failed error=${error.javaClass.simpleName}",
                )
            },
            block = block,
        )

    suspend fun importProfile(
        rawInput: String,
        preferredName: String? = null,
        allowInsecureTlsForProfile: Boolean = false,
        excludeInsecureTlsOptions: Boolean = false,
    ): Profile {
        val prepared = prepareProfileImport(
            rawInput,
            preferredName,
            allowInsecureTlsForProfile,
            excludeInsecureTlsOptions
        )
        return withProfileMutation { owner -> applyPreparedImport(validateImportForCommit(prepared, owner)) }
    }

    internal suspend fun importPreparedSingleLocked(prepared: PreparedProfileImport, owner: ProfileMutationLease): Profile {
        owner.requireOwner(mutationCoordinator)
        val parsed = requireNotNull(prepared.parsed)
        val secretRef = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val count = dao.count()
        val entity =
            ProfileEntity(
                name = prepared.preferredName ?: parsed.displayName,
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
                    rawInput = prepared.rawInput.trim(),
                    subscriptionUrl = parsed.sourceUrl,
                    resolvedConfigJson = parsed.normalizedConfigJson,
                    subscriptionExpiresAt = parsed.subscriptionExpiresAt,
                    protocolOptions = parsed.protocolOptions,
                    selectedProtocolOptionId = parsed.selectedProtocolOptionId,
                ).withInsecureTlsMarkers(
                    json = json,
                    forceRequiresInsecureTls = prepared.grantInsecureTlsConsent || parsed.requiresInsecureTls(json),
                    grantInsecureTlsConsent = prepared.grantInsecureTlsConsent && parsed.requiresInsecureTls(json),
                ),
            )
        val id =
            executeSerializedSecretFirstMutation(
                owner = owner,
                secretStore = secretStore,
                stagedWrites = listOf(stagedSecretWrite),
                onCleanupFailure = ::recordSecretCleanupFailure,
            ) {
                owner.transaction(database) {
                    if (entity.isActive) {
                        dao.clearActive()
                    }
                    dao.insert(entity)
                }
            }
        val saved = requireProfile(id)
        if (saved.isActive) {
            persistCachedActiveProfile(saved, owner)
        }
        diagnosticsLogger.record("profile", "profile imported")
        return saved
    }
    internal suspend fun importLocalProfileGroupLocked(
        rawInput: String,
        sourceType: ProfileSourceType,
        parsed: ParsedSubscriptionImport,
        forceRequiresInsecureTls: Boolean,
        grantInsecureTlsConsent: Boolean,
        owner: ProfileMutationLease,
    ): Profile {
        owner.requireOwner(mutationCoordinator)
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
                owner = owner,
                secretStore = secretStore,
                stagedWrites = preparedProfiles.map(PreparedLocalImportProfile::stagedSecretWrite),
                onCleanupFailure = ::recordSecretCleanupFailure,
            ) {
                val committedProfiles = mutableListOf<AppliedSubscriptionProfile>()
                owner.transaction(database) {
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
            persistCachedActiveProfile(saved, owner)
        }
        diagnosticsLogger.record("profile", "profile imported: ${parsed.profiles.size} profiles")
        return saved
    }

    suspend fun deleteProfile(profileId: Long) = withProfileMutation { owner ->
        owner.secrets(secretMutationMutex) {
            withContext(NonCancellable) {
                val entity = dao.getById(profileId) ?: return@withContext
                var nextActiveId: Long? = null
                owner.transaction(database) {
                    dao.delete(profileId)
                    if (entity.isActive) {
                        val replacementId = dao.getMostRecentProfileId()
                        if (replacementId != null) {
                            dao.setActive(replacementId)
                            nextActiveId = replacementId
                        }
                    }
                }
                owner.markLocalCommit()
                owner.recordingSecrets(secretStore).delete(entity.secretRef)
                owner.updateSettings(settingsRepository) { current ->
                    current.copy(
                        smartProfilePreferences = current.smartProfilePreferences.filterNot { it.profileId == profileId }
                    )
                }
                persistCachedActiveProfile(nextActiveId?.let { replacementId -> requireProfile(replacementId) }, owner)
                diagnosticsLogger.record("profile", "profile deleted")
            }
        }
    }

    internal suspend fun <T> executeSerializedSecretFirstMutation(
        owner: ProfileMutationLease,
        secretStore: ProfileSecretStore,
        stagedWrites: List<StagedProfileSecretWrite>,
        cleanupSecretRefsAfterSuccess: List<String> = emptyList(),
        onCleanupFailure: (secretRef: String, error: Throwable) -> Unit = { _, _ -> },
        mutation: suspend () -> T,
    ): T = owner.secrets(secretMutationMutex) {
        executeSecretFirstMutation(
            secretStore = owner.recordingSecrets(secretStore),
            stagedWrites = stagedWrites,
            cleanupSecretRefsAfterSuccess = cleanupSecretRefsAfterSuccess,
            onCleanupFailure = { ref, error -> owner.afterCommit { onCleanupFailure(ref, error) } },
        ) {
            mutation().also { owner.markLocalCommit() }
        }
    }

    suspend fun setActiveProfile(profileId: Long) = withProfileMutation { owner ->
        setActiveProfileLocked(profileId, owner)
    }

    internal suspend fun setActiveProfileLocked(profileId: Long, owner: ProfileMutationLease) {
        owner.requireOwner(mutationCoordinator)
        withContext(NonCancellable) {
            owner.transaction(database) { dao.setActiveProfileIfPresent(profileId) }
            owner.markLocalCommit()
            persistCachedActiveProfile(requireProfile(profileId), owner)
            diagnosticsLogger.record("profile", "active profile changed")
        }
    }

    suspend fun renameProfile(
        profileId: Long,
        name: String,
    ): Profile = withProfileMutation { owner ->
        owner.secrets(secretMutationMutex) {
            withContext(NonCancellable) {
                val trimmed = name.trim()
                require(trimmed.isNotBlank()) { "profile name is blank" }
                dao.updateName(profileId, trimmed)
                owner.markLocalCommit()
                val updated = requireProfile(profileId)
                if (updated.isActive) {
                    persistCachedActiveProfile(updated, owner)
                }
                diagnosticsLogger.record("profile", "profile renamed")
                updated
            }
        }
    }

    suspend fun selectProfileProtocolOption(
        profileId: Long,
        optionId: String,
    ): Profile = withProfileMutation { owner ->
        selectProfileProtocolOptionLocked(profileId, optionId, owner)
    }

    internal suspend fun selectProfileProtocolOptionLocked(
        profileId: Long,
        optionId: String,
        owner: ProfileMutationLease,
    ): Profile = owner.secrets(secretMutationMutex) {
        withContext(NonCancellable) {
            val entity = dao.getById(profileId) ?: error("profile not found")
            val secret = secretStore.read(entity.secretRef) ?: error("profile secret is missing")
            val selected = secret.selectableProtocolOptionOrNull(optionId) ?: error("protocol option not found")
            requireSelectableInsecureTls(secret = secret, option = selected)
            persistProtocolSelection(
                owner,
                entity.secretRef,
                secret,
                secret.copy(selectedProtocolOptionId = selected.id)
            ) {
                dao.updateProtocolHint(profileId, selected.protocolHint.name)
            }
            val updated = requireProfile(profileId)
            if (updated.isActive) {
                persistCachedActiveProfile(updated, owner)
            }
            diagnosticsLogger.record("profile", "profile protocol option changed")
            updated
        }
    }

    suspend fun setProfileProtocolOptionEnabled(
        profileId: Long,
        optionId: String,
        enabled: Boolean,
    ): Profile = withProfileMutation { owner ->
        setProfileProtocolOptionEnabledLocked(profileId, optionId, enabled, owner)
    }

    internal suspend fun setProfileProtocolOptionEnabledLocked(
        profileId: Long,
        optionId: String,
        enabled: Boolean,
        owner: ProfileMutationLease,
    ): Profile = owner.secrets(secretMutationMutex) {
        withContext(NonCancellable) {
            val entity = dao.getById(profileId) ?: error("profile not found")
            val secret = secretStore.read(entity.secretRef) ?: error("profile secret is missing")
            val allowInsecureTls = allowsInsecureTlsForProfileRuntime(secret)
            val update = secret.protocolOptionEnabledUpdate(optionId, enabled, json, allowInsecureTls)
            persistProtocolSelection(owner, entity.secretRef, secret, update.secret) {
                dao.updateProtocolHint(profileId, update.reselectedOption?.protocolHint?.name ?: entity.protocolHint)
            }
            val updated = requireProfile(profileId)
            if (updated.isActive) {
                persistCachedActiveProfile(updated, owner)
            }
            diagnosticsLogger.record("profile", "profile protocol option enabled toggled")
            updated
        }
    }

    private suspend fun persistProtocolSelection(
        owner: ProfileMutationLease,
        secretRef: String,
        original: StoredProfileSecret,
        updated: StoredProfileSecret,
        metadata: suspend () -> Unit,
    ) {
        try {
            owner.recordingSecrets(secretStore).write(secretRef, updated)
            metadata()
            owner.markLocalCommit()
        } catch (error: Throwable) {
            runCatching { owner.recordingSecrets(secretStore).write(secretRef, original) }.onFailure {
                diagnosticsLogger.recordFailure("profile", "protocol selection rollback failed")
            }
            throw error
        }
    }

    suspend fun getActiveProfile(): Profile? = withProfileMutation { owner ->
        ensureActiveProfileInvariantLocked(owner)
        val activeEntity = dao.getActiveProfile()
        val active =
            if (activeEntity != null) {
                resolveDomainProfile(activeEntity)
            } else {
                dao.getMostRecentProfileId()?.let { profileId -> requireProfile(profileId) }
            }
        persistCachedActiveProfile(active, owner)
        active
    }

    suspend fun ensureActiveProfileInvariant() = withProfileMutation { owner ->
        ensureActiveProfileInvariantLocked(
            owner
        )
    }

    private suspend fun ensureActiveProfileInvariantLocked(owner: ProfileMutationLease) {
        var recovered = false
        owner.transaction(database) {
            if (dao.getActiveProfile() != null) {
                return@transaction
            }
            val replacementId = dao.getMostRecentProfileId() ?: return@transaction
            dao.clearActive()
            dao.setActive(replacementId)
            recovered = true
            owner.markLocalCommit()
        }
        if (recovered) {
            persistCachedActiveProfile(dao.getActiveProfile()?.let { entity -> resolveDomainProfile(entity) }, owner)
            diagnosticsLogger.record("profile", "active profile recovered")
        }
    }

    suspend fun getProfile(profileId: Long): Profile? = mutationCoordinator.readCommitted {
        dao.getById(profileId)?.let { entity -> resolveDomainProfile(entity) }
    }

    suspend fun getResolvedConfig(
        profileId: Long,
        protocolOptionIdOverride: String? = null,
    ): String {
        val loaded = mutationCoordinator.readCommitted { loadResolvedConfig(profileId, protocolOptionIdOverride) }
        return resolveLoadedConfig(loaded)
    }

    private suspend fun resolveLoadedConfig(loaded: LoadedResolvedConfig): String {
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

    internal suspend fun prepareSessionSnapshot(
        profileId: Long,
        optionId: String?,
    ): PreparedProfileSessionSnapshot {
        settingsRepository.ensureNewAppQuarantineBaseline()
        val captured = mutationCoordinator.readCommitted {
            val entity = dao.getById(profileId) ?: error("profile not found")
            val secret = secretStore.read(entity.secretRef) ?: error("profile secret is missing")
            val settings = settingsRepository.current()
            val selected = secret.selectedStoredProtocolOptionForRuntime(optionId)
            val loaded = LoadedResolvedConfig(
                secret,
                settings,
                selected,
                selected?.normalizedConfigJson ?: secret.resolvedConfigJson ?: missingResolvedConfig(entity.toDomain()),
            )
            entity.toResolvedDomainProfile(secret, json) to loaded
        }
        return PreparedProfileSessionSnapshot(
            captured.first,
            captured.second.secret,
            captured.second.settings,
            optionId,
            resolveLoadedConfig(captured.second),
        )
    }

    suspend fun updateResolvedConfig(
        profileId: Long,
        editedJson: String,
        protocolOptionIdOverride: String? = null,
    ) {
        updateProfileEditor(profileId, null, listOf(ProfileEditorConfigUpdate(protocolOptionIdOverride, editedJson)))
    }

    @Suppress("CyclomaticComplexMethod")
    internal suspend fun persistCachedActiveProfile(profile: Profile?, owner: ProfileMutationLease) {
        val cached = profile?.let {
            CachedActiveProfile(it.id, it.name, it.sourceType, it.protocolHint)
        }
        owner.updateSettings(settingsRepository) { current -> current.copy(lastActiveProfile = cached) }
    }
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
