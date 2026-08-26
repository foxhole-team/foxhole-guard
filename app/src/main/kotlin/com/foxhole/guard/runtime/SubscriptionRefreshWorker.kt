package com.foxhole.guard.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeRefreshWorkerDependencies
import com.foxhole.guard.core.data.refreshProfile
import com.foxhole.guard.core.data.subscriptionRefreshRepresentatives

class SubscriptionRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val appGraph = (applicationContext as FoxholeApplication).appGraph
        if (appGraph.securityComponents.isDatabaseLockedForBackground()) {
            return Result.success()
        }
        val dependencies: FoxholeRefreshWorkerDependencies = appGraph
        val settings = dependencies.settingsRepository.current()
        var result = Result.success()
        if (!settings.connection.autoRefreshSubscriptions) {
            dependencies.diagnosticsLogger.record("worker", "scheduled refresh skipped: auto refresh disabled")
        } else {
            val networkState = applicationContext.scheduledRefreshNetworkState()
            if (
                shouldSkipScheduledRefreshForNetwork(
                    skipOnCellular = settings.networkRules.skipSubscriptionRefreshOnCellular,
                    transport = networkState?.transport,
                    isMetered = networkState?.isMetered == true,
                )
            ) {
                dependencies.diagnosticsLogger.record(
                    "worker",
                    "scheduled refresh skipped: cellular or metered network",
                )
            } else {
                val targets =
                    dependencies.profileRepository.subscriptionRefreshRepresentatives()
                var successfulProfiles = 0
                var retryableFailures = 0
                targets.forEach { profile ->
                    runCatching { dependencies.profileRepository.refreshProfile(profile.id) }
                        .onSuccess { refreshedProfile ->
                            successfulProfiles += 1
                            dependencies.diagnosticsLogger.record(
                                "worker",
                                "scheduled refresh succeeded for ${refreshedProfile.name}",
                            )
                        }
                        .onFailure { error ->
                            if (isRetryableScheduledRefreshFailure(error)) {
                                retryableFailures += 1
                            }
                            dependencies.diagnosticsLogger.recordFailure(
                                "worker",
                                "scheduled refresh failed for ${profile.name}: ${error.message ?: error.javaClass.simpleName}",
                            )
                        }
                }
                result =
                    when (
                        decideScheduledRefreshOutcome(
                            targetedProfiles = targets.size,
                            successfulProfiles = successfulProfiles,
                            retryableFailures = retryableFailures,
                        )
                    ) {
                        SubscriptionRefreshWorkDecision.SUCCESS -> {
                            dependencies.diagnosticsLogger.record(
                                "worker",
                                "scheduled refresh completed targeted=${targets.size} success=$successfulProfiles retryableFailures=$retryableFailures",
                            )
                            Result.success()
                        }
                        SubscriptionRefreshWorkDecision.RETRY -> {
                            dependencies.diagnosticsLogger.record("worker", "scheduled refresh requested retry")
                            Result.retry()
                        }
                    }
            }
        }
        return result
    }

    companion object {
        const val WORK_NAME = "subscription-refresh"
    }
}

private data class ScheduledRefreshNetworkState(
    val transport: String,
    val isMetered: Boolean,
)

private fun Context.scheduledRefreshNetworkState(): ScheduledRefreshNetworkState? {
    val connectivityManager = getSystemService<ConnectivityManager>()
    val capabilities =
        connectivityManager
            ?.activeNetwork
            ?.let { network -> connectivityManager.getNetworkCapabilities(network) }
    return capabilities?.let {
        ScheduledRefreshNetworkState(
            transport = it.scheduledRefreshTransportName(),
            isMetered = !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }
}

private fun NetworkCapabilities.scheduledRefreshTransportName(): String =
    when {
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }
