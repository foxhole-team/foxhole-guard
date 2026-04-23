package com.foxhole.beta.vpn

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.FoxholeRefreshWorkerDependencies
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.notifications.ProfileRefreshResultNotifier
import kotlinx.coroutines.flow.first

class SubscriptionRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dependencies: FoxholeRefreshWorkerDependencies = (applicationContext as FoxholeApplication).appGraph
        val targets =
            dependencies.profileRepository.profiles.first()
            .filter { it.sourceType == ProfileSourceType.SUBSCRIPTION_URL }
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
                        ProfileRefreshResultNotifier.showSuccess(
                            context = applicationContext,
                            profileId = refreshedProfile.id,
                            profileName = refreshedProfile.name,
                        )
                    }
                    .onFailure { error ->
                        if (isRetryableScheduledRefreshFailure(error)) {
                            retryableFailures += 1
                        }
                        ProfileRefreshResultNotifier.showFailure(
                            context = applicationContext,
                            profileId = profile.id,
                            profileName = profile.name,
                            details = error.message,
                        )
                        dependencies.diagnosticsLogger.record(
                            "worker",
                            "scheduled refresh failed for ${profile.name}: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
            }
        return when (
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

    companion object {
        const val WORK_NAME = "subscription-refresh"
    }
}
