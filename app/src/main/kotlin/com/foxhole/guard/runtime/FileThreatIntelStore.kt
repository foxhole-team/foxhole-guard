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
    private val stampFile = File(intelDir, REMOTE_INTEL_STAMP_FILE)

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
            // No in-place fallback. Writing the bytes straight over the live file used to be the
            // fallback here, and it turns a failed swap into the one outcome the swap exists to
            // prevent: a crash mid-write leaves a truncated feed where a verified one used to be,
            // and the scorer then runs on half a list without anything saying so. Keeping the
            // previous feed and failing the update is the safe half of that trade.
            tempFile.delete()
            error("could not install the verified threat intel feed atomically")
        }
        // The stamp is written AFTER the swap: if the process dies between them the worst case is
        // a feed that looks older than it is, so the next update re-installs it — the opposite
        // order would record a version that is not on disk and refuse the real one as a rollback.
        writeInstalledGeneratedAt(manifest.generatedAt)
        cached = null
        return intelFile.absolutePath
    }

    /**
     * `generated_at` of the feed currently on disk, or null when nothing is installed.
     *
     * This is the anti-rollback anchor: the signature proves a manifest was issued by us, not that
     * it is the *newest* one we issued, so a correctly signed older feed is a replay that silently
     * drops whatever indicators were added since. Kept beside the feed rather than in settings so
     * that deleting the feed also drops the floor, and the pair can never disagree.
     */
    override fun installedGeneratedAt(): String? =
        stampFile
            .takeIf(File::isFile)
            ?.let { file -> runCatching(file::readText).getOrNull() }
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun writeInstalledGeneratedAt(generatedAt: String) {
        runCatching {
            val tempStamp = File(intelDir, "$REMOTE_INTEL_STAMP_FILE.tmp")
            tempStamp.writeText(generatedAt)
            if (!tempStamp.renameTo(stampFile)) {
                tempStamp.delete()
            }
        }
    }

    /**
     * The remote feed converted to the scorer's matching structure; EMPTY when absent/invalid.
     * An installed-app inventory scan asks once per package, so the parsed feed is cached and
     * keyed on the file's (lastModified, length) — a feed update invalidates it automatically.
     */
    fun loadRemoteIntel(): InstalledAppThreatIntel = cachedRemote()?.intel ?: InstalledAppThreatIntel.EMPTY

    /**
     * The raw remote document for the network IOC matcher; null when absent/invalid. The same
     * cache backs both accessors, so the memoized instance is stable until the feed file changes.
     */
    fun loadRemoteDocument(): ThreatIntelDocument? = cachedRemote()?.document

    private fun cachedRemote(): CachedIntel? =
        runCatching {
            if (!intelFile.isFile) {
                return null
            }
            val stamp = intelFile.lastModified() to intelFile.length()
            cached?.takeIf { it.stamp == stamp }?.let { return it }
            val document = json.decodeFromString<ThreatIntelDocument>(intelFile.readText())
            // A range, not an equality: a feed written for an older schema still parses, and the
            // fields it lacks default to the reading that claims least.
            if (!ThreatIntelDocument.supportsSchema(document.schema)) {
                return null
            }
            CachedIntel(stamp, document, document.toThreatIntel()).also { cached = it }
        }.getOrNull()

    private data class CachedIntel(
        val stamp: Pair<Long, Long>,
        val document: ThreatIntelDocument,
        val intel: InstalledAppThreatIntel,
    )

    private companion object {
        const val REMOTE_INTEL_FILE = "threat-intel-remote.json"
        const val REMOTE_INTEL_STAMP_FILE = "threat-intel-remote.generated-at"
    }
}
