package com.foxhole.guard.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest

internal enum class RouterInstallProtocol(val canonical: String, val label: String) {
    HYSTERIA2("hysteria2", "Hysteria2"), VLESS("vless", "VLESS"), VMESS("vmess", "VMess"),
    TUIC("tuic", "TUIC"), TROJAN("trojan", "Trojan"), SHADOWSOCKS("shadowsocks", "Shadowsocks"),
    WIREGUARD("wireguard", "WireGuard"), NAIVE("naive", "Naive"), ANYTLS("anytls", "AnyTLS"),
    SHADOWTLS("shadowtls", "ShadowTLS"), SOCKS("socks", "SOCKS5"), HTTP("http", "HTTP");
}

internal fun routerInstallTarget(architecture: String): String? = when (architecture) {
    "x86_64" -> "x86/64"
    "aarch64_generic" -> "armsr/armv8"
    "aarch64_cortex-a53" -> "mediatek/filogic"
    else -> null
}

internal data class RouterInstallProtocolSelection(val catalogSha256: String, val protocols: Set<RouterInstallProtocol>, val includeServer: Boolean = false)
internal data class RouterInstallAsset(val file: String, val sha256: String)
internal data class RouterInstallVariant(
    val id: String,
    val architecture: String,
    val supportedOpenWrt: Set<String>,
    val supportedTargets: Set<String>,
    val protocols: Set<RouterInstallProtocol>,
    val coreProducer: RouterInstallAsset,
    val coreBinarySha256: String,
    val coreSourceSha256: String,
    val capabilities: RouterInstallAsset,
    val modes: Map<RouterStorageMode, RouterInstallAsset>,
    val serverProducer: RouterInstallAsset? = null,
) {
    val coreProducerSha256: String get() = coreProducer.sha256
    val capabilitiesSha256: String get() = capabilities.sha256
}

internal data class RouterInstallBinding(
    val selection: RouterInstallProtocolSelection,
    val variantId: String,
    val architecture: String,
    val storageMode: RouterStorageMode,
    val coreProducerSha256: String?,
    val coreBinarySha256: String?,
    val coreSourceSha256: String?,
    val capabilitiesSha256: String?,
    val budgetSha256: String,
    val target: String,
    val compact: RouterCompactInstallBinding? = null,
)

