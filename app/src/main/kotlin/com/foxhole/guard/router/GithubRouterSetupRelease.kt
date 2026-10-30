package com.foxhole.guard.router

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

internal fun interface RouterReleaseAssetSource {
    suspend fun read(name: String, maximumBytes: Int): ByteArray
    suspend fun read(name: String, maximumBytes: Int, onProgress: (Long, Long?) -> Unit): ByteArray = read(name, maximumBytes)
}

internal class GithubRouterSetupRelease(
    private val baseUrl: String,
    private val manifestSha256: String,
    private val assets: RouterReleaseAssetSource = GithubRouterReleaseAssets(baseUrl),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RouterSetupRelease {
    private val mutex = Mutex()
    @Volatile private var loadedCatalog: RouterInstallCatalog? = null
    private var entries = emptyMap<String, String>()
    override val catalog: RouterInstallCatalog? get() = loadedCatalog
    override val supportedPlatforms: Set<RouterPlatform> get() {
        val current = catalog ?: return emptySet()
        return (current.variants.flatMap { variant -> variant.supportedOpenWrt.flatMap { version -> variant.supportedTargets.map { target ->
            RouterPlatform(version.substringBeforeLast('.'), target, variant.architecture)
        } } } + current.compactPlatforms.flatMap { platform -> platform.supportedOpenWrt.flatMap { version -> platform.supportedTargets.map { target ->
            RouterPlatform(version.substringBeforeLast('.'), target, platform.architecture)
        } } }).toSet()
    }

    init { routerReleaseBaseUrl(baseUrl); require(manifestSha256.matches(ROUTER_RELEASE_HASH)) }

    override suspend fun initialize() = mutex.withLock {
        if (loadedCatalog != null) return@withLock
        routerReleasePhase { withContext(io) { loadCatalog() } }
    }

    private suspend fun loadCatalog() {
        val manifest = assets.read("SHA256SUMS", 1048576)
        entries = verifyRouterReleaseManifest(manifest, manifestSha256)
        val catalogName = "package-catalog.json"
        val candidate = RouterInstallCatalog.parseVerified(readVerified(catalogName, 524288), requireNotNull(entries[catalogName]), manifestSha256)
        require(if (candidate.schema == 2) candidate.availableProtocols.isNotEmpty() else candidate.variants.isNotEmpty() && candidate.defaultSelection != null)
        require(entries.containsKey("install.sh"))
        candidate.variants.forEach { variant ->
            require(entries[variant.coreProducer.file] == variant.coreProducerSha256 &&
                entries[variant.capabilities.file] == variant.capabilitiesSha256)
            variant.serverProducer?.let { require(entries[it.file] == it.sha256) }
            variant.modes.values.forEach { require(entries[it.file] == it.sha256) }
        }
        candidate.compactPlatforms.forEach { platform ->
            val metadata = listOf(platform.engineCatalog, platform.packageUniverse) + platform.clients.flatMap {
                listOf(it.producer, it.capabilities, it.slotManifest)
            } + listOfNotNull(platform.server?.producer)
            metadata.forEach { require(entries[it.file] == it.sha256) }
            require(entries[platform.basePackage.file] == platform.basePackage.sha256)
            platform.clients.forEach { require(entries[it.packageFile] == it.entry.compactHash("packageSha256")) }
            platform.server?.modes?.values?.forEach { require(entries[it.file] == it.sha256) }
        }
        loadedCatalog = candidate
    }

    override suspend fun payload(system: RouterSystemInspection, selection: RouterInstallProtocolSelection): RouterSetupPayload =
        payload(system, selection) {}

    override suspend fun payload(system: RouterSystemInspection, selection: RouterInstallProtocolSelection,
        onProgress: (RouterInstallProgress) -> Unit): RouterSetupPayload =
        routerReleasePhase { withContext(io) {
        onProgress(RouterInstallProgress(RouterInstallProgressStage.DOWNLOAD))
        val actual = requireNotNull(catalog)
        require(selection.catalogSha256 == actual.sha256)
        require(actual.accepts(selection.protocols, selection.includeServer))
        if (actual.schema == 2) {
            val platform = actual.compactPlatforms.singleOrNull { it.architecture == system.platform.architecture &&
                system.platform.target in it.supportedTargets && system.openWrtVersion in it.supportedOpenWrt
            } ?: throw RouterSetupFailure(RouterWizardError.UNSUPPORTED)
            val compact = routerCompactPayload(platform, system, selection, entries) { asset, maximum ->
                require(entries[asset.file] == asset.sha256)
                readVerified(asset.file, maximum, onProgress)
            }
            val backup = requireNotNull(system.compactBackupBytes)
            val script = RouterVerifiedInstallScript(readVerified("install.sh", 1048576, onProgress), requireNotNull(entries["install.sh"]))
            return@withContext RouterSetupPayload(null, compact.modes.mapValues { it.value.resources(backup) }, script, baseUrl,
                compact.modes.mapValues { it.value.packages }, compact)
        }

        val variant = actual.variants.singleOrNull { it.architecture == system.platform.architecture &&
            system.platform.target in it.supportedTargets && system.openWrtVersion in it.supportedOpenWrt && it.protocols == selection.protocols
        } ?: throw RouterSetupFailure(RouterWizardError.UNSUPPORTED)
        val producer = routerInstallDocument(readVerified(variant.coreProducer.file, 131072, onProgress), 131072)
        val capabilities = routerInstallDocument(readVerified(variant.capabilities.file, 131072, onProgress), 131072)
        verifyRouterCoreProducer(producer, capabilities, variant)
        val serverProducer = variant.serverProducer?.let { asset ->
            verifyRouterServerProducer(routerInstallDocument(readVerified(asset.file, 131072, onProgress), 131072), variant)
        }
        val budgets = mutableMapOf<RouterStorageMode, RouterInstallBudget>()
        val packages = mutableMapOf<RouterStorageMode, List<RouterPackageIdentity>>()
        for ((mode, asset) in variant.modes) {
            val budget = routerInstallDocument(readVerified(asset.file, 1048576, onProgress), 1048576)
            val proof = verifyRouterModeBudget(budget, mode, variant, system, entries, serverProducer)
            budgets[mode] = proof.first
            packages[mode] = proof.second
        }
        val script = RouterVerifiedInstallScript(readVerified("install.sh", 1048576, onProgress), requireNotNull(entries["install.sh"]))
        RouterSetupPayload(variant, budgets.toMap(), script, baseUrl, packages.toMap())
        } }

    private suspend fun readVerified(name: String, maximum: Int, onProgress: ((RouterInstallProgress) -> Unit)? = null): ByteArray {
        require(name.matches(ROUTER_RELEASE_FILE))
        val digest = requireNotNull(entries[name])
        val bytes = if (onProgress == null) assets.read(name, maximum) else assets.read(name, maximum) { completed, total ->
            onProgress(RouterInstallProgress(RouterInstallProgressStage.DOWNLOAD, completed, total))
        }
        require(bytes.size in 1..maximum && routerReleaseSha256(bytes) == digest)
        return bytes
    }
}

internal fun verifyRouterReleaseManifest(bytes: ByteArray, expected: String): Map<String, String> {
    require(bytes.size in 1..1048576 && expected.matches(ROUTER_RELEASE_HASH) && routerReleaseSha256(bytes) == expected)
    val lines = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
    require(lines.size in 1..4096)
    val result = linkedMapOf<String, String>()
    for (line in lines) {
        require(line.length in 67..256 && line.substring(0, 64).matches(ROUTER_RELEASE_HASH) && line.substring(64, 66) == "  ")
        val name = line.substring(66)
        require(name.matches(ROUTER_RELEASE_FILE) && result.put(name, line.substring(0, 64)) == null)
    }
    return result.toMap()
}

internal data class RouterServerProducer(val binarySha256: String, val binaryBytes: Long)

internal fun verifyRouterCoreProducer(producer: JsonObject, capabilities: JsonObject, variant: RouterInstallVariant) {
    val target = if (variant.architecture == "x86_64") "x86_64-unknown-linux-musl" else "aarch64-unknown-linux-musl"
    require(producer.installNumber("schema") == 1 && producer.installNumber("exitCode") == 0 && producer.installText("target") == target &&
        producer.installText("binarySha256") == variant.coreBinarySha256 && producer.installText("sourceSha256") == variant.coreSourceSha256 &&
        producer.installStrings("protocols").toSet() == variant.protocols.map { it.canonical }.toSet())
    require(capabilities.installNumber("schema_version") == 1 && capabilities.installNumber("router_contract_version") == 1 &&
        capabilities.installText("platform") == "linux" && capabilities.installText("core_version") == producer.installText("coreVersion") &&
        capabilities.installStrings("protocols").toSet() == producer.installStrings("protocols").toSet())
}

internal fun verifyRouterServerProducer(value: JsonObject, variant: RouterInstallVariant): RouterServerProducer {
    val target = if (variant.architecture == "x86_64") "linux-amd64" else "linux-arm64"
    require(variant.serverProducer != null && value.installNumber("schema") == 1 && value.installNumber("exitCode") == 0 &&
        value.installText("name") == "hysteria" && value.installText("version") == "2.12.2" &&
        value.installText("target") == target)
    require(value.installText("sourceSha256").matches(ROUTER_RELEASE_HASH) &&
        value.installText("sourceCommit").matches(Regex("[a-f0-9]{40}")) && value.installText("toolchain").length in 1..256 &&
        value.installStrings("features").toSet() == setOf("incoming-hysteria2", "legacy-outgoing-hysteria2"))
    return RouterServerProducer(value.installText("binarySha256").also { require(it.matches(ROUTER_RELEASE_HASH)) },
        value.budgetBytes("binaryBytes", positive = true))
}

internal fun verifyRouterModeBudget(value: JsonObject, mode: RouterStorageMode, variant: RouterInstallVariant,
    system: RouterSystemInspection, manifest: Map<String, String>, serverProducer: RouterServerProducer? = null): Pair<RouterInstallBudget, List<RouterPackageIdentity>> {
    require(value.installNumber("schema") == 1 && value.installText("architecture") == variant.architecture &&
        value.installText("mode") == mode.name.lowercase() && value.installText("coreProducerSha256") == variant.coreProducerSha256 &&
        value.installText("capabilitiesSha256") == variant.capabilitiesSha256 &&
        system.platform.target in value.installStrings("supportedTargets") && system.openWrtVersion in value.installStrings("supportedOpenWrt") &&
        value.installStrings("protocols").toSet() == variant.protocols.map { it.canonical }.toSet())
    val verification = value["verification"] as? JsonObject ?: error("router_budget_invalid")
    val platformPackages = value["platformPackages"] as? JsonObject ?: error("router_budget_invalid")
    require(system.kernelVersion != null && platformPackages.installText("kernel") == system.kernelVersion)
    require((verification["signatures"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true &&
        verification.installText("resolver") == "apk add --simulate --no-network")
    val core = value["corePackage"] as? JsonObject ?: error("router_budget_invalid")
    require((core["payloadVerified"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true &&
        core.installText("binarySha256") == variant.coreBinarySha256 && core.installText("producerSha256") == variant.coreProducerSha256)
    val memory = value["memoryEvidence"] as? JsonObject ?: error("router_budget_invalid")
    val runtime = value.budgetBytes("runtimeMemoryBytes", positive = true)
    require(memory.installNumber("schema") == 1 && memory.installText("type") in setOf("measured", "conservative") &&
        memory.installText("description").length in 1..4096 && memory.budgetBytes("runtimeMemoryBytes", positive = true) == runtime)
    memory["provisional"]?.let { require((it as? JsonPrimitive)?.takeIf { flag -> !flag.isString }?.booleanOrNull == false) }
    memory["acceptedForRelease"]?.let { require((it as? JsonPrimitive)?.takeIf { flag -> !flag.isString }?.booleanOrNull == true) }
    val compressed = value.budgetBytes("compressedBytes", positive = true)
    val installed = value.budgetBytes("installedFlashBytes", positive = true)
    val volatile = value.budgetBytes("installedRamBytes")
    require(mode == RouterStorageMode.RAM || volatile == 0L)
    val packageValues = (value["packages"] as? JsonArray ?: error("router_budget_invalid")).map { item ->
        item as? JsonObject ?: error("router_budget_invalid")
    }
    val repositoryBytes = verifyRouterRepositoryAuthentication(value, packageValues, manifest, variant.architecture)
    val payloadStaging = Math.addExact(Math.addExact(Math.multiplyExact(compressed, 2L), installed), volatile)
    val metadataStaging = Math.addExact(Math.multiplyExact(repositoryBytes.indexes, 2L), repositoryBytes.keys)
    val staging = Math.addExact(payloadStaging, metadataStaging)
    require(value.budgetBytes("stagingBytes", positive = true) == staging)
    val flash = Math.addExact(installed, value.budgetBytes("flashReserveBytes", positive = true))
    val ram = Math.addExact(Math.addExact(staging, runtime), value.budgetBytes("ramReserveBytes", positive = true))
    require(value.budgetBytes("requiredFlashBytes", positive = true) == flash && value.budgetBytes("requiredRamBytes", positive = true) == ram)
    require(packageValues.fold(0L) { sum, item -> Math.addExact(sum, item.budgetBytes("archiveBytes", positive = true)) } == compressed &&
        packageValues.fold(0L) { sum, item -> Math.addExact(sum, item.budgetBytes("installedBytes")) } == installed)
    val rootPackages = value.installStrings("rootPackages").toSet()
    require(rootPackages.isNotEmpty() && rootPackages.all { name -> packageValues.any { it.installText("name") == name } } &&
        packageValues.filter { it.installText("name") !in rootPackages }.fold(0L) { sum, item ->
            Math.addExact(sum, item.budgetBytes("installedBytes"))
        } == value.budgetBytes("dependencyInstalledBytes"))
    val packages = packageValues.map { entry ->
        RouterPackageIdentity(entry.installText("name"), entry.installText("version"), entry.installText("file"), entry.installText("sha256"))
    }
    require(packages.size in 1..256 && packages.map { it.name }.distinct().size == packages.size &&
        packages.all { it.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,127}")) &&
            it.version.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_~:-]{0,159}")) &&
            it.file.matches(ROUTER_RELEASE_FILE) && it.sha256.matches(ROUTER_RELEASE_HASH) && manifest[it.file] == it.sha256 })
    require(packages.count { it.name in setOf("foxcore-cli", "foxcore-cli-ram") } == 1 &&
        packages.any { it.name == if (mode == RouterStorageMode.FLASH) "foxcore-cli" else "foxcore-cli-ram" })
    val corePackageName = if (mode == RouterStorageMode.FLASH) "foxcore-cli" else "foxcore-cli-ram"
    require(packages.single { it.name == corePackageName }.let {
        it.name in rootPackages && it.file == core.installText("file") && it.sha256 == core.installText("sha256")
    })
    require(packages.singleOrNull { it.name == "kernel" }?.version == system.kernelVersion)
    val serverAsset = variant.serverProducer
    if (serverAsset != null) {
        val server = value["serverPackage"] as? JsonObject ?: error("router_budget_invalid")
        val serverPackageName = if (mode == RouterStorageMode.FLASH) "hysteria" else "hysteria-server-ram"
        require(serverProducer != null && manifest[serverAsset.file] == serverAsset.sha256 &&
            value.installText("serverProducerSha256") == serverAsset.sha256 &&
            server.installText("producerSha256") == serverAsset.sha256 &&
            server.installText("binarySha256") == serverProducer.binarySha256 &&
            (server["payloadVerified"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true &&
            server.installText("file") != core.installText("file") &&
            packages.count { it.name in setOf("hysteria", "hysteria-server-ram") } == 1 &&
            packages.singleOrNull { it.name == serverPackageName }?.let {
                it.name in rootPackages && it.file == server.installText("file") && it.sha256 == server.installText("sha256")
            } == true)
    } else require(serverProducer == null && "serverPackage" !in value && "serverProducerSha256" !in value)
    val dependencyValues = value["volatileDependencies"]
    val volatileDependencies = if (dependencyValues == null) emptyList() else {
        (dependencyValues as? JsonArray ?: error("router_budget_invalid")).map { it as? JsonObject ?: error("router_budget_invalid") }
    }
    require(volatileDependencies.size <= 1)
    if (mode == RouterStorageMode.RAM) {
        val binary = value["volatileBinary"] as? JsonObject ?: error("router_budget_invalid")
        val coreBytes = binary.budgetBytes("bytes", positive = true)
        require(binary.installText("file").matches(ROUTER_RELEASE_FILE) && binary.installText("sha256") == variant.coreBinarySha256 &&
            manifest[binary.installText("file")] == variant.coreBinarySha256)
        require(volatileDependencies.size == if (serverAsset != null) 1 else 0)
        val extraBytes = volatileDependencies.fold(0L) { sum, dependency ->
            val extraProducer = requireNotNull(serverProducer)
            val extraAsset = requireNotNull(serverAsset)
            require(dependency.keys == setOf("file", "bytes", "sha256", "producerFile", "producerSha256") &&
                dependency.installText("file").matches(ROUTER_RELEASE_FILE) && dependency.installText("file") != binary.installText("file") &&
                dependency.installText("sha256") == extraProducer.binarySha256 &&
                dependency.budgetBytes("bytes", positive = true) == extraProducer.binaryBytes &&
                dependency.installText("producerFile") == extraAsset.file && dependency.installText("producerSha256") == extraAsset.sha256 &&
                manifest[dependency.installText("file")] == dependency.installText("sha256"))
            Math.addExact(sum, dependency.budgetBytes("bytes", positive = true))
        }
        require(volatile > 0 && Math.addExact(coreBytes, extraBytes) == volatile)
    } else require(volatileDependencies.isEmpty() && "volatileBinary" !in value)
    return RouterInstallBudget(flash, flash, ram, ram) to packages
}

private fun JsonObject.budgetBytes(key: String, positive: Boolean = false): Long {
    val value = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: error("router_budget_invalid")
    require(value in (if (positive) 1L else 0L)..9007199254740991L)
    return value
}
internal fun routerReleaseSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
private val ROUTER_RELEASE_HASH = Regex("[a-f0-9]{64}")
private val ROUTER_RELEASE_FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")

internal suspend fun <T> routerReleasePhase(block: suspend () -> T): T = try {
    withTimeout(120_000L) { block() }
} catch (_: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw IOException("router_release_phase_timeout")
}
