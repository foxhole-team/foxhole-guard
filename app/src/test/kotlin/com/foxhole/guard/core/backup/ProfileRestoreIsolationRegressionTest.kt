package com.foxhole.guard.core.backup

import com.foxhole.core.model.Settings
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.guard.core.data.ProfileEntity
import com.foxhole.guard.core.data.ProfileMutationCoordinator
import com.foxhole.guard.core.settings.SettingsRestoreSession
import com.foxhole.guard.core.settings.readSettingsSnapshotUnderOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProfileRestoreIsolationRegressionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `rollback owns its writes while a concurrent profile settings writer and export await fresh state`() = runBlocking {
        val root = temporary.newFolder("owned-state")
        val files = OwnedProfileFiles(root)
        val settingsFile = File(root, "settings.json")
        val json = Json
        fun readSettings() = json.decodeFromString(Settings.serializer(), settingsFile.readText())
        fun writeSettings(value: Settings) = settingsFile.writeText(json.encodeToString(Settings.serializer(), value))
        writeSettings(Settings())
        files.insert(row(1, "before"), StoredProfileSecret(rawInput = "owned-before"))
        val coordinator = ProfileMutationCoordinator()
        val settingsLock = Mutex()
        val applyEntered = CountDownLatch(1)
        val releaseApply = CountDownLatch(1)
        val foreignAttempt = CountDownLatch(1)
        val foreignWritten = CountDownLatch(1)
        val exportAttempt = CountDownLatch(1)
        val exportCaptured = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val primary = CancellationException("owned interrupted apply")
        val restore = scope.async {
            runCatching {
                coordinator.write({ error -> throw error }) { owner ->
                    owner.secrets(coordinator.secretMutex) {
                        settingsLock.withLock(owner) {
                            val state = readSettings()
                            val session = SettingsRestoreSession(
                                state,
                                { check(settingsLock.holdsLock(owner)) },
                                ::writeSettings,
                                {}
                            )
                            owner.bindSettings(session)
                            executeFailAtomicBackupRestore(
                                capture = { captureProfileCheckpoint(files) },
                                apply = {
                                    files.insert(row(2, "restore"), StoredProfileSecret(rawInput = "owned-restore"))
                                    session.update { it.copy(anomaly = it.anomaly.copy(enabled = true)) }
                                    session.persist()
                                    applyEntered.countDown()
                                    check(releaseApply.await(5, TimeUnit.SECONDS))
                                    throw primary
                                },
                                rollback = { checkpoint ->
                                    owner.markRestoreRolledBack()
                                    try {
                                        restoreProfileCheckpointSettled(
                                            files,
                                            checkpoint,
                                            files.profiles().map(ProfileEntity::secretRef).toSet(),
                                            session::rollback,
                                        )
                                    } finally {
                                        owner.markRestoreRolledBack()
                                    }
                                },
                            )
                        }
                    }
                }
            }.exceptionOrNull()
        }
        if (!applyEntered.await(5, TimeUnit.SECONDS)) {
            val failure = if (restore.isCompleted) restore.await() else null
            throw AssertionError("owned apply phase was not reached", failure)
        }
        val foreign = scope.async {
            foreignAttempt.countDown()
            coordinator.write({ error -> throw error }) { owner ->
                owner.secrets(coordinator.secretMutex) {
                    settingsLock.withLock {
                        files.insert(row(3, "foreign"), StoredProfileSecret(rawInput = "owned-foreign"))
                        writeSettings(readSettings().let { it.copy(anomaly = it.anomaly.copy(enabled = true)) })
                        owner.markLocalCommit()
                        foreignWritten.countDown()
                    }
                }
            }
        }
        val exported = scope.async {
            exportAttempt.countDown()
            coordinator.readOwned { owner ->
                owner.secrets(coordinator.secretMutex) {
                    readSettingsSnapshotUnderOwner(settingsLock, coordinator, owner, { readSettings() }) { state ->
                        (files.profiles().map(ProfileEntity::id) to state.anomaly.enabled).also { exportCaptured.countDown() }
                    }
                }
            }
        }
        try {
            assertTrue(foreignAttempt.await(5, TimeUnit.SECONDS))
            assertTrue(exportAttempt.await(5, TimeUnit.SECONDS))
            assertFalse(foreignWritten.await(100, TimeUnit.MILLISECONDS))
            assertFalse(exportCaptured.await(100, TimeUnit.MILLISECONDS))
            releaseApply.countDown()
            assertSame(primary, restore.await())
            foreign.await()
            val snapshot = exported.await()
            assertFalse(snapshot.first.contains(2L))
            assertEquals(snapshot.first.contains(3L), snapshot.second)
            assertEquals(listOf(1L, 3L), files.profiles().map(ProfileEntity::id))
            assertEquals("owned-foreign", files.readSecret("foreign")?.rawInput)
            assertEquals(null, files.readSecret("restore"))
            assertTrue(readSettings().anomaly.enabled)
        } finally {
            releaseApply.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `export encoding after short coherent capture does not hold mutation locks`() = runBlocking {
        val files = OwnedProfileFiles(temporary.newFolder("export-state"))
        files.insert(row(1, "before"), StoredProfileSecret(rawInput = "owned-before"))
        val coordinator = ProfileMutationCoordinator()
        val settingsLock = Mutex()
        val state = Settings()
        val captured = coordinator.readOwned { owner ->
            owner.secrets(coordinator.secretMutex) {
                readSettingsSnapshotUnderOwner(settingsLock, coordinator, owner, { state }) { current ->
                    files.profiles() to current
                }
            }
        }
        assertFalse(coordinator.mutex.isLocked)
        assertFalse(coordinator.secretMutex.isLocked)
        assertFalse(settingsLock.isLocked)
        coordinator.write({ error -> throw error }) { owner ->
            files.insert(row(3, "later"), StoredProfileSecret(rawInput = "owned-later"))
            owner.markLocalCommit()
        }
        assertEquals(listOf(1L), captured.first.map(ProfileEntity::id))
        assertEquals(listOf(1L, 3L), files.profiles().map(ProfileEntity::id))
        assertEquals(Settings().schemaVersion, captured.second.schemaVersion)
    }

    @Test
    fun `protected current credentials and pending quarantine are merged from lease state`() = runBlocking {
        val current = Settings().let {
            it.copy(
                expert = it.expert.copy(
                    pendingQuarantinePackages = listOf("owned.pending"),
                    quarantinePolicyRevision = 17,
                )
            )
        }
        val incoming = Settings().let {
            it.copy(
                expert = it.expert.copy(
                    pendingQuarantinePackages = listOf("stale.backup"),
                    quarantinePolicyRevision = 1,
                )
            )
        }
        val coordinator = ProfileMutationCoordinator()
        coordinator.write({ error -> throw error }) { owner ->
            val session = SettingsRestoreSession(current, { owner.requireOwner(coordinator) }, {}, {})
            owner.bindSettings(session)
            session.update { live -> mergeRestoredSettings(incoming, live) }
            assertEquals(listOf("owned.pending"), session.current().expert.pendingQuarantinePackages)
            assertEquals(current.appLock, session.current().appLock)
            assertEquals(current.expert.localSurfaces.auth, session.current().expert.localSurfaces.auth)
            assertTrue(session.current().expert.quarantinePolicyRevision >= current.expert.quarantinePolicyRevision)
        }
    }

    @Test
    fun `failed secret rollback still restores settings and settles unrelated touched secrets`() = runBlocking {
        independentRollback(false)
    }

    @Test
    fun `failed row rollback still attempts settings and preserves every cleanup failure on primary`() = runBlocking {
        independentRollback(true)
    }

    private suspend fun independentRollback(failRows: Boolean) {
        val root = temporary.newFolder("owned-independent-rollback")
        val files = OwnedProfileFiles(root)
        val settings = File(root, "settings.txt").apply { writeText("old") }
        files.insert(row(1, "before"), StoredProfileSecret(rawInput = "owned-before"))
        val checkpoint = captureProfileCheckpoint(files)
        val primary = AssertionError("owned transaction commit failure after settings persist")
        val profileFailure: Throwable = if (failRows) {
            IllegalStateException("owned rows rollback failure")
        } else {
            CancellationException("owned secret rollback cancellation")
        }
        val deleteFailure = AssertionError("owned touched secret delete failure")
        var settingsAttempts = 0
        var rowAttempts = 0
        val access = object : ProfileBackupCheckpointAccess by files {
            override suspend fun writeSecret(ref: String, secret: StoredProfileSecret) {
                if (!failRows) throw profileFailure
                files.writeSecret(ref, secret)
            }
            override suspend fun replaceProfiles(rows: List<ProfileEntity>) {
                rowAttempts++
                if (failRows) throw profileFailure
                files.replaceProfiles(rows)
            }
            override suspend fun cleanupSecret(ref: String) {
                if (ref == "failed-touch") throw deleteFailure
                files.cleanupSecret(ref)
            }
        }
        val failure = runCatching {
            executeFailAtomicBackupRestore(
                capture = { checkpoint },
                apply = {
                    files.writeSecret("failed-touch", StoredProfileSecret(rawInput = "owned-new"))
                    files.writeSecret("other-touch", StoredProfileSecret(rawInput = "owned-new"))
                    settings.writeText("new persisted settings")
                    throw primary
                },
                rollback = { saved ->
                    restoreProfileCheckpointSettled(
                        access,
                        saved,
                        setOf("failed-touch", "other-touch"),
                        restoreSettings = {
                            settingsAttempts++
                            settings.writeText("old")
                        },
                    )
                },
            )
        }.exceptionOrNull()
        assertSame(primary, failure)
        assertTrue(primary.suppressed.any { it === profileFailure })
        assertTrue(primary.suppressed.any { it === deleteFailure })
        assertEquals(1, rowAttempts)
        assertEquals(1, settingsAttempts)
        assertEquals("old", settings.readText())
        assertEquals(null, files.readSecret("other-touch"))
        assertTrue(files.readSecret("failed-touch") != null)
    }

    private fun row(id: Long, ref: String) = ProfileEntity(
        id,
        "owned-$ref",
        "RAW_CONFIG_JSON",
        ref,
        "SOCKS",
        1L,
        null,
        id == 1L,
    )
}