internal class RouterInstallCatalog private constructor(
    val sha256: String,
    val manifestSha256: String,
    val variants: List<RouterInstallVariant>,
    val schema: Int = 1,
    val compactPlatforms: List<RouterCompactPlatform> = emptyList(),
) {
    val availableProtocols: List<RouterInstallProtocol> = RouterInstallProtocol.entries.filter { protocol -> if (schema == 2) compactPlatforms.any { platform -> platform.clients.any { it.protocol == protocol } } else variants.any { protocol in it.protocols } }
    val defaultSelection: RouterInstallProtocolSelection? = selection(setOf(RouterInstallProtocol.HYSTERIA2))
    val allSelection: RouterInstallProtocolSelection? = selection(if (schema == 2) availableProtocols.toSet() else RouterInstallProtocol.entries.toSet())

    fun accepts(protocols: Set<RouterInstallProtocol>, includeServer: Boolean = false): Boolean = protocols.isNotEmpty() &&
        if (schema == 2) compactPlatforms.any { it.modes(protocols, includeServer).isNotEmpty() } else !includeServer && variants.any { it.protocols == protocols }
    fun selection(protocols: Set<RouterInstallProtocol>, includeServer: Boolean = false): RouterInstallProtocolSelection? =
        protocols.takeIf { accepts(it, includeServer) }?.let { RouterInstallProtocolSelection(sha256, it.toSet(), includeServer) }

    fun matches(binding: RouterInstallBinding, version: String, manifest: String): Boolean {
        if (manifest != manifestSha256 || binding.selection.catalogSha256 != sha256) return false
        if (schema == 2) return matchesCompact(binding, version)
        if (binding.compact != null || binding.selection.includeServer) return false
        val variant = variants.singleOrNull { it.id == binding.variantId } ?: return false
        return variant.architecture == binding.architecture && version in variant.supportedOpenWrt && binding.target in variant.supportedTargets &&
            variant.protocols == binding.selection.protocols && variant.coreProducerSha256 == binding.coreProducerSha256 &&
            variant.coreBinarySha256 == binding.coreBinarySha256 && variant.coreSourceSha256 == binding.coreSourceSha256 &&
            variant.capabilitiesSha256 == binding.capabilitiesSha256 && variant.modes[binding.storageMode]?.sha256 == binding.budgetSha256
    }

    companion object {
        // The caller must first authenticate the manifest and its catalog entry.
        fun parseVerified(bytes: ByteArray, expectedSha256: String, manifestSha256: String): RouterInstallCatalog {
            require(expectedSha256.matches(HASH) && manifestSha256.matches(HASH) && bytes.size in 1..524288)
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(actual == expectedSha256)
            requireBoundedRouterJson(bytes)
            val document = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject ?: error("catalog_shape")
            val schema = (document["schema"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            if (schema == 2) return RouterInstallCatalog(expectedSha256, manifestSha256, emptyList(), 2,
                parseRouterCompactPlatforms(routerCompactDocument(bytes, 524288)))
            require(schema == 1 && bytes.size <= 131072 && "kind" !in document && "platforms" !in document)
            require((document["development"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == false)
            require(protocols(document["defaultProtocols"]) == setOf(RouterInstallProtocol.HYSTERIA2))
            require(protocols(document["allProtocols"]) == RouterInstallProtocol.entries.toSet())
            val values = document["variants"] as? JsonArray ?: error("catalog_variants")
            require(values.size <= 256)
            val variants = values.map { parseVariant(it as? JsonObject ?: error("catalog_variant")) }
            require(variants.map { it.id }.distinct().size == variants.size)
            require(variants.map { listOf(it.architecture, it.supportedOpenWrt.sorted(), it.protocols.map { protocol -> protocol.canonical }.sorted()) }.distinct().size == variants.size)
            val coverage = mutableSetOf<List<Any>>()
            variants.forEach { variant ->
                variant.supportedTargets.forEach { target -> variant.supportedOpenWrt.forEach { version ->
                    require(coverage.add(listOf(variant.architecture, target, version,
                        variant.protocols.map { it.canonical }.sorted())))
                } }
            }
            return RouterInstallCatalog(expectedSha256, manifestSha256, variants)
        }

        private fun parseVariant(value: JsonObject): RouterInstallVariant {
            val id = value.text("id").also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}"))) }
            val architecture = value.text("architecture")
            val supportedTarget = requireNotNull(routerInstallTarget(architecture))
            val versions = (value["supportedOpenWrt"] as? JsonArray ?: error("catalog_versions")).map {
                (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content?.also { version -> require(version.matches(Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{1,3}"))) }
                    ?: error("catalog_version")
            }
            require(versions.isNotEmpty() && versions.size <= 32 && versions.distinct().size == versions.size)
            val targets = (value["supportedTargets"] as? JsonArray ?: error("catalog_targets")).map {
                (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: error("catalog_target")
            }
            require(targets == listOf(supportedTarget))
            val modes = value["modes"] as? JsonObject ?: error("catalog_modes")
            require(modes.isNotEmpty() && modes.keys.all { it in setOf("flash", "ram") })
            return RouterInstallVariant(id, architecture, versions.toSet(), targets.toSet(), protocols(value["protocols"]),
                value.asset("coreProducerFile", "coreProducerSha256"), value.hash("coreBinarySha256"), value.hash("coreSourceSha256"),
                value.asset("capabilitiesFile", "capabilitiesSha256"), modes.map { (mode, item) ->
                    (if (mode == "flash") RouterStorageMode.FLASH else RouterStorageMode.RAM) to
                        (item as? JsonObject ?: error("catalog_mode")).asset("budgetFile", "budgetSha256")
                }.toMap(), value.optionalAsset("serverProducerFile", "serverProducerSha256"))
        }

        private fun protocols(value: kotlinx.serialization.json.JsonElement?): Set<RouterInstallProtocol> {
            val names = (value as? JsonArray ?: error("catalog_protocols")).map {
                (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: error("catalog_protocol")
            }
            require(names.isNotEmpty() && names.size <= RouterInstallProtocol.entries.size && names.distinct().size == names.size)
            return names.map { name -> RouterInstallProtocol.entries.singleOrNull { it.canonical == name } ?: error("catalog_protocol") }.toSet()
        }

        private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("catalog_field")
        private fun JsonObject.hash(key: String): String = text(key).also { require(it.matches(HASH)) }
        private fun JsonObject.asset(fileKey: String, hashKey: String): RouterInstallAsset {
            val file = text(fileKey).also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,159}")) && it != "." && it != "..") }
            return RouterInstallAsset(file, hash(hashKey))
        }
        private fun JsonObject.optionalAsset(fileKey: String, hashKey: String): RouterInstallAsset? {
            require(containsKey(fileKey) == containsKey(hashKey))
            return if (containsKey(fileKey)) asset(fileKey, hashKey) else null
        }
        private val HASH = Regex("[a-f0-9]{64}")
    }
}
