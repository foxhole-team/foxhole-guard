package com.foxhole.beta.core.settings

import android.content.Context
import androidx.core.content.edit
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.model.AnomalyHistoryRetention
import com.foxhole.beta.core.model.AnomalySensitivity
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.CachedActiveProfile
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.ConnectionSettings
import com.foxhole.beta.core.model.DEFAULT_DNS_FILTER_UPDATE_URL
import com.foxhole.beta.core.model.DashboardCard
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.DnsSettings
import com.foxhole.beta.core.model.DomainStrategy
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.InstalledAppChangeType
import com.foxhole.beta.core.model.InstalledAppInventoryAudit
import com.foxhole.beta.core.model.InstalledAppInventoryChange
import com.foxhole.beta.core.model.InstalledAppInventoryEntry
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.InstalledAppRiskLevel
import com.foxhole.beta.core.model.InstalledAppRiskSignal
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.NETWORK_FINGERPRINT_SCHEMA_CURRENT
import com.foxhole.beta.core.model.NetworkRulesSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.PrivacyRouteSettings
import com.foxhole.beta.core.model.ProfileTrafficTotal
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.ProxySurfaceMode
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.model.SETTINGS_SCHEMA_VERSION
import com.foxhole.beta.core.model.SMART_START_PROTOCOL_TIMEOUT_MIN_SECONDS
import com.foxhole.beta.core.model.SMART_START_REFRESH_TIMEOUT_MIN_SECONDS
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.SmartProfileNetworkMemory
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import com.foxhole.beta.core.model.SmartStartTransportPriority
import com.foxhole.beta.core.model.StatisticsMetric
import com.foxhole.beta.core.model.StatisticsRefreshInterval
import com.foxhole.beta.core.model.StatisticsRetention
import com.foxhole.beta.core.model.StatisticsSettings
import com.foxhole.beta.core.model.SubscriptionRefreshInterval
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSettings
import com.foxhole.beta.core.model.TransportProtocol
import com.foxhole.beta.core.model.TunStack
import com.foxhole.beta.core.model.UiSettings
import com.foxhole.beta.core.model.V2RayApiSettings
import com.foxhole.beta.core.network.ensurePublicHttpsUrl
import com.foxhole.beta.core.security.AndroidKeystoreFileCipher
import com.foxhole.beta.core.security.InstalledAppSecurityAnalyzer
import com.foxhole.beta.core.security.readBytesMigratingLegacy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

class SettingsRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val installedAppSecurityAnalyzer by lazy { InstalledAppSecurityAnalyzer(appContext) }
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
            writeFastUiSnapshot(normalized.ui)
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

    suspend fun updateSubscriptionRefreshInterval(value: SubscriptionRefreshInterval) =
        update { it.copy(connection = it.connection.copy(subscriptionRefreshInterval = value)) }

    suspend fun updateSafeModeEnabled(value: Boolean) =
        update { current ->
            if (value) {
                current.copy(
                    connection = current.connection.copy(safeModeEnabled = true),
                    traffic = TrafficSettings(),
                    privacyRoute = PrivacyRouteSettings(),
                    expert =
                        ExpertSettings(
                            unlockedAt = current.expert.unlockedAt,
                        warningAcknowledgedAt = current.expert.warningAcknowledgedAt,
                        blockScreenshots = current.expert.blockScreenshots,
                        killSwitchEnabled = current.expert.killSwitchEnabled,
                        newAppQuarantineEnabled = current.expert.newAppQuarantineEnabled,
                        networkActivityLogging = current.expert.networkActivityLogging,
                        networkActivityPersistentLogging = current.expert.networkActivityPersistentLogging,
                        sanitizeNetworkActivityPrivateData = current.expert.sanitizeNetworkActivityPrivateData,
                        diagnosticsRetention = current.expert.diagnosticsRetention,
                        smartStartReplayLogging = current.expert.smartStartReplayLogging && BuildConfig.DEBUG,
                        allowInsecureTls = current.expert.allowInsecureTls,
                        blockedPackages = current.expert.blockedPackages,
                        blockedPackagesEnabled = current.expert.blockedPackagesEnabled,
                        blockAppsAlways = current.expert.blockAppsAlways,
                    ),
                )
            } else {
                current.copy(connection = current.connection.copy(safeModeEnabled = false))
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

    suspend fun updateLatencyProbeMethod(value: LatencyProbeMethod) =
        update { it.copy(connection = it.connection.copy(latencyProbeMethod = value)) }

    suspend fun updateSmartStartEnabled(value: Boolean) =
        update { it.copy(connection = it.connection.copy(smartStartEnabled = value)) }

    suspend fun updateSmartStartProtocolSelectionTimeoutSeconds(value: Int) =
        update {
            it.copy(
                connection =
                    it.connection.copy(
                        smartStartProtocolSelectionTimeoutSeconds =
                            normalizeSmartStartTimeoutSeconds(
                                value = value,
                                minSeconds = SMART_START_PROTOCOL_TIMEOUT_MIN_SECONDS,
                            ),
                    ),
            )
        }

    suspend fun updateSmartStartRefreshSelectionTimeoutSeconds(value: Int) =
        update {
            it.copy(
                connection =
                    it.connection.copy(
                        smartStartRefreshSelectionTimeoutSeconds =
                            normalizeSmartStartTimeoutSeconds(
                                value = value,
                                minSeconds = SMART_START_REFRESH_TIMEOUT_MIN_SECONDS,
                            ),
                    ),
            )
        }

    suspend fun updateSmartStartTransportPriority(value: SmartStartTransportPriority) =
        update { it.copy(connection = it.connection.copy(smartStartTransportPriority = value)) }

    suspend fun updateSmartStartV2RayTunSubscriptionsEnabled(value: Boolean) =
        update { it.copy(connection = it.connection.copy(smartStartV2RayTunSubscriptionsEnabled = value)) }

    suspend fun updateSmartStartFailoverEnabled(value: Boolean) =
        update { it.copy(connection = it.connection.copy(smartStartFailoverEnabled = value)) }

    suspend fun updateSmartStartSubscriptionRetryAttempts(value: Int) =
        update {
            it.copy(
                connection =
                    it.connection.copy(
                        smartStartSubscriptionRetryAttempts = normalizeSmartStartSubscriptionRetryAttempts(value),
                    ),
            )
        }

    suspend fun updateSmartStartSubscriptionRetryDelaySeconds(value: Int) =
        update {
            it.copy(
                connection =
                    it.connection.copy(
                        smartStartSubscriptionRetryDelaySeconds = normalizeSmartStartSubscriptionRetryDelaySeconds(value),
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

    suspend fun clearSmartStartData() =
        update { current ->
            current.copy(
                smartProfilePreferences =
                    current.smartProfilePreferences.map(SmartProfilePreference::clearedSmartStartRuntimeData),
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
                lastFullSmartRefreshAt = refreshedAt.takeIf { it > 0L } ?: existing.lastFullSmartRefreshAt,
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
        protocolOptionId: String? = null,
        transport: TransportProtocol = TransportProtocol.UNKNOWN,
        rxBytes: Long,
        txBytes: Long,
        updatedAt: Long = System.currentTimeMillis(),
    ) = update { current ->
        if (!current.statistics.enabled || !current.statistics.profileTrafficEnabled || (rxBytes <= 0L && txBytes <= 0L)) {
            current
        } else {
            val normalizedProtocolOptionId = protocolOptionId.normalizedProfileTrafficProtocolOptionId()
            val trafficKey = ProfileTrafficKey(profileId, normalizedProtocolOptionId)
            val existing = current.profileTrafficTotals.associateBy(ProfileTrafficTotal::trafficKey).toMutableMap()
            val previous = existing[trafficKey]
            existing[trafficKey] =
                ProfileTrafficTotal(
                    profileId = profileId,
                    profileName = profileName,
                    protocolHint = protocolHint,
                    protocolOptionId = normalizedProtocolOptionId,
                    transport = transport,
                    rxTotalBytes = (previous?.rxTotalBytes ?: 0L) + rxBytes.coerceAtLeast(0L),
                    txTotalBytes = (previous?.txTotalBytes ?: 0L) + txBytes.coerceAtLeast(0L),
                    updatedAt = updatedAt,
                )
            current.copy(profileTrafficTotals = existing.values.sortedByDescending(ProfileTrafficTotal::updatedAt))
        }
    }

    suspend fun updateStatisticsEnabled(value: Boolean) =
        update { current ->
            current.copy(
                statistics =
                    current.statistics.copy(
                        enabled = value,
                        profileTrafficEnabled = current.statistics.profileTrafficEnabled || value,
                    ),
            )
        }

    suspend fun updateStatisticsRetention(value: StatisticsRetention) =
        update { current ->
            current.copy(
                statistics = current.statistics.copy(retention = value),
            )
        }

    suspend fun updateStatisticsRefreshInterval(value: StatisticsRefreshInterval) =
        update { current ->
            current.copy(
                statistics = current.statistics.copy(refreshInterval = value),
            )
        }

    suspend fun updateStatisticsMetricEnabled(
        metric: StatisticsMetric,
        value: Boolean,
    ) = update { current ->
        val statistics =
            when (metric) {
                StatisticsMetric.PROFILE_TRAFFIC -> current.statistics.copy(profileTrafficEnabled = value)
                StatisticsMetric.VPN_PROTOCOLS -> current.statistics.copy(vpnProtocolsEnabled = value)
                StatisticsMetric.PROFILE_COMPARISONS -> current.statistics.copy(profileComparisonsEnabled = value)
                StatisticsMetric.TRANSPORTS -> current.statistics.copy(transportsEnabled = value)
                StatisticsMetric.APP_TRAFFIC -> current.statistics.copy(appTrafficEnabled = value)
                StatisticsMetric.DNS_FILTERING -> current.statistics.copy(dnsFilteringEnabled = value)
                StatisticsMetric.COUNTRY_TRAFFIC -> current.statistics.copy(countryTrafficEnabled = value)
                StatisticsMetric.ANOMALIES -> current.statistics.copy(anomalyMetricsEnabled = value)
                StatisticsMetric.APP_CHANGES -> current.statistics.copy(appChangesEnabled = value)
            }
        current.copy(statistics = statistics)
    }

    suspend fun updateInstalledAppMonitoringEnabled(value: Boolean) =
        update { current ->
            val statistics =
                if (value) {
                    current.statistics.copy(enabled = true, appChangesEnabled = true)
                } else {
                    current.statistics.copy(appChangesEnabled = false)
                }
            current.copy(
                statistics = statistics,
                installedAppInventoryAudit =
                    if (value) {
                        current.installedAppInventoryAudit
                    } else {
                        InstalledAppInventoryAudit()
                    },
            )
        }

    suspend fun updateAppTrafficStatsEnabled(value: Boolean) =
        update { current ->
            current.copy(appTrafficStatsEnabled = value)
        }

    suspend fun recordInstalledAppInventory(
        apps: List<InstalledAppOption>,
        detectedAt: Long = System.currentTimeMillis(),
    ) {
        val currentSettings = current()
        if (!currentSettings.statistics.enabled || !currentSettings.statistics.appChangesEnabled) {
            return
        }
        val currentPackages =
            withContext(Dispatchers.IO) {
                apps
                    .asSequence()
                    .filterNot { app -> app.packageName == BuildConfig.APPLICATION_ID }
                    .map(::installedAppInventoryEntry)
                    .distinctBy(InstalledAppInventoryEntry::packageName)
                    .sortedBy(InstalledAppInventoryEntry::packageName)
                    .toList()
            }
        update { current ->
            if (!current.statistics.enabled || !current.statistics.appChangesEnabled) {
                return@update current
            }
            current.copy(
                installedAppInventoryAudit =
                    buildInstalledAppInventoryAudit(
                        previousAudit = current.installedAppInventoryAudit,
                        currentPackages = currentPackages,
                        detectedAt = detectedAt,
                    ),
            )
        }
    }

    private fun installedAppInventoryEntry(app: InstalledAppOption): InstalledAppInventoryEntry {
        val security =
            installedAppSecurityAnalyzer.analyzePackage(
                packageName = app.packageName,
                fallbackLabel = app.label,
                fallbackIsSystemApp = app.isSystemApp,
            )
        return InstalledAppInventoryEntry(
            packageName = app.packageName,
            label = security.label.takeIf(String::isNotBlank) ?: app.label.takeIf(String::isNotBlank) ?: app.packageName,
            isSystemApp = security.isSystemApp,
            installerPackageName = security.installerPackageName,
            riskLevel = security.riskLevel,
            riskSignals = security.riskSignals,
        )
    }

    private fun buildInstalledAppInventoryAudit(
        previousAudit: InstalledAppInventoryAudit,
        currentPackages: List<InstalledAppInventoryEntry>,
        detectedAt: Long,
    ): InstalledAppInventoryAudit {
        val previousPackages = previousAudit.packages
        val changes = installedAppInventoryChanges(previousPackages, currentPackages, detectedAt)
        return InstalledAppInventoryAudit(
            capturedAt = detectedAt,
            packages = currentPackages,
            recentChanges =
                (changes + previousAudit.recentChanges)
                    .distinctBy { change -> "${change.type}:${change.packageName}:${change.detectedAt}" }
                    .sortedByDescending(InstalledAppInventoryChange::detectedAt)
                    .take(INSTALLED_APP_CHANGE_HISTORY_LIMIT),
        )
    }

    private fun installedAppInventoryChanges(
        previousPackages: List<InstalledAppInventoryEntry>,
        currentPackages: List<InstalledAppInventoryEntry>,
        detectedAt: Long,
    ): List<InstalledAppInventoryChange> {
        if (previousPackages.isEmpty()) {
            return emptyList()
        }
        val previousByPackage = previousPackages.associateBy(InstalledAppInventoryEntry::packageName)
        val currentByPackage = currentPackages.associateBy(InstalledAppInventoryEntry::packageName)
        return buildList {
            currentPackages
                .filterNot { app -> app.packageName in previousByPackage }
                .forEach { app ->
                    add(app.toInstalledAppInventoryChange(InstalledAppChangeType.INSTALLED, detectedAt))
                }
            previousPackages
                .filterNot { app -> app.packageName in currentByPackage }
                .forEach { app ->
                    add(app.toInstalledAppInventoryChange(InstalledAppChangeType.REMOVED, detectedAt))
                }
        }
    }

    private fun InstalledAppInventoryEntry.toInstalledAppInventoryChange(
        type: InstalledAppChangeType,
        detectedAt: Long,
    ): InstalledAppInventoryChange =
        InstalledAppInventoryChange(
            packageName = packageName,
            label = label,
            isSystemApp = isSystemApp,
            type = type,
            detectedAt = detectedAt,
            installerPackageName = installerPackageName,
            riskLevel = riskLevel,
            riskSignals = riskSignals,
        )

    suspend fun recordInstalledAppChange(
        packageName: String,
        label: String,
        isSystemApp: Boolean,
        type: InstalledAppChangeType,
        detectedAt: Long = System.currentTimeMillis(),
        installerPackageName: String? = null,
        riskLevel: InstalledAppRiskLevel = InstalledAppRiskLevel.LOW,
        riskSignals: List<InstalledAppRiskSignal> = emptyList(),
    ) = update { current ->
        if (packageName == BuildConfig.APPLICATION_ID) {
            return@update current
        }
        val normalizedPackageName = packageName.trim().takeIf(String::isNotBlank) ?: return@update current
        val normalizedLabel = label.trim().takeIf(String::isNotBlank) ?: normalizedPackageName
        val quarantineNewApp =
            type == InstalledAppChangeType.INSTALLED &&
                current.expert.newAppQuarantineEnabled &&
                !isSystemApp
        val updatedConnection =
            current.connection.copy(safeModeEnabled = current.connection.safeModeEnabled && !quarantineNewApp)
        val updatedExpert =
            if (quarantineNewApp) {
                current.expert.quarantinePackage(normalizedPackageName)
            } else {
                current.expert
            }
        if (!current.statistics.enabled || !current.statistics.appChangesEnabled) {
            return@update current.copy(
                connection = updatedConnection,
                expert = updatedExpert,
            )
        }
        val packageEntry =
            InstalledAppInventoryEntry(
                packageName = normalizedPackageName,
                label = normalizedLabel,
                isSystemApp = isSystemApp,
                installerPackageName = installerPackageName?.trim()?.takeIf(String::isNotBlank),
                riskLevel = riskLevel,
                riskSignals = riskSignals.distinct(),
            )
        val updatedPackages =
            when (type) {
                InstalledAppChangeType.INSTALLED ->
                    (current.installedAppInventoryAudit.packages.filterNot { app -> app.packageName == normalizedPackageName } + packageEntry)
                        .sortedBy(InstalledAppInventoryEntry::packageName)
                InstalledAppChangeType.REMOVED ->
                    current.installedAppInventoryAudit.packages
                        .filterNot { app -> app.packageName == normalizedPackageName }
            }
        val change =
            InstalledAppInventoryChange(
                packageName = normalizedPackageName,
                label = normalizedLabel,
                isSystemApp = isSystemApp,
                type = type,
                detectedAt = detectedAt,
                installerPackageName = packageEntry.installerPackageName,
                riskLevel = packageEntry.riskLevel,
                riskSignals = packageEntry.riskSignals,
            )
        current.copy(
            connection = updatedConnection,
            expert = updatedExpert,
            installedAppInventoryAudit =
                current.installedAppInventoryAudit.copy(
                    capturedAt = detectedAt,
                    packages = updatedPackages,
                    recentChanges =
                        (listOf(change) + current.installedAppInventoryAudit.recentChanges)
                            .distinctBy { item -> "${item.type}:${item.packageName}:${item.detectedAt}" }
                            .sortedByDescending(InstalledAppInventoryChange::detectedAt)
                            .take(INSTALLED_APP_CHANGE_HISTORY_LIMIT),
                ),
        )
    }

    suspend fun updateTunStack(value: TunStack) =
        update { it.copy(traffic = it.traffic.copy(tunStack = value)) }

    suspend fun updateTrafficMode(value: TrafficMode) =
        update { current ->
            current.copy(
                connection =
                    current.connection.copy(
                        safeModeEnabled = current.connection.safeModeEnabled && value == TrafficMode.TUNNEL,
                ),
                traffic = current.traffic.copy(mode = value),
                expert =
                    current.expert.copy(
                        localSurfaces =
                            current.expert.localSurfaces.withProxyModeDefaults(
                                enableDefaults = value == TrafficMode.PROXY,
                            ),
                    ),
            )
        }

    suspend fun updatePrivacyRouteMode(value: PrivacyRouteMode) =
        update { current ->
            current.copy(
                connection =
                    current.connection.copy(
                        safeModeEnabled = current.connection.safeModeEnabled && value == PrivacyRouteMode.OFF,
                    ),
                traffic =
                    if (value == PrivacyRouteMode.TOR_OVER_VPN) {
                        current.traffic.copy(mode = TrafficMode.TUNNEL)
                    } else {
                        current.traffic
                    },
                privacyRoute = current.privacyRoute.copy(mode = value),
                ui =
                    current.ui.copy(
                        showTorQuickLaunch = current.ui.showTorQuickLaunch || value == PrivacyRouteMode.TOR_OVER_VPN,
                    ),
            )
        }

    suspend fun updatePrivacyRouteScope(value: PrivacyRouteScope) =
        update { current ->
            current.copy(
                connection =
                    current.connection.copy(
                        safeModeEnabled =
                            current.connection.safeModeEnabled &&
                                value == PrivacyRouteScope.SELECTED_APPS &&
                                !current.privacyRoute.enabled &&
                                current.privacyRoute.selectedPackages.isEmpty(),
                    ),
                privacyRoute = current.privacyRoute.copy(scope = value),
            )
        }

    suspend fun updatePrivacyRouteBypassVpnTunnel(value: Boolean) =
        update { current ->
            current.copy(
                connection =
                    current.connection.copy(
                        safeModeEnabled = current.connection.safeModeEnabled && !value,
                    ),
                traffic =
                    if (value && current.privacyRoute.enabled) {
                        current.traffic.copy(mode = TrafficMode.TUNNEL)
                    } else {
                        current.traffic
                    },
                privacyRoute =
                    current.privacyRoute.copy(
                        bypassVpnTunnel = value,
                    ),
            )
        }

    suspend fun updatePrivacyRouteSelectedPackages(value: List<String>) =
        update { current ->
            val selectedPackages = value.filterNot { it == BuildConfig.APPLICATION_ID }
            current.copy(
                connection =
                    current.connection.copy(
                        safeModeEnabled =
                            current.connection.safeModeEnabled &&
                                selectedPackages.isEmpty() &&
                                !current.privacyRoute.enabled,
                    ),
                privacyRoute =
                    current.privacyRoute.copy(
                        selectedPackages = selectedPackages,
                    ),
            )
        }

    suspend fun rotatePrivacyRouteIdentity() =
        update { current ->
            current.copy(
                privacyRoute =
                    current.privacyRoute.copy(
                        identityVersion = System.currentTimeMillis(),
                    ),
            )
        }

    suspend fun updateTrafficMtu(value: Int) =
        update { it.copy(traffic = it.traffic.copy(mtu = value.coerceIn(MIN_MTU, MAX_MTU))) }

    suspend fun updatePreferIpv6(value: Boolean) =
        update { it.copy(traffic = it.traffic.copy(preferIpv6 = value)) }

    suspend fun updateDomainStrategy(value: DomainStrategy) =
        update { it.copy(traffic = it.traffic.copy(domainStrategy = value)) }

    suspend fun updateDnsSettings(value: DnsSettings) =
        update { current ->
            current.copy(
                connection =
                    current.connection.copy(
                        safeModeEnabled = current.connection.safeModeEnabled && !value.runtimeRequiresExplicitTunnel(),
                    ),
                dns = value,
            )
        }

    suspend fun updateDnsBypassPackages(value: List<String>) =
        update { current ->
            current.copy(
                dns =
                    current.dns.copy(
                        appBypassPackages = value.filterNot { it == BuildConfig.APPLICATION_ID },
                    ),
            )
        }

    suspend fun updateDnsDomainBypassRules(value: List<String>) =
        update { current ->
            current.copy(
                dns = current.dns.copy(domainBypassRules = value),
            )
        }

    suspend fun markDnsFiltersUpdated(timestamp: Long = System.currentTimeMillis()) =
        update { current ->
            current.copy(
                dns = current.dns.copy(filtersUpdatedAt = timestamp),
            )
        }

    suspend fun updateNetworkRulesSettings(value: NetworkRulesSettings) =
        update { current ->
            current.copy(networkRules = value.normalized())
        }

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

    suspend fun updateNewAppQuarantineEnabled(value: Boolean) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value),
                expert =
                    it.expert.copy(
                        newAppQuarantineEnabled = value,
                        firewallEnabled = it.expert.firewallEnabled || value,
                        blockedPackagesEnabled = it.expert.blockedPackagesEnabled || (value && it.expert.blockedPackages.isNotEmpty()),
                        blockAppsAlways = it.expert.blockAppsAlways || (value && it.expert.blockedPackages.isNotEmpty()),
                    ),
            )
        }

    suspend fun updateTrafficMapEnabled(value: Boolean) =
        update { it.copy(ui = it.ui.copy(trafficMapEnabled = value)) }

    suspend fun updateNetworkCardEnabled(value: Boolean) =
        update { it.copy(ui = it.ui.copy(networkCardEnabled = value)) }

    suspend fun updateTrafficCardEnabled(value: Boolean) =
        update { it.copy(ui = it.ui.copy(trafficCardEnabled = value)) }

    suspend fun updateShowTorQuickLaunch(value: Boolean) =
        update { it.copy(ui = it.ui.copy(showTorQuickLaunch = value)) }

    suspend fun updateSmartStartDashboardControlsEnabled(value: Boolean) =
        update { it.copy(ui = it.ui.copy(smartStartDashboardControlsEnabled = value)) }

    suspend fun updateShowFirewallStatus(value: Boolean) =
        update { it.copy(ui = it.ui.copy(showFirewallStatus = value)) }

    suspend fun updateDashboardCardOrder(value: List<DashboardCard>) =
        update { current ->
            val normalized =
                (value + DashboardCard.entries)
                    .distinct()
                    .filter { card -> card in DashboardCard.entries }
            current.copy(ui = current.ui.copy(dashboardCardOrder = normalized))
        }

    suspend fun updateKillSwitchEnabled(value: Boolean) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value),
                expert = it.expert.copy(killSwitchEnabled = value),
            )
        }

    suspend fun updateFirewallEnabled(value: Boolean) =
        update { it.copy(expert = it.expert.copy(firewallEnabled = value)) }

    suspend fun updateSystemDnsProtectionEnabled(value: Boolean) =
        update { it.copy(expert = it.expert.copy(systemDnsProtectionEnabled = value)) }

    suspend fun updateNetworkActivityLogging(value: Boolean) =
        update { it.copy(expert = it.expert.copy(networkActivityLogging = value)) }

    suspend fun updateNetworkActivityPersistentLogging(value: Boolean) =
        update { it.copy(expert = it.expert.copy(networkActivityPersistentLogging = value)) }

    suspend fun updateSanitizeNetworkActivityPrivateData(value: Boolean) =
        update { it.copy(expert = it.expert.copy(sanitizeNetworkActivityPrivateData = value)) }

    suspend fun updateSmartStartReplayLogging(value: Boolean) =
        update { it.copy(expert = it.expert.copy(smartStartReplayLogging = value && BuildConfig.DEBUG)) }

    suspend fun updateDiagnosticsRetention(value: DiagnosticsRetention) =
        update { it.copy(expert = it.expert.copy(diagnosticsRetention = value)) }

    suspend fun updateRawLiveDiagnostics(value: Boolean) =
        update { it.copy(expert = it.expert.copy(rawLiveDiagnostics = value && BuildConfig.DEBUG)) }

    suspend fun updateNotifyUnusualTraffic(value: Boolean) =
        update { it.copy(anomaly = it.anomaly.copy(notifyUnusualTraffic = value)) }

    suspend fun updateAnomalyEnabled(value: Boolean) =
        update { it.copy(anomaly = it.anomaly.copy(enabled = value)) }

    suspend fun updateAnomalySensitivity(value: AnomalySensitivity) =
        update { it.copy(anomaly = it.anomaly.copy(sensitivity = value)) }

    suspend fun updateAnalyzeBackgroundTraffic(value: Boolean) =
        update { it.copy(anomaly = it.anomaly.copy(analyzeBackgroundTraffic = value)) }

    suspend fun updateAnalyzeDestinationCountries(value: Boolean) =
        update { it.copy(anomaly = it.anomaly.copy(analyzeDestinationCountries = value)) }

    suspend fun updateAnomalyHistoryRetention(value: AnomalyHistoryRetention) =
        update { it.copy(anomaly = it.anomaly.copy(historyRetention = value)) }

    suspend fun updateAllowInsecureTls(value: Boolean) =
        update { it.copy(expert = it.expert.copy(allowInsecureTls = value)) }

    suspend fun updateSniff(value: Boolean) =
        update { current ->
            current.copy(
                connection = current.connection.copy(safeModeEnabled = current.connection.safeModeEnabled && !value),
                expert = current.expert.copy(sniff = value, routeOnly = if (value) current.expert.routeOnly else false),
            )
        }

    suspend fun updateRouteOnly(value: Boolean) =
        update { current ->
            current.copy(
                connection = current.connection.copy(safeModeEnabled = current.connection.safeModeEnabled && !value),
                expert = current.expert.copy(routeOnly = value, sniff = current.expert.sniff || value),
            )
        }

    suspend fun updateStrictRoute(value: Boolean) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && value),
                expert = it.expert.copy(strictRoute = value),
            )
        }

    suspend fun updateBypassLan(value: Boolean) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value),
                expert = it.expert.copy(bypassLan = value),
            )
        }

    suspend fun updateAllowPrivateOutboundHosts(value: Boolean) =
        update { current ->
            current.copy(
                connection = current.connection.copy(safeModeEnabled = current.connection.safeModeEnabled && !value),
                expert = current.expert.copy(allowPrivateOutboundHosts = value),
            )
        }

    suspend fun updatePerAppRoutingMode(value: PerAppRoutingMode) =
        update { current ->
            val nextMode =
                value.takeUnless {
                    it != PerAppRoutingMode.FULL_TUNNEL && current.expert.selectedPackages.isEmpty()
                } ?: PerAppRoutingMode.FULL_TUNNEL
            current.copy(
                connection =
                    current.connection.copy(
                        safeModeEnabled = current.connection.safeModeEnabled && nextMode == PerAppRoutingMode.FULL_TUNNEL,
                    ),
                expert =
                    current.expert.copy(
                        perAppRoutingMode = nextMode,
                    ),
            )
        }

    suspend fun updateSelectedPackages(value: List<String>) =
        update {
            val normalizedSelectedPackages =
                value
                    .filterNot { packageName -> packageName == BuildConfig.APPLICATION_ID }
                    .distinct()
            it.copy(
                expert =
                    it.expert.copy(
                        selectedPackages = normalizedSelectedPackages,
                        blockedPackages = it.expert.blockedPackages.filterNot { packageName -> packageName in normalizedSelectedPackages },
                        perAppRoutingMode =
                            when {
                                normalizedSelectedPackages.isEmpty() -> PerAppRoutingMode.FULL_TUNNEL
                                else -> it.expert.perAppRoutingMode
                            },
                    ),
            )
        }

    suspend fun updateBlockedPackages(value: List<String>) =
        update {
            val normalizedBlockedPackages =
                value
                    .filterNot { packageName -> packageName == BuildConfig.APPLICATION_ID }
                    .distinct()
            it.copy(
                connection = it.connection.copy(safeModeEnabled = false),
                expert =
                    it.expert.copy(
                        blockedPackages = normalizedBlockedPackages,
                        selectedPackages = it.expert.selectedPackages.filterNot { packageName -> packageName in normalizedBlockedPackages },
                        blockedPackagesEnabled = normalizedBlockedPackages.isNotEmpty(),
                    ),
            )
        }

    suspend fun updateBlockedPackagesEnabled(value: Boolean) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value),
                expert =
                    it.expert.copy(
                        blockedPackagesEnabled = value,
                        blockAppsAlways = it.expert.blockAppsAlways && value && it.expert.blockedPackages.isNotEmpty(),
                    ),
            )
        }

    suspend fun updateBlockAppsAlways(value: Boolean) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value),
                expert =
                    it.expert.copy(
                        blockAppsAlways = value && it.expert.blockedPackagesEnabled && it.expert.blockedPackages.isNotEmpty(),
                    ),
            )
        }

    suspend fun updateSiteRoutingAction(value: RoutingRuleAction) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && value == RoutingRuleAction.PROXY),
                expert = it.expert.copy(siteRoutingAction = value.coerceSiteRoutingAction()),
            )
        }

    suspend fun updateProxySurfaceMode(value: ProxySurfaceMode) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = false),
                expert =
                    it.expert.copy(
                        localSurfaces =
                            it.expert.localSurfaces.copy(proxyMode = value).withEnabledProxyMode(value),
                    ),
            )
        }

    suspend fun updateLanProxySurfaceMode(value: ProxySurfaceMode) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = false),
                expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(lanProxyMode = value)),
            )
        }

    suspend fun updateSocksSurface(value: ProxyInboundSettings) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value.enabled),
                expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(socks = value.normalized())),
            )
        }

    suspend fun updateHttpSurface(value: ProxyInboundSettings) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value.enabled),
                expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(http = value.normalized())),
            )
        }

    suspend fun updateMixedSurface(value: ProxyInboundSettings) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value.enabled),
                expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(mixed = value.normalized())),
            )
        }

    suspend fun updateLocalProxyAuthEnabled(value: Boolean) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && value),
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
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && value.enabled),
                expert =
                    it.expert.copy(
                        localSurfaces =
                            it.expert.localSurfaces.copy(
                                auth = value.normalized(),
                            ),
                    ),
            )
        }

    suspend fun updateLanProxyAuthEnabled(value: Boolean) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && value),
                expert =
                    it.expert.copy(
                        localSurfaces =
                            it.expert.localSurfaces.copy(
                                lanAuth = it.expert.localSurfaces.lanAuth.copy(enabled = value),
                            ),
                    ),
            )
        }

    suspend fun updateLanProxyAuth(value: LocalAuthSettings) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && value.enabled),
                expert =
                    it.expert.copy(
                        localSurfaces =
                            it.expert.localSurfaces.copy(
                                lanAuth = value.normalized(),
                            ),
                    ),
            )
        }

    suspend fun updateLocalProxyLanAccessEnabled(value: Boolean) =
        update {
            it.copy(
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value),
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
                connection = it.connection.copy(safeModeEnabled = it.connection.safeModeEnabled && !value.enabled),
                expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(clashApi = value.normalized())),
            )
        }

    suspend fun updateV2RayApi(value: V2RayApiSettings) =
        update {
            it.copy(
                connection =
                    it.connection.copy(
                        safeModeEnabled =
                            it.connection.safeModeEnabled && !(value.enabled || value.statsEnabled),
                    ),
                expert = it.expert.copy(localSurfaces = it.expert.localSurfaces.copy(v2RayApi = value.normalized())),
            )
        }

    suspend fun resetExpertToSafeDefaults() =
        update { current -> current.resetExpertSettingsToSafeDefaults() }

    suspend fun resetExperimentalSettingsToDefaults() =
        update { current -> current.resetExperimentalSettingsToDefaults() }

    suspend fun resetApplicationSettingsToDefaults() =
        update { current -> current.resetApplicationSettingsToDefaults() }

    suspend fun resetUsageTracking(timestamp: Long = System.currentTimeMillis()) =
        update {
            it.copy(
                profileTrafficTotals = emptyList(),
                usageTrackingStartedAt = timestamp,
            )
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
        writeFastDashboardUi(effectiveSettings.ui)
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
                    val migrated = (readLegacySettingsOrNull() ?: defaultSettings()).normalized()
                    writeEncrypted(migrated)
                    deleteLegacySettings()
                    migrated
                }

                is EncryptedSettingsLoadResult.Loaded -> {
                    writeEncrypted(encryptedResult.settings)
                    encryptedResult.settings
                }

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
            writeFastUiSnapshot(next.ui)
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
                    themeMode = parseStoredThemeMode(preferences[LegacySettingsKeys.themeMode]),
                    locale = AppLocale.valueOf(preferences[LegacySettingsKeys.locale] ?: AppLocale.SYSTEM.name),
                    showExpertSettings = true,
                ),
            connection =
                ConnectionSettings(
                    autoReconnect = preferences[LegacySettingsKeys.autoReconnect] ?: true,
                    autoStartOnBoot = preferences[LegacySettingsKeys.autoStartOnBoot] ?: false,
                    ipInfoEndpoint = normalizeIpInfoEndpoint(preferences[LegacySettingsKeys.ipInfoEndpoint].orEmpty()),
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

    private fun hasFastDashboardUi(): Boolean =
        fastUiPreferences.contains(FAST_DASHBOARD_CARD_ORDER_KEY) ||
            fastUiPreferences.contains(FAST_NETWORK_CARD_ENABLED_KEY) ||
            fastUiPreferences.contains(FAST_TRAFFIC_CARD_ENABLED_KEY) ||
            fastUiPreferences.contains(FAST_TRAFFIC_MAP_ENABLED_KEY) ||
            fastUiPreferences.contains(FAST_SHOW_FIREWALL_STATUS_KEY) ||
            fastUiPreferences.contains(FAST_SHOW_TOR_QUICK_LAUNCH_KEY) ||
            fastUiPreferences.contains(FAST_SMART_START_DASHBOARD_CONTROLS_ENABLED_KEY)

    private fun readBootstrapDashboardUi(defaults: UiSettings): UiSettings {
        if (hasFastDashboardUi()) {
            return readFastDashboardUi(defaults)
        }
        val storedUi =
            runCatching {
                when (val result = readEncryptedResult()) {
                    is EncryptedSettingsLoadResult.Loaded -> result.settings.ui
                    EncryptedSettingsLoadResult.Missing,
                    is EncryptedSettingsLoadResult.Corrupt,
                    -> defaults
                }
            }.getOrDefault(defaults)
        return readFastDashboardUi(storedUi)
    }

    private fun readFastDashboardUi(fallback: UiSettings): UiSettings =
        fallback.copy(
            networkCardEnabled =
                fastUiPreferences.getBoolean(
                    FAST_NETWORK_CARD_ENABLED_KEY,
                    fallback.networkCardEnabled,
                ),
            trafficCardEnabled =
                fastUiPreferences.getBoolean(
                    FAST_TRAFFIC_CARD_ENABLED_KEY,
                    fallback.trafficCardEnabled,
                ),
            trafficMapEnabled =
                fastUiPreferences.getBoolean(
                    FAST_TRAFFIC_MAP_ENABLED_KEY,
                    fallback.trafficMapEnabled,
                ),
            showFirewallStatus =
                fastUiPreferences.getBoolean(
                    FAST_SHOW_FIREWALL_STATUS_KEY,
                    fallback.showFirewallStatus,
                ),
            showTorQuickLaunch =
                fastUiPreferences.getBoolean(
                    FAST_SHOW_TOR_QUICK_LAUNCH_KEY,
                    fallback.showTorQuickLaunch,
                ),
            smartStartDashboardControlsEnabled =
                fastUiPreferences.getBoolean(
                    FAST_SMART_START_DASHBOARD_CONTROLS_ENABLED_KEY,
                    fallback.smartStartDashboardControlsEnabled,
                ),
            dashboardCardOrder =
                parseFastDashboardCardOrder(fastUiPreferences.getString(FAST_DASHBOARD_CARD_ORDER_KEY, null))
                    ?: fallback.dashboardCardOrder,
        )

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

    private fun writeFastUiSnapshot(value: UiSettings) {
        fastUiPreferences.edit {
            putString(FAST_THEME_MODE_KEY, value.themeMode.name)
            putString(FAST_LOCALE_KEY, value.locale.name)
            putBoolean(FAST_NETWORK_CARD_ENABLED_KEY, value.networkCardEnabled)
            putBoolean(FAST_TRAFFIC_CARD_ENABLED_KEY, value.trafficCardEnabled)
            putBoolean(FAST_TRAFFIC_MAP_ENABLED_KEY, value.trafficMapEnabled)
            putBoolean(FAST_SHOW_FIREWALL_STATUS_KEY, value.showFirewallStatus)
            putBoolean(FAST_SHOW_TOR_QUICK_LAUNCH_KEY, value.showTorQuickLaunch)
            putBoolean(
                FAST_SMART_START_DASHBOARD_CONTROLS_ENABLED_KEY,
                value.smartStartDashboardControlsEnabled,
            )
            putString(FAST_DASHBOARD_CARD_ORDER_KEY, encodeFastDashboardCardOrder(value.dashboardCardOrder))
        }
    }

    private fun writeFastDashboardUi(value: UiSettings) {
        fastUiPreferences.edit {
            putBoolean(FAST_NETWORK_CARD_ENABLED_KEY, value.networkCardEnabled)
            putBoolean(FAST_TRAFFIC_CARD_ENABLED_KEY, value.trafficCardEnabled)
            putBoolean(FAST_TRAFFIC_MAP_ENABLED_KEY, value.trafficMapEnabled)
            putBoolean(FAST_SHOW_FIREWALL_STATUS_KEY, value.showFirewallStatus)
            putBoolean(FAST_SHOW_TOR_QUICK_LAUNCH_KEY, value.showTorQuickLaunch)
            putBoolean(
                FAST_SMART_START_DASHBOARD_CONTROLS_ENABLED_KEY,
                value.smartStartDashboardControlsEnabled,
            )
            putString(FAST_DASHBOARD_CARD_ORDER_KEY, encodeFastDashboardCardOrder(value.dashboardCardOrder))
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
                readBootstrapDashboardUi(defaults.ui).copy(
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
            val resetDefaults = schemaVersion < SETTINGS_RESET_DEFAULTS_SCHEMA_VERSION
            val enableTrafficMapByDefault = schemaVersion < TRAFFIC_MAP_DEFAULT_ENABLED_SCHEMA_VERSION
            copy(
                schemaVersion = SETTINGS_SCHEMA_VERSION,
                ui =
                    ui.copy(
                        themeMode = if (resetDefaults) ThemeMode.SYSTEM else ui.themeMode,
                        onboardingCompleted = true,
                        showExpertSettings = ui.showExpertSettings && expert.unlockedAt != null,
                        supportBotHandleOverride = storedSupportBotHandleOverride(ui.supportBotHandleOverride),
                        trafficMapEnabled = if (enableTrafficMapByDefault) true else ui.trafficMapEnabled,
                    ),
                connection =
                    connection.copy(
                        ipInfoEndpoint = normalizeIpInfoEndpoint(connection.ipInfoEndpoint),
                        smartStartProtocolSelectionTimeoutSeconds =
                            normalizeSmartStartTimeoutSeconds(
                                value = connection.smartStartProtocolSelectionTimeoutSeconds,
                                minSeconds = SMART_START_PROTOCOL_TIMEOUT_MIN_SECONDS,
                            ),
                        smartStartRefreshSelectionTimeoutSeconds =
                            normalizeSmartStartTimeoutSeconds(
                                value = connection.smartStartRefreshSelectionTimeoutSeconds,
                                minSeconds = SMART_START_REFRESH_TIMEOUT_MIN_SECONDS,
                            ),
                        smartStartSubscriptionRetryAttempts =
                            normalizeSmartStartSubscriptionRetryAttempts(connection.smartStartSubscriptionRetryAttempts),
                        smartStartSubscriptionRetryDelaySeconds =
                            normalizeSmartStartSubscriptionRetryDelaySeconds(connection.smartStartSubscriptionRetryDelaySeconds),
                    ),
                traffic =
                    if (connection.safeModeEnabled) {
                        TrafficSettings()
                    } else {
                        traffic.copy(
                            mtu = traffic.mtu.coerceIn(MIN_MTU, MAX_MTU),
                        )
                    },
                dns = dns.normalized(),
                networkRules = networkRules.normalized(),
                privacyRoute =
                    if (connection.safeModeEnabled) {
                        PrivacyRouteSettings()
                    } else {
                        privacyRoute.normalized()
                    },
                expert =
                    expert.normalized(
                        safeModeEnabled = connection.safeModeEnabled,
                        resetScreenshotBlocking = resetDefaults,
                        storedSchemaVersion = schemaVersion,
                    ),
                statistics = statistics.normalized(),
                smartProfilePreferences = normalizeSmartProfilePreferences(smartProfilePreferences),
                profileTrafficTotals =
                    profileTrafficTotals
                        .groupBy(ProfileTrafficTotal::trafficKey)
                        .values
                        .mapNotNull { items -> items.maxByOrNull(ProfileTrafficTotal::updatedAt) }
                        .sortedByDescending(ProfileTrafficTotal::updatedAt),
                installedAppInventoryAudit = installedAppInventoryAudit.normalized(),
                appTrafficStatsEnabled = appTrafficStatsEnabled,
                usageTrackingStartedAt = usageTrackingStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
            )
        }

    private fun ExpertSettings.normalized(
        safeModeEnabled: Boolean,
        resetScreenshotBlocking: Boolean,
        storedSchemaVersion: Int,
    ): ExpertSettings {
        val normalizedSelectedPackages =
            selectedPackages
                .filterNot { packageName -> packageName == BuildConfig.APPLICATION_ID }
                .distinct()
        val normalizedBlockedPackages =
            blockedPackages
                .filterNot { packageName -> packageName == BuildConfig.APPLICATION_ID }
                .distinct()
                .filterNot { packageName -> packageName in normalizedSelectedPackages }
        val normalized =
            copy(
                selectedPackages = normalizedSelectedPackages,
                blockedPackages = normalizedBlockedPackages,
                blockedPackagesEnabled = blockedPackagesEnabled && normalizedBlockedPackages.isNotEmpty(),
                blockAppsAlways = blockAppsAlways && blockedPackagesEnabled && normalizedBlockedPackages.isNotEmpty(),
                siteRoutingAction = siteRoutingAction.coerceSiteRoutingAction(),
                blockScreenshots = if (resetScreenshotBlocking) false else blockScreenshots,
                firewallEnabled = firewallEnabled,
                newAppQuarantineEnabled = newAppQuarantineEnabled && firewallEnabled,
                systemDnsProtectionEnabled = systemDnsProtectionEnabled,
                rawLiveDiagnostics = rawLiveDiagnostics && BuildConfig.DEBUG,
                smartStartReplayLogging = smartStartReplayLogging && BuildConfig.DEBUG,
                localSurfaces = localSurfaces.normalized().migratedProxySurfaceModesIfNeeded(storedSchemaVersion),
                routeOnly = routeOnly && sniff,
            )
        return if (!safeModeEnabled) {
            normalized
        } else {
            ExpertSettings(
                unlockedAt = normalized.unlockedAt,
                warningAcknowledgedAt = normalized.warningAcknowledgedAt,
                blockScreenshots = normalized.blockScreenshots,
                killSwitchEnabled = normalized.killSwitchEnabled,
                firewallEnabled = normalized.firewallEnabled,
                newAppQuarantineEnabled = normalized.newAppQuarantineEnabled,
                systemDnsProtectionEnabled = normalized.systemDnsProtectionEnabled,
                networkActivityLogging = normalized.networkActivityLogging,
                networkActivityPersistentLogging = normalized.networkActivityPersistentLogging,
                sanitizeNetworkActivityPrivateData = normalized.sanitizeNetworkActivityPrivateData,
                diagnosticsRetention = normalized.diagnosticsRetention,
                smartStartReplayLogging = normalized.smartStartReplayLogging && BuildConfig.DEBUG,
                allowInsecureTls = normalized.allowInsecureTls,
                blockedPackages = normalized.blockedPackages,
                blockedPackagesEnabled = normalized.blockedPackagesEnabled,
                blockAppsAlways = normalized.blockAppsAlways,
            )
        }
    }

    private fun StatisticsSettings.normalized(): StatisticsSettings =
        copy(
            retention =
                when (retention) {
                    StatisticsRetention.WEEK,
                    StatisticsRetention.MONTH,
                    StatisticsRetention.MONTHS_3,
                    StatisticsRetention.FOREVER,
                    -> retention
                },
            refreshInterval =
                when (refreshInterval) {
                    StatisticsRefreshInterval.SECONDS_1,
                    StatisticsRefreshInterval.SECONDS_3,
                    StatisticsRefreshInterval.SECONDS_5,
                    StatisticsRefreshInterval.SECONDS_10,
                    -> refreshInterval
                },
        )

    private fun InstalledAppInventoryAudit.normalized(): InstalledAppInventoryAudit =
        copy(
            capturedAt = capturedAt.takeIf { it > 0L } ?: 0L,
            packages =
                packages
                    .filter { app -> app.packageName.isNotBlank() }
                    .distinctBy(InstalledAppInventoryEntry::packageName)
                    .sortedBy(InstalledAppInventoryEntry::packageName),
            recentChanges =
                recentChanges
                    .filter { change -> change.packageName.isNotBlank() && change.detectedAt > 0L }
                    .distinctBy { change -> "${change.type}:${change.packageName}:${change.detectedAt}" }
                    .sortedByDescending(InstalledAppInventoryChange::detectedAt)
                    .take(INSTALLED_APP_CHANGE_HISTORY_LIMIT),
        )

    private fun PrivacyRouteSettings.normalized(): PrivacyRouteSettings =
        copy(
            bypassVpnTunnel = bypassVpnTunnel && enabled,
            selectedPackages =
                selectedPackages
                    .filterNot { packageName -> packageName == BuildConfig.APPLICATION_ID }
                    .distinct(),
        )

    private fun DnsSettings.normalized(): DnsSettings =
        copy(
            server = normalizedDnsServer(server),
            appBypassPackages =
                appBypassPackages
                    .filterNot { packageName -> packageName == BuildConfig.APPLICATION_ID }
                    .distinct(),
            domainBypassRules =
                domainBypassRules
                    .flatMap { raw -> raw.split(',', ';', '\n') }
                    .map(String::trim)
                    .map { value -> value.removePrefix("*.").removePrefix(".").lowercase() }
                    .filter { value -> value.isNotBlank() && value.length <= 253 }
                    .distinct(),
            dnsFilterUpdateUrl = normalizeDnsFilterUpdateUrl(dnsFilterUpdateUrl),
            filtersUpdatedAt = filtersUpdatedAt?.takeIf { it > 0L },
        )

    private fun NetworkRulesSettings.normalized(): NetworkRulesSettings =
        copy(
            wifiProfileId = wifiProfileId?.takeIf { it > 0L },
            wifiProtocolOptionId = wifiProtocolOptionId?.trim()?.takeIf(String::isNotBlank),
            useWifiProfile = useWifiProfile && wifiProfileId != null && wifiProfileId > 0L,
            cellularProfileId = cellularProfileId?.takeIf { it > 0L },
            useCellularProfile = useCellularProfile && cellularProfileId != null && cellularProfileId > 0L,
        )

    private fun LocalSurfaceSettings.normalized(): LocalSurfaceSettings =
        copy(
            proxyMode = proxyMode,
            lanProxyMode = lanProxyMode,
            socks = socks.normalized(),
            http = http.normalized(),
            mixed = mixed.normalized(),
            allowLanAccess = allowLanAccess,
            clashApi = clashApi.normalized(),
            v2RayApi = v2RayApi.normalized(),
            auth = auth.normalized(),
            lanAuth = lanAuth.normalized(),
        )

    private fun LocalSurfaceSettings.withProxyModeDefaults(enableDefaults: Boolean): LocalSurfaceSettings {
        if (!enableDefaults) {
            return this
        }
        return withEnabledProxyMode(proxyMode)
    }

    private fun LocalSurfaceSettings.withEnabledProxyMode(mode: ProxySurfaceMode): LocalSurfaceSettings =
        when (mode) {
            ProxySurfaceMode.SOCKS5 -> copy(
                proxyMode = mode,
                socks = socks.copy(enabled = true),
                http = http.copy(enabled = false),
                mixed = mixed.copy(enabled = false),
            )
            ProxySurfaceMode.HTTP -> copy(
                proxyMode = mode,
                socks = socks.copy(enabled = false),
                http = http.copy(enabled = true),
                mixed = mixed.copy(enabled = false),
            )
            ProxySurfaceMode.ALL -> copy(
                proxyMode = mode,
                socks = socks.copy(enabled = false),
                http = http.copy(enabled = false),
                mixed = mixed.copy(enabled = true),
            )
        }

    private fun LocalSurfaceSettings.migratedProxySurfaceModesIfNeeded(schemaVersion: Int): LocalSurfaceSettings {
        if (schemaVersion >= SETTINGS_SCHEMA_VERSION) {
            return this
        }
        val migratedMode =
            when {
                http.enabled -> ProxySurfaceMode.HTTP
                socks.enabled -> ProxySurfaceMode.SOCKS5
                mixed.enabled -> ProxySurfaceMode.ALL
                else -> proxyMode
            }
        return copy(
            proxyMode = migratedMode,
            lanProxyMode = migratedMode,
        ).withEnabledProxyMode(migratedMode)
    }

    private fun com.foxhole.beta.core.model.LocalAuthSettings.normalized(): com.foxhole.beta.core.model.LocalAuthSettings =
        copy(
            enabled = enabled,
            username = username.trim().ifBlank { DEFAULT_PROXY_LOGIN },
            password = password.trim().ifBlank { randomLocalProxyPassword() },
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

    companion object {
        private const val SETTINGS_RESET_DEFAULTS_SCHEMA_VERSION = 16
        private const val TRAFFIC_MAP_DEFAULT_ENABLED_SCHEMA_VERSION = 17
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
        private const val MIN_MTU = 576
        private const val MAX_MTU = 9_000
        private const val DEFAULT_PROXY_LOGIN = "foxhole"
        private const val PROXY_PASSWORD_PREFIX = "foxhole-"
        private const val PROXY_PASSWORD_RANDOM_LENGTH = 4
        private const val PROXY_PASSWORD_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private const val INSTALLED_APP_CHANGE_HISTORY_LIMIT = 60
        private val secureRandom = SecureRandom()

        private fun randomLocalProxyPassword(): String =
            buildString(PROXY_PASSWORD_PREFIX.length + PROXY_PASSWORD_RANDOM_LENGTH) {
                append(PROXY_PASSWORD_PREFIX)
                repeat(PROXY_PASSWORD_RANDOM_LENGTH) {
                    append(PROXY_PASSWORD_ALPHABET[secureRandom.nextInt(PROXY_PASSWORD_ALPHABET.length)])
                }
            }
    }
}

private fun DnsSettings.runtimeRequiresExplicitTunnel(): Boolean =
    filteringEnabled ||
        dnsThroughVpn ||
        blockOutsideTunnel ||
        interceptDnsRequests ||
        appBypassPackages.isNotEmpty() ||
        domainBypassRules.isNotEmpty()

private fun normalizedDnsServer(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) {
        return DEFAULT_DNS_SERVER
    }
    val url = trimmed.toHttpUrlOrNull()
    return url?.host ?: trimmed
        .removePrefix("https://")
        .removePrefix("tls://")
        .removeSuffix("/dns-query")
        .trim()
        .ifBlank { DEFAULT_DNS_SERVER }
}

internal fun normalizeDnsFilterUpdateUrl(value: String): String =
    runCatching {
        val url = value.trim().ensurePublicHttpsUrl()
        when {
            url.isOfficialFoxholeDnsRepositoryUrl() -> DEFAULT_DNS_FILTER_UPDATE_URL
            url.isOfficialFoxholeDnsPagesDirectoryUrl() -> DEFAULT_DNS_FILTER_UPDATE_URL
            url.pathSegments.lastOrNull().orEmpty().endsWith(".json", ignoreCase = true) -> url.toString()
            else -> url.newBuilder().addPathSegment("manifest.json").build().toString()
        }
    }.getOrDefault(DEFAULT_DNS_FILTER_UPDATE_URL)

private fun okhttp3.HttpUrl.isOfficialFoxholeDnsRepositoryUrl(): Boolean {
    val normalizedPath = encodedPath.trim('/').removeSuffix(".git")
    return host.equals("github.com", ignoreCase = true) &&
        normalizedPath.equals("foxhole-repo/foxhole-dns", ignoreCase = true)
}

private fun okhttp3.HttpUrl.isOfficialFoxholeDnsPagesDirectoryUrl(): Boolean {
    val normalizedPath = encodedPath.trim('/').removeSuffix("/")
    return host.equals("foxhole-repo.github.io", ignoreCase = true) &&
        normalizedPath.equals("foxhole-dns", ignoreCase = true)
}

private const val DEFAULT_DNS_SERVER = "1.1.1.1"

internal fun Settings.resetExpertSettingsToSafeDefaults(): Settings =
    copy(
        connection = connection.copy(safeModeEnabled = true),
        traffic = TrafficSettings(),
        expert =
            ExpertSettings(
                unlockedAt = expert.unlockedAt,
                warningAcknowledgedAt = null,
                blockScreenshots = expert.blockScreenshots,
            ),
    )

internal fun Settings.resetExperimentalSettingsToDefaults(): Settings =
    copy(
        expert =
            ExpertSettings(
                unlockedAt = expert.unlockedAt,
                warningAcknowledgedAt = null,
                blockScreenshots = expert.blockScreenshots,
            ),
    )

internal fun Settings.hasCustomExperimentalSettings(): Boolean =
    expert.experimentalSettingsComparable() != ExpertSettings().experimentalSettingsComparable()

private fun ExpertSettings.experimentalSettingsComparable(): ExpertSettings =
    copy(
        unlockedAt = null,
        warningAcknowledgedAt = null,
        blockScreenshots = false,
        localSurfaces =
            localSurfaces.copy(
                clashApi = localSurfaces.clashApi.copy(secret = ""),
                auth =
                    localSurfaces.auth.copy(
                        username = "",
                        password = "",
                        apiSecret = "",
                    ),
                lanAuth =
                    localSurfaces.lanAuth.copy(
                        username = "",
                        password = "",
                        apiSecret = "",
                    ),
            ),
    )

internal fun Settings.resetApplicationSettingsToDefaults(): Settings =
    copy(
        ui = UiSettings(),
        connection = ConnectionSettings(ipInfoEndpoint = BuildConfig.DEFAULT_IP_INFO_ENDPOINT),
        traffic = TrafficSettings(),
        networkRules = NetworkRulesSettings(),
        expert = ExpertSettings(),
    )

internal fun Settings.withExpertSettingsVisibility(visible: Boolean): Settings =
    if (visible) {
        copy(
            ui = ui.copy(showExpertSettings = true),
            expert = expert.copy(unlockedAt = expert.unlockedAt ?: System.currentTimeMillis()),
        )
    } else {
        copy(
            ui = ui.copy(showExpertSettings = false),
        )
    }

private fun ExpertSettings.quarantinePackage(packageName: String): ExpertSettings {
    val normalizedPackageName = packageName.trim().takeIf(String::isNotBlank) ?: return this
    val blocked = (blockedPackages + normalizedPackageName).distinct()
    return copy(
        firewallEnabled = true,
        blockedPackages = blocked,
        selectedPackages = selectedPackages.filterNot { selectedPackage -> selectedPackage in blocked },
        blockedPackagesEnabled = true,
        blockAppsAlways = true,
    )
}

internal fun Settings.smartProfilePreference(profileId: Long): SmartProfilePreference? =
    smartProfilePreferences.firstOrNull { preference -> preference.profileId == profileId }

private fun RoutingRuleAction.coerceSiteRoutingAction(): RoutingRuleAction =
    when (this) {
        RoutingRuleAction.PROXY,
        RoutingRuleAction.DIRECT,
        -> this
        RoutingRuleAction.BLOCK -> RoutingRuleAction.PROXY
    }

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
internal const val SMART_START_REMEMBERED_LATENCY_RETENTION_MS = 21L * 24L * 60L * 60L * 1000L
internal const val SMART_START_MEMORY_RETENTION_MS = SMART_START_REMEMBERED_LATENCY_RETENTION_MS
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

internal fun Settings.rememberedSmartProfileDownOptionIdsByProfileId(
    networkFingerprint: String?,
    now: Long = System.currentTimeMillis(),
): Map<Long, Set<String>> =
    smartProfilePreferences
        .mapNotNull { preference ->
            preference
                .rememberedSmartProfileDownOptionIds(networkFingerprint = networkFingerprint, now = now)
                .takeIf(Set<String>::isNotEmpty)
                ?.let { downOptionIds -> preference.profileId to downOptionIds }
        }.toMap()

internal fun Settings.rememberedSmartProfileLatencyUnavailableByProfileId(
    networkFingerprint: String?,
    now: Long = System.currentTimeMillis(),
): Map<Long, Set<String>> =
    smartProfilePreferences
        .mapNotNull { preference ->
            preference
                .rememberedSmartProfileLatencyUnavailableOptionIds(networkFingerprint = networkFingerprint, now = now)
                .takeIf(Set<String>::isNotEmpty)
                ?.let { unavailableOptionIds -> preference.profileId to unavailableOptionIds }
        }.toMap()

internal fun SmartProfilePreference.rememberedSmartProfileDownOptionIds(
    networkFingerprint: String?,
    now: Long = System.currentTimeMillis(),
): Set<String> {
    val scopedMemories =
        networkMemory(networkFingerprint)
            ?.protocolMemories
            ?.associateBy(SmartProfileProtocolMemory::optionId)
            .orEmpty()
    val globalMemories = protocolMemories.associateBy(SmartProfileProtocolMemory::optionId)
    return (scopedMemories.keys + globalMemories.keys)
        .filter { optionId ->
            val scopedDown = scopedMemories[optionId].freshRememberedDown(now)
            val globalDown = globalMemories[optionId].freshRememberedDown(now)
            scopedDown || globalDown
        }.toSet()
}

internal fun SmartProfilePreference.rememberedSmartProfileLatencyUnavailableOptionIds(
    networkFingerprint: String?,
    now: Long = System.currentTimeMillis(),
): Set<String> {
    val scopedMemories =
        networkMemory(networkFingerprint)
            ?.protocolMemories
            ?.associateBy(SmartProfileProtocolMemory::optionId)
            .orEmpty()
    val globalMemories = protocolMemories.associateBy(SmartProfileProtocolMemory::optionId)
    return (scopedMemories.keys + globalMemories.keys)
        .filter { optionId ->
            val scopedUnavailable = scopedMemories[optionId].freshRememberedLatencyUnavailable(now)
            val globalUnavailable = globalMemories[optionId].freshRememberedLatencyUnavailable(now)
            scopedUnavailable || globalUnavailable
        }.toSet()
}

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

private fun SmartProfileProtocolMemory?.freshRememberedDown(
    now: Long,
    retentionMs: Long = SMART_START_REMEMBERED_LATENCY_RETENTION_MS,
): Boolean {
    val memory = this ?: return false
    val failureAt = memory.lastFailureAt?.takeIf { it > 0L } ?: return false
    val successAt = memory.lastSuccessAt?.takeIf { it > 0L }
    val failureIsLatest = successAt == null || failureAt >= successAt
    val cooldownActive = memory.cooldownUntilAt?.let { cooldownUntil -> cooldownUntil > now } == true
    return failureIsLatest && (cooldownActive || now - failureAt <= retentionMs)
}

private fun SmartProfileProtocolMemory?.freshRememberedLatencyUnavailable(
    now: Long,
    retentionMs: Long = SMART_START_REMEMBERED_LATENCY_RETENTION_MS,
): Boolean {
    val memory = this
    val successAt = memory?.lastSuccessAt?.takeIf { it > 0L }
    val failureAt = memory?.lastFailureAt?.takeIf { it > 0L }
    return memory != null &&
        memory.lastLatencyMs?.takeIf { it > 0L } == null &&
        memory.lastReasonCode == AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED &&
        successAt != null &&
        (failureAt == null || successAt >= failureAt) &&
        now - successAt <= retentionMs
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

private data class ProfileTrafficKey(
    val profileId: Long,
    val protocolOptionId: String?,
)

private fun ProfileTrafficTotal.trafficKey(): ProfileTrafficKey =
    ProfileTrafficKey(
        profileId = profileId,
        protocolOptionId = protocolOptionId.normalizedProfileTrafficProtocolOptionId(),
    )

private fun String?.normalizedProfileTrafficProtocolOptionId(): String? =
    this?.trim()?.takeIf(String::isNotBlank)

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
