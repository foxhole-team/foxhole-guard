package com.foxhole.guard.router

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Synthetic signed-metadata relationships and byte budgets; these are not released artifacts. */
internal class RouterCompactBootstrapFixtures(
    ids: List<String> = listOf("hysteria2", "vless", "wireguard"),
    withServer: Boolean = true,
    modes: List<RouterStorageMode> = RouterStorageMode.entries,
    transformUniverse: (JsonObject) -> JsonObject = { it },
) {
    val documents = linkedMapOf<String, ByteArray>()
    val declared = linkedMapOf<String, String>()
    val requested = mutableListOf<String>()
    val clients = ids.flatMap { id -> modes.map { client(id, it) } }
    val system = RouterSystemInspection("25.12.5", RouterPlatform("25.12", "x86/64", "x86_64"),
        RouterResourceFacts(2_000_000_000, 2_000_000_000), KERNEL, 4096)
    private val serverProducer = if (withServer) publish("server-producer.json", buildJsonObject {
        put("schema", 1); put("exitCode", 0); put("name", "hysteria"); put("version", "2.12.2"); put("target", "linux-amd64")
        put("sourceCommit", COMMIT); put("sourceSha256", HASH); put("binarySha256", SERVER_BINARY); put("binaryBytes", 16384)
        put("toolchain", "synthetic-go-version"); put("features", compactArray(listOf("incoming-hysteria2", "legacy-outgoing-hysteria2")))
        put("sourcePatch", buildJsonObject { put("receiptSha256", SECURITY) })
    }) else null
    val server = serverProducer?.let { producer -> buildJsonObject {
        put("producer", producer)
        put("modes", buildJsonObject { for (mode in modes) put(mode.name.lowercase(), buildJsonObject {
            put("name", if (mode == RouterStorageMode.FLASH) "hysteria" else "hysteria-server-ram")
            put("file", "server-${mode.name.lowercase()}.apk"); put("sha256", HASH)
        }) })
    } } ?: JsonNull
    val engineCatalog = publish("engines-catalog.json", buildJsonObject { put("schema", 1); put("entries", JsonArray(clients.map { it.getValue("entry") })) })
    val universe: JsonObject
    val platform: JsonObject
    val catalogDocument: JsonObject
    val manifest: Map<String, String>
    val manifestSha256: String
    val catalog: RouterInstallCatalog
    val release: GithubRouterSetupRelease
    init {
        val packages = mutableListOf<JsonObject>()
        fun pkg(name: String, file: String, version: String = "1.0.0-r1", bytes: Int = 12288) {
            packages += buildJsonObject {
                put("name", name); put("file", file); put("version", version); put("arch", "x86_64"); put("sha256", HASH)
                put("archiveBytes", 1024); put("installedBytes", bytes)
            }
            declared[file] = HASH
        }
        pkg("foxhole-openwrt-client", "panel.apk")
        pkg("kernel", "kernel.apk", KERNEL)
        for (client in clients) { val entry = client.compactObject("entry"); pkg(entry.compactText("packageName"), entry.compactText("packageUrl").substringAfterLast('/')) }
        if (withServer) for (mode in modes) pkg(if (mode == RouterStorageMode.FLASH) "hysteria" else "hysteria-server-ram", "server-${mode.name.lowercase()}.apk")
        val files = buildJsonObject {
            packages.forEach { put(it.compactText("file"), HASH) }
            put("engines.adb", INDEX); put("upstream.adb", UPSTREAM_INDEX); put("foxhole-openwrt-apk.pem", SIGNING_FINGERPRINT); put("upstream.pem", UPSTREAM_KEY)
        }
        files.forEach { (file, sha) -> declared[file] = (sha as JsonPrimitive).content }
        clients.filter { it.compactText("storage") == "ram" }.forEach { client ->
            declared["engine-${client.compactText("id")}-x86_64-$HASH.bin"] = HASH
        }
        if (withServer) declared["hysteria-server.bin"] = SERVER_BINARY
        universe = transformUniverse(buildJsonObject {
            put("schema", 2); put("kind", "compact-package-universe-v1"); put("architecture", "x86_64")
            put("supportedTargets", compactArray(listOf("x86/64"))); put("supportedOpenWrt", compactArray(listOf("25.12.5")))
            put("verification", buildJsonObject { put("signatures", true); put("resolver", "apk add --simulate --no-network")
                put("signatureModel", "canonical-apk-and-upstream-index-v1"); put("factorySourceSha256", HASH) })
            put("packages", JsonArray(packages))
            put("requires", buildJsonObject { packages.forEach { put(it.compactText("name"), JsonArray(emptyList())) } })
            put("packageAuthentications", JsonArray(packages.map { pkg -> buildJsonObject {
                for (field in listOf("name", "version", "file")) put(field, pkg.getValue(field))
                put("architecture", "x86_64"); put("packageIdentity", HASH)
                put("indexFile", if (pkg.compactText("name") == "kernel") "upstream.adb" else "engines.adb")
            } }))
            put("repositoryIndexes", JsonArray(listOf(
                buildJsonObject { put("file", "engines.adb"); put("sha256", INDEX); put("bytes", 512); put("role", "foxhole")
                    put("sourceUrl", "$BASE/engines.adb"); put("trustKeyFiles", compactArray(listOf("foxhole-openwrt-apk.pem"))) },
                buildJsonObject { put("file", "upstream.adb"); put("sha256", UPSTREAM_INDEX); put("bytes", 256); put("role", "upstream")
                    put("sourceUrl", "https://downloads.openwrt.org/releases/25.12.5/packages/x86_64/base/packages.adb")
                    put("trustKeyFiles", compactArray(listOf("upstream.pem"))) })))
            put("trustKeys", JsonArray(listOf(buildJsonObject { put("file", "foxhole-openwrt-apk.pem"); put("sha256", SIGNING_FINGERPRINT); put("bytes", 400) },
                buildJsonObject { put("file", "upstream.pem"); put("sha256", UPSTREAM_KEY); put("bytes", 400) })))
            put("filesSha256", files)
            put("server", if (withServer) buildJsonObject { for (mode in modes) put(mode.name.lowercase(), buildJsonObject {
                put("name", if (mode == RouterStorageMode.FLASH) "hysteria" else "hysteria-server-ram")
                put("file", "server-${mode.name.lowercase()}.apk"); put("sha256", HASH)
                put("producerSha256", requireNotNull(serverProducer).compactHash("sha256")); put("binarySha256", SERVER_BINARY)
                put("executableSha256", if (mode == RouterStorageMode.FLASH) SERVER_BINARY else HASH)
                put("runtimeMemoryBytes", 16777216); put("securityReceiptSha256", SECURITY)
                if (mode == RouterStorageMode.RAM) { put("volatileFile", "hysteria-server.bin"); put("binaryBytes", 16384) }
            }) } else JsonNull)
            put("baseRuntimeMemoryBytes", 1048576); put("flashReserveBytes", 16777216); put("ramReserveBytes", 16777216)
            put("memoryEvidence", buildJsonObject { put("schema", 1); put("type", "conservative"); put("description", "synthetic exact closure fixture")
                put("provisional", false); put("acceptedForRelease", true); put("measured", false) })
        })
        val universeAsset = publish("universe.json", universe)
        platform = buildJsonObject {
            put("id", "x86-25.12.5"); put("architecture", "x86_64"); put("supportedTargets", compactArray(listOf("x86/64")))
            put("supportedOpenWrt", compactArray(listOf("25.12.5")))
            put("basePackage", buildJsonObject { put("name", "foxhole-openwrt-client"); put("file", "panel.apk"); put("sha256", HASH); put("packageIdentity", HASH) })
            put("engineCatalog", engineCatalog); put("packageUniverse", universeAsset); put("clients", JsonArray(clients)); put("server", server)
        }
        catalogDocument = buildJsonObject {
            put("schema", 2); put("kind", "compact-client-engines-v1"); put("development", false)
            put("defaultProtocols", compactArray(listOf("hysteria2"))); put("allProtocols", compactArray(RouterInstallProtocol.entries.map { it.canonical }.sorted()))
            put("platforms", JsonArray(listOf(platform)))
        }
        publish("package-catalog.json", catalogDocument)
        documents["install.sh"] = "#!/bin/sh\n# synthetic fixture only\nexit 0\n".toByteArray()
        declared["install.sh"] = routerReleaseSha256(documents.getValue("install.sh"))
        manifest = declared.toMap()
        val sums = manifest.entries.joinToString("\n", postfix = "\n") { "${it.value}  ${it.key}" }.toByteArray()
        documents["SHA256SUMS"] = sums; manifestSha256 = routerReleaseSha256(sums)
        val bytes = documents.getValue("package-catalog.json")
        catalog = RouterInstallCatalog.parseVerified(bytes, routerReleaseSha256(bytes), manifestSha256)
        release = GithubRouterSetupRelease(BASE, manifestSha256, RouterReleaseAssetSource { name, maximum ->
            requested += name; documents.getValue(name).also { require(it.size <= maximum) }
        }, kotlinx.coroutines.Dispatchers.Unconfined)
    }
    private fun publish(name: String, value: JsonObject): JsonObject {
        val bytes = value.toString().toByteArray(); documents[name] = bytes
        val sha = routerReleaseSha256(bytes); declared[name] = sha
        return buildJsonObject { put("file", name); put("sha256", sha) }
    }
    private fun client(id: String, mode: RouterStorageMode): JsonObject {
        val prefix = "$id-${mode.name.lowercase()}"
        val producer = publish("$prefix-producer.json", buildJsonObject {
            put("schema", 1); put("exitCode", 0); put("target", "x86_64-unknown-linux-musl"); put("binarySha256", HASH)
            put("sourceCommit", COMMIT); put("sourceSha256", HASH); put("coreVersion", "1.0.0"); put("protocols", compactArray(listOf(id)))
        })
        val caps = publish("$prefix-capabilities.json", buildJsonObject {
            put("schema_version", 1); put("router_contract_version", 1); put("platform", "linux"); put("core_version", "1.0.0")
            put("protocols", compactArray(listOf(id)))
        })
        val raw = buildJsonObject {
            put("id", id); put("storage", mode.name); put("version", "1.0.0"); put("architecture", "x86_64")
            put("binarySha256", HASH); put("executableSha256", HASH); put("producerSha256", producer.getValue("sha256"))
            put("capabilitiesSha256", caps.getValue("sha256")); put("legalProvenanceSha256", HASH); put("sourceCommit", COMMIT)
            put("binaryBytes", 8192); put("runtimeMemoryBytes", if (id == "wireguard") 50331648 else 33554432); put("reserveBytes", 16777216)
        }
        val slot = publish("$prefix-slot.json", JsonObject(raw + ("schema" to JsonPrimitive(1))))
        val packageName = "foxcore-engine-$prefix-${slot.compactHash("sha256")}"
        val entry = JsonObject(raw + mapOf("manifestSha256" to slot.getValue("sha256"), "archiveBytes" to JsonPrimitive(1024),
            "indexBytes" to JsonPrimitive(512), "installedBytes" to JsonPrimitive(12288), "packageName" to JsonPrimitive(packageName),
            "packageVersion" to JsonPrimitive("1.0.0-r1"), "packageSha256" to JsonPrimitive(HASH), "indexSha256" to JsonPrimitive(INDEX),
            "packageUrl" to JsonPrimitive("$BASE/$packageName-1.0.0-r1.apk"), "indexUrl" to JsonPrimitive("$BASE/engines.adb")))
        return buildJsonObject { put("id", id); put("storage", mode.name.lowercase()); put("entry", entry)
            put("producer", producer); put("capabilities", caps); put("slotManifest", slot) }
    }
    fun selection(vararg ids: RouterInstallProtocol, server: Boolean = false): RouterInstallProtocolSelection =
        requireNotNull(catalog.selection(ids.toSet(), server))
    fun readback(binding: RouterInstallBinding, packages: List<RouterPackageIdentity>): ByteArray {
        val compact = requireNotNull(binding.compact)
        return buildJsonObject {
            put("schema", 2); put("kind", "compact-installed-package-v1"); put("architecture", "x86_64")
            put("supportedTargets", compactArray(listOf("x86/64"))); put("supportedOpenWrt", compactArray(listOf("25.12.5")))
            put("protocols", compactArray(binding.selection.protocols.map { it.canonical }.sorted())); put("includeServer", binding.selection.includeServer)
            put("mode", binding.storageMode.name.lowercase()); put("catalogSha256", catalog.sha256); put("engineCatalogSha256", compact.platform.engineCatalog.sha256)
            put("basePackage", platform.getValue("basePackage")); put("server", compact.server ?: JsonNull)
            put("modules", JsonArray(compact.modules.map { buildJsonObject { put("id", it.protocol.canonical); put("storage", it.storage.name.lowercase()); put("entry", it.entry) } }))
            put("packages", JsonArray(packages.map { buildJsonObject { put("name", it.name); put("version", it.version); put("file", it.file); put("sha256", it.sha256) } }))
        }.toString().toByteArray()
    }
    companion object {
        const val BASE = "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.0"
        const val KERNEL = "6.12.94-r1"
        val HASH = "a".repeat(64)
        val COMMIT = "e".repeat(40)
        val SERVER_BINARY = "d".repeat(64)
        val SECURITY = "e".repeat(64)
        val INDEX = "b".repeat(64)
        val UPSTREAM_INDEX = "c".repeat(64)
        val UPSTREAM_KEY = "f".repeat(64)
        const val SIGNING_FINGERPRINT = "fa220a28fd8662fc5f617f46594bafe2415b4f605136570dc3c446caeb7d1ef1"
    }
}
internal fun JsonObject.compactChanged(name: String, value: JsonElement): JsonObject = JsonObject(this + (name to value))
