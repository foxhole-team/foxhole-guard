package com.foxhole.beta.core.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.smart.SmartStartReplayEvent
import com.foxhole.beta.core.smart.toJsonLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

data class DiagnosticEntry(
    val timestamp: Long,
    val tag: String,
    val message: String,
)

internal fun liveDiagnosticMessage(
    message: String,
    allowRawLiveDiagnostics: Boolean,
): String =
    if (allowRawLiveDiagnostics) {
        message
    } else {
        DiagnosticSanitizer.sanitizeForPersistence(message)
    }

class DiagnosticsLogger(
    private val context: Context,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val retentionProvider: () -> DiagnosticsRetention = { DiagnosticsRetention.HOURS_24 },
    private val settingsSnapshotProvider: () -> Settings = { Settings() },
) {
    private val formatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
    private val sessionStore =
        DiagnosticsSessionStore(
            journalDir = File(context.filesDir, JOURNAL_DIR_NAME),
            nowProvider = nowProvider,
        )
    private val entriesMutable = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    private val entriesLock = Any()
    private val throttleLock = Any()
    private val throttledKeys = LinkedHashMap<String, Long>()
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceLock = Any()
    private val pendingPersistedEntries = ArrayDeque<PendingDiagnosticWrite>()
    private var persistenceFlushScheduled = false
    private var droppedPersistenceEntries = 0
    private var persistenceGeneration = 0L
    private var lastPersistenceFailureAt = 0L

    val entries: StateFlow<List<DiagnosticEntry>> = entriesMutable

    init {
        loadRecentEntriesAsync()
        record("diagnostics", "Log session started")
    }

    fun record(tag: String, message: String) {
        val now = nowProvider()
        val retention = currentRetention()
        val allowRawLiveDiagnostics = allowRawLiveDiagnostics()
        val normalizedMessage = DiagnosticSanitizer.normalizeForStorage(message)
        val normalizedTag = tag.lowercase(Locale.ROOT)
        val liveMessage = liveDiagnosticMessage(normalizedMessage, allowRawLiveDiagnostics)
        val entry = DiagnosticEntry(now, normalizedTag, liveMessage)
        val persistedEntry =
            entry.copy(message = DiagnosticSanitizer.sanitizeForPersistence(normalizedMessage))
        publishLiveEntry(entry, now, retention)
        enqueuePersistence(persistedEntry, retention)
        if (BuildConfig.ENABLE_DIAGNOSTIC_LOGCAT) {
            Log.d(LOG_TAG, "[$normalizedTag] ${DiagnosticSanitizer.sanitizeForExport(normalizedMessage)}")
        }
    }

    fun recordStructured(
        tag: String,
        headline: String,
        vararg details: String?,
    ) {
        record(
            tag = tag,
            message =
                buildString {
                    append(headline.trim())
                    val visibleDetails = details.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                    if (visibleDetails.isNotEmpty()) {
                        append(": ")
                        append(visibleDetails.joinToString(separator = " • "))
                    }
                },
        )
    }

    fun recordThrottled(
        tag: String,
        throttleKey: String,
        windowMs: Long,
        message: String,
    ) {
        val now = nowProvider()
        val shouldRecord =
            synchronized(throttleLock) {
                pruneThrottledKeys(now)
                val previousAt = throttledKeys[throttleKey]
                if (previousAt != null && now - previousAt < windowMs) {
                    false
                } else {
                    throttledKeys[throttleKey] = now
                    true
                }
            }
        if (shouldRecord) {
            record(tag, message)
        }
    }

    fun recordConnection(state: ConnectionState, reason: String? = null) {
        val suffix = reason?.takeIf { it.isNotBlank() }?.let { " reason=$it" }.orEmpty()
        record("connection", "state=${state.name.lowercase(Locale.ROOT)}$suffix")
    }

    fun recordSmartStartReplay(
        event: SmartStartReplayEvent,
        enabled: Boolean,
    ) {
        if (!BuildConfig.DEBUG || !enabled) {
            return
        }
        runCatching {
            val now = nowProvider()
            val retention = currentRetention()
            val allowRawLiveDiagnostics = allowRawLiveDiagnostics()
            val replayMessage = event.toJsonLine()
            val entry =
                DiagnosticEntry(
                    timestamp = now,
                    tag = SMART_START_REPLAY_TAG,
                    message = liveDiagnosticMessage(replayMessage, allowRawLiveDiagnostics),
                )
            val persistedEntry =
                entry.copy(message = DiagnosticSanitizer.sanitizeForPersistence(replayMessage))
            publishLiveEntry(entry, now, retention)
            enqueuePersistence(persistedEntry, retention)
        }.onFailure {
            record("diagnostics", "smart start replay write failed")
        }
    }

    fun applyLiveDiagnosticsPrivacySetting() {
        if (allowRawLiveDiagnostics()) {
            return
        }
        val now = nowProvider()
        synchronized(entriesLock) {
            entriesMutable.value = prune(entriesMutable.value, now).sanitizeLiveEntriesIfNeeded()
        }
    }

    fun snapshotForExport(sanitize: Boolean = true): String {
        val now = nowProvider()
        val retention = currentRetention()
        val snapshot =
            synchronized(entriesLock) {
                val liveSnapshot = prune(entriesMutable.value, now, retention)
                val prepared =
                    if (sanitize) {
                        liveSnapshot.map { entry ->
                            entry.copy(message = DiagnosticSanitizer.sanitizeForExport(entry.message))
                        }
                    } else {
                        liveSnapshot
                    }
                if (sanitize) {
                    entriesMutable.value = prepared
                }
                prepared
            }
        return formatDiagnosticsExport(
            metadata = diagnosticsExportMetadata(retention),
            entries = snapshot,
            formatter = formatter,
            sanitizeMessages = sanitize,
        )
    }

    fun createExportFile(sanitize: Boolean = true): File {
        cleanupExpiredExports()
        val targetDir = File(context.cacheDir, EXPORT_DIR_NAME).apply { mkdirs() }
        val targetFile = File(targetDir, "foxhole-diagnostics-${UUID.randomUUID()}.log.gz")
        GZIPOutputStream(targetFile.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(snapshotForExport(sanitize = sanitize))
        }
        scheduleCleanup()
        return targetFile
    }

    fun clear() {
        synchronized(entriesLock) {
            entriesMutable.value = emptyList()
        }
        synchronized(throttleLock) {
            throttledKeys.clear()
        }
        synchronized(persistenceLock) {
            pendingPersistedEntries.clear()
            droppedPersistenceEntries = 0
            persistenceGeneration += 1
        }
        persistenceScope.launch {
            runCatching { sessionStore.clear() }
                .onFailure { error -> publishPersistenceFailure(error) }
        }
    }

    fun cleanupExpiredExports() {
        val now = nowProvider()
        val retention = currentRetention()
        sessionStore.cleanup(now, retention)
        cleanupLegacySmartStartReplay(now, retention)
        val cutoff = now - EXPORT_TTL_MS
        val exportDir = File(context.cacheDir, EXPORT_DIR_NAME)
        if (!exportDir.exists()) {
            return
        }
        exportDir.listFiles()
            ?.filter { it.isFile && it.lastModified() < cutoff }
            ?.forEach(File::delete)
    }

    private fun cleanupLegacySmartStartReplay(
        now: Long,
        retention: DiagnosticsRetention,
    ) {
        val cutoff = now - retention.retentionHours * 60L * 60L * 1000L
        val replayDir = File(context.filesDir, LEGACY_SMART_START_REPLAY_DIR_NAME)
        replayDir
            .listFiles()
            ?.filter { file -> file.isFile && file.lastModified() < cutoff }
            ?.forEach(File::delete)
        replayDir.takeIf { dir -> dir.isDirectory && dir.listFiles().orEmpty().isEmpty() }?.delete()
    }

    private fun loadRecentEntriesAsync() {
        persistenceScope.launch {
            val now = nowProvider()
            val retention = currentRetention()
            runCatching { sessionStore.loadRecentEntries(now, retention) }
                .onSuccess { persistedEntries ->
                    val allowRawLiveDiagnostics = allowRawLiveDiagnostics()
                    synchronized(entriesLock) {
                        val merged =
                            mergeEntries(
                                first = persistedEntries,
                                second = entriesMutable.value,
                            )
                        entriesMutable.value =
                            prune(merged, now, retention)
                                .let { entries ->
                                    if (allowRawLiveDiagnostics) {
                                        entries
                                    } else {
                                        entries.map { entry -> entry.copy(message = liveDiagnosticMessage(entry.message, false)) }
                                    }
                                }
                    }
                }.onFailure { error ->
                    publishPersistenceFailure(error)
                }
        }
    }

    private fun publishLiveEntry(
        entry: DiagnosticEntry,
        now: Long,
        retention: DiagnosticsRetention,
    ) {
        synchronized(entriesLock) {
            entriesMutable.value = (prune(entriesMutable.value, now, retention) + entry).takeLast(retention.maxEntries)
        }
    }

    private fun enqueuePersistence(
        entry: DiagnosticEntry,
        retention: DiagnosticsRetention,
    ) {
        var shouldLaunch = false
        synchronized(persistenceLock) {
            while (pendingPersistedEntries.size >= MAX_PENDING_PERSISTENCE_ENTRIES) {
                pendingPersistedEntries.removeFirst()
                droppedPersistenceEntries += 1
            }
            pendingPersistedEntries.addLast(PendingDiagnosticWrite(entry, retention, persistenceGeneration))
            if (!persistenceFlushScheduled) {
                persistenceFlushScheduled = true
                shouldLaunch = true
            }
        }
        if (shouldLaunch) {
            persistenceScope.launch {
                delay(PERSISTENCE_BATCH_DELAY_MS)
                flushPersistenceQueue()
            }
        }
    }

    private suspend fun flushPersistenceQueue() {
        while (true) {
            val batch = drainPersistenceBatch()
            if (batch.isEmpty()) {
                return
            }
            val currentGeneration = synchronized(persistenceLock) { persistenceGeneration }
            val currentBatch = batch.filter { write -> write.generation == currentGeneration }
            if (currentBatch.isNotEmpty()) {
                runCatching {
                    sessionStore.appendAll(
                        entries = currentBatch.map(PendingDiagnosticWrite::entry),
                        retention = currentBatch.last().retention,
                    )
                }.onFailure { error ->
                    publishPersistenceFailure(error)
                }
            }
            if (batch.size >= MAX_PERSISTENCE_BATCH_SIZE) {
                delay(PERSISTENCE_BATCH_COOLDOWN_MS)
            }
        }
    }

    private fun drainPersistenceBatch(): List<PendingDiagnosticWrite> =
        synchronized(persistenceLock) {
            if (pendingPersistedEntries.isEmpty() && droppedPersistenceEntries == 0) {
                persistenceFlushScheduled = false
                return@synchronized emptyList()
            }
            buildList {
                val dropped = droppedPersistenceEntries
                if (dropped > 0) {
                    droppedPersistenceEntries = 0
                    val now = nowProvider()
                    add(
                        PendingDiagnosticWrite(
                            entry =
                                DiagnosticEntry(
                                    timestamp = now,
                                    tag = "diagnostics",
                                    message = "diagnostics journal dropped entries count=$dropped",
                                ),
                            retention = currentRetention(),
                            generation = persistenceGeneration,
                        ),
                    )
                }
                while (size < MAX_PERSISTENCE_BATCH_SIZE && pendingPersistedEntries.isNotEmpty()) {
                    add(pendingPersistedEntries.removeFirst())
                }
            }
        }

    private fun publishPersistenceFailure(error: Throwable) {
        val now = nowProvider()
        val shouldPublish =
            synchronized(persistenceLock) {
                if (now - lastPersistenceFailureAt < PERSISTENCE_FAILURE_THROTTLE_MS) {
                    false
                } else {
                    lastPersistenceFailureAt = now
                    true
                }
            }
        if (!shouldPublish) {
            return
        }
        val retention = currentRetention()
        val failureEntry =
            DiagnosticEntry(
                timestamp = now,
                tag = "diagnostics",
                message = "diagnostics journal write failed error=${error.javaClass.simpleName}",
            )
        publishLiveEntry(failureEntry, now, retention)
        Log.w(LOG_TAG, failureEntry.message)
    }

    private fun mergeEntries(
        first: List<DiagnosticEntry>,
        second: List<DiagnosticEntry>,
    ): List<DiagnosticEntry> {
        val seen = HashSet<String>(first.size + second.size)
        return (first + second).filter { entry ->
            seen.add("${entry.timestamp}\u0000${entry.tag}\u0000${entry.message}")
        }
    }

    private fun prune(entries: List<DiagnosticEntry>, now: Long): List<DiagnosticEntry> {
        return prune(entries, now, currentRetention())
    }

    private fun prune(
        entries: List<DiagnosticEntry>,
        now: Long,
        retention: DiagnosticsRetention,
    ): List<DiagnosticEntry> {
        val cutoff = now - retention.retentionHours * 60L * 60L * 1000L
        return entries.filter { it.timestamp >= cutoff }.takeLast(retention.maxEntries)
    }

    private fun currentRetention(): DiagnosticsRetention =
        runCatching { retentionProvider() }.getOrDefault(DiagnosticsRetention.HOURS_24)

    private fun allowRawLiveDiagnostics(): Boolean =
        BuildConfig.DEBUG &&
            runCatching { settingsSnapshotProvider().expert.rawLiveDiagnostics }
            .getOrDefault(false)

    private fun List<DiagnosticEntry>.sanitizeLiveEntriesIfNeeded(): List<DiagnosticEntry> =
        if (allowRawLiveDiagnostics()) {
            this
        } else {
            map { entry -> entry.copy(message = liveDiagnosticMessage(entry.message, false)) }
        }

    private fun diagnosticsExportMetadata(retention: DiagnosticsRetention): DiagnosticsExportMetadata {
        val settings = runCatching(settingsSnapshotProvider).getOrDefault(Settings())
        return DiagnosticsExportMetadata(
            generatedAt = nowProvider(),
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            coreVersion = BuildConfig.LIBBOX_SOURCE_VERSION,
            androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "unknown" },
            sdkInt = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.filter(String::isNotBlank),
            themeMode = settings.ui.themeMode.name.lowercase(Locale.ROOT),
            locale = settings.ui.locale.tag.ifBlank { "system" },
            trafficMode = settings.traffic.mode.name.lowercase(Locale.ROOT),
            tunStack = settings.traffic.tunStack.configValue,
            diagnosticsRetention = retention,
            networkActivityLoggingEnabled = settings.expert.networkActivityLogging,
        )
    }

    private fun pruneThrottledKeys(now: Long) {
        val cutoff = now - THROTTLE_TTL_MS
        val iterator = throttledKeys.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) {
                iterator.remove()
            }
        }
    }

    private fun scheduleCleanup() {
        val request =
            OneTimeWorkRequestBuilder<DiagnosticsCleanupWorker>()
                .setInitialDelay(EXPORT_TTL_MS, TimeUnit.MILLISECONDS)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DIAGNOSTIC_CLEANUP_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private data class PendingDiagnosticWrite(
        val entry: DiagnosticEntry,
        val retention: DiagnosticsRetention,
        val generation: Long,
    )

    companion object {
        private const val LOG_TAG = "FoxholeDiag"
        private const val THROTTLE_TTL_MS = 30 * 60 * 1000L
        private const val MAX_PENDING_PERSISTENCE_ENTRIES = 1_024
        private const val MAX_PERSISTENCE_BATCH_SIZE = 128
        private const val PERSISTENCE_BATCH_DELAY_MS = 250L
        private const val PERSISTENCE_BATCH_COOLDOWN_MS = 20L
        private const val PERSISTENCE_FAILURE_THROTTLE_MS = 30_000L
        private const val EXPORT_TTL_MS = 5 * 60 * 1000L
        private const val EXPORT_DIR_NAME = "diagnostics-export"
        private const val JOURNAL_DIR_NAME = "diagnostics-journal"
        private const val SMART_START_REPLAY_TAG = "smart-start-replay"
        private const val LEGACY_SMART_START_REPLAY_DIR_NAME = "smart-start-replay"
        private const val DIAGNOSTIC_CLEANUP_WORK_NAME = "diagnostics-export-cleanup"
    }
}
