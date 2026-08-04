package com.foxhole.guard.core.webapps

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.guard.FoxholeApplication

/**
 * The watchdog's safety pass: the ticker lives in the process and dies with it, so WorkManager
 * wakes the process every 15 minutes (its minimum) and runs the same pollOnce. The gates are checked
 * by pollOnce itself, so there are no conditions here.
 */
class WebAppsWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? FoxholeApplication ?: return Result.success()
        runCatching { application.appGraph.webAppsWatchdog.pollOnce() }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "webapps-watchdog"
    }
}
