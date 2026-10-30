package com.foxhole.guard.core.backup

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.importer.ProfileImportParser
import com.foxhole.core.model.ApkScanCost
import com.foxhole.core.model.ApkScanSettings
import com.foxhole.core.model.I2pSettings
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StoredProfileSecret
import com.foxhole.core.runtime.I2pdEndpoints
import com.foxhole.core.runtime.I2pdManager
import com.foxhole.core.runtime.I2pdSnapshot
import com.foxhole.core.runtime.I2pdStopPolicy
import com.foxhole.core.runtime.RuntimeConfigAssembler
import com.foxhole.core.runtime.TorRuntimeInstaller
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.core.data.EncryptedProfileSecretStore
import com.foxhole.guard.core.data.ProfileDatabase
import com.foxhole.guard.core.data.ProfileRepository
import com.foxhole.guard.core.data.ProfileSecretStore
import com.foxhole.guard.core.data.RoutingRepository
import com.foxhole.guard.core.diagnostics.DiagnosticsLogger
import com.foxhole.guard.core.security.DatabaseKeySource
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.runtime.DnsFilterAssetInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class OwnedBackupRestoreRoomAndroidTest {
    @Test
    fun staticPolicyRestoresThroughOwnedRoomAndEncryptedSettingsWithoutReplacingDeviceCredentials() = runBlocking {
        Fixture().use { fixture ->
            val policy = ApkScanSettings(
                dexEnabled = true,
                yaraEnabled = true,
                notifyFindings = false,
                automaticNewUpdatedApps = true,
                chargingOnly = true,
                batteryNotLow = true,
                cost = ApkScanCost.BALANCED,
            )
            fixture.settings.update { current ->
                current.copy(
                    updateSources = current.updateSources.copy(
                        appReleasesUrl = "https://api.github.com/repos/owned-fixture/backup/releases/latest",
                        appReleasesToken = "owned current token",
                    )
                )
            }
            assertEquals("owned current token", fixture.settings.current().updateSources.appReleasesToken)
            val defaults = Settings()
            val imported = defaults.copy(anomaly = defaults.anomaly.copy(apkScan = policy))
            fixture.repository.restoreBackupDocument(BackupDocument(settings = imported))
            val current = fixture.settings.current()
            assertEquals(policy, current.anomaly.apkScan)
            assertEquals("owned current token", current.updateSources.appReleasesToken)
            val captured = fixture.repository.captureBackupExportSnapshot(false, true)
            assertEquals(policy, requireNotNull(captured.settings).anomaly.apkScan)
        }
    }

    @Test
    fun cancelledRestoreRollsBackBeforeWaitingWriterAndCommittedFlowRequery() = runBlocking {
        Fixture().use { fixture ->
            val original = fixture.repository.importProfile(profileUri(1080), preferredName = "original")
            val observed = Collections.synchronizedList(mutableListOf<List<String>>())
            val started = CompletableDeferred<Unit>()
            val writerObserved = CompletableDeferred<Unit>()
            val collection = launch(Dispatchers.IO) {
                fixture.repository.profiles.collect { profiles ->
                    observed += profiles.map { it.name }
                    started.complete(Unit)
                    if (profiles.map { it.name } == listOf("committed writer")) writerObserved.complete(Unit)
                }
            }
            withTimeout(5000) { started.await() }
            fixture.secrets.holdWriteNumber(2) {
                assertTrue(fixture.database.profileDao().getAllProfiles().any { it.name == "transient" })
            }
            val restored = async(Dispatchers.IO) {
                runCatching {
                    fixture.repository.restoreBackupDocument(
                        BackupDocument(
                            profiles = listOf(
                                payload("transient", 1081),
                                payload("held", 1082),
                            )
                        )
                    )
                }
            }
            withTimeout(5000) {
                kotlinx.coroutines.selects.select<Unit> {
                    fixture.secrets.entered.onAwait { }
                    restored.onAwait { outcome ->
                        throw AssertionError(
                            "restore did not reach held second write",
                            outcome.exceptionOrNull()
                        )
                    }
                }
            }
            val writerStarted = CompletableDeferred<Unit>()
            val writer = async(Dispatchers.IO) {
                writerStarted.complete(Unit)
                fixture.repository.renameProfile(original.id, "committed writer")
            }
            withTimeout(5000) { writerStarted.await() }
            val exportStarted = CompletableDeferred<Unit>()
            val export = async(Dispatchers.IO) {
                exportStarted.complete(Unit)
                fixture.repository.captureBackupExportSnapshot(true, true)
            }
            withTimeout(5000) { exportStarted.await() }
            try {
                assertFalse(writer.isCompleted)
                assertFalse(export.isCompleted)
                restored.cancel(CancellationException("owned cancelled restore"))
                fixture.secrets.release.complete(Unit)
                restored.join()
                writer.await()
                withTimeout(5000) { writerObserved.await() }
                val snapshot = export.await()
                assertEquals(listOf(original.id), snapshot.profiles.map { it.backupId })
                assertFalse(observed.flatten().contains("transient"))
                assertEquals(listOf("committed writer"), fixture.database.profileDao().getAllProfiles().map { it.name })
                withTimeout(5000) { fixture.repository.captureBackupExportSnapshot(true, true) }.let { final ->
                    assertEquals("committed writer", final.profiles.single().name)
                    assertEquals("committed writer", final.settings!!.lastActiveProfile!!.name)
                }
            } finally {
                fixture.secrets.release.complete(Unit)
                restored.cancelAndJoin()
                writer.cancelAndJoin()
                export.cancelAndJoin()
                collection.cancelAndJoin()
            }
        }
    }

    @Test
    fun ordinaryProfileFailureCommitsSiblingsAndPreservesCurrentDeviceCredentials() = runBlocking {
        Fixture().use { fixture ->
            fixture.settings.update { state ->
                state.copy(
                    updateSources = state.updateSources.copy(appReleasesToken = "owned current token"),
                    expert = state.expert.copy(
                        localSurfaces = state.expert.localSurfaces.copy(
                            auth = LocalAuthSettings(username = "owned", password = "owned-password"),
                        )
                    ),
                )
            }
            val current = fixture.settings.current()
            fixture.secrets.failWriteNumber(2, IllegalStateException("owned profile write failure"))
            val payloads = listOf(payload("first", 1081), payload("failed", 1082), payload("last", 1083))
            val results = fixture.repository.restoreBackupDocument(
                BackupDocument(settings = Settings(), profiles = payloads)
            )
            assertEquals(listOf(true, false, true), results.map { it.restored })
            assertEquals(listOf("first", "last"), fixture.database.profileDao().getAllProfiles().map { it.name })
            val restored = fixture.settings.current()
            assertEquals(current.updateSources.appReleasesToken, restored.updateSources.appReleasesToken)
            assertEquals(current.expert.localSurfaces.auth, restored.expert.localSurfaces.auth)
            val exported = fixture.repository.captureBackupExportSnapshot(true, true)
            assertEquals(restored.schemaVersion, exported.settingsSchemaVersion)
            assertEquals(2, exported.profiles.size)
            exported.profiles.forEach { profile -> assertNotNull(profile.resolvedConfigJson) }
        }
    }

    @Test
    fun fatalRestoreRollsBackItsRowsSecretsAndSettingsPreservingPrimaryIdentity() = runBlocking {
        Fixture().use { fixture ->
            val original = fixture.repository.importProfile(profileUri(1080), preferredName = "original")
            val before = fixture.repository.captureBackupExportSnapshot(true, true)
            val primary = OwnedStorageError()
            fixture.secrets.failWriteNumber(2, primary)
            val failure = runCatching {
                fixture.repository.restoreBackupDocument(
                    BackupDocument(
                        settings = Settings(),
                        profiles = listOf(payload("temporary", 1081), payload("fatal", 1082)),
                    )
                )
            }.exceptionOrNull()
            assertSame(primary, failure)
            val after = fixture.repository.captureBackupExportSnapshot(true, true)
            assertEquals(before, after)
            assertEquals(listOf(original.id), fixture.database.profileDao().getAllProfiles().map { it.id })
            assertEquals(1, fixture.context.filesDir.resolve("profile-secrets").listFiles()!!.size)
        }
    }

    @Test
    fun durableRoomMutationSettlesEffectsOutsideLocksBeforeCancellationEscapes() = runBlocking {
        Fixture().use { fixture ->
            val original = fixture.repository.importProfile(profileUri(1080), preferredName = "original")
            val calls = AtomicInteger()
            val primary = OwnedCancellation()
            val failure = runCatching {
                fixture.repository.withProfileMutation { owner ->
                    owner.secrets(fixture.repository.secretMutationMutex) {
                        fixture.database.profileDao().updateName(original.id, "durable")
                        owner.markLocalCommit()
                        owner.afterCommit {
                            assertFalse(fixture.repository.mutationCoordinator.mutex.isLocked)
                            assertFalse(fixture.repository.secretMutationMutex.isLocked)
                            calls.incrementAndGet()
                        }
                        throw primary
                    }
                }
            }.exceptionOrNull()
            assertSame(primary, failure)
            assertEquals(1, calls.get())
            assertEquals("durable", fixture.database.profileDao().getById(original.id)!!.name)
        }
    }

    private class Fixture : AutoCloseable {
        val context: OwnedContext
        private val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
        val database: ProfileDatabase
        val settings: SettingsRepository
        val secrets: ControlledSecrets
        val repository: ProfileRepository

        init {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            assumeTrue(
                "requires explicit owned VM invocation",
                InstrumentationRegistry.getArguments().getString("ownedVmBackupRestore") == "true"
            )
            check(BuildConfig.DEBUG && instrumentation.targetContext.packageName.endsWith(".debug")) {
                "owned restore refuses release namespaces"
            }
            check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "owned restore refuses physical devices" }
            context = OwnedContext(instrumentation.targetContext)
            database = ProfileDatabase.create(
                context,
                object : DatabaseKeySource {
                    override fun acquirePassphrase(): ByteArray = passphrase.copyOf()
                }
            )
            settings = SettingsRepository(context)
            val json = Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
            secrets = ControlledSecrets(EncryptedProfileSecretStore(context, json))
            val client = OkHttpClient.Builder().addInterceptor {
                throw AssertionError(
                    "owned restore must not fetch"
                )
            }.build()
            repository = ProfileRepository(
                databaseProvider = { database }, secretStore = secrets,
                parser = ProfileImportParser(json, remoteHostResolver = { throw AssertionError("owned restore must not resolve") }),
                httpClient = client, diagnosticsLogger = DiagnosticsLogger.forManualExportTests(context),
                settingsRepository = settings, routingRepository = RoutingRepository({ database }, client, json),
                runtimeConfigAssembler = RuntimeConfigAssembler(json, selfPackageName = context.packageName),
                torRuntimeInstaller = TorRuntimeInstaller(context), i2pdManager = RefusingRuntimeDependency,
                dnsFilterAssetInstaller = DnsFilterAssetInstaller(context, json), json = json,
            )
        }

        override fun close() {
            database.close()
            passphrase.fill(0)
            context.clearOwnedFiles()
        }
    }

    private class ControlledSecrets(private val delegate: ProfileSecretStore) : ProfileSecretStore by delegate {
        private val writeNumber = AtomicInteger()
        private var failingNumber = -1
        private var failure: Throwable? = null
        private var holdingNumber = -1
        private var beforeHold: suspend () -> Unit = {}
        var entered = CompletableDeferred<Unit>()
            private set
        var release = CompletableDeferred<Unit>()
            private set

        fun holdWriteNumber(number: Int, inspectOwnedTransaction: suspend () -> Unit) {
            writeNumber.set(0)
            holdingNumber = number
            beforeHold = inspectOwnedTransaction
            entered = CompletableDeferred()
            release = CompletableDeferred()
        }
        fun failWriteNumber(number: Int, error: Throwable) {
            writeNumber.set(0)
            failingNumber = number
            failure = error
        }
        override suspend fun write(secretRef: String, value: StoredProfileSecret) {
            val number = writeNumber.incrementAndGet()
            if (number == holdingNumber) {
                holdingNumber = -1
                beforeHold()
                entered.complete(Unit)
                release.await()
            }
            if (number == failingNumber) throw checkNotNull(failure)
            delegate.write(secretRef, value)
        }
    }

    private class OwnedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "owned-backup-${UUID.randomUUID()}"
        private val root = File(base.cacheDir, prefix).apply {
            check(canonicalFile.parentFile == base.cacheDir.canonicalFile) { "owned path escaped its cache namespace" }
            check(mkdirs())
        }
        private val preferenceNames = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile!!.mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val ownedName = "$prefix-$name"
            synchronized(preferenceNames) { preferenceNames += ownedName }
            return super.getSharedPreferences(ownedName, mode)
        }
        fun clearOwnedFiles() {
            synchronized(preferenceNames) { preferenceNames.forEach { super.deleteSharedPreferences(it) } }
            check(root.deleteRecursively())
        }
    }

    private class OwnedStorageError : Error("owned storage fatal")
    private class OwnedCancellation : CancellationException("owned post-commit cancellation")

    private companion object {
        fun profileUri(port: Int) = "trojan://owned-secret@1.1.1.1:$port?security=tls&type=tcp"
        fun payload(name: String, port: Int) = BackupProfilePayload(
            name = name,
            sourceType = "LOCAL_LINK",
            rawInput = profileUri(port),
        )
        fun document(name: String, port: Int) = BackupDocument(profiles = listOf(payload(name, port)))
    }
}

private object RefusingRuntimeDependency : I2pdManager {
    override suspend fun ensureStarted(settings: I2pSettings): I2pdEndpoints = unexpected()
    override suspend fun awaitReady(timeoutMs: Long): Boolean = unexpected()
    override fun confirmCarrierReady(expectedGeneration: Long): Boolean = unexpected()
    override fun markCarrierUnavailable(): Unit = unexpected()
    override suspend fun stop(policy: I2pdStopPolicy): Unit = unexpected()
    override fun kill(reason: String): Unit = unexpected()
    override fun snapshot(): I2pdSnapshot = unexpected()
    override fun setUnexpectedExitListener(listener: (() -> Unit)?): Unit = unexpected()
    private fun unexpected(): Nothing = throw AssertionError("owned restore refuses runtime operations")
}
