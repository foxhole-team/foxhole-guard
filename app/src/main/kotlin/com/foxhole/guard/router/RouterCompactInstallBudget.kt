package com.foxhole.guard.router

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class RouterCompactModeBudget(
    val document: JsonObject,
    val binding: RouterCompactInstallBinding,
    val packages: List<RouterPackageIdentity>,
) {
    fun resources(backupBytes: Long): RouterInstallBudget {
        require(backupBytes in 0L..536870912L)
        val flash = document.compactLong("requiredFlashBytes")
        val ram = Math.addExact(document.compactLong("requiredRamBytes"), backupBytes)
        require(ram <= 9007199254740991L)
        return RouterInstallBudget(flash, flash, ram, ram)
    }
}
internal data class RouterCompactPayload(val modes: Map<RouterStorageMode, RouterCompactModeBudget>)

internal suspend fun routerCompactPayload(
    platform: RouterCompactPlatform,
    system: RouterSystemInspection,
    selection: RouterInstallProtocolSelection,
    manifest: Map<String, String>,
    read: suspend (RouterInstallAsset, Int) -> ByteArray,
): RouterCompactPayload {
    require(platform.architecture == system.platform.architecture && system.platform.target in platform.supportedTargets &&
        system.openWrtVersion in platform.supportedOpenWrt && system.compactBackupBytes != null)
    val modes = platform.modes(selection.protocols, selection.includeServer)
    require(modes.isNotEmpty())
    val universe = routerCompactDocument(read(platform.packageUniverse, 2097152), 2097152)
    universe.compactExact("schema", "kind", "architecture", "supportedTargets", "supportedOpenWrt", "verification", "packages",
        "packageAuthentications", "repositoryIndexes", "trustKeys", "filesSha256", "requires", "server", "baseRuntimeMemoryBytes",
        "flashReserveBytes", "ramReserveBytes", "memoryEvidence")
    require(universe.compactLong("schema") == 2L && universe.compactText("kind") == "compact-package-universe-v1" &&
        universe.compactText("architecture") == platform.architecture &&
        universe.compactStrings("supportedTargets", 1..1) == platform.supportedTargets.toList() &&
        universe.compactStrings("supportedOpenWrt", 1..32) == platform.supportedOpenWrt.toList())
    val catalog = routerCompactDocument(read(platform.engineCatalog, 524288), 524288)
    catalog.compactExact("schema", "entries")
    require(catalog.compactLong("schema") == 1L)
    val published = catalog.compactObjects("entries", 0..72).filter { it.compactText("architecture") == platform.architecture }
    require(published.size == platform.clients.size)
    for (client in platform.clients) {
        require(published.singleOrNull { it.compactText("id") == client.protocol.canonical && it.compactText("storage") == client.storage.name } == client.entry)
    }
    val documents = mutableMapOf<RouterInstallAsset, JsonObject>()
    for (client in platform.clients.filter { it.protocol in selection.protocols }) {
        for (asset in listOf(client.producer, client.capabilities, client.slotManifest)) {
            if (asset !in documents) documents[asset] = routerCompactDocument(read(asset, 131072), 131072)
        }
        verifyRouterCompactProducer(client, documents.getValue(client.producer), documents.getValue(client.capabilities), documents.getValue(client.slotManifest), platform.architecture)
    }
    // Incoming Go server metadata is never requested for an ordinary client-only installation.
    val serverProducer = if (selection.includeServer) {
        val asset = requireNotNull(platform.server).producer
        routerCompactDocument(read(asset, 131072), 131072)
    } else null
    return RouterCompactPayload(modes.associateWith { mode ->
        val modules = platform.clients.filter { it.protocol in selection.protocols && it.storage == mode }.sortedBy { it.protocol.canonical }
        val server = if (selection.includeServer) verifyRouterCompactServer(platform, universe, mode, requireNotNull(serverProducer), manifest) else null
        composeRouterCompactBudget(platform, system, selection, mode, modules, server, universe, manifest)
    })
}

