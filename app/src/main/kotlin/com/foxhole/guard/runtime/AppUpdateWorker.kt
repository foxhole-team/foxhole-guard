package com.foxhole.guard.runtime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.FoxholeAppUpdateDependencies
import com.foxhole.guard.FoxholeApplication

class AppUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies: FoxholeAppUpdateDependencies = (applicationContext as FoxholeApplication).appGraph
        if (
            !AppUpdatePolicy.backgroundCheckAllowed(
                channel = BuildConfig.UPDATE_CHANNEL,
                componentUpdateCheckEnabled =
                dependencies.settingsRepository.current().connection.componentUpdateCheckEnabled,
            )
        ) {
            return Result.success()
        }
        return when (val check = dependencies.appUpdateRepository.checkInBackground()) {
            is AppUpdateCheck.Available -> {
                AppUpdateNotifier(applicationContext).notifyAvailable(check)
                Result.success()
            }
            AppUpdateCheck.UpToDate -> {
                AppUpdateNotifier(applicationContext).clearAnnouncedVersion()
                Result.success()
            }
            is AppUpdateCheck.Failed ->
                if (check.failure in RETRYABLE_FAILURES) Result.retry() else Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "app-update-check"

        private val RETRYABLE_FAILURES =
            setOf(AppUpdateFailure.NETWORK, AppUpdateFailure.RATE_LIMITED, AppUpdateFailure.UNKNOWN)
    }
}
