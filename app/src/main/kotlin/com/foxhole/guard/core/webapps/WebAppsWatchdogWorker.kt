package com.foxhole.guard.core.webapps

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.WatchdogNames

/**
 * The safety pass of [WatchdogNames.WEB]: the ticker lives in the process and dies with it, so
 * WorkManager wakes the process every 15 minutes (its minimum) and runs the same pollOnce. The
 * gates are checked by pollOnce itself, so there are no conditions here.
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
        /** WorkManager keys unique work by string, so the watchdog's name goes in as an id. */
        const val WORK_NAME = WatchdogNames.WEB_ID
    }
}
