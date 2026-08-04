package com.foxhole.core.runtime

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

/**
 * On-disk home of the updatable IP→country database. The bundled application database is the
 * fallback; a successfully installed download (override files + metadata) takes precedence in
 * [TorGeoIpCountryResolver]. Installation is atomic (temp files + rename) and always ends by
 * invalidating the shared range cache so every live resolver re-reads the new data.
 */
@Serializable
data class GeoIpDatabaseMetadata(
    val version: String,
    @SerialName("source_repo") val sourceRepo: String,
    val license: String,
    @SerialName("downloaded_at_ms") val downloadedAtMs: Long,
    @SerialName("ipv4_ranges") val ipv4Ranges: Int,
    @SerialName("ipv6_ranges") val ipv6Ranges: Int,
)

data class GeoIpDatabaseInfo(
    // Null when only the bundled database is present.
    val installed: GeoIpDatabaseMetadata?,
    // Human-readable label of the bundled fallback (parsed from the asset header), e.g.
    // "IPFire Location Database · 23 Mar 2026".
    val bundledLabel: String,
    // When the update source was last successfully probed (manual or scheduled), regardless of
    // whether a download followed. Null when the source has never been reached.
    val lastCheckedAtMs: Long? = null,
)

class GeoIpDatabaseStore(
    context: Context,
    private val json: Json,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext

    fun currentInfo(): GeoIpDatabaseInfo =
        GeoIpDatabaseInfo(
            installed = readMetadata(),
            bundledLabel = bundledDatabaseLabel(),
            lastCheckedAtMs = readLastCheckedAtMs(),
        )

    // The check timestamp lives outside the override directory so clearing a downloaded database
    // does not erase the "last checked" history shown in settings.
    fun markChecked(timestampMs: Long = nowProvider()) {
        runCatching {
            checkStateFile().writeText(timestampMs.toString())
        }
    }

    fun readLastCheckedAtMs(): Long? =
        runCatching {
            checkStateFile()
                .takeIf(File::isFile)
                ?.readText()
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        }.getOrNull()

    private fun checkStateFile(): File = File(appContext.filesDir, CHECK_STATE_FILE_NAME)

    fun readMetadata(): GeoIpDatabaseMetadata? =
        runCatching {
            metadataFile()
                .takeIf(File::isFile)
                ?.readText()
                ?.let { content -> json.decodeFromString<GeoIpDatabaseMetadata>(content) }
        }.getOrNull()
            ?.takeIf { metadata ->
                TorGeoIpCountryResolver.overrideIpv4File(appContext).isFile &&
                    TorGeoIpCountryResolver.overrideIpv6File(appContext).isFile
            }

    /**
     * Validates and installs downloaded range files. Each stream must be the raw range CSV
     * ("start,end,CC" per line; IPv4 as integers or dotted quads, IPv6 as literals). Throws when
     * either file parses to fewer than [MIN_RANGES_PER_FAMILY] usable ranges — a truncated or
     * bogus download must never replace a working database.
     */
    fun install(
        version: String,
        sourceRepo: String,
        license: String,
        ipv4File: File,
        ipv6File: File,
    ): GeoIpDatabaseMetadata {
        val ipv4Count = ipv4File.countValidRanges { line -> TorGeoIpCountryResolver.parseIpv4Range(line) != null }
        require(ipv4Count >= MIN_RANGES_PER_FAMILY) { "ipv4 database too small: $ipv4Count ranges" }
        val ipv6Count = ipv6File.countValidRanges { line -> TorGeoIpCountryResolver.parseIpv6Range(line) != null }
        require(ipv6Count >= MIN_RANGES_PER_FAMILY) { "ipv6 database too small: $ipv6Count ranges" }
        val directory = TorGeoIpCountryResolver.overrideDirectory(appContext).apply { mkdirs() }
        val metadata =
            GeoIpDatabaseMetadata(
                version = version,
                sourceRepo = sourceRepo,
                license = license,
                downloadedAtMs = nowProvider(),
                ipv4Ranges = ipv4Count,
                ipv6Ranges = ipv6Count,
            )
        moveInto(ipv4File, TorGeoIpCountryResolver.overrideIpv4File(appContext))
        moveInto(ipv6File, TorGeoIpCountryResolver.overrideIpv6File(appContext))
        File(directory, METADATA_FILE_NAME).writeText(json.encodeToString(GeoIpDatabaseMetadata.serializer(), metadata))
        TorGeoIpCountryResolver.invalidateShared()
        return metadata
    }

    fun clearOverride(): Boolean {
        val directory = TorGeoIpCountryResolver.overrideDirectory(appContext)
        val removed = directory.deleteRecursively()
        TorGeoIpCountryResolver.invalidateShared()
        return removed
    }

    private fun metadataFile(): File =
        File(TorGeoIpCountryResolver.overrideDirectory(appContext), METADATA_FILE_NAME)

    private fun bundledDatabaseLabel(): String =
        runCatching {
            BUNDLED_ASSET_CANDIDATES.firstNotNullOfOrNull { assetPath ->
                runCatching { appContext.assets.open(assetPath).use(InputStream::readHeaderLabel) }.getOrNull()
            }
        }.getOrNull() ?: BUNDLED_FALLBACK_LABEL

    private fun moveInto(
        source: File,
        target: File,
    ) {
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private fun File.countValidRanges(isValid: (String) -> Boolean): Int {
        var count = 0
        bufferedReader().use { reader ->
            reader.forEachLine { line ->
                if (isValid(line)) {
                    count += 1
                }
            }
        }
        return count
    }

    companion object {
        const val METADATA_FILE_NAME = "geoip-meta.json"
        const val CHECK_STATE_FILE_NAME = "geoip-last-check"
        const val MIN_RANGES_PER_FAMILY = 50_000
        private const val BUNDLED_FALLBACK_LABEL = "IPFire Location Database (bundled)"

        private val BUNDLED_ASSET_CANDIDATES =
            listOf(
                "geoip/geoip",
            )
    }
}

// The bundled geoip asset starts with comment lines including
// "# Generated: Mon, 23 Mar 2026 04:33:02 GMT" and "# Vendor:    IPFire Project".
@Suppress("LoopWithTooManyJumpStatements")
private fun InputStream.readHeaderLabel(): String? {
    var generated: String? = null
    var vendor: String? = null
    bufferedReader().useLines { lines ->
        for (line in lines.take(MAX_HEADER_LINES)) {
            if (!line.startsWith("#")) {
                break
            }
            val content = line.trimStart('#').trim()
            when {
                content.startsWith("Generated:") -> generated = content.removePrefix("Generated:").trim()
                content.startsWith("Vendor:") -> vendor = content.removePrefix("Vendor:").trim()
            }
            if (generated != null && vendor != null) {
                break
            }
        }
    }
    val vendorLabel = vendor ?: return null
    return generated?.let { date -> "$vendorLabel · $date" } ?: vendorLabel
}

private const val MAX_HEADER_LINES = 40
