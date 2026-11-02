package com.foxhole.guard.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

internal data class RouterBundleAsset(val name: String, val sha256: String, val bytes: Long, val contents: ByteArray? = null) {
    init { require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")) && sha256.matches(Regex("[a-f0-9]{64}")) && bytes in 1L..536870912L) }
}
internal data class RouterInstallBundlePlan(val baseUrl: String, val manifestSha256: String, val assets: List<RouterBundleAsset>) {
    val bytes: Long = assets.fold(0L) { sum, item -> Math.addExact(sum, item.bytes) }
    val stagingBytes: Long = Math.addExact(bytes, Math.multiplyExact(assets.size.toLong() + 2, 8192L))
    init {
        routerReleaseBaseUrl(baseUrl)
        require(assets.size in 1..1024 && assets.map { it.name }.distinct().size == assets.size && bytes in 1L..536870912L)
        require(assets.single { it.name == "SHA256SUMS" }.sha256 == manifestSha256)
    }
}
internal class RouterDeviceInstallBundle(val plan: RouterInstallBundlePlan, val directory: File) : AutoCloseable {
    fun file(asset: RouterBundleAsset): File = File(directory, asset.name)
    override fun close() { plan.assets.forEach { file(it).delete() }; directory.delete() }
}
internal data class RouterBundleTransferFacts(val bootId: String, val availableRamBytes: Long, val availableTmpBytes: Long)
internal fun parseRouterBundleTransferFacts(bytes: ByteArray): RouterBundleTransferFacts {
    require(bytes.size in 1..256)
    val lines = bytes.toString(Charsets.US_ASCII).trim().lines()
    require(lines.size == 3 && lines[0].matches(Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
    val ram = lines[1].toLongOrNull()?.takeIf { it > 0 } ?: throw RouterSetupFailure(RouterWizardError.STORAGE)
    val tmp = lines[2].toLongOrNull()?.takeIf { it > 0 } ?: throw RouterSetupFailure(RouterWizardError.STORAGE)
    return RouterBundleTransferFacts(lines[0], Math.multiplyExact(ram, 1024L), Math.multiplyExact(tmp, 1024L))
}

internal class RouterDeviceInstallBundleProvider(private val cache: File,
    private val http: RouterDeviceReleaseHttp = RouterDeviceReleaseHttp()) {
    suspend fun prepare(release: RouterSetupRelease, payload: RouterSetupPayload, proposal: RouterInstallProposal,
        facts: RouterBundleTransferFacts, workingRamBytes: Long,
        progress: (RouterInstallProgress) -> Unit): RouterDeviceInstallBundle {
        val pending = AtomicReference<RouterDeviceInstallBundle?>()
        try {
            val result = withContext(Dispatchers.IO) {
                val plan = release.bundlePlan(payload, proposal)
                val needed = Math.addExact(workingRamBytes, plan.stagingBytes)
                if (facts.availableRamBytes < needed || facts.availableTmpBytes < needed ||
                    cache.usableSpace < Math.addExact(plan.bytes, 16777216L)) throw RouterSetupFailure(RouterWizardError.STORAGE)
                require(cache.isDirectory && !Files.isSymbolicLink(cache.toPath()))
                val directory = Files.createTempDirectory(cache.toPath(), "router-install-").toFile()
                val bundle = RouterDeviceInstallBundle(plan, directory)
                pending.set(bundle)
                var completed = 0L
                for (asset in plan.assets) {
                    val file = bundle.file(asset)
                    require(file.createNewFile() && file.setReadable(false, false) && file.setWritable(false, false) &&
                        file.setReadable(true, true) && file.setWritable(true, true))
                    if (asset.contents != null) file.outputStream().use { it.write(asset.contents) }
                    else http.downloadInstallAsset((plan.baseUrl.trimEnd('/') + "/" + asset.name).toHttpUrl(), asset.bytes, file) { count, _ ->
                        progress(RouterInstallProgress(RouterInstallProgressStage.DOWNLOAD, completed + count, plan.bytes, viaDevice = true))
                    }
                    verifyRouterBundleAsset(file, asset)
                    completed = Math.addExact(completed, asset.bytes)
                    progress(RouterInstallProgress(RouterInstallProgressStage.DOWNLOAD, completed, plan.bytes, viaDevice = true))
                }
                bundle
            }
            pending.compareAndSet(result, null)
            return result
        } finally { pending.getAndSet(null)?.close() }
    }
}

internal fun verifyRouterBundleAsset(file: File, asset: RouterBundleAsset) {
    require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == asset.bytes)
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { source ->
        val buffer = ByteArray(65536)
        try { while (true) { val count = source.read(buffer); if (count < 0) break; require(count > 0); digest.update(buffer, 0, count) } }
        finally { buffer.fill(0) }
    }
    require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == asset.sha256)
}

internal suspend fun buildRouterInstallBundlePlan(catalog: RouterInstallCatalog, payload: RouterSetupPayload,
    proposal: RouterInstallProposal, document: JsonObject, manifest: Map<String, String>,
    read: suspend (String, Int) -> ByteArray): RouterInstallBundlePlan {
    val result = linkedMapOf<String, RouterBundleAsset>()
    suspend fun metadata(name: String, maximum: Int = 2097152) {
        if (name in result) return
        val contents = read(name, maximum)
        val hash = if (name == "SHA256SUMS") proposal.manifestSha256 else requireNotNull(manifest[name])
        require(contents.size in 1..maximum && routerReleaseSha256(contents) == hash)
        result[name] = RouterBundleAsset(name, hash, contents.size.toLong(), contents)
    }
    fun artifact(item: JsonObject, bytesField: String, maximum: Long) {
        val name = item.compactFile("file"); val hash = item.compactHash("sha256"); val bytes = item.compactLong(bytesField, 1L..maximum)
        require(manifest[name] == hash)
        val asset = RouterBundleAsset(name, hash, bytes)
        val prior = result[name]
        require(prior == null || prior.sha256 == hash && prior.bytes == bytes)
        if (prior == null) result[name] = asset
    }
    metadata("SHA256SUMS", 1048576); metadata("package-catalog.json", 524288); metadata("install.sh", 1048576)
    val scripts = if (catalog.schema == 2) listOf("install-compact.sh", "install-plan.uc", "install-plan-model.uc", "install-compact-plan.uc",
        "engine-package-layout.uc", "register-compact-engines.uc", "indexed-install.uc")
        else listOf("install-core.sh", "install-plan.uc", "install-plan-model.uc", "indexed-install.uc")
    for (name in scripts) metadata(name)
    metadata("foxhole-openwrt-apk.pem", 16384)
    if (catalog.schema == 2) {
        val platform = requireNotNull(proposal.binding.compact).platform
        metadata(platform.engineCatalog.file, 524288); metadata(platform.packageUniverse.file)
        for (client in platform.clients.filter { it.protocol in proposal.binding.selection.protocols }) {
            metadata(client.producer.file, 131072); metadata(client.capabilities.file, 131072); metadata(client.slotManifest.file, 131072)
        }
        if (proposal.binding.selection.includeServer) metadata(requireNotNull(platform.server).producer.file, 131072)
    } else {
        val variant = requireNotNull(payload.variant)
        metadata(variant.coreProducer.file, 131072); metadata(variant.capabilities.file, 131072)
        variant.serverProducer?.let { metadata(it.file, 131072) }
        for (mode in variant.modes.values) metadata(mode.file, 1048576)
    }
    for (item in document.engineObjects("packages", 512)) artifact(item, "archiveBytes", 536870912)
    for (item in document.engineObjects("repositoryIndexes", 16)) artifact(item, "bytes", 8388608)
    for (item in document.engineObjects("trustKeys", 32)) artifact(item, "bytes", 16384)
    if (catalog.schema == 2) for (item in document.engineObjects("volatile", 16)) artifact(item, "bytes", 67108864)
    else {
        document.engineNullableObject("volatileBinary")?.let { artifact(it, "bytes", 67108864) }
        if ("volatileDependencies" in document) for (item in document.engineObjects("volatileDependencies", 16)) artifact(item, "bytes", 67108864)
    }
    val selected = requireNotNull(payload.packagesByMode[proposal.storageMode])
    require(selected.all { result[it.file]?.sha256 == it.sha256 } && selected.any { it.name == "foxhole-openwrt-client" })
    val expected = requireNotNull(document.engineNullableObject("filesSha256"))
    require(expected.keys.all { result[it]?.sha256 == expected.engineText(it) })
    require(result.values.sumOf { it.contents?.size?.toLong() ?: 0L } <= 16777216L)
    return RouterInstallBundlePlan(payload.baseUrl, proposal.manifestSha256, result.values.toList())
}
