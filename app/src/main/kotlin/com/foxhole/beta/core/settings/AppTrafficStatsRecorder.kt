package com.foxhole.beta.core.settings

import android.content.Context
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.Build
import com.foxhole.beta.core.model.AppTrafficBaseline
import com.foxhole.beta.core.model.AppTrafficSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppTrafficStatsRecorder(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun recordSnapshot(now: Long = System.currentTimeMillis()) {
        val packageManager = context.packageManager
        val current =
            withContext(Dispatchers.IO) {
                val applications =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        packageManager.getInstalledApplications(0)
                    }
                applications
                    .asSequence()
                    .filterNot { app -> app.packageName == context.packageName }
                    .mapNotNull { app ->
                        val rx = TrafficStats.getUidRxBytes(app.uid)
                        val tx = TrafficStats.getUidTxBytes(app.uid)
                        if (app.uid <= 0 || rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
                            null
                        } else {
                            AppTrafficBaseline(
                                packageName = app.packageName,
                                uid = app.uid,
                                rxBytes = rx.coerceAtLeast(0L),
                                txBytes = tx.coerceAtLeast(0L),
                                sampledAt = now,
                            )
                        }
                    }
                    .distinctBy(AppTrafficBaseline::packageName)
                    .toList()
            }
        val previous = settingsRepository.settings.value.appTrafficBaselines.associateBy(AppTrafficBaseline::packageName)
        val samples =
            current.mapNotNull { baseline ->
                val old = previous[baseline.packageName] ?: return@mapNotNull null
                val rxDelta = baseline.rxBytes - old.rxBytes
                val txDelta = baseline.txBytes - old.txBytes
                if (rxDelta <= 0L && txDelta <= 0L) {
                    null
                } else {
                    AppTrafficSample(
                        packageName = baseline.packageName,
                        uid = baseline.uid,
                        rxBytes = rxDelta.coerceAtLeast(0L),
                        txBytes = txDelta.coerceAtLeast(0L),
                        sampledAt = now,
                    )
                }
            }
        settingsRepository.recordAppTrafficSnapshot(current, samples, now)
    }
}
