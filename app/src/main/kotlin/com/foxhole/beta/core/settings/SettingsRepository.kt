package com.foxhole.beta.core.settings

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.CachedActiveProfile
import com.foxhole.beta.core.model.ConnectionSettings
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.DomainStrategy
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.NETWORK_FINGERPRINT_SCHEMA_CURRENT
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.ProfileTrafficTotal
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.SETTINGS_SCHEMA_VERSION
import com.foxhole.beta.core.model.SmartProfileNetworkMemory
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSettings
import com.foxhole.beta.core.model.TunStack
import com.foxhole.beta.core.model.UiSettings
import com.foxhole.beta.core.model.V2RayApiSettings
import com.foxhole.beta.core.network.ensurePublicHttpsUrl
import com.foxhole.beta.core.security.AndroidKeystoreFileCipher
import com.foxhole.beta.core.security.readBytesMigratingLegacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class SettingsRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    private val fileCipher = AndroidKeystoreFileCipher("foxhole.settings")
    private val settingsDir = File(appContext.filesDir, "settings").apply { mkdirs() }
    private val settingsFile = File(settingsDir, "settings.json")
    private val legacySettingsFile = appContext.preferencesDataStoreFile("foxhole_settings.preferences_pb")
    private val fastUiPreferences = appContext.getSharedPreferences(FAST_UI_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val settingsMutable: MutableStateFlow<Settings>
    private val themeModeMutable: MutableStateFlow<ThemeMode>
    @Volatile
    private var initializationResult: Result<Settings>? = null

    val settings: StateFlow<Settings>
        get() = settingsMutable

    val themeMode: StateFlow<ThemeMode>
        get() = themeModeMutable

    init {
        val bootstrapSettings = bootstrapInitialSettings()
        settingsMutable = MutableStateFlow(bootstrapSettings)
        themeModeMutable = MutableStateFlow(bootstrapSettings.ui.themeMode)
    }

    suspend fun warmUp(): Settings {
        ensureInitialized()
        return settingsMutable.value
    }

    suspend fun current(): Settings {
        ensureInitialized()
        return settingsMutable.value
    }

    internal suspend fun replaceForTests(value: Settings) {
        lock.withLock {
            val normalized = value.normalized()
            withContext(Dispatchers.IO) {
                writeEncrypted(normalized)
            }
            initializationResult = Result.success(normalized)
            settingsMutable.value = normalized
            themeModeMutable.value = normalized.ui.themeMode
            writeFastThemeMode(normalized.ui.themeMode)
            writeFastLocale(normalized.ui.locale)
        }
    }

    suspend fun updateThemeMode(value: ThemeMode) {
        ensureInitialized()
        writeFastThemeMode(value)
        themeModeMutable.value = value
        update { it.copy(ui = it.ui.copy(themeMode = value)) }
    }

    suspend fun updateLocale(value: AppLocale) {
        ensureInitialized()
        writeFastLocale(value)
        update { it.copy(ui = it.ui.copy(locale = value)) }
    }

    suspend fun updateSupportBotHandleOverride(value: String?) =
        update {
            it.copy(
                ui =
                    it.ui.copy(
                        supportBotHandleOverride = storedSupportBotHandleOverride(value),
                    ),
            )
        }

    suspend fun updateShowExpertSettings(value: Boolean) =
        update { current -> current.withExpertSettingsVisibility(value) }

    suspend fun completeOnboarding() =
        update { it.copy(ui = it.ui.copy(onboardingCompleted = true)) }

    suspend fun updateAutoReconnect(value: Boolean) =
        update { it.copy(connection = it.connection.copy(autoReconnect = value)) }

    suspend fun updateAutoStartOnBoot(value: Boolean) =
        update { it.copy(connection = it.connection.copy(autoStartOnBoot = value)) }

    suspend fun updateAutoRefreshSubscriptions(value: Boolean) =
        update { it.copy(connection = it.connection.copy(autoRefreshSubscriptions = value)) }

    suspend fun updateStealthModeEnabled(value: Boolean) =
        update { current ->
            if (value) {
                current.copy(
                    connection = current.connection.copy(stealthModeEnabled = true),
                    traffic = TrafficSettings(),
                    expert =
                        ExpertSettings(
                            unlockedAt = current.expert.unlockedAt,
                        warningAcknowledgedAt = current.expert.warningAcknowledgedAt,
                        blockScreenshots = current.expert.blockScreenshots,
                        networkActivityLogging = current.expert.networkActivityLogging,
                        diagnosticsRetention = current.expert.diagnosticsRetention,
                        smartStartReplayLogging = current.expert.smartStartReplayLogging,
                        allowHttpConfigImports = current.expert.allowHttpConfigImports,
                        allowInsecureTls = current.expert.allowInsecureTls,
                    ),
                )
            } else {
                current.copy(connection = current.connection.copy(stealthModeEnabled = false))
            }
        }

    suspend fun updateIpInfoEndpoint(value: String) =
        update {
            it.copy(
                connection =
                    it.connection.copy(
                        ipInfoEndpoint = normalizeIpInfoEndpoint(value),
                    ),
            )
        }

    suspend fun updateLastActiveProfile(value: CachedActiveProfile?) =
        update { current ->
            if (current.lastActiveProfile == value) {
                current
            } else {
                current.copy(lastActiveProfile = value)
            }
        }

    suspend fun updateSmartProfileExcludedProtocolOptionIds(
        profileId: Long,
        excludedProtocolOptionIds: Set<String>,
    ) = update { current ->
        val normalizedIds =
            excludedProtocolOptionIds
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
        val existing = current.smartProfilePreference(profileId) ?: SmartProfilePreference(profileId = profileId)
        current.withSmartProfilePreference(
            existing.copy(
                excludedProtocolOptionIds = normalizedIds,
            ),
        )
    }

    suspend fun recordSmartProfileProbeResult(
        profileId: Long,
        optionId: String,
        latencyMs: Long?,
        success: Boolean,
        reasonCode: AutoConnectReasonCode? = null,
        markAsLastKnownGood: Boolean = false,
        networkFingerprint: String? = null,
        recordedAt: Long = System.currentTimeMillis(),
        connectDurationMs: Long? = null,
        validatedAt: Long? = null,
        trafficObservedAt: Long? = null,
        countTowardOutcomeHistory: Boolean = true,
        affectsFailureRankingMemory: Boolean = true,
    ) = update { current ->
        val normalizedOptionId = optionId.trim().takeIf(String::isNotBlank) ?: return@update current
        val normalizedLatencyMs = latencyMs?.coerceAtLeast(1L)
        val normalizedNetworkFingerprint = networkFingerprint?.trim()?.takeIf(String::isNotBlank)
        val existing = current.smartProfilePreference(profileId) ?: SmartProfilePreference(profileId = profileId)
        val globalMemoryUpdate =
            recordProbeResultIntoMemory(
                lastKnownGoodOptionId = existing.lastKnownGoodOptionId,
                lastKnownGoodLatencyMs = existing.lastKnownGoodLatencyMs,
                lastKnownGoodAt = existing.lastKnownGoodAt,
                protocolMemories = existing.protocolMemories,
                optionId = normalizedOptionId,
                latencyMs = normalizedLatencyMs,
                success = success,
                reasonCode = reasonCode,
                markAsLastKnownGood = markAsLastKnownGood,
                recordedAt = recordedAt,
                connectDurationMs = connectDurationMs,
                validatedAt = validatedAt,
                trafficAt = trafficObservedAt,
                countTowardOutcomeHistory = countTowardOutcomeHistory,
                affectsFailureRankingMemory = affectsFailureRankingMemory,
            )
        val updatedNetworkMemories =
            normalizedNetworkFingerprint?.let { fingerprint ->
                val currentNetworkMemories = existing.networkMemories.associateBy(SmartProfileNetworkMemory::networkFingerprint).toMutableMap()
                val previousNetworkMemory =
                    currentNetworkMemories[fingerprint]
                        ?.takeIf { memory -> memory.networkFingerprintSchema == NETWORK_FINGERPRINT_SCHEMA_CURRENT }
                        ?: SmartProfileNetworkMemory(
                            networkFingerprint = fingerprint,
                            networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
                        )
                val scopedMemoryUpdate =
                    recordProbeResultIntoMemory(
                        lastKnownGoodOptionId = previousNetworkMemory.lastKnownGoodOptionId,
                        lastKnownGoodLatencyMs = previousNetworkMemory.lastKnownGoodLatencyMs,
                        lastKnownGoodAt = previousNetworkMemory.lastKnownGoodAt,
                        protocolMemories = previousNetworkMemory.protocolMemories,
                        optionId = normalizedOptionId,
                        latencyMs = normalizedLatencyMs,
                        success = success,
                        reasonCode = reasonCode,
                        markAsLastKnownGood = markAsLastKnownGood,
                        recordedAt = recordedAt,
                        connectDurationMs = connectDurationMs,
                        validatedAt = validatedAt,
                        trafficAt = trafficObservedAt,
                        countTowardOutcomeHistory = countTowardOutcomeHistory,
                        affectsFailureRankingMemory = affectsFailureRankingMemory,
                    )
                currentNetworkMemories[fingerprint] =
                    previousNetworkMemory.copy(
                        lastKnownGoodOptionId = scopedMemoryUpdate.lastKnownGoodOptionId,
                        lastKnownGoodLatencyMs = scopedMemoryUpdate.lastKnownGoodLatencyMs,
                        lastKnownGoodAt = scopedMemoryUpdate.lastKnownGoodAt,
                        protocolMemories = scopedMemoryUpdate.protocolMemories,
                    )
                currentNetworkMemories.values.sortedBy(SmartProfileNetworkMemory::networkFingerprint)
            } ?: existing.networkMemories
        current.withSmartProfilePreference(
            existing.copy(
                lastKnownGoodOptionId = globalMemoryUpdate.lastKnownGoodOptionId,
                lastKnownGoodLatencyMs = globalMemoryUpdate.lastKnownGoodLatencyMs,
                lastKnownGoodAt = globalMemoryUpdate.lastKnownGoodAt,
                protocolMemories = globalMemoryUpdate.protocolMemories,
                networkMemories = updatedNetworkMemories,
            ),
        )
    }

    suspend fun recordSmartProfileServerPing(
        profileId: Long,
        optionId: String,
        serverPingMs: Long,
        networkFingerprint: String? = null,
        recordedAt: Long = System.currentTimeMillis(),
    ) = update { current ->
        val normalizedOptionId = optionId.trim().takeIf(String::isNotBlank) ?: return@update current
        val normalizedServerPingMs = serverPingMs.coerceAtLeast(1L)
        val normalizedNetworkFingerprint = networkFingerprint?.trim()?.takeIf(String::isNotBlank)
        val existing = current.smartProfilePreference(profileId) ?: SmartProfilePreference(profileId = profileId)
        fun recordInto(memories: List<SmartProfileProtocolMemory>): List<SmartProfileProtocolMemory> {
            val currentMemories = memories.associateBy(SmartProfileProtocolMemory::optionId).toMutableMap()
            val previous = currentMemories[normalizedOptionId]
            currentMemories[normalizedOptionId] =
                (previous ?: SmartProfileProtocolMemory(optionId = normalizedOptionId)).copy(
                    lastServerPingMs = normalizedServerPingMs,
                    lastServerPingAt = recordedAt.takeIf { it > 0L },
                )
            return currentMemories.values.sortedBy(SmartProfileProtocolMemory::optionId)
        }
        val updatedNetworkMemories =
            normalizedNetworkFingerprint?.let { fingerprint ->
                val currentNetworkMemories = existing.networkMemories.associateBy(SmartProfileNetworkMemory::networkFingerprint).toMutableMap()
                val previousNetworkMemory =
                    currentNetworkMemories[fingerprint]
                        ?.takeIf { memory -> memory.networkFingerprintSchema == NETWORK_FINGERPRINT_SCHEMA_CURRENT }
                        ?: SmartProfileNetworkMemory(
                            networkFingerprint = fingerprint,
                            networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
                        )
                currentNetworkMemories[fingerprint] =
                    previousNetworkMemory.copy(
                        protocolMemories = recordInto(previousNetworkMemory.protocolMemories),
                    )
                currentNetworkMemories.values.sortedBy(SmartProfileNetworkMemory::networkFingerprint)
            } ?: existing.networkMemories
        current.withSmartProfilePreference(
            existing.copy(
                protocolMemories = recordInto(existing.protocolMemories),
                networkMemories = updatedNetworkMemories,
            ),
        )
    }

    suspend fun recordSmartProfileBaseline(
        profileId: Long,
        recommendedProtocolIds: List<String>,
        enabledProtocolSetHash: String,
        refreshedAt: Long = System.currentTimeMillis(),
    ) = update { current ->
        val normalizedRecommendedIds =
            recommendedProtocolIds
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(SMART_START_RECOMMENDED_LIMIT)
        val normalizedHash = enabledProtocolSetHash.trim().takeIf(String::isNotBlank) ?: return@update current
        val existing = current.smartProfilePreference(profileId) ?: SmartProfilePreference(profileId = profileId)
        current.withSmartProfilePreference(
            existing.copy(
                lastFullSmartRefreshAt = refreshedAt.takeIf { it > 0L },
                smartStartBaselineReady = normalizedRecommendedIds.isNotEmpty(),
                recommendedProtocolIds = normalizedRecommendedIds,
                enabledProtocolSetHash = normalizedHash,
                networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
            ),
        )
    }

    suspend fun accumulateProfileTraffic(
        profileId: Long,
        profileName: String,
        protocolHint: ProtocolHint,
        rxBytes: Long,
        txBytes: Long,
        updatedAt: Long = System.currentTimeMillis(),
    ) = update { current ->
        if (rxBytes <= 0L && txBytes <= 0L) {
            current
        } else {
            val existing = current.profileTrafficTotals.associateBy(ProfileTrafficTotal::profileId).toMutableMap()
            val previous = existing[profileId]
            existing[profileId] =
                ProfileTrafficTotal(
                    profileId = profileId,
                    profileName = profileName,
                    protocolHint = protocolHint,
                    rxTotalBytes = (previous?.rxTotalBytes ?: 0L) + rxBytes.coerceAtLeast(0L),
                    txTotalBytes = (previous?.txTotalBytes ?: 0L) + txBytes.coerceAtLeast(0L),
                    updatedAt = updatedAt,
                )
            current.copy(profileTrafficTotals = existing.values.sortedByDescending(ProfileTrafficTotal::updatedAt))
        }
    }

    suspend fun updateTunStack(value: TunStack) =
        update { it.copy(traffic = it.traffic.copy(tunStack = value)) }

    suspend fun updateTrafficMode(value: TrafficMode) =
        update { current ->
            current.copy(
                connection =
                    current.connection.copy(
                        stealthModeEnabled = current.connection.stealthModeEnabled && value == TrafficMode.TUNNEL,
                    ),
                traffic = current.traffic.copy(mode = value),
                expert =
                    current.expert.copy(
                        perAppRoutingMode = PerAppRoutingMode.FULL_TUNNEL,
                        selectedPackages = emptyList(),
                        localSurfaces =
                            current.expert.localSurfaces.withProxyModeDefaults(
                                enableDefaults = value == TrafficMode.PROXY,
                            ),
                    ),
            )
        }

    suspend fun updateTrafficMtu(value: Int) =
        update { it.copy(traffic = it.traffic.copy(mtu = value.coerceIn(MIN_MTU, MAX_MTU))) }

    suspend fun updatePreferIpv6(value: Boolean) =
        update { it.copy(traffic = it.traffic.copy(preferIpv6 = value)) }

    suspend fun updateDomainStrategy(value: DomainStrategy) =
        update { it.copy(traffic = it.traffic.copy(domainStrategy = value)) }

    suspend fun unlockExpertSettings(timestamp: Long = System.currentTimeMillis()) =
        update {
            it.copy(
                ui = it.ui.copy(showExpertSettings = true),
                expert = it.expert.copy(unlockedAt = timestamp),
            )
        }

    suspend fun acknowledgeUnsafeWarning(timestamp: Long = System.currentTimeMillis()) =
        update { it.copy(expert = it.expert.copy(warningAcknowledgedAt = timestamp)) }

    suspend fun updateBlockScreenshots(value: Boolean) =
        update { it.copy(expert = it.expert.copy(blockScreenshots = value)) }

    suspend fun updateNetworkActivityLogging(value: Boolean) =
        update { it.copy(expert = it.expert.copy(networkActivityLogging = value)) }

    suspend fun updateSmartStartReplayLogging(value: Boolean) =
        update { it.copy(expert = it.expert.copy(smartStartReplayLogging = value)) }

    suspend fun updateDiagnosticsRetention(value: DiagnosticsRetention) =
        update { it.copy(expert = it.expert.copy(diagnosticsRetention = value)) }

    suspend fun updateAllowHttpConfigImports(value: Boolean) =
        update { it.copy(expert = it.expert.copy(allowHttpConfigImports = value)) }

    suspend fun updateAllowInsecureTls(value: Boolean) =
        update { it.copy(expert = it.expert.copy(allowInsecureTls = value)) }

    suspend fun updateSniff(value: Boolean) =
        update { current ->
            current.copy(
                connection = current.connection.copy(stealthModeEnabled = current.connection.stealthModeEnabled && !value),
                expert = current.expert.copy(sniff = value, routeOnly = if (value) current.expert.routeOnly else false),
            )
        }

    suspend fun updateRouteOnly(value: Boolean) =
        update { current ->
            current.copy(
                connection = current.connection.copy(stealthModeEnabled = current.connection.stealthModeEnabled && !value),
                expert = current.expert.copy(routeOnly = value, sniff = current.expert.sniff || value),
            )
        }

    suspend fun updateStrictRoute(value: Boolean) =
        update {
            it.copy(
                connection = it.connection.copy(stealthModeEnabled = it.connection.stealthModeEnabled && value),
                expert = it.expert.copy(strictRoute = value),
            )
        }

    suspend fun updateBypassLan(value: Boolean) =
        update {
            it.copy(
                connection = it.connection.copy(stealthModeEnabled = it.connection.stealthModeEnabled && !value),
                expert = it.expert.copy(bypassLan = value),
            )
        }

    suspend fun updateAllowPrivateOutboundHosts(value: Boolean) =
        update { current ->
            current.copy(
                connection = current.connection.copy(stealthModeEnabled = current.connection.stealthModeEnabled && !value),
                expert = current.expert.copy(allowPrivateOutboundHosts = value),
            )
        }

    suspend fun updatePerAppRoutingMode(value: PerAppRoutingMode) =
        update { current ->
            current.copy(
                connection =
                    current.connection.copy(
                        stealthModeEnabled =
                            current.connection.stealthModeEnabled && value == PerAppRoutingMode.FULL_TUNNEL,
                    ),
                expert =
                    current.expert.copy(
                        perAppRoutingMode = value,
                        selectedPackages =
                            current.expert.selectedPackages.takeIf { value != PerAppRoutingMode.FULL_TUNNEL } ?: emptyList(),
                    ),
            )
        }

    suspend fun updateSelectedPackages(value: List<String>) =
        update {
            it.copy(
                expert =
                    it.expert.copy(
                        selectedPackages =
                            value
                                .filterNot { packageName -> packageName == BuildConfig.APPLICATION_ID }
                                .distinct()
                                .sorted(),
                    ),
            )
        }

    suspend fun updateSocksSurface(value: ProxyInboundSettings) =
        update {
            it.copy(
                connection = it.connection.copy(stealthModeEnabled = it.connection.stealthModeEnabled && !value.enabled),
                expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(socks = value.normalized())),
            )
        }

    suspend fun updateHttpSurface(value: ProxyInboundSettings) =
        update {
            it.copy(
                connection = it.connection.copy(stealthModeEnabled = it.connection.stealthModeEnabled && !value.enabled),
                expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(http = value.normalized())),
            )
        }

    suspend fun updateMixedSurface(value: ProxyInboundSettings) =
        update {
            it.copy(
                connection = it.connection.copy(stealthModeEnabled = it.connection.stealthModeEnabled && !value.enabled),
                expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(mixed = value.normalized())),
            )
        }

    suspend fun updateLocalProxyAuthEnabled(value: Boolean) =
        update {
            it.copy(
                connection = it.connection.copy(stealthModeEnabled = it.connection.stealthModeEnabled && value),
                expert =
                    it.expert.copy(
                        localSurfaces =
                            it.expert.localSurfaces.copy(
                                auth = it.expert.localSurfaces.auth.copy(enabled = value),
                            ),
                    ),
            )
        }

    suspend fun updateLocalProxyAuth(value: LocalAuthSettings) =
        update {
            it.copy(
                connection = it.connection.copy(stealthModeEnabled = it.connection.stealthModeEnabled && value.enabled),
                expert =
                    it.expert.copy(
                        localSurfaces =
                            it.expert.localSurfaces.copy(
                                auth = value.normalized(),
                            ),
                    ),
            )
        }

    suspend fun updateLocalProxyLanAccessEnabled(value: Boolean) =
        update {
            it.copy(
                connection = it.connection.copy(stealthModeEnabled = it.connection.stealthModeEnabled && !value),
                expert =
                    it.expert.copy(
                        localSurfaces =
                            it.expert.localSurfaces.copy(
                                allowLanAccess = value,
                            ),
                    ),
            )
        }

    suspend fun updateClashApi(value: ClashApiSettings) =
        update {
            it.copy(
                connection = it.connection.copy(stealthModeEnabled = it.connection.stealthModeEnabled && !value.enabled),
                expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(clashApi = value.normalized())),
            )
        }

    suspend fun updateV2RayApi(value: V2RayApiSettings) =
        update {
            it.copy(
                connection =
                    it.connection.copy(
                        stealthModeEnabled =
                            it.connection.stealthModeEnabled && !(value.enabled || value.statsEnabled),
                    ),
                expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(v2RayApi = value.normalized())),
            )
        }

    suspend fun resetExpertToSafeDefaults() =
        update { current ->
            current.copy(
                connection = current.connection.copy(stealthModeEnabled = true),
                traffic = TrafficSettings(),
                expert =
                    ExpertSettings(
                        unlockedAt = current.expert.unlockedAt,
                        warningAcknowledgedAt = null,
                        blockScreenshots = current.expert.blockScreenshots,
                        allowHttpConfigImports = current.expert.allowHttpConfigImports,
                        allowInsecureTls = current.expert.allowInsecureTls,
                    ),
            )
        }

    suspend fun resetUsageTracking(timestamp: Long = System.currentTimeMillis()) =
        update {
            it.copy(
                profileTrafficTotals = emptyList(),
                usageTrackingStartedAt = timestamp,
            )
        }

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val locale = stringPreferencesKey("locale")
        val autoReconnect = booleanPreferencesKey("auto_reconnect")
        val autoStartOnBoot = booleanPreferencesKey("auto_start_on_boot")
        val ipInfoEndpoint = stringPreferencesKey("ip_info_endpoint")
    }

    private suspend fun ensureInitialized(): Settings {
        initializationResult?.let { return it.getOrThrow() }
        return lock.withLock {
            initializationResult?.let { return it.getOrThrow() }
            val result = runCatching { loadAndFinalizeInitialSettings() }
            initializationResult = result
            result.onSuccess { initialized ->
                settingsMutable.value = initialized
                themeModeMutable.value = initialized.ui.themeMode
            }
            result.getOrThrow()
        }
    }

    private suspend fun loadAndFinalizeInitialSettings(): Settings {
        val encryptedSettings = loadInitialSettings()
        val fastThemeMode = readFastThemeMode()
        val fastLocale = readFastLocale()
        val effectiveSettings =
            fastThemeMode
                ?.takeIf { it != encryptedSettings.ui.themeMode && encryptedSettings.ui.themeMode != ThemeMode.SYSTEM }
                ?.let { themeMode -> encryptedSettings.copy(ui = encryptedSettings.ui.copy(themeMode = themeMode)).normalized() }
                ?: encryptedSettings
        if (fastThemeMode == null || encryptedSettings.ui.themeMode == ThemeMode.SYSTEM) {
            writeFastThemeMode(effectiveSettings.ui.themeMode)
        }
        if (fastLocale == null) {
            writeFastLocale(effectiveSettings.ui.locale)
        }
        if (effectiveSettings != encryptedSettings) {
            withContext(Dispatchers.IO) {
                writeEncrypted(effectiveSettings)
            }
        }
        return effectiveSettings
    }

    private suspend fun loadInitialSettings(): Settings =
        withContext(Dispatchers.IO) {
            when (val encryptedResult = readEncryptedResult()) {
                EncryptedSettingsLoadResult.Missing -> {
                    val migrated = readLegacySettingsOrNull() ?: defaultSettings()
                    writeEncrypted(migrated)
                    deleteLegacySettings()
                    migrated
                }

                is EncryptedSettingsLoadResult.Loaded -> encryptedResult.settings

                is EncryptedSettingsLoadResult.Corrupt -> {
                    throw SettingsCorruptedException(
                        preservedCopy = encryptedResult.preservedCopy,
                        cause = encryptedResult.cause,
                    )
                }
            }
        }

    private fun readEncryptedResult(): EncryptedSettingsLoadResult =
        readEncryptedSettingsResult(
            settingsFile = settingsFile,
            readPayload = {
                fileCipher.readBytesMigratingLegacy(appContext, settingsFile).decodeToString()
            },
            decodePayload = ::readSettingsPayload,
            sanitizePayload = ::sanitizeStoredThemeModePayload,
            rewriteSanitized = ::writeEncrypted,
            preserveCorruptFile = ::preserveCorruptSettingsFile,
        )

    private fun preserveCorruptSettingsFile(): File? {
        if (!settingsFile.exists()) {
            return null
        }
        val forensicCopy =
            File(
                settingsDir,
                "${settingsFile.name}.corrupt-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
            )
        settingsFile.copyTo(forensicCopy, overwrite = false)
        return forensicCopy
    }

    private suspend fun update(transform: (Settings) -> Settings) {
        ensureInitialized()
        lock.withLock {
            val current = settingsMutable.value
            val next = transform(current).normalized()
            if (next == current) {
                return@withLock
            }
            withContext(Dispatchers.IO) {
                writeEncrypted(next)
            }
            initializationResult = Result.success(next)
            settingsMutable.value = next
            themeModeMutable.value = next.ui.themeMode
            writeFastLocale(next.ui.locale)
        }
    }

    private fun readSettingsPayload(payload: String): Settings =
        runCatching { json.decodeFromString(Settings.serializer(), payload).normalized() }
            .recoverCatching { json.decodeFromString(LegacyFlatSettings.serializer(), payload).toCurrent() }
            .getOrThrow()

    private suspend fun readLegacySettingsOrNull(): Settings? {
        if (!legacySettingsFile.exists()) {
            return null
        }
        val legacyStore =
            PreferenceDataStoreFactory.create(
                produceFile = { legacySettingsFile },
            )
        val preferences = legacyStore.data.firstOrNull() ?: return null
        return Settings(
            ui =
                UiSettings(
                    themeMode = parseStoredThemeMode(preferences[Keys.themeMode]),
                    locale = AppLocale.valueOf(preferences[Keys.locale] ?: AppLocale.SYSTEM.name),
                    showExpertSettings = true,
                ),
            connection =
                ConnectionSettings(
                    autoReconnect = preferences[Keys.autoReconnect] ?: true,
                    autoStartOnBoot = preferences[Keys.autoStartOnBoot] ?: false,
                    ipInfoEndpoint = normalizeIpInfoEndpoint(preferences[Keys.ipInfoEndpoint].orEmpty()),
                ),
        ).normalized()
    }

    private fun writeEncrypted(value: Settings) {
        fileCipher.writeBytesAtomic(
            settingsFile,
            json.encodeToString(Settings.serializer(), value.normalized()).encodeToByteArray(),
        )
    }

    private fun readFastThemeMode(): ThemeMode? =
        parseOptionalStoredThemeMode(fastUiPreferences.getString(FAST_THEME_MODE_KEY, null))

    private fun readFastLocale(): AppLocale? =
        parseOptionalStoredAppLocale(fastUiPreferences.getString(FAST_LOCALE_KEY, null))

    private fun writeFastThemeMode(value: ThemeMode) {
        fastUiPreferences.edit {
            putString(FAST_THEME_MODE_KEY, value.name)
        }
    }

    private fun writeFastLocale(value: AppLocale) {
        fastUiPreferences.edit {
            putString(FAST_LOCALE_KEY, value.name)
        }
    }

    private fun deleteLegacySettings() {
        legacySettingsFile.delete()
        File("${legacySettingsFile.absolutePath}.crc").delete()
    }

    private fun bootstrapInitialSettings(): Settings {
        val defaults = defaultSettings()
        return defaults.copy(
            ui =
                defaults.ui.copy(
                    themeMode = readFastThemeMode() ?: ThemeMode.SYSTEM,
                    locale = readFastLocale() ?: AppLocale.SYSTEM,
                ),
        )
    }

    private fun defaultSettings(): Settings =
        Settings(
            connection = ConnectionSettings(ipInfoEndpoint = BuildConfig.DEFAULT_IP_INFO_ENDPOINT),
        )

    private fun Settings.normalized(): Settings =
        run {
            val resetDefaults = schemaVersion < SETTINGS_SCHEMA_VERSION
            copy(
                schemaVersion = SETTINGS_SCHEMA_VERSION,
                ui =
                    ui.copy(
                        themeMode = if (resetDefaults) ThemeMode.SYSTEM else ui.themeMode,
                        onboardingCompleted = true,
                        showExpertSettings = ui.showExpertSettings && expert.unlockedAt != null,
                        supportBotHandleOverride = storedSupportBotHandleOverride(ui.supportBotHandleOverride),
                    ),
                connection =
                    connection.copy(
                        ipInfoEndpoint = normalizeIpInfoEndpoint(connection.ipInfoEndpoint),
                    ),
                traffic =
                    if (connection.stealthModeEnabled) {
                        TrafficSettings()
                    } else {
                        traffic.copy(
                            mtu = traffic.mtu.coerceIn(MIN_MTU, MAX_MTU),
                        )
                    },
                expert =
                    expert.normalized(
                        stealthModeEnabled = connection.stealthModeEnabled,
                        resetScreenshotBlocking = resetDefaults,
                    ),
                smartProfilePreferences = normalizeSmartProfilePreferences(smartProfilePreferences),
                profileTrafficTotals =
                    profileTrafficTotals
                        .groupBy(ProfileTrafficTotal::profileId)
                        .values
                        .mapNotNull { items -> items.maxByOrNull(ProfileTrafficTotal::updatedAt) }
                        .sortedByDescending(ProfileTrafficTotal::updatedAt),
                usageTrackingStartedAt = usageTrackingStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        }

    private fun ExpertSettings.normalized(
        stealthModeEnabled: Boolean,
        resetScreenshotBlocking: Boolean,
    ): ExpertSettings {
        val normalized =
            copy(
                selectedPackages =
                    if (perAppRoutingMode == PerAppRoutingMode.FULL_TUNNEL) {
                        emptyList()
                    } else {
                        selectedPackages
                            .filterNot { packageName -> packageName == BuildConfig.APPLICATION_ID }
                            .distinct()
                            .sorted()
                    },
                blockScreenshots = if (resetScreenshotBlocking) false else blockScreenshots,
                localSurfaces = localSurfaces.normalized(),
                routeOnly = routeOnly && sniff,
            )
        return if (!stealthModeEnabled) {
            normalized
        } else {
            ExpertSettings(
                unlockedAt = normalized.unlockedAt,
                warningAcknowledgedAt = normalized.warningAcknowledgedAt,
                blockScreenshots = normalized.blockScreenshots,
                networkActivityLogging = normalized.networkActivityLogging,
                diagnosticsRetention = normalized.diagnosticsRetention,
                smartStartReplayLogging = normalized.smartStartReplayLogging,
                allowHttpConfigImports = normalized.allowHttpConfigImports,
                allowInsecureTls = normalized.allowInsecureTls,
            )
        }
    }

    private fun LocalSurfaceSettings.normalized(): LocalSurfaceSettings =
        copy(
            socks = socks.normalized(),
            http = http.normalized(),
            mixed = mixed.normalized(),
            allowLanAccess = allowLanAccess,
            clashApi = clashApi.normalized(),
            v2RayApi = v2RayApi.normalized(),
            auth = auth.normalized(),
        )

    private fun LocalSurfaceSettings.withProxyModeDefaults(enableDefaults: Boolean): LocalSurfaceSettings {
        if (!enableDefaults) {
            return this
        }
        if (socks.enabled || http.enabled || mixed.enabled) {
            return this
        }
        return copy(http = http.copy(enabled = true))
    }

    private fun com.foxhole.beta.core.model.LocalAuthSettings.normalized(): com.foxhole.beta.core.model.LocalAuthSettings =
        copy(
            enabled = enabled,
            username = username.trim().ifBlank { "foxhole-${UUID.randomUUID().toString().take(8)}" },
            password = password.trim().ifBlank { UUID.randomUUID().toString().replace("-", "") },
            apiSecret =
                apiSecret.trim().ifBlank {
                    UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().take(8)
                },
        )

    private fun ProxyInboundSettings.normalized(): ProxyInboundSettings =
        copy(
            host = host.trim().ifLoopbackOrDefault(),
            port = port.coerceIn(MIN_PORT, MAX_PORT),
        )

    private fun ClashApiSettings.normalized(): ClashApiSettings =
        copy(
            host = host.trim().ifLoopbackOrDefault(),
            port = port.coerceIn(MIN_PORT, MAX_PORT),
            secret = "",
        )

    private fun V2RayApiSettings.normalized(): V2RayApiSettings =
        copy(
            host = host.trim().ifLoopbackOrDefault(),
            port = port.coerceIn(MIN_PORT, MAX_PORT),
        )

    @Serializable
    private data class LegacyFlatSettings(
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val locale: AppLocale = AppLocale.SYSTEM,
        val autoReconnect: Boolean = true,
        val autoStartOnBoot: Boolean = false,
        val ipInfoEndpoint: String = "",
    ) {
        fun toCurrent(): Settings =
            Settings(
                ui =
                    UiSettings(
                        themeMode = themeMode,
                        locale = locale,
                        onboardingCompleted = true,
                        showExpertSettings = true,
                    ),
                connection =
                    ConnectionSettings(
                        autoReconnect = autoReconnect,
                        autoStartOnBoot = autoStartOnBoot,
                        ipInfoEndpoint = normalizeIpInfoEndpoint(ipInfoEndpoint),
                    ),
            )
    }

    companion object {
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
        private const val MIN_MTU = 576
        private const val MAX_MTU = 9_000
        private const val FAST_UI_PREFERENCES_NAME = "foxhole_fast_ui"
        private const val FAST_THEME_MODE_KEY = "theme_mode"
        private const val FAST_LOCALE_KEY = "locale"
    }
}

