package com.foxhole.guard.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal data class RouterPackageIdentity(val name: String, val version: String, val file: String, val sha256: String)

internal fun verifyRouterInstalledPackage(bytes: ByteArray, binding: RouterInstallBinding,
    system: RouterSystemInspection, packages: List<RouterPackageIdentity>, serverProducerSha256: String? = null) {
    if (binding.compact != null) {
        require(serverProducerSha256 == null)
        verifyRouterCompactInstalledPackage(bytes, binding, system, packages)
        return
    }
    val value = routerInstallDocument(bytes, 131072)
    require(value.installNumber("schema") == 1 && value.installText("variantId") == binding.variantId &&
        value.installText("architecture") == binding.architecture && value.installText("catalogSha256") == binding.selection.catalogSha256 &&
        value.installText("mode") == binding.storageMode.name.lowercase() &&
        value.installText("coreBinarySha256") == binding.coreBinarySha256 && value.installText("capabilitiesSha256") == binding.capabilitiesSha256 &&
        value.installText("coreProducerSha256") == binding.coreProducerSha256 && value.installText("coreSourceSha256") == binding.coreSourceSha256)
    require(if (serverProducerSha256 != null) value.installText("serverProducerSha256") == serverProducerSha256
        else value["serverProducerSha256"] == null || value["serverProducerSha256"] == JsonNull)
    require(value.installStrings("supportedOpenWrt").contains(system.openWrtVersion) &&
        value.installStrings("supportedTargets").contains(system.platform.target) &&
        value.installStrings("protocols").toSet() == binding.selection.protocols.map { it.canonical }.toSet())
    val actual = (value["packages"] as? JsonArray ?: error("router_package_receipt_invalid")).map { item ->
        val entry = item as? JsonObject ?: error("router_package_receipt_invalid")
        RouterPackageIdentity(entry.installText("name"), entry.installText("version"), entry.installText("file"), entry.installText("sha256"))
    }
    require(packages.isNotEmpty() && packages.size <= 256 && actual.size == packages.size &&
        actual.map { it.name }.distinct().size == actual.size && actual.toSet() == packages.toSet())
}

internal fun routerBootstrapCredential(bytes: ByteArray): CharArray? = try {
    val value = routerInstallDocument(bytes, 1024)
    require(value.installNumber("schema") == 1)
    when (value.installText("status")) {
        "fresh" -> {
            require(value.installNumber("expiresInSeconds") in 1..300)
            val pin = value.installText("bootstrapPin")
            require(pin.length == 4 && pin.all { it in '0'..'9' })
            pin.toCharArray()
        }
        "already_configured" -> {
            require(value["bootstrapPin"] == JsonNull && value.installNumber("expiresInSeconds") == 0)
            null
        }
        else -> throw IOException("router_bootstrap_invalid")
    }
} finally { bytes.fill(0) }

internal fun routerInstallDocument(bytes: ByteArray, maximum: Int): JsonObject {
    require(bytes.size in 1..maximum)
    requireBoundedRouterJson(bytes, maxDepth = 8, maxNodes = 8192)
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    return Json.parseToJsonElement(text) as? JsonObject ?: error("router_install_metadata_invalid")
}

internal fun JsonObject.installText(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: error("router_install_metadata_invalid")
internal fun JsonObject.installNumber(key: String): Int = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    ?: error("router_install_metadata_invalid")
internal fun JsonObject.installStrings(key: String): List<String> = (this[key] as? JsonArray)?.map {
    (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: error("router_install_metadata_invalid")
}?.also { require(it.distinct().size == it.size) } ?: error("router_install_metadata_invalid")
