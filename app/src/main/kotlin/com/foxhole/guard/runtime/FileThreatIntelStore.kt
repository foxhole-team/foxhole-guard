package com.foxhole.guard.runtime

import android.content.Context
import com.foxhole.core.model.ThreatIntelDocument
import com.foxhole.core.sentinel.detection.InstalledAppThreatIntel
import com.foxhole.core.sentinel.detection.toThreatIntel
import kotlinx.serialization.json.Json
import java.io.File

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
            tempFile.delete()
            error("could not install the verified threat intel feed atomically")
        }

        writeInstalledGeneratedAt(manifest.generatedAt)
        cached = null
        return intelFile.absolutePath
    }

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

    fun loadRemoteIntel(): InstalledAppThreatIntel = cachedRemote()?.intel ?: InstalledAppThreatIntel.EMPTY

    fun loadRemoteDocument(): ThreatIntelDocument? = cachedRemote()?.document

    private fun cachedRemote(): CachedIntel? =
        runCatching {
            if (!intelFile.isFile) {
                return null
            }
            val stamp = intelFile.lastModified() to intelFile.length()
            cached?.takeIf { it.stamp == stamp }?.let { return it }
            val document = json.decodeFromString<ThreatIntelDocument>(intelFile.readText())

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
