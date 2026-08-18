package com.foxhole.guard.runtime

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

class FileTlsFingerprintStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TlsFingerprintStore {
    private val appContext = context.applicationContext
    private val tablesDir = File(appContext.filesDir, "fingerprints").apply { mkdirs() }
    private val tablesFile = File(tablesDir, REMOTE_TABLES_FILE)
    private val stampFile = File(tablesDir, REMOTE_TABLES_STAMP_FILE)

    @Volatile
    private var cached: CachedTables? = null

    override suspend fun installVerifiedTables(
        documentBytes: ByteArray,
        manifest: TlsFingerprintManifest,
    ): String {
        tablesDir.mkdirs()
        val tempFile = File(tablesDir, "$REMOTE_TABLES_FILE.tmp")
        tempFile.writeBytes(documentBytes)
        if (!tempFile.renameTo(tablesFile)) {
            tempFile.delete()
            error("could not install the verified tls fingerprint tables atomically")
        }
        writeInstalledGeneratedAt(manifest.generatedAt)
        cached = null
        return tablesFile.absolutePath
    }

    override fun installedGeneratedAt(): String? =
        stampFile
            .takeIf(File::isFile)
            ?.let { file -> runCatching(file::readText).getOrNull() }
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun writeInstalledGeneratedAt(generatedAt: String) {
        runCatching {
            val tempStamp = File(tablesDir, "$REMOTE_TABLES_STAMP_FILE.tmp")
            tempStamp.writeText(generatedAt)
            if (!tempStamp.renameTo(stampFile)) {
                tempStamp.delete()
            }
        }
    }

    fun loadInstalledTables(): TlsFingerprintTables? = cachedTables()

    override fun installedDocumentBytes(): ByteArray? =
        cachedTables()?.let { runCatching(tablesFile::readBytes).getOrNull() }

    private fun cachedTables(): TlsFingerprintTables? =
        runCatching {
            if (!tablesFile.isFile) {
                return null
            }
            val stamp = tablesFile.lastModified() to tablesFile.length()
            cached?.takeIf { entry -> entry.stamp == stamp }?.let { entry -> return entry.tables }
            val tables = json.decodeFromString<TlsFingerprintTables>(tablesFile.readText())
            if (!tables.isUsable()) {
                return null
            }
            CachedTables(stamp, tables).also { entry -> cached = entry }.tables
        }.getOrNull()

    private data class CachedTables(
        val stamp: Pair<Long, Long>,
        val tables: TlsFingerprintTables,
    )

    private companion object {
        const val REMOTE_TABLES_FILE = "fingerprints-remote.json"
        const val REMOTE_TABLES_STAMP_FILE = "fingerprints-remote.generated-at"
    }
}

internal fun TlsFingerprintTables.isUsable(): Boolean =
    schema == TLS_FINGERPRINT_TABLES_SCHEMA &&
        profiles.isNotEmpty() &&
        profiles.map(TlsFingerprintProfile::name).toSet().size == profiles.size &&
        profiles.all { profile ->
            profile.name.isNotBlank() &&
                profile.fingerprintSha256.isSha256Hex() &&
                profile.fingerprint.canonicalDigest() == profile.fingerprintSha256
        }

internal const val TLS_FINGERPRINT_TABLES_SCHEMA = 1
