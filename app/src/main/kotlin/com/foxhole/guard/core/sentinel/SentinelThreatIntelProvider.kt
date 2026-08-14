package com.foxhole.guard.core.sentinel

import android.content.Context
import com.foxhole.core.model.ThreatIntelDocument
import com.foxhole.core.sentinel.detection.InstalledAppThreatIntel
import com.foxhole.core.sentinel.detection.NetworkIocMatcher
import com.foxhole.core.sentinel.detection.toThreatIntel
import com.foxhole.guard.runtime.FileThreatIntelStore
import kotlinx.serialization.json.Json

/**
 * Supplies FoxHole Sentinel's installed-app threat intelligence to the risk scorer.
 *
 * The baseline is a curated seed shipped in `assets/sentinel/threat-intel.json` (works offline,
 * no infrastructure). A signed remote feed — fetched and verified like the DNS filter rule set —
 * is layered on top through [remoteIntel] so updates ship without an app release. The bundled seed
 * is parsed once and cached; [current] merges it with whatever the remote source currently holds.
 */
class SentinelThreatIntelProvider(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
    store: FileThreatIntelStore = FileThreatIntelStore(context),
    // The remote feed is read from the signed-and-verified on-disk store written by
    // ThreatIntelUpdateClient. It is EMPTY (file absent) until the feed is hosted and activated, so
    // scoring transparently falls back to the bundled seed alone.
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

    /**
     * Matcher over the bundled seed's network indicators merged with the remote feed's. Memoized
     * on the remote document instance the same way [current] memoizes on the remote intel: the
     * store returns the same object until the on-disk feed changes, so a per-event-batch call does
     * not rebuild the domain sets.
     */
    fun networkIocMatcher(): NetworkIocMatcher {
        val remote = remoteDocument()
        networkMatcher?.takeIf { it.first === remote }?.let { return it.second }
        val seed = bundledDocument()
        val mergedDocument =
            ThreatIntelDocument(
                domains = seed.domains + remote?.domains.orEmpty(),
                ips = seed.ips + remote?.ips.orEmpty(),
                // Remote last: the shipped seed is a snapshot, and a classification the live feed
                // has revised — a former C2 host that is now somebody's parked CDN — must win.
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
                // A range, not an equality: every field added since schema 1 defaults to its absent
                // value, and every absent value is the quiet reading, so an older seed keeps working
                // and simply claims less.
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
