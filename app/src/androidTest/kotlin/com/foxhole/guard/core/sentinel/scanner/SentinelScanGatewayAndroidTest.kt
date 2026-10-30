package com.foxhole.guard.core.sentinel.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.ApkScanCost
import com.foxhole.guard.FoxholeApplication
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SentinelScanGatewayAndroidTest {
    @Test fun realInstalledSmallApkUsesBoundedIpcAndNeverClaimsSafety() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as FoxholeApplication
        val saved = app.appGraph.settingsRepository.current()
        val packageName = InstrumentationRegistry.getArguments().getString("owned_static_package") ?: instrumentation.context.packageName
        val db = app.appGraph.profileDatabase
        val initialIds = db.anomalyDao().getAnomalyEvents(0).map { it.id }.toSet()
        try {
            val scanPolicy = saved.anomaly.apkScan.copy(
                yaraEnabled = true,
                notifyFindings = false,
                cost = ApkScanCost.BALANCED
            )
            app.appGraph.settingsRepository.update { current ->
                current.copy(anomaly = current.anomaly.copy(enabled = true, apkScan = scanPolicy))
            }
            val gateway = app.appGraph.sentinelScanGateway
            assertTrue(gateway.engineAvailable)
            gateway.requestPackage(packageName)
            val state = gateway.controller.state.value
            val summary = requireNotNull(state.lastSummary)
            val detail = "stage=${state.stage} complete=${summary.complete} " +
                "referenceKinds=${summary.observations.size} rules=${summary.ruleIds.size} " +
                "issues=${summary.issues.sorted().joinToString(",")}"
            android.util.Log.i("OwnedStaticScan", detail)
            assertTrue(detail, state.stage == SentinelScanStage.COMPLETE)
            assertTrue(detail, summary.complete)
            assertTrue(summary.source.packageName == packageName)
            assertFalse(
                summary.ruleIds.any { it.contains("safe", ignoreCase = true) || it.contains("malware", ignoreCase = true) }
            )
            assertFalse(summary.issues.isEmpty())
        } finally {
            withContext(NonCancellable) {
                app.appGraph.sentinelScanGateway.controller.cancel()
                db.anomalyDao().getAnomalyEvents(
                    0
                ).filter { it.id !in initialIds && it.packageName == packageName }.forEach {
                    db.openHelper.writableDatabase.execSQL("delete from anomaly_events where id = ?", arrayOf(it.id))
                }
                app.appGraph.settingsRepository.restoreExactCheckpoint(saved)
            }
        }
    }
}
