package com.foxhole.guard.core.sentinel.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import com.foxhole.guard.FoxholeApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScannerJournalTransactionAndroidTest {
    @Test fun currentOwnerWritesNeutralStaticEvent() = runBlocking {
        withOwnedJournal { app, summary ->
            assertTrue(app.appGraph.anomalyRepository.recordStaticScan(summary) { true })
            val rows = app.appGraph.profileDatabase.anomalyDao().getAnomalyEvents(0).filter {
                it.packageName == summary.source.packageName
            }
            assertEquals(1, rows.size)
            assertEquals(AnomalyType.APP_STATIC_REFERENCE.name, rows.single().type)
            assertEquals(AnomalySeverity.ACTIVITY_LOG.name, rows.single().severity)
            assertEquals(0, rows.single().score)
            assertFalse(rows.single().notificationShown)
        }
    }

    @Test fun changedOwnerAfterInsertRollsBackInsteadOfClaimingCommit() = runBlocking {
        withOwnedJournal { app, summary ->
            var checks = 0
            assertFalse(app.appGraph.anomalyRepository.recordStaticScan(summary) { ++checks < 3 })
            assertEquals(
                0,
                app.appGraph.profileDatabase.anomalyDao().getAnomalyEvents(0).count {
                    it.packageName == summary.source.packageName
                }
            )
        }
    }

    @Test fun cancelledAndFatalAfterInsertRetainIdentityAndRollback() = runBlocking {
        withOwnedJournal { app, summary ->
            for (failure in listOf(OwnedJournalCancellation(), OwnedJournalFatal())) {
                var checks = 0
                val actual = try {
                    app.appGraph.anomalyRepository.recordStaticScan(summary) {
                        if (++checks == 3) throw failure
                        true
                    }
                    null
                } catch (caught: Throwable) { caught }
                assertSame(failure, actual)
                assertEquals(
                    0,
                    app.appGraph.profileDatabase.anomalyDao().getAnomalyEvents(0).count {
                        it.packageName == summary.source.packageName
                    }
                )
            }
        }
    }

    @Test fun staticRowsCannotConsumeTrafficNotificationRetryLimit() = runBlocking {
        withOwnedJournal { app, summary ->
            assertTrue(app.appGraph.anomalyRepository.recordStaticScan(summary) { true })
            val candidates = app.appGraph.profileDatabase.anomalyDao().undeliveredAnomalyEvents(
                since = 0,
                limit = 1,
                excludedType = AnomalyType.TOR_OR_I2P_ROUTE_MISMATCH.name,
            )
            assertFalse(candidates.any { it.type == AnomalyType.APP_STATIC_REFERENCE.name })
        }
    }

    private suspend fun withOwnedJournal(operation: suspend (FoxholeApplication, SentinelScanSummary) -> Unit) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FoxholeApplication
        val saved = app.appGraph.settingsRepository.current()
        val packageName = "org.foxhole.owned." + UUID.randomUUID().toString().replace("-", "")
        val repository = app.appGraph.anomalyRepository
        try {
            app.appGraph.settingsRepository.update {
                it.copy(
                    anomaly = it.anomaly.copy(enabled = true, apkScan = it.anomaly.apkScan.copy(notifyFindings = false))
                )
            }
            val summary = SentinelScanSummary(
                SentinelScanSource(packageName, "owned:1", repository.captureStaticScanJournalEpoch()),
                true,
                setOf("DEX_CLASS_LOADER_REFERENCE"),
                emptySet(),
            )
            operation(app, summary)
        } finally {
            withContext(NonCancellable) {
                val db = app.appGraph.profileDatabase
                db.openHelper.writableDatabase.execSQL(
                    "delete from anomaly_events where packageName = ?",
                    arrayOf(packageName)
                )
                app.appGraph.settingsRepository.restoreExactCheckpoint(saved)
            }
        }
    }
}
private class OwnedJournalCancellation : CancellationException("owned journal cancellation")
private class OwnedJournalFatal : Error("owned journal fatal")
