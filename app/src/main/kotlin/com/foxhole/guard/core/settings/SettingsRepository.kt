package com.foxhole.guard.core.settings

import android.content.Context
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.CachedActiveProfile
import com.foxhole.core.model.KnownApplicationIdentity
import com.foxhole.core.model.LatencyProbeMethod
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.SubscriptionRefreshInterval
import com.foxhole.core.model.ThemeMode
import com.foxhole.core.model.TrafficSettings
import com.foxhole.core.runtime.AndroidApplicationIdentityResolver
import com.foxhole.guard.core.data.ProfileMutationCoordinator
import com.foxhole.guard.core.data.ProfileMutationLease
import com.foxhole.guard.core.sentinel.InstalledAppSecurityAnalyzer
import com.foxhole.guard.core.sentinel.SentinelThreatIntelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    private val onQuarantinePolicyRevisionChanged: (revision: Long) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val threatIntelProvider by lazy { SentinelThreatIntelProvider(appContext) }
    private val applicationIdentityResolver by lazy { AndroidApplicationIdentityResolver(appContext) }
    internal val installedAppSecurityAnalyzer by lazy {
        InstalledAppSecurityAnalyzer(appContext, threatIntelProvider::current, onSecurityAnalysisFallback)
    }
    internal val storage = SettingsRepositoryStorage(appContext)
    private val lock = Mutex()
    internal val profileMutationCoordinator = ProfileMutationCoordinator()
    private val settingsMutable: MutableStateFlow<Settings>
    private val hydratedMutable = MutableStateFlow(false)
    private val routerHomeShortcutVisibleMutable = MutableStateFlow(storage.readRouterHomeShortcutVisible())
    private val routerDockShortcutVisibleMutable = MutableStateFlow(storage.readRouterDockShortcutVisible())
    private val dockOptionalOrderMutable = MutableStateFlow(storage.readDockOptionalOrder())

    private val hydration = SettingsHydration()

    val settings: StateFlow<Settings>
        get() = settingsMutable

    val hydrated: StateFlow<Boolean>
        get() = hydratedMutable

    internal val dockOptionalOrder: StateFlow<List<String>>
        get() = dockOptionalOrderMutable

    internal fun updateDockOptionalOrder(value: List<String>) {
        val normalized = value.filter { it in setOf("WEBAPPS", "STATS", "ROUTER") }.distinct()
        if (dockOptionalOrderMutable.value == normalized) return
        storage.writeDockOptionalOrder(normalized)
        dockOptionalOrderMutable.value = normalized
    }

    internal val routerHomeShortcutVisible: StateFlow<Boolean>
        get() = routerHomeShortcutVisibleMutable

    internal fun updateRouterHomeShortcutVisible(value: Boolean) {
        storage.writeRouterHomeShortcutVisible(value)
        routerHomeShortcutVisibleMutable.value = value
    }

    internal val routerDockShortcutVisible: StateFlow<Boolean>
        get() = routerDockShortcutVisibleMutable

    internal fun updateRouterDockShortcutVisible(value: Boolean) {
        storage.writeRouterDockShortcutVisible(value)
        routerDockShortcutVisibleMutable.value = value
    }

    init {
        val bootstrapSettings = storage.bootstrapInitialSettings()
        settingsMutable = MutableStateFlow(bootstrapSettings)
    }

    suspend fun warmUp(): Settings {
        ensureInitialized()
        return settingsMutable.value
    }

    suspend fun current(): Settings {
        ensureInitialized()
        return settingsMutable.value
    }

    internal suspend fun restoreExactCheckpoint(value: Settings) {
        lock.withLock {
            val normalized = value.normalized()
            withContext(Dispatchers.IO) {
                storage.writeEncrypted(normalized)
            }
            hydration.replace(normalized)
            settingsMutable.value = normalized
            hydratedMutable.value = true
            storage.writeFastUiSnapshot(normalized.ui)
            storage.writeFastAppLockUi(normalized.appLock)
        }
    }

    internal suspend fun <T> readRestoreSnapshot(owner: ProfileMutationLease, block: suspend (Settings) -> T): T {
        ensureInitialized()
        return readSettingsSnapshotUnderOwner(lock, profileMutationCoordinator, owner, { settingsMutable.value }, block)
    }

    internal suspend fun <T> withRestoreSettings(
        owner: ProfileMutationLease,
        block: suspend (SettingsRestoreSession) -> T,
    ): T {
        ensureInitialized()
        return lock.withLock(owner) {
            owner.requireOwner(profileMutationCoordinator)
            val initial = settingsMutable.value
            val session = SettingsRestoreSession(
                checkpoint = initial,
                requireOwner = { check(lock.holdsLock(owner)) { "restore settings lock is not owned" } },
                write = { value ->
                    withContext(Dispatchers.IO) {
                        storage.writeEncrypted(value)
                        storage.writeFastUiSnapshot(value.ui)
                        storage.writeFastAppLockUi(value.appLock)
                    }
                },
                publish = { value ->
                    hydration.replace(value)
                    settingsMutable.value = value
                    hydratedMutable.value = true
                    if (value.expert.quarantinePolicyRevision != initial.expert.quarantinePolicyRevision) {
                        val revision = value.expert.quarantinePolicyRevision
                        owner.afterCommit(
                            retryKey = "quarantine-policy"
                        ) { onQuarantinePolicyRevisionChanged(revision) }
                    }
                },
            )
            owner.bindSettings(session)
            try {
                block(session)
            } finally {
                session.close()
            }
        }
    }

    internal suspend fun replaceForTests(value: Settings) = restoreExactCheckpoint(value)

    suspend fun updateThemeMode(value: ThemeMode) {
        update {
            it.copy(
                ui = it.ui.copy(themeMode = value, panelAppearance = PanelAppearance.AUTO),
            )
        }
    }

    suspend fun updateLocale(value: AppLocale) {
        updateLocaleIfCurrent(value, null)
    }

    internal suspend fun updateLocaleIfCurrent(value: AppLocale, expected: AppLocale?): Boolean {
        ensureInitialized()
        return lock.withLock {
            val current = settingsMutable.value
            if (expected != null && current.ui.locale != expected) return@withLock false
            val next = current.copy(ui = current.ui.copy(locale = value))
            if (next == current) return@withLock true
            val committed = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                withContext(Dispatchers.IO) {
                    storage.writeEncrypted(next)
                    committed.set(true)
                }
            } finally {
                if (committed.get()) {
                    // Publish an already durable locale commit even after IO prompt cancellation.
                    withContext(NonCancellable) {
                        hydration.replace(next)
                        settingsMutable.value = next
                        withContext(Dispatchers.IO) { storage.writeFastLocale(value) }
                    }
                }
            }
            true
        }
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

    suspend fun updateAutoReconnect(value: Boolean) =
        update { it.copy(connection = it.connection.copy(autoReconnect = value)) }

    suspend fun updateAtomicConnection(value: Boolean) =
        update { it.copy(connection = it.connection.copy(atomicConnection = value)) }

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
        val cached = hydration.cached()
        return if (cached != null) {
            cached
        } else {
            lock.withLock {
                val locked = hydration.cached()
                if (locked != null) {
                    locked
                } else {
                    val initialized = hydration.load { storage.loadAndFinalizeInitialSettings() }
                    settingsMutable.value = initialized
                    hydratedMutable.value = true
                    initialized
                }
            }
        }
    }

    internal suspend fun update(transform: (Settings) -> Settings) = updateVerified(transform) {}

    internal suspend fun updateVerified(
        transform: (Settings) -> Settings,
        verifyPersisted: (Settings) -> Unit,
    ) {
        ensureInitialized()
        var changedQuarantineRevision: Long? = null
        lock.withLock {
            val current = settingsMutable.value
            val transformed = transform(current).normalized()
            val next = withAdvancedQuarantinePolicyRevision(current, transformed)
            if (next.expert.quarantinePolicyRevision != current.expert.quarantinePolicyRevision) {
                changedQuarantineRevision = next.expert.quarantinePolicyRevision
            }
            if (next == current) {
                withContext(Dispatchers.IO) { verifyPersisted(next) }
                return@withLock
            }
            withContext(Dispatchers.IO) {
                storage.writeEncrypted(next)
                verifyPersisted(next)
            }
            hydration.replace(next)
            settingsMutable.value = next
            storage.writeFastUiSnapshot(next.ui)
            storage.writeFastAppLockUi(next.appLock)
        }
        changedQuarantineRevision?.let { revision ->

            runCatching { onQuarantinePolicyRevisionChanged(revision) }
        }
    }

    /** Captures one fail-closed baseline for native new-app quarantine. */
    internal suspend fun captureKnownApplicationsForQuarantine(): List<KnownApplicationIdentity> =
        withContext(Dispatchers.IO) {
            applicationIdentityResolver.snapshotKnownApplications().getOrThrow()
        }.also { applications ->
            require(applications.isNotEmpty()) { "installed application baseline is empty" }
            require(applications.size <= MAX_QUARANTINE_KNOWN_APPLICATIONS) {
                "installed application baseline exceeds native policy capacity"
            }
        }

    internal suspend fun currentQuarantineIdentity(packageName: String): KnownApplicationIdentity? =
        captureKnownApplicationsForQuarantine()
            .firstOrNull { application -> application.packageName == packageName }

    companion object {
        private const val DEFAULT_PROXY_LOGIN = "foxhole"
        private const val PROXY_PASSWORD_PREFIX = "foxhole-"
        private const val PROXY_PASSWORD_RANDOM_LENGTH = 22
        private const val LEGACY_PROXY_PASSWORD_RANDOM_LENGTH = 4
        private const val MAX_QUARANTINE_KNOWN_APPLICATIONS = 4_096
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

private data class QuarantineEnforcementIdentity(
    val enabled: Boolean,
    val knownApplications: List<KnownApplicationIdentity>,
    val pendingPackages: List<String>,
    val blockedPackages: List<String>,
    val blockedPackagesEnabled: Boolean,
    val blockAppsAlways: Boolean,
)

private fun Settings.quarantineEnforcementIdentity(): QuarantineEnforcementIdentity =
    QuarantineEnforcementIdentity(
        enabled = expert.newAppQuarantineEnabled,
        knownApplications = expert.quarantineKnownApplications.sortedBy(KnownApplicationIdentity::packageName),
        pendingPackages = expert.pendingQuarantinePackages.sorted(),
        blockedPackages =
        expert.appAssignments
            .asSequence()
            .filter { (_, lane) -> lane == AppTunnelLane.BLOCK }
            .map(Map.Entry<String, AppTunnelLane>::key)
            .sorted()
            .toList(),
        blockedPackagesEnabled = expert.blockedPackagesEnabled,
        blockAppsAlways = expert.blockAppsAlways,
    )

internal fun withAdvancedQuarantinePolicyRevision(
    current: Settings,
    transformed: Settings,
): Settings {
    if (transformed.quarantineEnforcementIdentity() == current.quarantineEnforcementIdentity()) {
        return transformed
    }
    val nextRevision =
        maxOf(
            current.expert.quarantinePolicyRevision,
            transformed.expert.quarantinePolicyRevision,
        ).incrementSaturated()
    return transformed.copy(
        expert = transformed.expert.copy(quarantinePolicyRevision = nextRevision),
    )
}

private fun Long.incrementSaturated(): Long = if (this == Long.MAX_VALUE) this else this + 1L
