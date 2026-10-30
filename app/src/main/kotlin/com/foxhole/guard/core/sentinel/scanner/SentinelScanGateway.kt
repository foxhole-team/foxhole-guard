package com.foxhole.guard.core.sentinel.scanner

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import com.foxhole.core.model.ApkScanCost
import com.foxhole.core.model.Settings
import com.foxhole.guard.core.security.FoxholeSecurityComponents
import com.foxhole.guard.core.sentinel.AndroidKeystoreFileCipher
import com.foxhole.guard.core.sentinel.anomaly.AnomalyRepository
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.guardian.GuardEventType
import com.foxhole.guard.guardian.GuardPackageAdmission
import com.foxhole.guard.guardian.GuardPackageWorkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class SentinelScanGateway(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val repository: AnomalyRepository,
    private val security: () -> FoxholeSecurityComponents,
) {
    private val appContext = context.applicationContext
    private val rules = BundledScannerRuleSource(appContext)
    private val backend = InstalledSentinelScanBackend(appContext, rules::current)
    val controller = SentinelScanController(
        policyCurrent = { policy -> settingsRepository.settings.value.scanWorkPolicy(policy.ruleEpoch) == policy },
        capture = { packageName ->
            backend.capture(
                packageName
            ).copy(journalEpoch = repository.captureStaticScanJournalEpoch())
        },
        isCurrent = { source -> repository.isStaticScanJournalCurrent(source.journalEpoch) && backend.isCurrent(source) },
        visiblePackages = backend::visiblePackages,
        environment = ::environment,
        scan = backend::inspect,
        persist = { summary, current ->
            if (security().isDatabaseLockedForBackground()) {
                if (current()) ScannerJournalOutcome.DEFERRED else ScannerJournalOutcome.REJECTED
            } else {
                if (repository.recordStaticScan(
                        summary,
                        current
                    )
                ) {
                    ScannerJournalOutcome.COMMITTED
                } else {
                    ScannerJournalOutcome.REJECTED
                }
            }
        },
    )
    val engineAvailable: Boolean get() = backend.packagedEngineAvailable
    internal val automaticQueue = GuardPackageWorkStore(
        File(appContext.filesDir, "sentinel-static-work.json"),
        AndroidKeystoreFileCipher("foxhole.sentinel.static.work"),
    )

    internal val baselineCursor = ScannerBaselineCursorStore(
        File(appContext.filesDir, "sentinel-static-baseline.json"),
        AndroidKeystoreFileCipher("foxhole.sentinel.static.work"),
    )

    suspend fun automaticBaselineClaim(epoch: String): ScannerBaselineClaim = withContext(Dispatchers.IO) {
        baselineCursor.claim(epoch, backend.visiblePackages())
    }

    suspend fun enqueueAutomatic(packageName: String) = withContext(Dispatchers.IO) {
        val settings = settingsRepository.current()
        if (!settings.anomaly.enabled || !settings.anomaly.apkScan.dexEnabled || !settings.anomaly.apkScan.automaticNewUpdatedApps) {
            return@withContext
        }
        val admitted = automaticQueue.enqueue(
            GuardEventType.PACKAGE_REPLACED,
            packageName,
            System.currentTimeMillis()
        ) {
            settingsRepository.settings.value.anomaly.enabled && settingsRepository.settings.value.anomaly.apkScan.automaticNewUpdatedApps
        }
        if (admitted != GuardPackageAdmission.INACTIVE) {
            automaticQueue.reserveWake()?.let { reservation ->
                try {
                    appContext.enqueueSentinelStaticWork(settings)
                } catch (failure: Throwable) {
                    automaticQueue.releaseFailedWake(reservation)
                    throw failure
                }
            }
        }
    }

    suspend fun clearAutomaticQueue() = withContext(Dispatchers.IO) {
        controller.cancel()
        androidx.work.WorkManager.getInstance(appContext).cancelUniqueWork(SentinelStaticScanWorker.WORK_NAME)
        automaticQueue.invalidateEpoch()
        automaticQueue.settleInvalidation()
        baselineCursor.clear()
    }

    suspend fun refreshPolicy() {
        val settings = settingsRepository.current()
        val scan = settings.anomaly.apkScan
        val ruleEpoch = if (settings.anomaly.enabled && scan.yaraEnabled) rules.current().sha256 else "dex-only"
        controller.updatePolicy(settings.scanWorkPolicy(ruleEpoch))
    }

    suspend fun requestPackage(packageName: String) {
        refreshPolicy()
        controller.requestPackage(packageName)
    }

    suspend fun requestBaseline() {
        refreshPolicy()
        controller.requestVisibleBaseline()
    }

    private fun environment(): SentinelScanEnvironment {
        val battery = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return SentinelScanEnvironment(
            charging = battery.isCharging,
            batteryNotLow = level in 16..100,
            storageNotLow = StatFs(appContext.filesDir.absolutePath).availableBytes >= 128L * 1024 * 1024,
            criticalBattery = level !in 6..100,
        )
    }
}

internal fun Settings.scanWorkPolicy(ruleEpoch: String): SentinelScanWorkPolicy {
    val scan = anomaly.apkScan
    return SentinelScanWorkPolicy(
        masterEnabled = anomaly.enabled,
        dexEnabled = scan.dexEnabled,
        yaraEnabled = scan.yaraEnabled,
        notifyFindings = scan.notifyFindings,
        automaticNewUpdatedApps = scan.automaticNewUpdatedApps,
        chargingOnly = scan.chargingOnly,
        batteryNotLow = scan.batteryNotLow,
        maxArtifactBytes = if (scan.cost == ApkScanCost.ECONOMY) 16 * 1024 * 1024 else 64 * 1024 * 1024,
        ruleEpoch = ruleEpoch,
    )
}
