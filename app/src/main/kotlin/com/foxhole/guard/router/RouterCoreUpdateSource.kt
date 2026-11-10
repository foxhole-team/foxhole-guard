package com.foxhole.guard.router

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

internal const val ROUTER_CORE_RELEASE_METADATA = "https://api.github.com/repos/foxhole-team/foxhole-core/releases/latest"
internal enum class RouterCoreAssetKind { CATALOG_INDEX, CATALOG_APK, INDEX, PACKAGE, BINARY }
internal data class RouterCoreAsset(val id: String, val sha256: String?, val bytes: Long, val url: String,
    val kind: RouterCoreAssetKind) {
    init {
        requireRouterCoreAssetId(id)
        require(bytes in 1..if (id.startsWith("catalog-")) 1048576L else 134217728L)
        require(sha256?.matches(Regex("[a-f0-9]{64}")) ?: id.startsWith("catalog-"))
        require(id == "catalog-index" && kind == RouterCoreAssetKind.CATALOG_INDEX ||
            id == "catalog-apk" && kind == RouterCoreAssetKind.CATALOG_APK ||
            id == "asset-$sha256" && kind in setOf(RouterCoreAssetKind.INDEX, RouterCoreAssetKind.PACKAGE, RouterCoreAssetKind.BINARY))
        requireRouterCoreAssetUrl(url)
    }
}
internal fun requireRouterCoreAssetId(id: String) {
    require(id.matches(Regex("catalog-(index|apk)|asset-[a-f0-9]{64}")))
}
internal fun requireRouterCoreAssetUrl(value: String) {
    val url = value.toHttpUrl()
    require(url.isHttps && url.port == 443 && url.host == "github.com" && url.query == null && url.fragment == null &&
        url.username.isEmpty() && url.password.isEmpty() && url.encodedPath.matches(Regex(
            "/foxhole-team/(foxhole-core|foxhole-openwrt-client)/releases/download/v[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}/[A-Za-z0-9][A-Za-z0-9._-]{0,254}")))
}
internal data class RouterCoreRelease(val tag: String, val version: String, val index: RouterCoreAsset)
internal data class RouterCoreUpload(val uploadId: String, val assetId: String?, val nextOffset: Long,
    val phase: String, val requiredAssets: List<RouterCoreAsset>, val cohortSha256: String?, val status: RouterUpdateStatus?)
internal fun parseRouterCoreUpload(value: JsonObject): RouterCoreUpload {
    value.engineSchema()
    if (value.engineText("cohortKind") != "CORE" || value.engineNumber("maxChunkBytes") != 65536L) invalidEngineResponse()
    val uploadId = value.engineText("uploadId").also(::requireRouterDeviceUploadId)
    val assetId = value.engineNullableText("assetId")?.also(::requireRouterCoreAssetId)
    val offset = value.engineNumber("nextOffset")
    val phase = value.engineText("phase")
    if (phase !in setOf("RECEIVING", "VERIFYING", "WAITING", "STAGED")) invalidEngineResponse()
    val assets = value.engineObjects("requiredAssets", 16).map { item ->
        RouterCoreAsset(item.engineText("id"), item.engineNullableText("sha256"), item.engineNumber("bytes"),
            item.engineText("url", 1024), item.engineEnum("kind"))
    }
    if (assets.any { it.sha256 == null && it.id != "catalog-apk" }) invalidEngineResponse()
    if (assets.map { it.id }.distinct().size != assets.size ||
        assetId == null && offset != 0L || assetId != null && assets.singleOrNull { it.id == assetId }?.let { offset <= it.bytes } != true ||
        phase in setOf("WAITING", "STAGED") && assetId != null || phase == "STAGED" && assets.isNotEmpty()) invalidEngineResponse()
    val cohort = value.engineNullableText("cohortSha256")?.also { if (!it.matches(Regex("[a-f0-9]{64}"))) invalidEngineResponse() }
    val status = value.engineNullableObject("status")?.let(::parseRouterUpdateStatus)
    if (phase == "STAGED" && (cohort == null || status == null) || phase != "STAGED" && status != null) invalidEngineResponse()
    return RouterCoreUpload(uploadId, assetId, offset, phase, assets, cohort, status)
}
internal fun parseRouterCoreRelease(bytes: ByteArray): RouterCoreRelease {
    require(bytes.size in 1..4194304)
    requireBoundedRouterJson(bytes, maxNodes = 500000)
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    val release = Json.parseToJsonElement(text) as? JsonObject ?: invalidEngineResponse()
    require(!release.engineBoolean("draft") && !release.engineBoolean("prerelease"))
    val tag = release.engineText("tag_name")
    require(tag.matches(Regex("v[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")))
    val rows = release["assets"] as? JsonArray ?: invalidEngineResponse()
    require(rows.size <= 1024)
    val indices = rows.map { it as? JsonObject ?: invalidEngineResponse() }.filter { it.engineText("name") == "core-catalog.adb" }
    if (indices.size != 1) throw RouterUpdateFailure(RouterUpdateFailureReason.RELEASE_UNAVAILABLE)
    val item = indices.single()
    val url = "https://github.com/foxhole-team/foxhole-core/releases/download/$tag/core-catalog.adb"
    require(item.engineText("browser_download_url", 1024) == url)
    // The router authenticates this index with its pinned native APK key.
    return RouterCoreRelease(tag, tag.removePrefix("v"), RouterCoreAsset("catalog-index", null,
        item.engineNumber("size"), url, RouterCoreAssetKind.CATALOG_INDEX))
}

