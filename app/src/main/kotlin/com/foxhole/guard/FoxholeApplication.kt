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
import com.foxhole.guard.runtime.DnsFilterUpdateWorker
import com.foxhole.guard.runtime.FOXHOLE_THREAT_INTEL_MANIFEST_URL
import com.foxhole.guard.runtime.GeoIpUpdateWorker
import com.foxhole.guard.runtime.GuardReconcileWorker
import com.foxhole.guard.runtime.SubscriptionRefreshWorker
import com.foxhole.guard.runtime.SystemDnsChangeMonitor
import com.foxhole.guard.runtime.ThreatIntelUpdateWorker
import com.foxhole.guard.runtime.TorBridgeUpdateWorker
import com.foxhole.guard.runtime.TorExitRotationSupervisor
import com.foxhole.guard.runtime.enqueueQuarantineRuntimeEnforcement
import com.foxhole.guard.runtime.shouldRearmQuarantineRuntimeEnforcement
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

    // Process-lifetime scope. Internal (not private) because post-destroy runtime teardown needs a
    // scope that OUTLIVES the service that started it — see [processLifetimeScope].
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setMinimumLoggingLevel(workManagerMinimumLoggingLevel())
                .build()

    // Zeroizes in-memory app-lock keys on screen-off. Process-lifetime registration: an active
    // VPN keeps the process (and unlocked SecureSessionHolder) alive with no Activity.
    // ACTION_SCREEN_OFF is a protected system broadcast — no export flag needed.
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
        // First thing after the context exists: with no Play Vitals on F-Droid/self-hosted
        // builds, the on-device journal is the only record a graph-construction crash ever gets.
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
        // Applied receipts are process-local by design. Every cold start re-arms the persisted
        // revision so a restored/queued service must prove that this process applied it.
        if (shouldRearmQuarantineRuntimeEnforcement(settings)) {
            enqueueQuarantineRuntimeEnforcement(settings.expert.quarantinePolicyRevision)
        }
        runProfileStartupOrDeferUntilUnlock(startupDependencies, settings.lastActiveProfile == null)
        applyAppLocale(settings.ui.locale)
        // Also catches a language changed from Android's own per-app settings: nothing in the app
        // observes that, and the channels the system shows would keep the previous locale's names.
        refreshLocalizedNotificationChannels()
        // Before anything reschedules: the old unique work has to be cancelled by its old name, and
        // this is the last moment the app still knows that name.
        migrateRenamedWatchdogs()
        // Retention is otherwise enforced only on writes: an idle install would never
        // age its statistics/journals out. Skipped while the DB is still PIN-locked.
        if (!appGraph.securityComponents.isDatabaseLockedForBackground()) {
            // Detections found while POST_NOTIFICATIONS was denied were persisted but never
            // announced. Start is where the permission has most likely just been granted, so this
            // is where they get their one retry.
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
        // Automatic Tor exit rotation ticks in the app scope for as long as the process lives.
        TorExitRotationSupervisor(
            scope = appScope,
            settingsRepository = appGraph.settingsRepository,
            connectionController = appGraph.connectionController,
            diagnosticsLogger = appGraph.diagnosticsLogger,
        ).start()
        // Watches upstream networks for a full DNS-resolver substitution (possible spoofing);
        // alerts via a system notification plus an in-app banner while the UI is open.
        SystemDnsChangeMonitor(context = this).start()
        // Self-arming web-apps push watchdog: watches settings/tunnel itself and engages only
        // when push is on and the guard is up; start() merely begins observing.
        appGraph.webAppsWatchdog.start()
        // Web-apps widget re-renders on repository changes (CRUD/badges).
        appScope.launch {
            appGraph.webAppsRepository.changes.collect {
                runCatching { WebAppsWidget().updateAll(this@FoxholeApplication) }
            }
        }
        // Widget-defaults changes in app settings re-render both widgets.
        appScope.launch {
            appGraph.settingsRepository.settings
                .map { it.widgets }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    runCatching { WebAppsWidget().updateAll(this@FoxholeApplication) }
                    runCatching { StatusWidget().updateAll(this@FoxholeApplication) }
                }
        }
        // Status widget re-renders on runtime-bridge state/mode changes.
        appScope.launch {
            FoxholeVpnRuntimeBridge.snapshot
                .map { snapshot -> snapshot.statusWidgetRuntimeKey() }
                .distinctUntilChanged()
                .collect {
                    runCatching { StatusWidget().updateAll(this@FoxholeApplication) }
                }
        }
        // The image-only status widget animates continuously while a real primary route owns the
        // connection. collectLatest cancels it immediately when STOP wins the race.
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
        // The "Check for updates" master (Component updates screen) gates every scheduled
        // component refresh: with it off nothing probes the update sources in the background.
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
        applyTorBridgeUpdateSchedule(
            enabled = componentUpdatesPermitted && settings.privacyRoute.bridgesAutoUpdate,
            policy = ExistingPeriodicWorkPolicy.KEEP,
        )
        // Dormant until the signed feed is hosted: scheduling activates with the configured endpoint.
        applyThreatIntelUpdateSchedule(
            enabled = FOXHOLE_THREAT_INTEL_MANIFEST_URL.isNotBlank(),
            policy = ExistingPeriodicWorkPolicy.KEEP,
        )
        applyGuardHeartbeatSchedule(
            enabled = appGraph.securityComponents.isEventMonitoringActive(),
            policy = ExistingPeriodicWorkPolicy.KEEP,
        )
    }

    /**
     * The only crash reporter: no Play Vitals, no third-party SDK (a privacy tool ships no crash
     * uploader). Reports land in the on-device journal the diagnostics screen already reads.
     */
    private fun installUncaughtCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { journalUncaughtCrash(thread, error) }
            if (previous != null) {
                // MUST run: the platform handler is what actually ends the process; swallowing
                // here leaves a live process with a dead main thread — a permanent freeze.
                previous.uncaughtException(thread, error)
            } else {
                // Unreachable in an Android app process; never leave a half-dead process behind
                // if that ever stops being true.
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /**
     * Writes straight to the journal store: [DiagnosticsLogger] is asynchronous and the process
     * is dying, so a queued record would never reach disk. A fresh store starts its own session
     * file, so it cannot corrupt the file the logger holds open.
     */
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
                // The graph may not exist yet (crash during startup) — fall back to the default
                // policy rather than losing the report.
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

    /**
     * Startup profile work touches the SQLCipher DB, so under password protection it waits for
     * the first unlock; STATS_PAUSED/STATS_RESUMED mark the wait in the tamper-evident journal.
     */
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

// Cheap 12h manifest probe; the artifact downloads only when a newer list is published
// (72h forced full refresh as the safety net).
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
    // No network constraint on purpose: the guard daemon must tick offline.
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

// WorkManager's periodic minimum; the guard tolerates Doze slack (blackouts are journaled
// as SUSPECTED, corroborated by boot count).
internal const val GUARD_HEARTBEAT_INTERVAL_MINUTES = 15L

/**
 * Out-of-process safety net for the local guard: services are START_NOT_STICKY (fail-closed) and
 * the in-service heal dies with its process, so a low-memory kill left the firewall down.
 * No network constraint — a local firewall must come back offline as well.
 */
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
            // Without a delay WorkManager runs the first period immediately — re-armed from
            // syncLocalGuard, which just fixed the guard — duplicating START_LOCAL_GUARD and
            // restarting a still-starting guard on every firewall enable. Two minutes lets the
            // guard settle into an honest no-op; a full interval here would stretch firewall
            // recovery after boot-without-network from minutes to ~15.
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

/**
 * Out-of-process insurance for the guard self-heal: the heal lives on the service scope and every
 * error-teardown ends in stopService, so a kept heal died with the process anyway. One-time work
 * survives both and delegates to [GuardReconcileWorker], which no-ops if the guard is up or off.
 * REPLACE: each re-arm moves the deadline; only a deliberate heal cancel removes it, not onDestroy.
 */
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

// 12h version probe; range files download only on a real version change (upstream ~monthly).
internal const val GEOIP_UPDATE_INTERVAL_HOURS = 12L

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

// Bridge lists expire faster than geo/DNS assets; 12h keeps a usable set for censored networks.
// A fresh list applies on the next Tor start.
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
private const val BACKGROUND_INITIALIZATION_STARTUP_DELAY_MS = 1_500L
private const val BACKGROUND_WORK_SCHEDULE_IDLE_DELAY_MS = 30_000L

/**
 * Scope for work a service starts but cannot own: onDestroy() cancels the service scope, so the
 * native runtime stop that follows must be launched somewhere that survives it.
 */
internal val Service.processLifetimeScope: CoroutineScope
    get() = (application as FoxholeApplication).appScope

internal const val CRASH_DIAGNOSTIC_TAG = "crash"

// Mirrors DiagnosticsLogger's private journal dir: the crash handler must append to the SAME
// store the diagnostics screen reads. Renaming there without renaming here silently sends crash
// reports to a directory nothing reads.
internal const val DIAGNOSTICS_JOURNAL_DIR_NAME = "diagnostics-journal"

private const val CRASH_REPORT_MAX_FRAMES = 12
private const val CRASH_REPORT_MAX_CAUSES = 4

/**
 * One-line, journal-safe rendering of an uncaught exception. Deliberate transformations:
 *  - `Throwable.message` is NEVER included: failures routinely carry data this journal must not
 *    keep (server addresses, credentials in proxy URIs); type plus frames identify the defect.
 *  - Dots become `/` in class names: the journal sanitizer reads dotted lowercase tokens as
 *    hostnames and would persist a normal stack trace as a row of `[host]`.
 */
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
        // '@' not ':' before the line: the sanitizer reads "<hex-ish>:<digits>" as IPv6 and would
        // persist `Foo#add:42` as `Foo#[ip]`. Negative line = native/unknown frame.
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
