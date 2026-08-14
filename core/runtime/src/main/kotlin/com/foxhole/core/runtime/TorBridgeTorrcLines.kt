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

/**
 * Pure, Android-free translation of a bridge policy + a bridge source (a downloaded list or the
 * bundled `pt_config.json`) into the `UseBridges` / `Bridge …` torrc-defaults lines. Kept as a
 * top-level function so it is unit-tested on the JVM without a device.
 */
data class TorBridgePolicy(
    val enabled: Boolean = true,
    val transport: TorBridgeTransport = TorBridgeTransport.AUTO,
) {
    // Stirred into TorRuntimeInstaller's cache key so a settings change or a fresh downloaded list
    // forces the torrc-defaults to be rewritten instead of served from the bundle-version cache.
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

/**
 * @param transportLines normalized `ClientTransportPlugin …` lines (only transports whose native
 *   binary is present survive — see [TorRuntimeInstaller.normalizedTorrcDefaultsLine]).
 * @param downloadedGroupsJson the store payload (preferred); a bare group object or a
 *   `{"bridges": {...}}` wrapper, optionally carrying `recommendedDefault`.
 * @param bundledPtConfigJson the bundled `pt_config.json` fallback.
 */
fun buildBridgeTorrcLines(
    policy: TorBridgePolicy,
    transportLines: List<String>,
    downloadedGroupsJson: String?,
    bundledPtConfigJson: String?,
): List<String> {
    if (!policy.enabled) {
        return emptyList()
    }
    val source =
        parseBridgeSource(downloadedGroupsJson)
            ?: parseBridgeSource(bundledPtConfigJson)
            ?: return emptyList()
    val readyBridges =
        orderedBridges(source).filter { bridge -> transportReady(bridge.substringBefore(' '), transportLines) }
    if (readyBridges.isEmpty()) {
        return emptyList()
    }
    val selected =
        if (policy.transport == TorBridgeTransport.AUTO) {
            readyBridges
        } else {
            // A specific transport that yields no ready bridges (e.g. WEBTUNNEL/CONJURE absent from
            // the current list) falls back to the full AUTO set — never leave Tor bridge-less.
            readyBridges
                .filter { bridge -> policy.transport.matchesBridgeTransportToken(bridge.substringBefore(' ')) }
                .ifEmpty { readyBridges }
        }
    return listOf("UseBridges 1") + selected.map { bridge -> "Bridge $bridge" }
}

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
