package com.foxhole.guard

import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.glance.appwidget.updateAll
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.RetentionPolicy
import com.foxhole.core.model.Settings
import com.foxhole.core.model.SubscriptionRefreshInterval
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.core.model.effectiveDiagnosticsRetention
import com.foxhole.core.profile.PROFILE_EXPORT_DIR_NAME
import com.foxhole.core.profile.cleanupProfileExportArtifacts
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.guard.core.data.ProfileSecretCleanupWorker
import com.foxhole.guard.core.data.cleanupOrphanProfileSecrets
import com.foxhole.guard.core.diagnostics.DiagnosticsSessionStore
import com.foxhole.guard.core.settings.readFastStoredAppLocale
import com.foxhole.guard.guardian.GuardHeartbeatWorker
import com.foxhole.guard.guardian.enqueuePendingQuarantineAnalysis
import com.foxhole.guard.runtime.AppUpdatePolicy
import com.foxhole.guard.runtime.AppUpdateWorker
import com.foxhole.guard.runtime.DnsFilterUpdateWorker
import com.foxhole.guard.runtime.FOXHOLE_THREAT_INTEL_MANIFEST_URL
import com.foxhole.guard.runtime.GeoIpUpdateWorker
import com.foxhole.guard.runtime.GuardReconcileWorker
import com.foxhole.guard.runtime.SubscriptionRefreshWorker
import com.foxhole.guard.runtime.SystemDnsChangeMonitor
import com.foxhole.guard.runtime.ThreatIntelUpdateWorker
import com.foxhole.guard.runtime.TlsFingerprintUpdateWorker
import com.foxhole.guard.runtime.TorBridgeUpdateWorker
import com.foxhole.guard.runtime.TorExitRotationSupervisor
import com.foxhole.guard.runtime.enqueueQuarantineRuntimeEnforcement
import com.foxhole.guard.runtime.shouldRearmQuarantineRuntimeEnforcement
import com.foxhole.guard.widget.FoxStatusWidget
import com.foxhole.guard.widget.FoxStatusWidgetAnimation
import com.foxhole.guard.widget.StatusWidget
import com.foxhole.guard.widget.WebAppsWidget
import com.foxhole.guard.widget.statusWidgetRuntimeKey
import com.foxhole.guard.widget.widgetHasPrimaryConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class FoxholeApplication :
    Application(),
    Configuration.Provider {
    lateinit var appGraph: FoxholeAppGraph
        private set

    internal val container: FoxholeAppGraph
        get() = appGraph

    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setMinimumLoggingLevel(workManagerMinimumLoggingLevel())
                .build()

    private val screenOffKeyWipeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    appGraph.securityComponents.appLockManager.onScreenOff()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()

        installUncaughtCrashHandler()
        installDebugStrictMode()
        appGraph = FoxholeAppGraph(this)
        FoxholeVpnRuntimeBridge.onWriteRejected = { mode, incoming ->
            appGraph.diagnosticsLogger.record(
                "connection",
                "bridge write rejected: stale mode=${mode.name.lowercase()} incoming=${incoming?.state?.name?.lowercase() ?: "-"}",
            )
        }
        registerReceiver(screenOffKeyWipeReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        applyAppLocale(readFastStoredAppLocale(this))

        appScope.launch {
            delay(BACKGROUND_INITIALIZATION_STARTUP_DELAY_MS)
            initializeInBackground()
        }
    }

    private suspend fun initializeInBackground() {
        val startupDependencies: FoxholeStartupDependencies = appGraph
        // Heals a keybox/passphrase split from an interrupted enable-password migration.
        runCatching { appGraph.securityComponents.appLockSetupCoordinator.reconcileKeySources() }
            .onFailure { error ->
                startupDependencies.diagnosticsLogger.recordFailure(
                    "security",
                    "key source reconcile failed: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        startupDependencies.diagnosticsLogger.cleanupExpiredExports()
        cleanupProfileExportArtifacts(File(cacheDir, PROFILE_EXPORT_DIR_NAME))
        val settings =
            runCatching { startupDependencies.settingsRepository.warmUp() }
                .onFailure { error ->
                    startupDependencies.diagnosticsLogger.recordFailure(
                        "settings",
                        "secure settings initialization failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }.getOrElse { return }
        if (settings.expert.pendingQuarantineAppDetails.any { details -> !details.analysisComplete }) {
            enqueuePendingQuarantineAnalysis()
        }

        if (shouldRearmQuarantineRuntimeEnforcement(settings)) {
            enqueueQuarantineRuntimeEnforcement(settings.expert.quarantinePolicyRevision)
        }
        runProfileStartupOrDeferUntilUnlock(startupDependencies, settings.lastActiveProfile == null)
        applyAppLocale(settings.ui.locale)

        refreshLocalizedNotificationChannels()

        migrateRenamedWatchdogs()

        if (!appGraph.securityComponents.isDatabaseLockedForBackground()) {
            runCatching { appGraph.anomalyRepository.deliverPendingNotifications() }
                .onFailure { error ->
                    startupDependencies.diagnosticsLogger.recordFailure(
                        "anomaly",
                        "pending detection notifications failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            runCatching { appGraph.anomalyRepository.cleanupExpiredNow() }
                .onFailure { error ->
                    startupDependencies.diagnosticsLogger.recordFailure(
                        "statistics",
                        "expired statistics cleanup failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
        }

        TorExitRotationSupervisor(
            scope = appScope,
            settingsRepository = appGraph.settingsRepository,
            connectionController = appGraph.connectionController,
            diagnosticsLogger = appGraph.diagnosticsLogger,
        ).start()

        SystemDnsChangeMonitor(context = this).start()

        appGraph.webAppsWatchdog.start()

        appScope.launch {
            appGraph.webAppsRepository.changes.collect {
                runCatching { WebAppsWidget().updateAll(this@FoxholeApplication) }
            }
        }
        appScope.launch {
            appGraph.settingsRepository.settings
                .map { it.widgets }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    runCatching { WebAppsWidget().updateAll(this@FoxholeApplication) }
                    runCatching { StatusWidget().updateAll(this@FoxholeApplication) }
                    runCatching { FoxStatusWidget().updateAll(this@FoxholeApplication) }
                }
        }

        appScope.launch {
            FoxholeVpnRuntimeBridge.snapshot
                .map { snapshot -> snapshot.statusWidgetRuntimeKey() }
                .distinctUntilChanged()
                .collect {
                    runCatching { StatusWidget().updateAll(this@FoxholeApplication) }
                }
        }

        appScope.launch {
            FoxholeVpnRuntimeBridge.snapshot
                .map(::widgetHasPrimaryConnection)
                .distinctUntilChanged()
                .collectLatest { connected ->
                    FoxStatusWidgetAnimation.renderConnectionState(this@FoxholeApplication, connected)
                }
        }
        delay(BACKGROUND_WORK_SCHEDULE_IDLE_DELAY_MS)
        applyProfileSecretCleanupSchedule()
        applySubscriptionRefreshSchedule(
            enabled = settings.connection.autoRefreshSubscriptions,
            interval = settings.connection.subscriptionRefreshInterval,
            policy = ExistingPeriodicWorkPolicy.KEEP,
        )
        applyComponentUpdateSchedules(settings)
        applyGuardHeartbeatSchedule(
            enabled = appGraph.securityComponents.isEventMonitoringActive(),
            policy = ExistingPeriodicWorkPolicy.KEEP,
        )
    }

    private fun applyComponentUpdateSchedules(settings: Settings) {
        val componentUpdatesPermitted = settings.connection.componentUpdateCheckEnabled
        applyDnsFilterUpdateSchedule(
            enabled =
            componentUpdatesPermitted &&
                settings.dns.autoUpdateFilters &&
                settings.dns.dnsRuleSetFilteringEnabled(),
            policy = ExistingPeriodicWorkPolicy.KEEP,
        )
        applyGeoIpUpdateSchedule(
            enabled = componentUpdatesPermitted && settings.connection.geoIpAutoUpdate,
            policy = ExistingPeriodicWorkPolicy.KEEP,
        )
        applyAppUpdateSchedule(
            enabled =
            AppUpdatePolicy.backgroundCheckAllowed(
                channel = BuildConfig.UPDATE_CHANNEL,
                componentUpdateCheckEnabled = componentUpdatesPermitted,
            ),
            policy = ExistingPeriodicWorkPolicy.KEEP,
        )
        applyTorBridgeUpdateSchedule(
            enabled = componentUpdatesPermitted && settings.privacyRoute.bridgesAutoUpdate,
            policy = ExistingPeriodicWorkPolicy.KEEP,
        )
        applyThreatIntelUpdateSchedule(
            enabled = threatIntelBackgroundUpdateEnabled(settings),
            policy = ExistingPeriodicWorkPolicy.KEEP,
        )
        applyTlsFingerprintUpdateSchedule(
            enabled = componentUpdatesPermitted && settings.connection.tlsFingerprintAutoUpdate,
            policy = ExistingPeriodicWorkPolicy.KEEP,
        )
    }

    private fun installUncaughtCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { journalUncaughtCrash(thread, error) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun journalUncaughtCrash(
        thread: Thread,
        error: Throwable,
    ) {
        DiagnosticsSessionStore(journalDir = File(filesDir, DIAGNOSTICS_JOURNAL_DIR_NAME))
            .append(
                entry =
                DiagnosticEntry(
                    timestamp = System.currentTimeMillis(),
                    tag = CRASH_DIAGNOSTIC_TAG,
                    message = formatUncaughtCrashReport(thread, error),
                ),

                retention =
                runCatching { appGraph.settingsRepository.settings.value.expert.effectiveDiagnosticsRetention() }
                    .getOrDefault(RetentionPolicy()),
            )
    }

    private fun installDebugStrictMode() {
        if (!BuildConfig.ENABLE_STRICT_MODE) {
            return
        }
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }

    private fun applyProfileSecretCleanupSchedule() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ProfileSecretCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ProfileSecretCleanupWorker>(
                PROFILE_SECRET_CLEANUP_INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).build(),
        )
    }

    private fun runProfileStartupOrDeferUntilUnlock(
        startupDependencies: FoxholeStartupDependencies,
        needsActiveProfileLookup: Boolean,
    ) {
        val security = appGraph.securityComponents
        if (!security.isDatabaseLockedForBackground()) {
            appScope.launch { runProfileStartup(startupDependencies, needsActiveProfileLookup) }
            return
        }
        security.journalEvent(
            com.foxhole.guard.guardian.GuardEvent(type = com.foxhole.guard.guardian.GuardEventType.STATS_PAUSED)
        )
        appScope.launch {
            security.dataKeyAvailable.first { it }
            security.journalEvent(
                com.foxhole.guard.guardian.GuardEvent(type = com.foxhole.guard.guardian.GuardEventType.STATS_RESUMED)
            )
            runProfileStartup(startupDependencies, needsActiveProfileLookup)
        }
    }

    private suspend fun runProfileStartup(
        startupDependencies: FoxholeStartupDependencies,
        needsActiveProfileLookup: Boolean,
    ) {
        if (needsActiveProfileLookup) {
            startupDependencies.profileRepository.getActiveProfile()
        }
        startupDependencies.profileRepository.cleanupOrphanProfileSecrets()
    }

    private companion object {
        const val PROFILE_SECRET_CLEANUP_INTERVAL_HOURS = 24L

        fun workManagerMinimumLoggingLevel(): Int =
            if (BuildConfig.DEBUG) {
                android.util.Log.DEBUG
            } else {
                android.util.Log.INFO
            }
    }
}

internal fun Context.applySubscriptionRefreshSchedule(
    enabled: Boolean,
    interval: SubscriptionRefreshInterval = SubscriptionRefreshInterval.HOURS_6,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
) {
    val workManager = WorkManager.getInstance(this)
    if (!enabled) {
        workManager.cancelUniqueWork(SubscriptionRefreshWorker.WORK_NAME)
        return
    }
    val work =
        PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(
            subscriptionRefreshIntervalHours(interval),
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            ).setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.MINUTES,
            ).build()
    workManager.enqueueUniquePeriodicWork(
        SubscriptionRefreshWorker.WORK_NAME,
        policy,
        work,
    )
}

internal fun subscriptionRefreshIntervalHours(interval: SubscriptionRefreshInterval): Long = interval.hours

internal fun Context.applyDnsFilterUpdateSchedule(
    enabled: Boolean,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
) {
    val workManager = WorkManager.getInstance(this)
    if (!enabled) {
        workManager.cancelUniqueWork(DnsFilterUpdateWorker.WORK_NAME)
        return
    }
    val work =
        PeriodicWorkRequestBuilder<DnsFilterUpdateWorker>(
            DNS_FILTER_UPDATE_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            ).setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.MINUTES,
            ).build()
    workManager.enqueueUniquePeriodicWork(
        DnsFilterUpdateWorker.WORK_NAME,
        policy,
        work,
    )
}

internal const val DNS_FILTER_UPDATE_INTERVAL_HOURS = 12L

internal fun Context.applyGuardHeartbeatSchedule(
    enabled: Boolean,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
) {
    val workManager = WorkManager.getInstance(this)
    if (!enabled) {
        workManager.cancelUniqueWork(GuardHeartbeatWorker.WORK_NAME)
        return
    }

    val work =
        PeriodicWorkRequestBuilder<GuardHeartbeatWorker>(
            GUARD_HEARTBEAT_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        ).setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            10,
            TimeUnit.MINUTES,
        ).build()
    workManager.enqueueUniquePeriodicWork(
        GuardHeartbeatWorker.WORK_NAME,
        policy,
        work,
    )
}

internal const val GUARD_HEARTBEAT_INTERVAL_MINUTES = 15L

internal fun Context.applyGuardReconcileSchedule(
    enabled: Boolean,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
) {
    val workManager = WorkManager.getInstance(this)
    if (!enabled) {
        workManager.cancelUniqueWork(GuardReconcileWorker.WORK_NAME)
        return
    }
    val work =
        PeriodicWorkRequestBuilder<GuardReconcileWorker>(
            GUARD_RECONCILE_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        ).setInitialDelay(

            GUARD_RECONCILE_INITIAL_DELAY_MINUTES,
            TimeUnit.MINUTES,
        ).setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            10,
            TimeUnit.MINUTES,
        ).build()
    workManager.enqueueUniquePeriodicWork(
        GuardReconcileWorker.WORK_NAME,
        policy,
        work,
    )
}

internal const val GUARD_RECONCILE_INTERVAL_MINUTES = 15L
internal const val GUARD_RECONCILE_INITIAL_DELAY_MINUTES = 2L

internal fun Context.enqueueGuardHealSafetyNet(delayMs: Long) {
    WorkManager.getInstance(this).enqueueUniqueWork(
        GuardReconcileWorker.HEAL_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<GuardReconcileWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build(),
    )
}

internal fun Context.cancelGuardHealSafetyNet() {
    WorkManager.getInstance(this).cancelUniqueWork(GuardReconcileWorker.HEAL_WORK_NAME)
}

internal fun Context.applyGeoIpUpdateSchedule(
    enabled: Boolean,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
) {
    val workManager = WorkManager.getInstance(this)
    if (!enabled) {
        workManager.cancelUniqueWork(GeoIpUpdateWorker.WORK_NAME)
        return
    }
    val work =
        PeriodicWorkRequestBuilder<GeoIpUpdateWorker>(
            GEOIP_UPDATE_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            ).setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.MINUTES,
            ).build()
    workManager.enqueueUniquePeriodicWork(
        GeoIpUpdateWorker.WORK_NAME,
        policy,
        work,
    )
}

internal const val GEOIP_UPDATE_INTERVAL_HOURS = 12L

internal fun Context.applyAppUpdateSchedule(
    enabled: Boolean,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
) {
    val workManager = WorkManager.getInstance(this)
    if (!enabled) {
        workManager.cancelUniqueWork(AppUpdateWorker.WORK_NAME)
        return
    }
    val work =
        PeriodicWorkRequestBuilder<AppUpdateWorker>(
            APP_UPDATE_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            ).setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.MINUTES,
            ).build()
    workManager.enqueueUniquePeriodicWork(
        AppUpdateWorker.WORK_NAME,
        policy,
        work,
    )
}

