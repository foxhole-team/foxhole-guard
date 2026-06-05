package com.foxhole.beta.core.data

import androidx.room.withTransaction
import com.foxhole.beta.core.model.RoutingCatalog
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingPresetSource
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.network.ensurePublicHttpsUrl
import com.foxhole.beta.core.network.requirePublicHttpsUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.Locale

class RoutingRepository(
    databaseProvider: () -> ProfileDatabase,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val trustedCatalogSha256ByUrl: Map<String, String> = emptyMap(),
) {
    private val database: ProfileDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED, databaseProvider)
    private val presetDao by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { database.routingPresetDao() }
    private val ruleDao by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { database.routingRuleDao() }
    private val catalogDao by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { database.routingCatalogDao() }
    private val routingCatalogHttpClient: OkHttpClient by lazy {
        httpClient.withBoundedRemoteFetchTimeouts()
    }

    val presets: Flow<List<RoutingPreset>> =
        flow {
            emitAll(
                presetDao.observePresets().map { list ->
                    list.map { it.preset.toDomain(it.rules.map(RoutingRuleEntity::toDomain)) }
                },
            )
        }.flowOn(Dispatchers.IO)

    val activePreset: Flow<RoutingPreset?> =
        flow {
            emitAll(
                presetDao.observeActivePreset().map { relation ->
                    relation?.preset?.toDomain(relation.rules.map(RoutingRuleEntity::toDomain))?.takeIf { it.enabled }
                },
            )
        }.flowOn(Dispatchers.IO)

    val catalogs: Flow<List<RoutingCatalog>> =
        flow {
            emitAll(
                catalogDao.observeCatalogs().map { list ->
                    list.map { entity ->
                        entity.toDomain(parsedManifest(entity.cachedManifestJson)?.presets?.size ?: 0)
                    }
                },
            )
        }.flowOn(Dispatchers.IO)

    suspend fun getPreset(id: Long): RoutingPreset? =
        presetDao.getById(id)?.let { it.preset.toDomain(it.rules.map(RoutingRuleEntity::toDomain)) }

    suspend fun getCatalog(id: Long): RoutingCatalog? =
        catalogDao.getById(id)?.let { it.toDomain(parsedManifest(it.cachedManifestJson)?.presets?.size ?: 0) }

    suspend fun currentPresetForRuntime(): RoutingPreset? =
        presetDao.getActivePreset()?.let { relation ->
            relation.preset.toDomain(relation.rules.map(RoutingRuleEntity::toDomain)).takeIf { it.enabled }
        }

    suspend fun createPreset(
        name: String,
        source: RoutingPresetSource = RoutingPresetSource.LOCAL,
        catalogId: Long? = null,
        overrideMode: RoutingPresetOverrideMode = RoutingPresetOverrideMode.RESPECT_PROFILE,
        enabled: Boolean = true,
        activate: Boolean = false,
    ): Long {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val count = presetDao.count()
            if (activate || count == 0) {
                presetDao.clearActive()
            }
            presetDao.insert(
                RoutingPresetEntity(
                    name = name.ifBlank { "preset" },
                    source = source.name,
                    catalogId = catalogId,
                    overrideMode = overrideMode.name,
                    enabled = enabled,
                    updatedAt = now,
                    isActive = activate || count == 0,
                ),
            )
        }
    }

    suspend fun updatePreset(
        presetId: Long,
        name: String,
        overrideMode: RoutingPresetOverrideMode,
        enabled: Boolean,
    ) {
        val current = presetDao.getById(presetId)?.preset ?: error("preset not found")
        presetDao.update(
            id = current.id,
            name = name.ifBlank { current.name },
            source = current.source,
            catalogId = current.catalogId,
            overrideMode = overrideMode.name,
            enabled = enabled,
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun setActivePreset(presetId: Long?) {
        database.withTransaction {
            presetDao.clearActive()
            if (presetId != null) {
                presetDao.setActive(presetId)
            }
        }
    }

    suspend fun deletePreset(presetId: Long) {
        val wasActive = presetDao.getById(presetId)?.preset?.isActive == true
        database.withTransaction {
            presetDao.delete(presetId)
            if (wasActive) {
                val replacementId = presetDao.getReplacementPresetId(presetId)
                if (replacementId != null) {
                    presetDao.setActive(replacementId)
                }
            }
        }
    }

    suspend fun upsertRule(
        presetId: Long,
        ruleId: Long?,
        name: String,
        enabled: Boolean,
        order: Int?,
        action: RoutingRuleAction,
        matchDomains: List<String>,
        matchIpCidrs: List<String>,
        matchPorts: List<String>,
        matchProtocols: List<String>,
        matchNetworks: List<String>,
    ): Long {
        require(matchDomains.isNotEmpty() || matchIpCidrs.isNotEmpty() || matchPorts.isNotEmpty() || matchProtocols.isNotEmpty() || matchNetworks.isNotEmpty()) {
            "rule must define at least one matcher"
        }
        val normalizedOrder = order ?: ruleDao.nextOrder(presetId)
        val ruleEntity =
            RoutingRuleEntity(
                id = ruleId ?: 0,
                presetId = presetId,
                name = name.ifBlank { "rule" },
                enabled = enabled,
                order = normalizedOrder,
                action = action.name,
                matchDomains = normalizeTokens(matchDomains),
                matchIpCidrs = normalizeTokens(matchIpCidrs),
                matchPorts = normalizeTokens(matchPorts),
                matchProtocols = normalizeTokens(matchProtocols),
                matchNetworks = normalizeTokens(matchNetworks),
            )
        return if (ruleId == null) {
            ruleDao.insert(ruleEntity)
        } else {
            ruleDao.update(
                id = ruleId,
                name = ruleEntity.name,
                enabled = ruleEntity.enabled,
                order = ruleEntity.order,
                action = ruleEntity.action,
                matchDomains = ruleEntity.matchDomains,
                matchIpCidrs = ruleEntity.matchIpCidrs,
                matchPorts = ruleEntity.matchPorts,
                matchProtocols = ruleEntity.matchProtocols,
                matchNetworks = ruleEntity.matchNetworks,
            )
            ruleId
        }
    }

    suspend fun deleteRule(ruleId: Long) {
        ruleDao.delete(ruleId)
    }

    suspend fun updateRuleActionAndOrder(
        ruleId: Long,
        name: String,
        action: RoutingRuleAction,
        ruleIdsInOrder: List<Long>,
    ) {
        database.withTransaction {
            val rule = ruleDao.getById(ruleId) ?: return@withTransaction
            ruleDao.update(
                id = ruleId,
                name = name,
                enabled = rule.enabled,
                order = rule.order,
                action = action.name,
                matchDomains = rule.matchDomains,
                matchIpCidrs = rule.matchIpCidrs,
                matchPorts = rule.matchPorts,
                matchProtocols = rule.matchProtocols,
                matchNetworks = rule.matchNetworks,
            )
            ruleIdsInOrder.distinct().forEachIndexed { index, orderedRuleId ->
                ruleDao.updateOrder(orderedRuleId, index)
            }
        }
    }

    suspend fun addCatalog(
        name: String,
        url: String,
        warningAcceptedAt: Long = System.currentTimeMillis(),
    ): Long {
        val normalizedUrl = url.trim().ensurePublicHttpsUrl()
        return catalogDao.insert(
            RoutingCatalogEntity(
                name = name.ifBlank { normalizedUrl.host ?: "catalog" },
                url = normalizedUrl.toString(),
                enabled = true,
                etag = null,
                lastSyncAt = null,
                warningAcceptedAt = warningAcceptedAt,
                cachedManifestJson = null,
            ),
        )
    }

    suspend fun deleteCatalog(catalogId: Long) {
        catalogDao.delete(catalogId)
    }

    suspend fun refreshCatalog(catalogId: Long): RoutingCatalog {
        val catalog = catalogDao.getById(catalogId) ?: error("catalog not found")
        val safeUrl = catalog.url.ensureHttpsUrl().requirePublicHttpsUrl(resolveHost = true)
        requireSafeRoutingCatalogUrlPath(safeUrl)
        val response =
            executeBoundedPublicGet(
                client = routingCatalogHttpClient,
                initialUrl = safeUrl,
                allowHttp = false,
                maxBytes = MAX_ROUTING_CATALOG_BYTES,
            ) { url ->
                Request.Builder()
                    .url(url)
                    .get()
                    .apply {
                        if (!catalog.etag.isNullOrBlank()) {
                            header("If-None-Match", catalog.etag)
                        }
                    }.build()
            }
        require(response.isSuccessful || response.code == 304) { "catalog refresh failed with http ${response.code}" }
        requireSafeRoutingCatalogUrlPath(response.finalUrl)
        val now = System.currentTimeMillis()
        val cachedManifest =
            if (response.code == 304) {
                catalog.cachedManifestJson
            } else {
                val body = response.body.orEmpty()
                requireRoutingCatalogJsonContent(response.headers)
                requireTrustedRoutingCatalogPayload(
                    catalogUrl = catalog.url,
                    finalUrl = response.finalUrl.toString(),
                    warningAcceptedAt = catalog.warningAcceptedAt,
                    body = body,
                    trustedCatalogSha256ByUrl = trustedCatalogSha256ByUrl,
                )
                val parsed = parseCatalogManifest(body)
                json.encodeToString(RoutingCatalogManifest.serializer(), parsed)
            }
        catalogDao.update(
            id = catalog.id,
            name = catalog.name,
            url = catalog.url,
            enabled = catalog.enabled,
            etag = response.headers["ETag"] ?: catalog.etag,
            lastSyncAt = now,
            warningAcceptedAt = catalog.warningAcceptedAt,
            cachedManifestJson = cachedManifest,
        )
        return getCatalog(catalogId) ?: error("catalog disappeared")
    }

    suspend fun importPresetFromCatalog(
        catalogId: Long,
        remotePresetId: String,
        activate: Boolean = false,
    ): Long {
        val catalog = catalogDao.getById(catalogId) ?: error("catalog not found")
        val manifest = parsedManifest(catalog.cachedManifestJson) ?: error("catalog has no cached presets")
        val payload = manifest.presets.firstOrNull { it.id == remotePresetId } ?: error("preset not found")
        return importPresetPayload(
            payload = payload,
            source = RoutingPresetSource.REMOTE,
            catalogId = catalog.id,
            activate = activate,
        )
    }

    suspend fun importPresetDocument(
        raw: String,
        source: RoutingPresetSource = RoutingPresetSource.FILE,
        activateFirst: Boolean = false,
    ): List<Long> {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "preset payload is empty" }
        return runCatching {
            val manifest = parseCatalogManifest(trimmed)
            manifest.presets.mapIndexed { index, payload ->
                importPresetPayload(
                    payload = payload,
                    source = source,
                    catalogId = null,
                    activate = activateFirst && index == 0,
                )
            }
        }.recoverCatching {
            listOf(
                importPresetPayload(
                    payload = parseSinglePreset(trimmed),
                    source = source,
                    catalogId = null,
                    activate = activateFirst,
                ),
            )
        }.getOrThrow()
    }

    suspend fun exportPresetDocument(presetId: Long): String {
        val preset = getPreset(presetId) ?: error("preset not found")
        return json.encodeToString(
            RoutingPresetExchange.serializer(),
            RoutingPresetExchange(
                name = preset.name,
                overrideMode = preset.overrideMode,
                rules = preset.rules.map { it.toExchange() },
            ),
        )
    }

    suspend fun previewCatalogPresets(catalogId: Long): List<RoutingCatalogPresetPreview> {
        val catalog = catalogDao.getById(catalogId) ?: error("catalog not found")
        val manifest = parsedManifest(catalog.cachedManifestJson) ?: return emptyList()
        return manifest.presets.map { payload ->
            RoutingCatalogPresetPreview(
                id = payload.id,
                name = payload.name,
                overrideMode = payload.overrideMode,
                ruleCount = payload.rules.size,
            )
        }
    }

    private suspend fun importPresetPayload(
        payload: RoutingPresetExchange,
        source: RoutingPresetSource,
        catalogId: Long?,
        activate: Boolean,
    ): Long {
        val presetId =
            createPreset(
                name = payload.name,
                source = source,
                catalogId = catalogId,
                overrideMode = payload.overrideMode,
                enabled = true,
                activate = activate,
            )
        payload.rules.forEachIndexed { index, rule ->
            upsertRule(
                presetId = presetId,
                ruleId = null,
                name = rule.name,
                enabled = rule.enabled,
                order = index,
                action = rule.action,
                matchDomains = rule.matchDomains,
                matchIpCidrs = rule.matchIpCidrs,
                matchPorts = rule.matchPorts,
                matchProtocols = rule.matchProtocols,
                matchNetworks = rule.matchNetworks,
            )
        }
        return presetId
    }

    private fun parseSinglePreset(raw: String): RoutingPresetExchange =
        json.decodeFromString(RoutingPresetExchange.serializer(), raw)

    private fun parseCatalogManifest(raw: String): RoutingCatalogManifest {
        val manifest = json.decodeFromString(RoutingCatalogManifest.serializer(), raw)
        require(manifest.presets.isNotEmpty()) { "catalog must contain presets" }
        return manifest.copy(
            presets =
                manifest.presets.map { payload ->
                    payload.copy(
                        id = payload.id.ifBlank { payload.name },
                        name = payload.name.ifBlank { "preset" },
                        rules = payload.rules.map(RoutingRuleExchange::normalized),
                    )
                },
        )
    }

    private fun parsedManifest(raw: String?): RoutingCatalogManifest? =
        raw?.takeIf(String::isNotBlank)?.let {
            runCatching { json.decodeFromString(RoutingCatalogManifest.serializer(), it) }.getOrNull()
        }

    private fun String.ensureHttpsUrl() = trim().ensurePublicHttpsUrl()

    @Serializable
    data class RoutingCatalogManifest(
        val name: String = "",
        val version: String = "1",
        val presets: List<RoutingPresetExchange> = emptyList(),
    )

    @Serializable
    data class RoutingPresetExchange(
        val id: String = "",
        val name: String,
        val overrideMode: RoutingPresetOverrideMode = RoutingPresetOverrideMode.RESPECT_PROFILE,
        val rules: List<RoutingRuleExchange> = emptyList(),
    )

    @Serializable
    data class RoutingRuleExchange(
        val name: String,
        val enabled: Boolean = true,
        val action: RoutingRuleAction,
        val matchDomains: List<String> = emptyList(),
        val matchIpCidrs: List<String> = emptyList(),
        val matchPorts: List<String> = emptyList(),
        val matchProtocols: List<String> = emptyList(),
        val matchNetworks: List<String> = emptyList(),
    ) {
        fun normalized(): RoutingRuleExchange =
            copy(
                name = name.ifBlank { "rule" },
                matchDomains = normalizeTokens(matchDomains),
                matchIpCidrs = normalizeTokens(matchIpCidrs),
                matchPorts = normalizeTokens(matchPorts),
                matchProtocols = normalizeTokens(matchProtocols),
                matchNetworks = normalizeTokens(matchNetworks),
            )
    }

    data class RoutingCatalogPresetPreview(
        val id: String,
        val name: String,
        val overrideMode: RoutingPresetOverrideMode,
        val ruleCount: Int,
    )

    private fun RoutingRule.toExchange(): RoutingRuleExchange =
        RoutingRuleExchange(
            name = name,
            enabled = enabled,
            action = action,
            matchDomains = matchDomains,
            matchIpCidrs = matchIpCidrs,
            matchPorts = matchPorts,
            matchProtocols = matchProtocols,
            matchNetworks = matchNetworks,
        )
}

