package com.foxhole.guard.runtime

import android.content.Context
import com.foxhole.core.model.ThreatIntelDocument
import com.foxhole.core.sentinel.detection.InstalledAppThreatIntel
import com.foxhole.core.sentinel.detection.toThreatIntel
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-backed [ThreatIntelStore] for the signed SENTINEL feed. Verified document bytes are written
 * atomically to `filesDir/sentinel/threat-intel-remote.json`; [loadRemoteIntel] reads them back as
 * the `remoteIntel` source layered on top of the bundled seed in [SentinelThreatIntelProvider].
 *
 * Both directions are defensive: a write goes through a temp file + rename, and a read that finds a
 * missing/corrupt/wrong-schema file falls back to [InstalledAppThreatIntel.EMPTY] so a bad feed can
 * never break installed-app scoring.
 */
class FileThreatIntelStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ThreatIntelStore {
    private val appContext = context.applicationContext
    private val intelDir = File(appContext.filesDir, "sentinel").apply { mkdirs() }
    private val intelFile = File(intelDir, REMOTE_INTEL_FILE)

    @Volatile
    private var cached: CachedIntel? = null

    override suspend fun installVerifiedThreatIntel(
        documentBytes: ByteArray,
        manifest: ThreatIntelManifest,
    ): String {
        intelDir.mkdirs()
        val tempFile = File(intelDir, "$REMOTE_INTEL_FILE.tmp")
        tempFile.writeBytes(documentBytes)
        if (!tempFile.renameTo(intelFile)) {
            intelFile.writeBytes(documentBytes)
            tempFile.delete()
        }
        cached = null
        return intelFile.absolutePath
    }

    /**
     * The remote feed converted to the scorer's matching structure; EMPTY when absent/invalid.
     * An installed-app inventory scan asks once per package, so the parsed feed is cached and
     * keyed on the file's (lastModified, length) — a feed update invalidates it automatically.
     */
    fun loadRemoteIntel(): InstalledAppThreatIntel =
        runCatching {
            if (!intelFile.isFile) {
                return InstalledAppThreatIntel.EMPTY
            }
            val stamp = intelFile.lastModified() to intelFile.length()
            cached?.takeIf { it.stamp == stamp }?.let { return it.intel }
            val document = json.decodeFromString<ThreatIntelDocument>(intelFile.readText())
            val intel =
                if (document.schema == ThreatIntelDocument.SCHEMA) {
                    document.toThreatIntel()
                } else {
                    InstalledAppThreatIntel.EMPTY
                }
            cached = CachedIntel(stamp, intel)
            intel
        }.getOrElse { InstalledAppThreatIntel.EMPTY }

    private data class CachedIntel(
        val stamp: Pair<Long, Long>,
        val intel: InstalledAppThreatIntel,
    )

    private companion object {
        const val REMOTE_INTEL_FILE = "threat-intel-remote.json"
    }
}
