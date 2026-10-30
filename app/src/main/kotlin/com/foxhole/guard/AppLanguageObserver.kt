package com.foxhole.guard

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.foxhole.guard.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun Application.observeAppLanguageChanges(scope: CoroutineScope, repository: () -> SettingsRepository) {
    registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            scope.launch {
                try {
                    val settings = repository()
                    if (!settings.hydrated.value) return@launch
                    val before = settings.settings.value.ui.locale
                    if (synchronizeGuardAppLanguage(this@observeAppLanguageChanges, settings) && before != settings.settings.value.ui.locale) {
                        this@observeAppLanguageChanges.refreshLocalizedNotificationChannels()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    android.util.Log.w("FoxHoleLocale", "Language sync failed: ${error.javaClass.simpleName}")
                }
            }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    })
}
