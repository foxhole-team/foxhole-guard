package com.foxhole.guard.core.sentinel

import android.content.Context
import com.foxhole.core.model.ThreatIntelDocument
import com.foxhole.core.sentinel.detection.InstalledAppThreatIntel
import com.foxhole.core.sentinel.detection.NetworkIocMatcher
import com.foxhole.core.sentinel.detection.toThreatIntel
import com.foxhole.guard.runtime.FileThreatIntelStore
import kotlinx.serialization.json.Json

class SentinelThreatIntelProvider(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
    store: FileThreatIntelStore = FileThreatIntelStore(context),

    private val remoteIntel: () -> InstalledAppThreatIntel = store::loadRemoteIntel,
    private val remoteDocument: () -> ThreatIntelDocument? = store::loadRemoteDocument,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var bundledDoc: ThreatIntelDocument? = null

    @Volatile
    private var bundled: InstalledAppThreatIntel? = null

    @Volatile
    private var merged: Pair<InstalledAppThreatIntel, InstalledAppThreatIntel>? = null

    @Volatile
    private var networkMatcher: Pair<ThreatIntelDocument?, NetworkIocMatcher>? = null

    fun current(): InstalledAppThreatIntel {
        val remote = remoteIntel()
        merged?.takeIf { it.first === remote }?.let { return it.second }
        return bundledSeed().mergedWith(remote).also { merged = remote to it }
    }

    fun networkIocMatcher(): NetworkIocMatcher {
        val remote = remoteDocument()
        networkMatcher?.takeIf { it.first === remote }?.let { return it.second }
        val seed = bundledDocument()
        val mergedDocument =
            ThreatIntelDocument(
                domains = seed.domains + remote?.domains.orEmpty(),
                ips = seed.ips + remote?.ips.orEmpty(),

                indicatorKinds = seed.indicatorKinds + remote?.indicatorKinds.orEmpty(),
            )
        return NetworkIocMatcher(mergedDocument).also { networkMatcher = remote to it }
    }

    private fun bundledSeed(): InstalledAppThreatIntel =
        bundled ?: synchronized(this) {
            bundled ?: bundledDocument().toThreatIntel().also { bundled = it }
        }

    private fun bundledDocument(): ThreatIntelDocument =
        bundledDoc ?: synchronized(this) {
            bundledDoc ?: loadBundledDocument().also { bundledDoc = it }
        }

    private fun loadBundledDocument(): ThreatIntelDocument =
        runCatching {
            appContext.assets.open(BUNDLED_ASSET_PATH).use { stream ->
                val document = json.decodeFromString<ThreatIntelDocument>(stream.readBytes().toString(Charsets.UTF_8))

                if (ThreatIntelDocument.supportsSchema(document.schema)) {
                    document
                } else {
                    EMPTY_DOCUMENT
                }
            }
        }.getOrElse { EMPTY_DOCUMENT }

    private companion object {
        const val BUNDLED_ASSET_PATH = "sentinel/threat-intel.json"
        val EMPTY_DOCUMENT = ThreatIntelDocument()
    }
}
