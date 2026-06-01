package com.foxhole.beta

import android.app.Application
import android.content.Context
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.foxhole.beta.core.data.ProfileSecretCleanupWorker
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.SubscriptionRefreshInterval
import com.foxhole.beta.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.beta.core.profile.PROFILE_EXPORT_DIR_NAME
import com.foxhole.beta.core.profile.cleanupProfileExportArtifacts
import com.foxhole.beta.core.settings.readFastStoredAppLocale
import com.foxhole.beta.ui.prewarmTrafficMapCountryShapes
import com.foxhole.beta.vpn.DnsFilterUpdateWorker
import com.foxhole.beta.vpn.SubscriptionRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setMinimumLoggingLevel(workManagerMinimumLoggingLevel())
                .build()

    override fun onCreate() {
        super.onCreate()
        installDebugStrictMode()
        appGraph = FoxholeAppGraph(this)
        applyAppLocale(readFastStoredAppLocale(this))

        appScope.launch {
            runCatching { prewarmTrafficMapCountryShapes(this@FoxholeApplication) }
                .onFailure { error ->
                    appGraph.diagnosticsLogger.record(
                        "traffic-map",
                        "country shape prewarm failed: ${error.javaClass.simpleName}",
                    )
                }
        }
        appScope.launch {
            initializeInBackground()
        }
    }

    private suspend fun initializeInBackground() {
        val startupDependencies: FoxholeStartupDependencies = appGraph
        startupDependencies.diagnosticsLogger.cleanupExpiredExports()
        cleanupProfileExportArtifacts(File(cacheDir, PROFILE_EXPORT_DIR_NAME))
        val settings =
            runCatching { startupDependencies.settingsRepository.warmUp() }
                .onFailure { error ->
                    startupDependencies.diagnosticsLogger.record(
                        "settings",
                        "secure settings initialization failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }.getOrElse { return }
        if (settings.lastActiveProfile == null) {
            startupDependencies.profileRepository.getActiveProfile()
        }
        startupDependencies.profileRepository.cleanupOrphanProfileSecrets()
        applyAppLocale(settings.ui.locale)
        applyProfileSecretCleanupSchedule()
        applySubscriptionRefreshSchedule(
            enabled = settings.connection.autoRefreshSubscriptions,
            interval = settings.connection.subscriptionRefreshInterval,
        )
        applyDnsFilterUpdateSchedule(
            enabled = settings.dns.autoUpdateFilters && settings.dns.dnsRuleSetFilteringEnabled(),
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
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ProfileSecretCleanupWorker>(
                PROFILE_SECRET_CLEANUP_INTERVAL_HOURS,
                TimeUnit.HOURS,
            ).build(),
        )
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
        ExistingPeriodicWorkPolicy.UPDATE,
        work,
    )
}

internal fun subscriptionRefreshIntervalHours(interval: SubscriptionRefreshInterval): Long = interval.hours

internal fun Context.applyDnsFilterUpdateSchedule(enabled: Boolean) {
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
        ExistingPeriodicWorkPolicy.UPDATE,
        work,
    )
}

internal const val DNS_FILTER_UPDATE_INTERVAL_HOURS = 72L

internal fun applyAppLocale(locale: AppLocale) {
    val locales =
        if (locale == AppLocale.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(locale.appLanguageTags())
        }
    AppCompatDelegate.setApplicationLocales(locales)
}
