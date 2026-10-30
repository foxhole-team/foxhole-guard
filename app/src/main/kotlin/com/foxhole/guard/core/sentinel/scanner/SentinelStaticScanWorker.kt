package com.foxhole.guard.core.sentinel.scanner

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.foxhole.core.model.Settings
import com.foxhole.guard.FoxholeApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SentinelStaticScanWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as FoxholeApplication).appGraph
        val gateway = graph.sentinelScanGateway
        gateway.refreshPolicy()
        val settings = graph.settingsRepository.current()
        if (!automaticEnabled(settings)) {
            gateway.clearAutomaticQueue()
            return Result.success()
        }
        return drain(gateway, settings)
    }

    private suspend fun drain(gateway: SentinelScanGateway, settings: Settings): Result {
        val queue = gateway.automaticQueue
        val owner = withContext(Dispatchers.IO) { queue.beginDrain() } ?: return Result.success()
        var finished = false
        var successor: String?
        try {
            val claim = withContext(Dispatchers.IO) { queue.claim(owner, 8) }
            val repairSettled = !claim.fullRepairRequired || repairChunk(gateway, claim.epoch)
            if (needsRetry(gateway) || !scanNames(gateway, claim.observations.map { it.packageName }.distinct())) {
                return Result.retry()
            }
            finished = withContext(Dispatchers.IO) {
                val accepted = queue.acknowledge(claim, repairSettled)
                if (repairSettled && queue.isCurrent(claim.epoch)) gateway.baselineCursor.clear()
                accepted
            }
        } finally {
            successor = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                queue.finishDrain(owner, reserveSuccessor = finished && !isStopped)
            }
        }
        successor?.let { reservation -> scheduleSuccessor(gateway, settings, reservation) }
        return if (finished) Result.success() else Result.retry()
    }

    private suspend fun repairChunk(gateway: SentinelScanGateway, epoch: String): Boolean {
        val claim = gateway.automaticBaselineClaim(epoch)
        if (claim.packages.isEmpty()) return gateway.automaticQueue.isCurrent(epoch)
        if (!scanNames(gateway, claim.packages) || !gateway.automaticQueue.isCurrent(epoch)) return false
        val advanced = withContext(Dispatchers.IO) { gateway.baselineCursor.acknowledge(claim) }
        return advanced && gateway.automaticQueue.isCurrent(epoch) && gateway.automaticBaselineClaim(epoch).packages.isEmpty()
    }

    private suspend fun scanNames(gateway: SentinelScanGateway, packages: List<String>): Boolean {
        for (packageName in packages) {
            gateway.controller.requestAutomaticPackage(packageName)
            if (needsRetry(gateway)) return false
        }
        return true
    }

    private fun needsRetry(gateway: SentinelScanGateway): Boolean = gateway.controller.state.value.stage in setOf(
        SentinelScanStage.CANCELLED, SentinelScanStage.WAITING, SentinelScanStage.DISABLED, SentinelScanStage.STALE,
    )

    private suspend fun scheduleSuccessor(gateway: SentinelScanGateway, settings: Settings, reservation: String) {
        try {
            applicationContext.enqueueSentinelStaticWork(settings)
        } catch (failure: Throwable) {
            gateway.automaticQueue.releaseFailedWake(reservation)
            throw failure
        }
    }

    private fun automaticEnabled(settings: Settings): Boolean =
        settings.anomaly.enabled && settings.anomaly.apkScan.dexEnabled && settings.anomaly.apkScan.automaticNewUpdatedApps

    companion object {
        const val WORK_NAME = "sentinel-static-scan"
    }
}

internal suspend fun Context.enqueueSentinelStaticWork(settings: Settings) {
    val scan = settings.anomaly.apkScan
    val constraints = Constraints.Builder()
        .setRequiresCharging(scan.chargingOnly)
        .setRequiresBatteryNotLow(scan.batteryNotLow)
        .setRequiresStorageNotLow(true)
        .build()
    val request = OneTimeWorkRequestBuilder<SentinelStaticScanWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(
        this
    ).enqueueUniqueWork(SentinelStaticScanWorker.WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request).await()
}
