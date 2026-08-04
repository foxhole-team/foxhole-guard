package com.foxhole.guard.core.sentinel

import android.content.Context
import com.foxhole.core.model.ThreatIntelDocument
import com.foxhole.core.sentinel.detection.InstalledAppThreatIntel
import com.foxhole.core.sentinel.detection.toThreatIntel
import com.foxhole.guard.runtime.FileThreatIntelStore
import kotlinx.serialization.json.Json

/**
 * Supplies FOXHOLE SENTINEL's installed-app threat intelligence to the risk scorer.
 *
 * The baseline is a curated seed shipped in `assets/sentinel/threat-intel.json` (works offline,
 * no infrastructure). A signed remote feed — fetched and verified like the DNS filter rule set —
 * is layered on top through [remoteIntel] so updates ship without an app release. The bundled seed
 * is parsed once and cached; [current] merges it with whatever the remote source currently holds.
 */
class SentinelThreatIntelProvider(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
    // The remote feed is read from the signed-and-verified on-disk store written by
    // ThreatIntelUpdateClient. It is EMPTY (file absent) until the feed is hosted and activated, so
    // scoring transparently falls back to the bundled seed alone.
    private val remoteIntel: () -> InstalledAppThreatIntel = FileThreatIntelStore(context)::loadRemoteIntel,
) {
    private val appContext = context.applicationContext

    @Volatile
    private var bundled: InstalledAppThreatIntel? = null

    @Volatile
    private var merged: Pair<InstalledAppThreatIntel, InstalledAppThreatIntel>? = null

    /**
     * The effective threat intel: the cached bundled seed merged with the latest remote feed.
     * The merge is memoized on the remote instance — the file store returns the same object while
     * the on-disk feed is unchanged — so an inventory scan asking once per package does not
     * re-normalize the whole feed every call.
     */
    fun current(): InstalledAppThreatIntel {
        val remote = remoteIntel()
        merged?.takeIf { it.first === remote }?.let { return it.second }
        return bundledSeed().mergedWith(remote).also { merged = remote to it }
    }

    private fun bundledSeed(): InstalledAppThreatIntel =
        bundled ?: synchronized(this) {
            bundled ?: loadBundledSeed().also { bundled = it }
        }

    private fun loadBundledSeed(): InstalledAppThreatIntel =
        runCatching {
            appContext.assets.open(BUNDLED_ASSET_PATH).use { stream ->
                val document = json.decodeFromString<ThreatIntelDocument>(stream.readBytes().toString(Charsets.UTF_8))
                if (document.schema == ThreatIntelDocument.SCHEMA) {
                    document.toThreatIntel()
                } else {
                    InstalledAppThreatIntel.EMPTY
                }
            }
        }.getOrElse { InstalledAppThreatIntel.EMPTY }

    private companion object {
        const val BUNDLED_ASSET_PATH = "sentinel/threat-intel.json"
    }
}