internal class RouterCoreDownload(val asset: RouterCoreAsset, val file: File, val sha256: String,
    private val directory: File) : AutoCloseable {
    override fun close() { file.delete(); directory.delete() }
}
internal class RouterCoreUpdateSource(private val cache: File, private val http: RouterDeviceReleaseHttp,
    private val io: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun latest(): RouterCoreRelease = withContext(io) { parseRouterCoreRelease(http.read(ROUTER_CORE_RELEASE_METADATA.toHttpUrl(), 4194304)) }
    suspend fun download(asset: RouterCoreAsset, discoveryIndex: Boolean = false, progress: (Int?) -> Unit): RouterCoreDownload {
        require(!discoveryIndex || asset.id == "catalog-index")
        val pending = AtomicReference<RouterCoreDownload?>()
        try {
            val result = withContext(io) {
                require(cache.isDirectory && !Files.isSymbolicLink(cache.toPath()))
                val directory = Files.createTempDirectory(cache.toPath(), "router-core-").toFile()
                val file = try { File.createTempFile("asset-", ".bin", directory) }
                    catch (failure: Exception) { directory.delete(); throw failure }
                try {
                    require(file.setReadable(false, false) && file.setWritable(false, false) &&
                        file.setReadable(true, true) && file.setWritable(true, true))
                    http.downloadInstallAsset(asset.url.toHttpUrl(), asset.bytes, file) { done, _ ->
                        progress((done * 90 / asset.bytes).toInt().coerceIn(0, 90))
                    }
                    val actual = verifyRouterCoreDownload(file, asset, discoveryIndex)
                    RouterCoreDownload(asset, file, actual, directory).also { pending.set(it) }
                } catch (failure: Exception) { file.delete(); directory.delete(); throw failure }
            }
            pending.compareAndSet(result, null); return result
        } finally { pending.getAndSet(null)?.close() }
    }
}
internal fun verifyRouterCoreDownload(file: File, asset: RouterCoreAsset, discoveryIndex: Boolean = false): String {
    require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == asset.bytes)
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val header = ByteArray(4)
        require(input.read(header) == 4 && header.contentEquals(if (asset.kind == RouterCoreAssetKind.BINARY)
            byteArrayOf(127, 69, 76, 70) else byteArrayOf(65, 68, 66, 100)))
        digest.update(header)
        val buffer = ByteArray(8192)
        try { while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        finally { buffer.fill(0) }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    require(discoveryIndex && asset.id == "catalog-index" || asset.sha256 == null && asset.id == "catalog-apk" || actual == asset.sha256)
    return actual
}