internal const val APP_UPDATE_INTERVAL_HOURS = 24L

internal fun Context.applyTorBridgeUpdateSchedule(
    enabled: Boolean,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
) {
    val workManager = WorkManager.getInstance(this)
    if (!enabled) {
        workManager.cancelUniqueWork(TorBridgeUpdateWorker.WORK_NAME)
        return
    }
    val work =
        PeriodicWorkRequestBuilder<TorBridgeUpdateWorker>(
            TOR_BRIDGE_UPDATE_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            ).setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.MINUTES,
            ).build()
    workManager.enqueueUniquePeriodicWork(
        TorBridgeUpdateWorker.WORK_NAME,
        policy,
        work,
    )
}

internal const val TOR_BRIDGE_UPDATE_INTERVAL_HOURS = 12L

internal fun Context.applyThreatIntelUpdateSchedule(
    enabled: Boolean,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
) {
    val workManager = WorkManager.getInstance(this)
    if (!enabled) {
        workManager.cancelUniqueWork(ThreatIntelUpdateWorker.WORK_NAME)
        return
    }
    val work =
        PeriodicWorkRequestBuilder<ThreatIntelUpdateWorker>(
            THREAT_INTEL_UPDATE_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            ).setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.MINUTES,
            ).build()
    workManager.enqueueUniquePeriodicWork(
        ThreatIntelUpdateWorker.WORK_NAME,
        policy,
        work,
    )
}