internal fun verifyRouterCompactProducer(client: RouterCompactClient, producer: JsonObject, caps: JsonObject,
    slot: JsonObject, architecture: String) {
    val target = if (architecture == "x86_64") "x86_64-unknown-linux-musl" else "aarch64-unknown-linux-musl"
    val entry = client.entry
    require(producer.compactLong("schema") == 1L && producer.compactLong("exitCode") == 0L && producer.compactText("target") == target &&
        producer.compactHash("binarySha256") == entry.compactHash("binarySha256") && producer.compactHash("sourceSha256").matches(COMPACT_HASH) &&
        producer.compactText("sourceCommit") == entry.compactText("sourceCommit") && producer.compactText("coreVersion") == entry.compactText("version") &&
        producer.compactStrings("protocols", 1..1) == listOf(client.protocol.canonical))
    require(caps.compactLong("schema_version") == 1L && caps.compactLong("router_contract_version") == 1L && caps.compactText("platform") == "linux" &&
        caps.compactText("core_version") == producer.compactText("coreVersion") && caps.compactStrings("protocols", 1..1) == listOf(client.protocol.canonical) &&
        slot.compactLong("schema") == 1L)
    for (field in listOf("id", "storage", "version", "architecture", "binarySha256", "executableSha256", "producerSha256", "capabilitiesSha256",
        "legalProvenanceSha256", "sourceCommit", "binaryBytes", "runtimeMemoryBytes", "reserveBytes")) require(slot[field] == entry[field])
}

internal fun verifyRouterCompactServer(platform: RouterCompactPlatform, universe: JsonObject, mode: RouterStorageMode,
    producer: JsonObject, manifest: Map<String, String>): JsonObject {
    val declared = requireNotNull(platform.server?.modes?.get(mode))
    val server = universe.compactObject("server").compactObject(mode.name.lowercase())
    val fields = setOf("name", "file", "sha256", "producerSha256", "binarySha256", "executableSha256", "runtimeMemoryBytes", "securityReceiptSha256") +
        if (mode == RouterStorageMode.RAM) setOf("volatileFile", "binaryBytes") else emptySet()
    server.compactExact(*fields.toTypedArray())
    require(server.compactText("name") == declared.name && server.compactFile("file") == declared.file && server.compactHash("sha256") == declared.sha256 &&
        server.compactHash("producerSha256") == platform.server?.producer?.sha256)
    server.compactLong("runtimeMemoryBytes", 1L..2147483648L)
    val target = if (platform.architecture == "x86_64") "linux-amd64" else "linux-arm64"
    require(producer.compactLong("schema") == 1L && producer.compactLong("exitCode") == 0L && producer.compactText("target") == target &&
        producer.compactText("name") == "hysteria" && producer.compactText("version") == "2.12.2" &&
        producer.compactText("toolchain").length in 1..256 &&
        producer.compactStrings("features", 2..2).toSet() == setOf("incoming-hysteria2", "legacy-outgoing-hysteria2") &&
        producer.compactText("sourceCommit").matches(Regex("[a-f0-9]{40}")) && producer.compactHash("sourceSha256").matches(COMPACT_HASH) &&
        producer.compactHash("binarySha256") == server.compactHash("binarySha256") &&
        producer.compactObject("sourcePatch").compactHash("receiptSha256") == server.compactHash("securityReceiptSha256"))
    server.compactHash("executableSha256")
    if (mode == RouterStorageMode.FLASH) require(server.compactHash("executableSha256") == server.compactHash("binarySha256"))
    else {
        require(server.compactLong("binaryBytes", 1L..67108864L) == producer.compactLong("binaryBytes", 1L..67108864L))
        require(manifest[server.compactFile("volatileFile")] == server.compactHash("binarySha256"))
    }
    return server
}

