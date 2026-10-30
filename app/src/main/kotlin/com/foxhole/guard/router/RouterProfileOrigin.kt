package com.foxhole.guard.router

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
internal data class RouterProfileMarker(val schema: Int, val kind: String, val pairingId: String,
    val clientId: String, val uriSha256: String) {
    fun matches(uri: String, expectedPairingId: String): Boolean = validRouterProfileMarker(
        schema, kind, pairingId, clientId, uriSha256, uri, expectedPairingId)
}

@Serializable
internal data class RouterTrustedProfile(val profileId: String, val configSha256: String,
    val certificateSha256: String, val marker: RouterProfileMarker) {
    fun validate(pairing: RouterPairing) {
        require(profileId.isNotBlank() && profileId.length <= 160 && profileId.none(Char::isISOControl))
        require(configSha256.matches(Regex("[a-f0-9]{64}")) && certificateSha256 == pairing.identity.certificateSha256)
        require(marker.schema == 1 && marker.kind == "foxhole-openwrt-client" && marker.pairingId == pairing.id &&
            marker.clientId.matches(Regex("client-[a-f0-9]{12}")) && marker.uriSha256.matches(Regex("[a-f0-9]{64}")))
    }
    override fun toString(): String = "RouterTrustedProfile"
}

internal class RouterClientExport(val uri: String, val marker: RouterProfileMarker?) {
    override fun toString(): String = "RouterClientExport(marked=${marker != null})"
}

internal class RouterAuthenticatedProfileImport(val content: String, val sourceUri: String,
    val marker: RouterProfileMarker, val identity: RouterTlsIdentity) {
    init { require(marker.matches(sourceUri, marker.pairingId)) }
    override fun toString(): String = "RouterAuthenticatedProfileImport"
}

internal fun routerProfileMarker(value: JsonElement?, uri: String, pairingId: String): RouterProfileMarker? {
    if (value == null) return null
    val objectValue = value as? JsonObject ?: malformedRouterMarker()
    if (objectValue.keys != setOf("schema", "kind", "pairingId", "clientId", "uriSha256") ||
        objectValue.toString().toByteArray(Charsets.UTF_8).size > 256) malformedRouterMarker()
    fun text(key: String): String = (objectValue[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: malformedRouterMarker()
    val schema = (objectValue["schema"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: malformedRouterMarker()
    return RouterProfileMarker(schema, text("kind"), text("pairingId"), text("clientId"), text("uriSha256"))
        .also { if (!it.matches(uri, pairingId)) malformedRouterMarker() }
}

private fun malformedRouterMarker(): Nothing = throw RouterApiFailure(RouterApiError.INVALID_RESPONSE)