internal const val THREAT_INTEL_UPDATE_INTERVAL_HOURS = 72L

internal fun threatIntelBackgroundUpdateEnabled(settings: Settings): Boolean =
    settings.anomaly.enabled &&
        settings.connection.componentUpdateCheckEnabled &&
        FOXHOLE_THREAT_INTEL_MANIFEST_URL.isNotBlank()

internal fun Context.applyTlsFingerprintUpdateSchedule(
    enabled: Boolean,
    policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
) {
    val workManager = WorkManager.getInstance(this)
    if (!enabled) {
        workManager.cancelUniqueWork(TlsFingerprintUpdateWorker.WORK_NAME)
        return
    }
    val work =
        PeriodicWorkRequestBuilder<TlsFingerprintUpdateWorker>(
            TLS_FINGERPRINT_UPDATE_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            ).setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.MINUTES,
            ).build()
    workManager.enqueueUniquePeriodicWork(
        TlsFingerprintUpdateWorker.WORK_NAME,
        policy,
        work,
    )
}

internal const val TLS_FINGERPRINT_UPDATE_INTERVAL_HOURS = 24L
private const val BACKGROUND_INITIALIZATION_STARTUP_DELAY_MS = 1_500L
private const val BACKGROUND_WORK_SCHEDULE_IDLE_DELAY_MS = 30_000L

