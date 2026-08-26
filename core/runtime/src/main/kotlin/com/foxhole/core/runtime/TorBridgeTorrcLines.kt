@file:Suppress("MatchingDeclarationName")

package com.foxhole.core.runtime

import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.TorBridgeTransport
import com.foxhole.core.model.matchesBridgeTransportToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class TorBridgePolicy(
    val enabled: Boolean = true,
    val transport: TorBridgeTransport = TorBridgeTransport.AUTO,
) {
    fun fingerprint(payloadFingerprint: String?): String =
        "$enabled:${transport.name}:${payloadFingerprint ?: "bundled"}"

    companion object {
        val DEFAULT = TorBridgePolicy()
    }
}

fun PrivacyRouteSettings.torBridgePolicy(): TorBridgePolicy =
    TorBridgePolicy(enabled = bridgesEnabled, transport = bridgeTransport)

private val bridgeSourceJson = Json { ignoreUnknownKeys = true }

private data class BridgeSource(
    val recommendedDefault: String?,
    val groups: Map<String, List<String>>,
)

fun buildBridgeTorrcLines(
    policy: TorBridgePolicy,
    transportLines: List<String>,
    downloadedGroupsJson: String?,
    bundledPtConfigJson: String?,
): List<String> {
    if (!policy.enabled) {
        return emptyList()
    }
    val selectedTransport =
        policy.transport.takeUnless { it == TorBridgeTransport.AUTO }
            ?: TorBridgeTransport.SNOWFLAKE
    val selectedBridges =
        sequenceOf(
            parseBridgeSource(downloadedGroupsJson),
            parseBridgeSource(bundledPtConfigJson),
        ).filterNotNull()
            .map { source -> selectedReadyBridges(source, selectedTransport, transportLines) }
            .firstOrNull { bridges -> bridges.isNotEmpty() }
            ?: return emptyList()
    return listOf("UseBridges 1") + selectedBridges.map { bridge -> "Bridge $bridge" }
}

private fun selectedReadyBridges(
    source: BridgeSource,
    transport: TorBridgeTransport,
    transportLines: List<String>,
): List<String> =
    orderedBridges(source)
        .filter { bridge -> transport.matchesBridgeTransportToken(bridge.substringBefore(' ')) }
        .filter { bridge -> transportReady(bridge.substringBefore(' '), transportLines) }
        .filter(::isArtiCompatibleBridgeLine)

private fun parseBridgeSource(rawJson: String?): BridgeSource? {
    val text = rawJson?.takeIf(String::isNotBlank) ?: return null
    val root = runCatching { bridgeSourceJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val recommended =
        root["recommendedDefault"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotBlank)
    val groupsObject =
        root["bridges"]?.jsonObject
            ?: root.takeIf { obj -> obj.values.isNotEmpty() && obj.values.all { it is JsonArray } }
            ?: return null
    val groups =
        groupsObject
            .mapNotNull { (key, value) ->
                val lines =
                    (value as? JsonArray)
                        ?.mapNotNull { line -> line.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                        .orEmpty()
                if (lines.isEmpty()) null else key to lines
            }.toMap()
    return groups.takeIf { it.isNotEmpty() }?.let { BridgeSource(recommended, it) }
}

private fun orderedBridges(source: BridgeSource): List<String> {
    val orderedKeys =
        buildList {
            source.recommendedDefault?.takeIf { source.groups.containsKey(it) }?.let(::add)
            source.groups.keys.forEach { key -> if (key !in this) add(key) }
        }
    return orderedKeys.flatMap { key -> source.groups[key].orEmpty() }.distinct()
}

private fun transportReady(
    bridgeTransport: String,
    transportLines: List<String>,
): Boolean =
    transportLines.any { line ->
        line.startsWith("ClientTransportPlugin ") &&
            line
                .substringAfter("ClientTransportPlugin ")
                .substringBefore(" exec ")
                .split(',')
                .map(String::trim)
                .contains(bridgeTransport)
    }
