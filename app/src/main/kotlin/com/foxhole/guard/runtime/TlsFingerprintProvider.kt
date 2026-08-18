package com.foxhole.guard.runtime

import android.content.Context
import kotlinx.serialization.json.Json

class TlsFingerprintProvider(
    private val installedTables: () -> TlsFingerprintTables?,
    private val bundledBytes: () -> ByteArray?,
    private val installedBytes: () -> ByteArray? = { null },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @Volatile
    private var bundled: TlsFingerprintTables? = null

    fun tables(): TlsFingerprintTables? = downloadedTables() ?: bundledTables()

    fun isUsingDownloadedTables(): Boolean = downloadedTables() != null

    fun documentInEffect(): ByteArray? =
        installedBytes.takeIf { downloadedTables() != null }?.let { bytes ->
            runCatching(bytes).getOrNull()
        } ?: runCatching(bundledBytes).getOrNull()

    private fun downloadedTables(): TlsFingerprintTables? =
        runCatching { installedTables() }
            .getOrNull()
            ?.takeIf(TlsFingerprintTables::isUsable)

    fun bundledTables(): TlsFingerprintTables? =
        bundled ?: runCatching {
            val bytes = bundledBytes() ?: return null
            json
                .decodeFromString<TlsFingerprintTables>(bytes.toString(Charsets.UTF_8))
                .takeIf(TlsFingerprintTables::isUsable)
                ?.also { parsed -> bundled = parsed }
        }.getOrNull()
}

internal fun tlsFingerprintProvider(
    context: Context,
    store: FileTlsFingerprintStore,
    json: Json,
): TlsFingerprintProvider {
    val appContext = context.applicationContext
    return TlsFingerprintProvider(
        installedTables = store::loadInstalledTables,
        bundledBytes = {
            runCatching {
                appContext.assets.open(BUNDLED_FINGERPRINTS_ASSET_PATH).use { input -> input.readBytes() }
            }.getOrNull()
        },
        installedBytes = store::installedDocumentBytes,
        json = json,
    )
}

internal const val BUNDLED_FINGERPRINTS_ASSET_PATH = "fingerprints/fingerprints.json"