internal val Service.processLifetimeScope: CoroutineScope
    get() = (application as FoxholeApplication).appScope

internal const val CRASH_DIAGNOSTIC_TAG = "crash"

internal const val DIAGNOSTICS_JOURNAL_DIR_NAME = "diagnostics-journal"

private const val CRASH_REPORT_MAX_FRAMES = 12
private const val CRASH_REPORT_MAX_CAUSES = 4

internal fun formatUncaughtCrashReport(
    thread: Thread,
    error: Throwable,
): String {
    val causes =
        generateSequence(error.cause) { cause -> cause.cause.takeIf { next -> next !== cause } }
            .take(CRASH_REPORT_MAX_CAUSES)
            .map { cause -> cause.javaClass.name.journalSafeName() }
            .joinToString(separator = " <- ")
    val frames =
        error.stackTrace
            .take(CRASH_REPORT_MAX_FRAMES)
            .joinToString(separator = " | ", transform = ::formatCrashFrame)
    return buildString {
        append("uncaught exception thread=")
        append(thread.name.journalSafeName())
        append(" type=")
        append(error.javaClass.name.journalSafeName())
        if (causes.isNotEmpty()) {
            append(" causes=")
            append(causes)
        }
        if (frames.isNotEmpty()) {
            append(" at=")
            append(frames)
        }
    }
}

private fun formatCrashFrame(frame: StackTraceElement): String =
    buildString {
        append(frame.className.journalSafeName())
        append('#')
        append(frame.methodName.journalSafeName())

        append(if (frame.lineNumber >= 0) "@${frame.lineNumber}" else "@native")
    }

private fun String.journalSafeName(): String = replace('.', '/')

internal fun applyAppLocale(locale: AppLocale) {
    val locales =
        if (locale == AppLocale.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(locale.appLanguageTags())
        }
    AppCompatDelegate.setApplicationLocales(locales)
}
