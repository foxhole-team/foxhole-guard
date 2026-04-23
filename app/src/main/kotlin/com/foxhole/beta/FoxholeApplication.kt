package com.foxhole.beta

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.settings.readFastStoredAppLocale
import com.foxhole.beta.vpn.SubscriptionRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class FoxholeApplication : Application(), Configuration.Provider {
    lateinit var appGraph: FoxholeAppGraph
        private set

    internal val container: FoxholeAppGraph
        get() = appGraph

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appGraph = FoxholeAppGraph(this)
        applyAppLocale(readFastStoredAppLocale(this))

        appScope.launch {
            initializeInBackground()
        }
    }

    private suspend fun initializeInBackground() {
        val startupDependencies: FoxholeStartupDependencies = appGraph
        startupDependencies.diagnosticsLogger.cleanupExpiredExports()
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
        applyAppLocale(settings.ui.locale)
        scheduleSubscriptionRefresh()
    }

    private fun scheduleSubscriptionRefresh() {
        val work =
            PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                ).setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.MINUTES,
                ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SubscriptionRefreshWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            work,
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}

internal fun applyAppLocale(locale: AppLocale) {
    val locales =
        if (locale == AppLocale.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(locale.appLanguageTags())
        }
    AppCompatDelegate.setApplicationLocales(locales)
}