internal fun Settings.withExpertSettingsVisibility(visible: Boolean): Settings =
    if (visible) {
        copy(
            ui = ui.copy(showExpertSettings = expert.unlockedAt != null),
        )
    } else {
        copy(
            ui = ui.copy(showExpertSettings = false),
            expert = expert.copy(unlockedAt = null),
        )
    }

internal fun Settings.smartProfilePreference(profileId: Long): SmartProfilePreference? =
    smartProfilePreferences.firstOrNull { preference -> preference.profileId == profileId }

internal fun SmartProfilePreference.networkMemory(networkFingerprint: String?): SmartProfileNetworkMemory? {
    val normalizedNetworkFingerprint = networkFingerprint?.trim()?.takeIf(String::isNotBlank) ?: return null
    return networkMemories.firstOrNull { memory ->
        memory.networkFingerprint == normalizedNetworkFingerprint &&
            memory.networkFingerprintSchema == NETWORK_FINGERPRINT_SCHEMA_CURRENT
    }
}

internal fun SmartProfilePreference.preferredLastKnownGoodOptionId(networkFingerprint: String?): String? =
    networkMemory(networkFingerprint)?.lastKnownGoodOptionId ?: lastKnownGoodOptionId

internal const val SMART_START_RECOMMENDED_LIMIT = 3
internal const val SMART_START_REMEMBERED_LATENCY_RETENTION_MS = 72L * 60L * 60L * 1000L
internal const val SMART_START_FULL_REFRESH_STALE_MS = 7L * 24L * 60L * 60L * 1000L