internal fun composeRouterCompactBudget(
    platform: RouterCompactPlatform,
    system: RouterSystemInspection,
    selection: RouterInstallProtocolSelection,
    mode: RouterStorageMode,
    modules: List<RouterCompactClient>,
    server: JsonObject?,
    universe: JsonObject,
    manifest: Map<String, String>,
): RouterCompactModeBudget {
    require(modules.map { it.protocol }.toSet() == selection.protocols && modules.size == selection.protocols.size &&
        modules.all { it.storage == mode } && (server != null) == selection.includeServer)
    val all = universe.compactObjects("packages", 1..512)
    val byName = all.associateBy { item -> item.compactText("name").also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.+-]{0,127}"))) } }
    require(byName.size == all.size)
    val requires = universe.compactObject("requires")
    require(requires.keys == byName.keys)
    val edges = requires.mapValues { (_, raw) ->
        val array = raw as? JsonArray ?: error("compact_dependencies")
        require(array.size <= 64)
        array.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: error("compact_dependency") }.also {
            require(it.distinct().size == it.size && it.all { name -> name in byName })
        }
    }
    val roots = listOf(platform.basePackage.name) + modules.map { it.packageName } + listOfNotNull(server?.compactText("name"))
    val selected = linkedSetOf<String>(); val pending = java.util.ArrayDeque<String>()
    pending.add("kernel"); roots.forEach { pending.add(it) }
    while (pending.isNotEmpty()) {
        val name = pending.removeFirst()
        if (!selected.add(name)) continue
        require(selected.size <= 512 && name in byName)
        edges.getValue(name).forEach { if (it !in selected) pending.add(it) }
    }
    val packages = all.filter { it.compactText("name") in selected }
    val own = roots.toSet()
    require(packages.none { item -> val name = item.compactText("name"); name in setOf("foxcore-cli", "foxcore-cli-ram") ||
        ((name.startsWith("foxcore-engine-") || name in setOf("hysteria", "hysteria-server-ram")) && name !in own) })
    val auth = universe.compactObjects("packageAuthentications", 1..512).also { require(it.size == all.size) }
        .filter { it.compactText("name") in selected }
    val usedIndexes = auth.map { it.compactFile("indexFile") }.toSet()
    val indexes = universe.compactObjects("repositoryIndexes", 1..16).filter { it.compactFile("file") in usedIndexes }
    val usedKeys = indexes.flatMap { it.compactStrings("trustKeyFiles", 1..16) }.toSet()
    val keys = universe.compactObjects("trustKeys", 1..32).filter { it.compactFile("file") in usedKeys }
    val universeFiles = universe.compactObject("filesSha256")
    val files = linkedMapOf<String, JsonElement>()
    var compressed = 0L; var installed = 0L; var rootInstalled = 0L
    fun add(item: JsonObject) {
        val file = item.compactFile("file"); val hash = item.compactHash("sha256")
        require(file !in files && (universeFiles[file] as? JsonPrimitive)?.takeIf { it.isString }?.content == hash && manifest[file] == hash)
        files[file] = JsonPrimitive(hash)
    }
    for (item in packages) {
        add(item); require(item.compactFile("file").endsWith(".apk"))
        compressed = Math.addExact(compressed, item.compactLong("archiveBytes", 1L..536870912L))
        installed = Math.addExact(installed, item.compactLong("installedBytes", 0L..2147483648L))
        if (item.compactText("name") in own) rootInstalled = Math.addExact(rootInstalled, item.compactLong("installedBytes"))
    }
    var indexBytes = 0L; var keyBytes = 0L
    for (item in indexes) { add(item); indexBytes = Math.addExact(indexBytes, item.compactLong("bytes", 1L..8388608L)) }
    for (item in keys) { add(item); keyBytes = Math.addExact(keyBytes, item.compactLong("bytes", 1L..16384L)) }
    val kernel = requireNotNull(byName["kernel"])
    require(system.kernelVersion != null && kernel.compactText("version") == system.kernelVersion)
    val base = requireNotNull(byName[platform.basePackage.name])
    require(base.compactFile("file") == platform.basePackage.file && base.compactHash("sha256") == platform.basePackage.sha256 &&
        auth.singleOrNull { it.compactFile("file") == platform.basePackage.file }?.compactHash("packageIdentity") == platform.basePackage.packageIdentity)
    for (client in modules) {
        val item = requireNotNull(byName[client.packageName]); val entry = client.entry
        require(item.compactFile("file") == client.packageFile && item.compactHash("sha256") == entry.compactHash("packageSha256") &&
            item.compactText("version") == entry.compactText("packageVersion") && item.compactText("arch") == platform.architecture &&
            item.compactLong("archiveBytes") == entry.compactLong("archiveBytes") && item.compactLong("installedBytes") == entry.compactLong("installedBytes"))
        val indexFile = requireNotNull(auth.singleOrNull { it.compactFile("file") == client.packageFile }).compactFile("indexFile")
        val index = requireNotNull(indexes.singleOrNull { it.compactFile("file") == indexFile })
        require(index.compactText("role") == "foxhole" && index.compactHash("sha256") == entry.compactHash("indexSha256") &&
            index.compactLong("bytes") == entry.compactLong("indexBytes") && index.compactText("sourceUrl") == entry.compactText("indexUrl"))
    }
    if (server != null) require(byName[server.compactText("name")]?.let { it.compactFile("file") == server.compactFile("file") && it.compactHash("sha256") == server.compactHash("sha256") } == true)
    val volatile = mutableListOf<JsonObject>(); var volatileBytes = 0L
    var runtime = modules.maxOf { it.entry.compactLong("runtimeMemoryBytes") }
    for (client in modules) if (mode == RouterStorageMode.RAM) {
        val file = "engine-${client.protocol.canonical}-${platform.architecture}-${client.entry.compactHash("binarySha256")}.bin"
        require(manifest[file] == client.entry.compactHash("binarySha256"))
        val bytes = client.entry.compactLong("binaryBytes"); volatileBytes = Math.addExact(volatileBytes, bytes)
        volatile += buildJsonObject { put("id", client.protocol.canonical); put("file", file); put("sha256", client.entry.compactHash("binarySha256")); put("bytes", bytes) }
    }
    runtime = Math.addExact(runtime, universe.compactLong("baseRuntimeMemoryBytes", 1L..2147483648L))
    if (server != null) {
        runtime = Math.addExact(runtime, server.compactLong("runtimeMemoryBytes"))
        if (mode == RouterStorageMode.RAM) {
            val bytes = server.compactLong("binaryBytes"); volatileBytes = Math.addExact(volatileBytes, bytes)
            volatile += buildJsonObject { put("id", "server"); put("file", server.compactFile("volatileFile")); put("sha256", server.compactHash("binarySha256")); put("bytes", bytes) }
        }
    }
    val flashReserve = universe.compactLong("flashReserveBytes", 16777216L..2147483648L)
    val ramReserve = universe.compactLong("ramReserveBytes", 16777216L..2147483648L)
    val staging = Math.addExact(Math.addExact(Math.addExact(Math.multiplyExact(compressed, 2L), installed), volatileBytes), Math.addExact(Math.multiplyExact(indexBytes, 2L), keyBytes))
    val requiredFlash = Math.addExact(installed, flashReserve)
    val requiredRam = Math.addExact(Math.addExact(staging, runtime), ramReserve)
    listOf(compressed, installed, volatileBytes, staging, runtime, requiredFlash, requiredRam).forEach { require(it in 0L..9007199254740991L) }
    val memory = universe.compactObject("memoryEvidence")
    require(memory.compactLong("schema") == 1L && memory.compactText("type") == "conservative" && memory.compactText("description").length in 1..4096)
    for ((key, wanted) in listOf("provisional" to false, "acceptedForRelease" to true, "measured" to false)) if (key in memory) require(memory.compactBoolean(key) == wanted)
    val document = buildJsonObject {
        put("schema", 2); put("kind", "compact-selected-budget-v1"); put("architecture", platform.architecture); put("mode", mode.name.lowercase())
        put("supportedTargets", compactArray(platform.supportedTargets)); put("supportedOpenWrt", compactArray(platform.supportedOpenWrt))
        put("protocols", compactArray(selection.protocols.map { it.canonical }.sorted())); put("includeServer", selection.includeServer)
        put("modules", JsonArray(modules.map(::compactClientJson))); put("server", server ?: JsonNull)
        put("basePackage", buildJsonObject { put("name", platform.basePackage.name); put("file", platform.basePackage.file); put("sha256", platform.basePackage.sha256); put("packageIdentity", platform.basePackage.packageIdentity) })
        put("engineCatalogSha256", platform.engineCatalog.sha256); put("verification", universe.compactObject("verification"))
        put("packages", JsonArray(packages)); put("packageAuthentications", JsonArray(auth)); put("repositoryIndexes", JsonArray(indexes)); put("trustKeys", JsonArray(keys)); put("filesSha256", JsonObject(files))
        put("rootPackages", compactArray(roots)); put("repositoryIndexBytes", indexBytes); put("trustKeyBytes", keyBytes); put("volatile", JsonArray(volatile))
        put("platformPackages", buildJsonObject { put("kernel", system.kernelVersion) })
        put("compressedBytes", compressed); put("installedFlashBytes", installed); put("installedRamBytes", volatileBytes)
        put("dependencyInstalledBytes", installed - rootInstalled); put("stagingBytes", staging); put("runtimeMemoryBytes", runtime)
        put("flashReserveBytes", flashReserve); put("ramReserveBytes", ramReserve); put("requiredFlashBytes", requiredFlash); put("requiredRamBytes", requiredRam)
        put("memoryEvidence", buildJsonObject { put("schema", 1); put("type", "conservative"); put("description", memory.compactText("description")); put("runtimeMemoryBytes", runtime) })
    }
    val verification = document.compactObject("verification")
    require(verification.compactBoolean("signatures") && verification.compactText("resolver") == "apk add --simulate --no-network")
    verifyRouterCompactRepositoryAuthentication(document, packages, manifest, platform.architecture, own)
    val identities = packages.map { RouterPackageIdentity(it.compactText("name"), it.compactText("version"), it.compactFile("file"), it.compactHash("sha256")) }
    val binding = RouterCompactInstallBinding(platform, modules, server, routerReleaseSha256(document.toString().toByteArray()))
    return RouterCompactModeBudget(document, binding, identities)
}

internal fun compactClientJson(client: RouterCompactClient): JsonObject = buildJsonObject {
    put("id", client.protocol.canonical); put("storage", client.storage.name.lowercase()); put("entry", client.entry)
    for ((key, asset) in listOf("producer" to client.producer, "capabilities" to client.capabilities, "slotManifest" to client.slotManifest)) {
        put(key, buildJsonObject { put("file", asset.file); put("sha256", asset.sha256) })
    }
}
internal fun compactArray(values: Collection<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