private fun normalizeTokens(value: List<String>): List<String> =
    value
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

internal fun requireTrustedRoutingCatalogPayload(
    catalogUrl: String,
    finalUrl: String,
    warningAcceptedAt: Long?,
    body: String,
    trustedCatalogSha256ByUrl: Map<String, String>,
) {
    val expectedSha256 =
        trustedCatalogSha256ByUrl[catalogUrl]
            ?: trustedCatalogSha256ByUrl[finalUrl]
    if (expectedSha256 != null) {
        require(expectedSha256.isRoutingCatalogSha256Hex()) { "invalid routing catalog checksum" }
        val actualSha256 = body.toByteArray(Charsets.UTF_8).routingCatalogSha256Hex()
        require(actualSha256 == expectedSha256.lowercase(Locale.US)) {
            "routing catalog sha256 mismatch"
        }
        return
    }
    require(warningAcceptedAt != null) {
        "routing catalog requires trusted checksum or user warning"
    }
}

internal fun requireRoutingCatalogJsonContent(headers: Headers) {
    val contentType = headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase(Locale.US) ?: return
    require(contentType in RoutingCatalogJsonContentTypes) {
        "routing catalog response must be JSON"
    }
}

internal fun requireSafeRoutingCatalogUrlPath(url: HttpUrl) {
    val fileName = url.encodedPath.substringAfterLast('/').substringBefore('?').lowercase(Locale.US)
    require(fileName.noneExecutableRoutingCatalogSuffix()) {
        "routing catalog URL points to executable content"
    }
}

internal fun ByteArray.routingCatalogSha256Hex(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun String.isRoutingCatalogSha256Hex(): Boolean =
    length == ROUTING_CATALOG_SHA256_HEX_LENGTH &&
        all { character -> character in '0'..'9' || character.lowercaseChar() in 'a'..'f' }

private fun String.noneExecutableRoutingCatalogSuffix(): Boolean =
    RoutingCatalogExecutableSuffixes.none(::endsWith)

private val RoutingCatalogJsonContentTypes =
    setOf(
        "application/json",
        "application/vnd.foxhole.routing-catalog+json",
        "text/json",
        "text/plain",
    )

private val RoutingCatalogExecutableSuffixes =
    setOf(
        ".apk",
        ".apks",
        ".dex",
        ".exe",
        ".jar",
        ".sh",
        ".so",
        ".zip",
    )

private const val ROUTING_CATALOG_SHA256_HEX_LENGTH = 64
