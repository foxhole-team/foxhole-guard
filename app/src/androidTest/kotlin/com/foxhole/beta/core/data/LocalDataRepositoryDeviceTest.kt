package com.foxhole.beta.core.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.beta.FoxholeApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalDataRepositoryDeviceTest {
    @Test
    fun factoryResetDeletesSensitiveTablesAndLocalFiles() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FoxholeApplication
        val database = app.container.profileDatabase

        runBlocking {
            database.clearAllTables()
            deleteLocalDataTargets(nonDatabaseFactoryResetTargets(app))
            app.container.profileRepository.importProfile(
                "vless://11111111-1111-1111-1111-111111111111@1.1.1.1:443?encryption=none&security=none&type=tcp#Reset%20Proof",
            )
            seedSensitiveRows(database)
            seedSensitiveFiles(app)

            assertTrue(countRows(database, "profiles") > 0)
            assertTrue(countRows(database, "traffic_windows") > 0)
            assertTrue(countRows(database, "app_traffic_windows") > 0)
            assertTrue(countRows(database, "network_activity_events") > 0)
            assertTrue(countRows(database, "anomaly_events") > 0)
            assertTrue(File(app.filesDir, "profile-secrets").walkTopDown().any { file -> file.isFile })
            assertTrue(File(app.filesDir, "diagnostics-journal").exists())
            assertTrue(File(app.cacheDir, "diagnostics-export").exists())
            assertTrue(File(app.filesDir, "dns-rule-sets").exists())
            assertTrue(File(app.filesDir, "keys/profile-db.passphrase").exists())
            assertTrue(databaseFiles(app, "foxhole.db").all(File::exists))

            val result = app.container.localDataRepository.factoryReset()

            assertTrue(result.deletedFiles >= 1)
            listOf(
                "profiles",
                "traffic_windows",
                "app_traffic_windows",
                "traffic_baselines",
                "app_baselines",
                "network_activity_events",
                "anomaly_events",
            ).forEach { table ->
                assertEquals("Expected $table to be empty after factory reset", 0, countRows(database, table))
            }
            nonDatabaseFactoryResetTargets(app).forEach { target ->
                assertFalse("Expected factory reset to delete ${target.absolutePath}", target.exists())
            }
        }
    }

    private suspend fun seedSensitiveRows(database: ProfileDatabase) {
        val dao = database.anomalyDao()
        val now = System.currentTimeMillis()
        dao.insertTrafficWindow(
            TrafficWindowEntity(
                startedAtMs = now,
                durationSec = 30,
                networkType = "WIFI",
                vpnMode = "NORMAL",
                profileId = "profile-reset-proof",
                protocol = "VLESS",
                hourBucket = anomalyHourBucket(now),
                rxBytes = 1024,
                txBytes = 2048,
                blockedDns = 1,
                allowedDns = 2,
                reconnects = 0,
                latencyMs = 42,
                destinationCountries = mapOf("FR" to 1024L),
                blockedDnsDomains = mapOf("tracker.example" to 1L),
            ),
        )
        dao.insertAppTrafficWindows(
            listOf(
                AppTrafficWindowEntity(
                    packageName = "com.example.resetproof",
                    uid = 12345,
                    startedAtMs = now,
                    durationSec = 30,
                    rxBytes = 512,
                    txBytes = 256,
                    foreground = true,
                    networkType = "WIFI",
                    hourBucket = anomalyHourBucket(now),
                ),
            ),
        )
        dao.insertNetworkActivityEvent(
            NetworkActivityEventEntity(
                timestampMs = now,
                packageNames = listOf("com.example.resetproof"),
                protocol = "tcp",
                remoteHost = "canary.example",
                remotePort = 443,
                countryCode = "FR",
                bytesRx = 512,
                bytesTx = 256,
                profileId = 1L,
                sessionId = "session-reset-proof",
            ),
        )
        dao.upsertTrafficBaseline(
            TrafficBaselineEntity(
                baselineKey = "traffic-reset-proof",
                profileId = "profile-reset-proof",
                protocol = "VLESS",
                networkType = "WIFI",
                hourBucket = anomalyHourBucket(now),
                metric = "rx",
                median = 1.0,
                mad = 0.1,
                ewma = 1.0,
                ewmad = 0.1,
                sampleCount = 1,
                lastUpdatedAt = now,
            ),
        )
        dao.upsertAppBaseline(
            AppBaselineEntity(
                baselineKey = "app-reset-proof",
                packageName = "com.example.resetproof",
                profileId = "profile-reset-proof",
                protocol = "VLESS",
                networkType = "WIFI",
                hourBucket = anomalyHourBucket(now),
                metric = "rx",
                median = 1.0,
                mad = 0.1,
                ewma = 1.0,
                ewmad = 0.1,
                sampleCount = 1,
                lastUpdatedAt = now,
            ),
        )
        dao.insertAnomalyEvent(
            AnomalyEventEntity(
                createdAtMs = now,
                type = "APP_UPLOAD_SPIKE",
                severity = "NOTIFICATION",
                score = 90,
                reason = "reset proof",
                evidence = mapOf("package" to "com.example.resetproof"),
                packageName = "com.example.resetproof",
                profileId = "profile-reset-proof",
                protocol = "VLESS",
                notificationShown = true,
            ),
        )
    }

    private fun seedSensitiveFiles(app: FoxholeApplication) {
        writeProofFile(File(app.filesDir, "diagnostics-journal/session.log"))
        writeProofFile(File(app.cacheDir, "diagnostics-export/export.zip"))
        writeProofFile(File(app.filesDir, "dns-rule-sets/user-cache.srs"))
        writeProofFile(File(app.filesDir, "keys/profile-db.passphrase"))
        databaseFiles(app, "foxhole.db").forEach(::writeProofFile)
    }

    private fun writeProofFile(file: File) {
        file.parentFile?.mkdirs()
        file.writeText("reset-proof")
    }

    private fun nonDatabaseFactoryResetTargets(app: FoxholeApplication): List<File> =
        factoryResetFileTargets(app).filterNot { file ->
            file.absolutePath.startsWith(app.getDatabasePath("foxhole.secure.db").absolutePath)
        }

    private fun countRows(
        database: ProfileDatabase,
        table: String,
    ): Int {
        database.query(SimpleSQLiteQuery("select count(*) from $table")).use { cursor ->
            assertTrue(cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }
}
