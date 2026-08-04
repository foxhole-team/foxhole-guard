package com.foxhole.guard.core.settings

import android.content.Context
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.CachedActiveProfile
import com.foxhole.core.model.LatencyProbeMethod
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.SubscriptionRefreshInterval
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.TrafficSettings
import com.foxhole.guard.core.sentinel.InstalledAppSecurityAnalyzer
import com.foxhole.guard.core.sentinel.SentinelThreatIntelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.UUID

class SettingsRepository(
    context: Context,
    private val onSecurityAnalysisFallback: (packageName: String, error: Throwable) -> Unit = { _, _ -> },
) {
    private val appContext = context.applicationContext
    private val threatIntelProvider by lazy { SentinelThreatIntelProvider(appContext) }
    internal val installedAppSecurityAnalyzer by lazy {
        InstalledAppSecurityAnalyzer(appContext, threatIntelProvider::current, onSecurityAnalysisFallback)
    }
    internal val storage = SettingsRepositoryStorage(appContext)
    private val lock = Mutex()
    private val settingsMutable: MutableStateFlow<Settings>
    private val themeModeMutable: MutableStateFlow<ThemeMode>
    private val hydratedMutable = MutableStateFlow(false)

    @Volatile
    private var initializationResult: Result<Settings>? = null

    val settings: StateFlow<Settings>
        get() = settingsMutable

    val themeMode: StateFlow<ThemeMode>
        get() = themeModeMutable

    /**
     * False until the encrypted settings have actually been loaded from disk. Until then
     * [settings] carries bootstrap defaults (only the fast UI snapshot is real), so screens that
     * gate content on a toggle must not treat the pre-hydration value as the user's choice.
     */
    val hydrated: StateFlow<Boolean>
        get() = hydratedMutable

    init {
        val bootstrapSettings = storage.bootstrapInitialSettings()
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
                storage.writeEncrypted(normalized)
            }
            initializationResult = Result.success(normalized)
            settingsMutable.value = normalized
            themeModeMutable.value = normalized.ui.themeMode
            hydratedMutable.value = true
            storage.writeFastUiSnapshot(normalized.ui)
            storage.writeFastAppLockUi(normalized.appLock)
        }
    }

    suspend fun updateThemeMode(value: ThemeMode) {
        ensureInitialized()
        storage.writeFastThemeMode(value)
        themeModeMutable.value = value
        update { it.copy(ui = it.ui.copy(themeMode = value)) }
    }

    suspend fun updateLocale(value: AppLocale) {
        ensureInitialized()
        storage.writeFastLocale(value)
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

    suspend fun acknowledgeBetaNotice() =
        update { it.copy(ui = it.ui.copy(betaNoticeAcknowledged = true)) }

    suspend fun updateAutoReconnect(value: Boolean) =
        update { it.copy(connection = it.connection.copy(autoReconnect = value)) }

    suspend fun updateAutoStartOnBoot(value: Boolean) =
        update { it.copy(connection = it.connection.copy(autoStartOnBoot = value)) }

    suspend fun updateAutoRefreshSubscriptions(value: Boolean) =
        update { it.copy(connection = it.connection.copy(autoRefreshSubscriptions = value)) }

    suspend fun updateSubscriptionRefreshInterval(value: SubscriptionRefreshInterval) =
        update { it.copy(connection = it.connection.copy(subscriptionRefreshInterval = value)) }

    // Turning safe mode ON is an explicit user action, so unlike passive normalization it also
    // resets the whole traffic and privacy-route blocks. What it does to the expert block is
    // delegated to the ONE shared definition — see [disarmedBySafeMode].
    suspend fun updateSafeModeEnabled(value: Boolean) =
        update { current ->
            if (value) {
                current.copy(
                    connection = current.connection.copy(safeModeEnabled = true),
                    traffic = TrafficSettings(),
                    privacyRoute = PrivacyRouteSettings(),
                    expert = current.expert.disarmedBySafeMode(),
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

    suspend fun updateGeoIpAutoUpdate(value: Boolean) =
        update { it.copy(connection = it.connection.copy(geoIpAutoUpdate = value)) }

    suspend fun updateComponentUpdateCheckEnabled(value: Boolean) =
        update { it.copy(connection = it.connection.copy(componentUpdateCheckEnabled = value)) }

    // The ONE auto-update switch of the "Component updates" screen: it mirrors into every
    // per-component flag so the workers' own re-checks (and the legacy scheduling paths) agree.
    suspend fun updateComponentAutoUpdate(value: Boolean) =
        update {
            it.copy(
                connection = it.connection.copy(componentAutoUpdateEnabled = value, geoIpAutoUpdate = value),
                dns = it.dns.copy(autoUpdateFilters = value),
                privacyRoute = it.privacyRoute.copy(bridgesAutoUpdate = value),
            )
        }

    // After the "delete downloaded databases" action: the on-disk overrides are gone, so the
    // update stamps go back to the never-downloaded state the fresh-install UI shows.
    suspend fun clearComponentUpdateStamps() =
        update {
            it.copy(
                dns = it.dns.copy(filtersUpdatedAt = null, filtersCheckedAt = null),
                privacyRoute =
                it.privacyRoute.copy(
                    bridgesUpdatedAt = null,
                    bridgesCheckedAt = null,
                    bridgesLastUpdateSuccess = null,
                ),
            )
        }

    suspend fun updateGeoOfflineMode(value: Boolean) =
        update { it.copy(connection = it.connection.copy(geoOfflineMode = value)) }

    suspend fun updateLastActiveProfile(value: CachedActiveProfile?) =
        update { current ->
            if (current.lastActiveProfile == value) {
                current
            } else {
                current.copy(lastActiveProfile = value)
            }
        }

    private suspend fun ensureInitialized(): Settings {
        val cachedResult = initializationResult
        return if (cachedResult != null) {
            cachedResult.getOrThrow()
        } else {
            lock.withLock {
                val lockedResult = initializationResult
                if (lockedResult != null) {
                    lockedResult.getOrThrow()
                } else {
                    val loadedResult = runCatching { storage.loadAndFinalizeInitialSettings() }
                    initializationResult = loadedResult
                    loadedResult.onSuccess { initialized ->
                        settingsMutable.value = initialized
                        themeModeMutable.value = initialized.ui.themeMode
                        hydratedMutable.value = true
                    }
                    loadedResult.getOrThrow()
                }
            }
        }
    }

    internal suspend fun update(transform: (Settings) -> Settings) {
        ensureInitialized()
        lock.withLock {
            val current = settingsMutable.value
            val next = transform(current).normalized()
            if (next == current) {
                return@withLock
            }
            withContext(Dispatchers.IO) {
                storage.writeEncrypted(next)
            }
            initializationResult = Result.success(next)
            settingsMutable.value = next
            themeModeMutable.value = next.ui.themeMode
            storage.writeFastUiSnapshot(next.ui)
            storage.writeFastAppLockUi(next.appLock)
        }
    }

    companion object {
        private const val DEFAULT_PROXY_LOGIN = "foxhole"
        private const val PROXY_PASSWORD_PREFIX = "foxhole-"
        private const val PROXY_PASSWORD_RANDOM_LENGTH = 22
        private const val LEGACY_PROXY_PASSWORD_RANDOM_LENGTH = 4
        private const val LEGACY_PROXY_PASSWORD_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private const val PROXY_PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private val secureRandom = SecureRandom()

        internal fun normalizeLocalProxyAuthForStorage(value: LocalAuthSettings): LocalAuthSettings {
            val normalizedPassword = value.password.trim()
            val password =
                if (normalizedPassword.isBlank() || normalizedPassword.isLegacyGeneratedLocalProxyPassword()) {
                    randomLocalProxyPassword()
                } else {
                    normalizedPassword
                }
            val apiSecret =
                value.apiSecret.trim().ifBlank {
                    UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().take(8)
                }
            return value.copy(
                enabled = value.enabled,
                username = value.username.trim().ifBlank { DEFAULT_PROXY_LOGIN },
                password = password,
                apiSecret = apiSecret,
            )
        }

        private fun randomLocalProxyPassword(): String =
            buildString(PROXY_PASSWORD_PREFIX.length + PROXY_PASSWORD_RANDOM_LENGTH) {
                append(PROXY_PASSWORD_PREFIX)
                repeat(PROXY_PASSWORD_RANDOM_LENGTH) {
                    append(PROXY_PASSWORD_ALPHABET[secureRandom.nextInt(PROXY_PASSWORD_ALPHABET.length)])
                }
            }

        private fun String.isLegacyGeneratedLocalProxyPassword(): Boolean =
            length == PROXY_PASSWORD_PREFIX.length + LEGACY_PROXY_PASSWORD_RANDOM_LENGTH &&
                startsWith(PROXY_PASSWORD_PREFIX) &&
                drop(PROXY_PASSWORD_PREFIX.length).all { it in LEGACY_PROXY_PASSWORD_ALPHABET }
    }
}