internal fun smartStartEnabledProtocolSetHash(optionIds: Collection<String>): String {
    val payload =
        optionIds
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .joinToString(separator = "\n")
    val digest = MessageDigest.getInstance("SHA-256").digest(payload.encodeToByteArray())
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun SmartProfilePreference.needsSmartStartColdScan(
    enabledProtocolSetHash: String,
    now: Long = System.currentTimeMillis(),
    includeStaleRefresh: Boolean = false,
): Boolean =
    !smartStartBaselineReady ||
        recommendedProtocolIds.isEmpty() ||
        this.enabledProtocolSetHash != enabledProtocolSetHash ||
        networkFingerprintSchema != NETWORK_FINGERPRINT_SCHEMA_CURRENT ||
        (
            includeStaleRefresh &&
                lastFullSmartRefreshAt?.let { refreshedAt -> now - refreshedAt > SMART_START_FULL_REFRESH_STALE_MS } != false
        )

internal fun Settings.rememberedSmartStartLatencyByProfileId(
    networkFingerprint: String?,
    now: Long = System.currentTimeMillis(),
): Map<Long, Map<String, Long>> =
    smartProfilePreferences
        .mapNotNull { preference ->
            preference
                .rememberedSmartStartLatencyByOptionId(networkFingerprint = networkFingerprint, now = now)
                .takeIf(Map<String, Long>::isNotEmpty)
                ?.let { preference.profileId to it }
        }.toMap()

internal fun SmartProfilePreference.rememberedSmartStartLatencyByOptionId(
    networkFingerprint: String?,
    now: Long = System.currentTimeMillis(),
): Map<String, Long> {
    val scopedMemories =
        networkMemory(networkFingerprint)
            ?.protocolMemories
            ?.associateBy(SmartProfileProtocolMemory::optionId)
            .orEmpty()
    val globalMemories = protocolMemories.associateBy(SmartProfileProtocolMemory::optionId)
    return buildMap {
        (scopedMemories.keys + globalMemories.keys)
            .sorted()
            .forEach { optionId ->
                val scopedLatency = scopedMemories[optionId].freshRememberedLatency(now)
                val globalLatency = globalMemories[optionId].freshRememberedLatency(now)
                val rememberedLatency = scopedLatency ?: globalLatency
                if (rememberedLatency != null) {
                    put(optionId, rememberedLatency)
                }
            }
    }
}

internal fun Settings.rememberedSmartProfileServerPingByProfileId(
    networkFingerprint: String?,
    now: Long = System.currentTimeMillis(),
): Map<Long, Map<String, Long>> =
    smartProfilePreferences
        .mapNotNull { preference ->
            preference
                .rememberedSmartProfileServerPingByOptionId(networkFingerprint = networkFingerprint, now = now)
                .takeIf(Map<String, Long>::isNotEmpty)
                ?.let { preference.profileId to it }
        }.toMap()

internal fun SmartProfilePreference.rememberedSmartProfileServerPingByOptionId(
    networkFingerprint: String?,
    now: Long = System.currentTimeMillis(),
): Map<String, Long> {
    val scopedMemories =
        networkMemory(networkFingerprint)
            ?.protocolMemories
            ?.associateBy(SmartProfileProtocolMemory::optionId)
            .orEmpty()
    val globalMemories = protocolMemories.associateBy(SmartProfileProtocolMemory::optionId)
    return buildMap {
        (scopedMemories.keys + globalMemories.keys)
            .sorted()
            .forEach { optionId ->
                val scopedServerPing = scopedMemories[optionId].freshRememberedServerPing(now)
                val globalServerPing = globalMemories[optionId].freshRememberedServerPing(now)
                val rememberedServerPing = scopedServerPing ?: globalServerPing
                if (rememberedServerPing != null) {
                    put(optionId, rememberedServerPing)
                }
            }
    }
}

internal fun Settings.rememberedSmartProfileMetricsUpdatedAtByProfileId(
    networkFingerprint: String?,
    now: Long = System.currentTimeMillis(),
): Map<Long, Map<String, Long>> =
    smartProfilePreferences
        .mapNotNull { preference ->
            preference
                .rememberedSmartProfileMetricsUpdatedAtByOptionId(networkFingerprint = networkFingerprint, now = now)
                .takeIf(Map<String, Long>::isNotEmpty)
                ?.let { preference.profileId to it }
        }.toMap()

internal fun SmartProfilePreference.rememberedSmartProfileMetricsUpdatedAtByOptionId(
    networkFingerprint: String?,
    now: Long = System.currentTimeMillis(),
): Map<String, Long> {
    val scopedMemories =
        networkMemory(networkFingerprint)
            ?.protocolMemories
            ?.associateBy(SmartProfileProtocolMemory::optionId)
            .orEmpty()
    val globalMemories = protocolMemories.associateBy(SmartProfileProtocolMemory::optionId)
    return buildMap {
        (scopedMemories.keys + globalMemories.keys)
            .sorted()
            .forEach { optionId ->
                val scopedUpdatedAt = scopedMemories[optionId].freshRememberedMetricsUpdatedAt(now)
                val globalUpdatedAt = globalMemories[optionId].freshRememberedMetricsUpdatedAt(now)
                val rememberedUpdatedAt = listOfNotNull(scopedUpdatedAt, globalUpdatedAt).maxOrNull()
                if (rememberedUpdatedAt != null) {
                    put(optionId, rememberedUpdatedAt)
                }
            }
    }
}

private fun SmartProfileProtocolMemory?.freshRememberedLatency(
    now: Long,
    retentionMs: Long = SMART_START_REMEMBERED_LATENCY_RETENTION_MS,
): Long? {
    val memory = this ?: return null
    val latencyMs = memory.lastLatencyMs?.takeIf { it > 0L } ?: return null
    val successAt = memory.lastSuccessAt?.takeIf { it > 0L } ?: return null
    return if (now - successAt <= retentionMs) latencyMs else null
}

private fun SmartProfileProtocolMemory?.freshRememberedServerPing(
    now: Long,
    retentionMs: Long = SMART_START_REMEMBERED_LATENCY_RETENTION_MS,
): Long? {
    val memory = this ?: return null
    val serverPingMs = memory.lastServerPingMs?.takeIf { it > 0L } ?: return null
    val serverPingAt = memory.lastServerPingAt?.takeIf { it > 0L } ?: return null
    return if (now - serverPingAt <= retentionMs) serverPingMs else null
}

private fun SmartProfileProtocolMemory?.freshRememberedMetricsUpdatedAt(
    now: Long,
    retentionMs: Long = SMART_START_REMEMBERED_LATENCY_RETENTION_MS,
): Long? {
    val memory = this ?: return null
    val updatedAt =
        listOfNotNull(
            memory.lastServerPingAt?.takeIf { it > 0L },
            memory.lastValidatedAt?.takeIf { it > 0L },
            memory.lastSuccessAt?.takeIf { it > 0L },
        ).maxOrNull() ?: return null
    return if (now - updatedAt <= retentionMs) updatedAt else null
}

internal fun normalizeSmartProfilePreferences(
    preferences: List<SmartProfilePreference>,
): List<SmartProfilePreference> =
    preferences
        .mapNotNull { preference ->
            val normalizedExcludedOptionIds =
                preference.excludedProtocolOptionIds
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .sorted()
            val normalizedLastKnownGoodOptionId = preference.lastKnownGoodOptionId?.trim()?.takeIf(String::isNotBlank)
            val normalizedRecommendedProtocolIds =
                preference.recommendedProtocolIds
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(SMART_START_RECOMMENDED_LIMIT)
            val normalizedProtocolMemories =
                preference.protocolMemories
                    .mapNotNull(SmartProfileProtocolMemory::normalized)
                    .distinctBy(SmartProfileProtocolMemory::optionId)
                    .sortedBy(SmartProfileProtocolMemory::optionId)
            val normalizedNetworkMemories =
                preference.networkMemories
                    .mapNotNull(SmartProfileNetworkMemory::normalized)
                    .distinctBy(SmartProfileNetworkMemory::networkFingerprint)
                    .sortedBy(SmartProfileNetworkMemory::networkFingerprint)
            val normalizedPreference =
                preference.copy(
                    excludedProtocolOptionIds = normalizedExcludedOptionIds,
                    lastKnownGoodOptionId = normalizedLastKnownGoodOptionId,
                    lastKnownGoodLatencyMs = preference.lastKnownGoodLatencyMs?.takeIf { it > 0L },
                    lastKnownGoodAt = preference.lastKnownGoodAt?.takeIf { it > 0L },
                    lastFullSmartRefreshAt = preference.lastFullSmartRefreshAt?.takeIf { it > 0L },
                    smartStartBaselineReady = preference.smartStartBaselineReady && normalizedRecommendedProtocolIds.isNotEmpty(),
                    recommendedProtocolIds = normalizedRecommendedProtocolIds,
                    enabledProtocolSetHash = preference.enabledProtocolSetHash?.trim()?.takeIf(String::isNotBlank),
                    networkFingerprintSchema = preference.networkFingerprintSchema.coerceAtLeast(1),
                    protocolMemories = normalizedProtocolMemories,
                    networkMemories = normalizedNetworkMemories,
                )
            normalizedPreference.takeIf {
                normalizedExcludedOptionIds.isNotEmpty() ||
                    normalizedLastKnownGoodOptionId != null ||
                    normalizedPreference.lastFullSmartRefreshAt != null ||
                    normalizedPreference.smartStartBaselineReady ||
                    normalizedPreference.recommendedProtocolIds.isNotEmpty() ||
                    normalizedProtocolMemories.isNotEmpty() ||
                    normalizedNetworkMemories.isNotEmpty()
            }
        }.distinctBy(SmartProfilePreference::profileId)
        .sortedBy(SmartProfilePreference::profileId)
