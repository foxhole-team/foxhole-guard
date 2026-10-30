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
    private val disk = ThreatIntelDiskState(File(context.applicationContext.filesDir, "sentinel"))

    @Volatile
    private var cached: CachedIntel? = null

    override suspend fun installVerifiedThreatIntel(
        documentBytes: ByteArray,
        manifest: ThreatIntelManifest,
    ): String = disk.install(documentBytes, manifest.generatedAt).also { cached = null }

    override fun installedGeneratedAt(): String? = runCatching(disk::generation).getOrNull()

    fun loadRemoteIntel(): InstalledAppThreatIntel = cachedRemote()?.intel ?: InstalledAppThreatIntel.EMPTY

    fun loadRemoteDocument(): ThreatIntelDocument? = cachedRemote()?.document

    private fun cachedRemote(): CachedIntel? =
        runCatching {
            val key = disk.key() ?: return null
            cached?.takeIf { it.key == key }?.let { return it }
            val snapshot = disk.read() ?: return null
            val document = json.decodeFromString<ThreatIntelDocument>(snapshot.bytes.toString(Charsets.UTF_8))
            if (!ThreatIntelDocument.supportsSchema(document.schema)) return null
            CachedIntel(snapshot.key, document, document.toThreatIntel()).also { cached = it }
        }.getOrNull()

    private data class CachedIntel(
        val key: String,
        val document: ThreatIntelDocument,
        val intel: InstalledAppThreatIntel,
    )
}
