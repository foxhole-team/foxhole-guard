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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

data class DiagnosticEntry(
    val timestamp: Long,
    val tag: String,
    val message: String,
)

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
    private val throttledKeys = LinkedHashMap<String, Long>()

    val entries: StateFlow<List<DiagnosticEntry>> = entriesMutable

    init {
        val now = nowProvider()
        entriesMutable.value = sessionStore.loadRecentEntries(now, currentRetention())
        record("diagnostics", "Log session started")
    }

    fun record(tag: String, message: String) {
        val now = nowProvider()
        val retention = currentRetention()
        val current = prune(entriesMutable.value, now, retention)
        val normalizedMessage = DiagnosticSanitizer.normalizeForStorage(message)
        val entry = DiagnosticEntry(now, tag.lowercase(Locale.ROOT), normalizedMessage)
        val next =
            (current + entry)
                .takeLast(retention.maxEntries)
        entriesMutable.value = next
        runCatching { sessionStore.append(entry, retention) }
        if (BuildConfig.ENABLE_DIAGNOSTIC_LOGCAT) {
            Log.d(LOG_TAG, "[${tag.lowercase(Locale.ROOT)}] ${DiagnosticSanitizer.sanitize(normalizedMessage)}")
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
        pruneThrottledKeys(now)
        val previousAt = throttledKeys[throttleKey]
        if (previousAt != null && now - previousAt < windowMs) {
            return
        }
        throttledKeys[throttleKey] = now
        record(tag, message)
    }

    fun recordConnection(state: ConnectionState, reason: String? = null) {
        val suffix = reason?.takeIf { it.isNotBlank() }?.let { " reason=$it" }.orEmpty()
        record("connection", "state=${state.name.lowercase(Locale.ROOT)}$suffix")
    }

    fun recordSmartStartReplay(
        event: SmartStartReplayEvent,
        enabled: Boolean,
    ) {
        if (!enabled) {
            return
        }
        runCatching {
            val now = nowProvider()
            val retention = currentRetention()
            val entry =
                DiagnosticEntry(
                    timestamp = now,
                    tag = SMART_START_REPLAY_TAG,
                    message = event.toJsonLine(),
                )
            sessionStore.append(entry, retention)
            entriesMutable.value =
                (prune(entriesMutable.value, now, retention) + entry)
                    .takeLast(retention.maxEntries)
        }.onFailure {
            record("diagnostics", "smart start replay write failed")
        }
    }

    fun snapshotForExport(): String {
        val now = nowProvider()
        val retention = currentRetention()
        val snapshot = prune(sessionStore.loadRecentEntries(now, retention), now, retention)
        entriesMutable.value = snapshot
        return formatDiagnosticsExport(
            metadata = diagnosticsExportMetadata(retention),
            entries = snapshot,
            formatter = formatter,
        )
    }

    fun createExportFile(): File {
        cleanupExpiredExports()
        val targetDir = File(context.cacheDir, EXPORT_DIR_NAME).apply { mkdirs() }
        val targetFile = File(targetDir, "foxhole-diagnostics-${UUID.randomUUID()}.log.gz")
        GZIPOutputStream(targetFile.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(snapshotForExport())
        }
        scheduleCleanup()
        return targetFile
    }

    fun clear() {
        entriesMutable.value = emptyList()
        throttledKeys.clear()
        sessionStore.clear()
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

    companion object {
        private const val LOG_TAG = "FoxholeDiag"
        private const val THROTTLE_TTL_MS = 30 * 60 * 1000L
        private const val EXPORT_TTL_MS = 5 * 60 * 1000L
        private const val EXPORT_DIR_NAME = "diagnostics-export"
        private const val JOURNAL_DIR_NAME = "diagnostics-journal"
        private const val SMART_START_REPLAY_TAG = "smart-start-replay"
        private const val LEGACY_SMART_START_REPLAY_DIR_NAME = "smart-start-replay"
        private const val DIAGNOSTIC_CLEANUP_WORK_NAME = "diagnostics-export-cleanup"
    }
}
