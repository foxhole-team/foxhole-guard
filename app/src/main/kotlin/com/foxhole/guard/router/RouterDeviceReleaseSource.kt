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

internal data class RouterDeviceRelease(val releaseTag: String, val version: String, val fileName: String,
    val sha256: String, val bytes: Long) {
    init {
        require(releaseTag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")) && version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+-r[1-9][0-9]*")))
        require(releaseTag == "v" + version.substringBefore("-r") && fileName == "foxhole-openwrt-client-$version.apk")
        require(sha256.matches(Regex("[a-f0-9]{64}")) && bytes in 4L..8388608L)
    }
}

internal class RouterDevicePackage(val release: RouterDeviceRelease, val file: File, private val directory: File) : AutoCloseable {
    override fun close() { file.delete(); directory.delete() }
}

internal class RouterDeviceReleaseSource(private val cache: File,
    private val http: RouterDeviceReleaseHttp = RouterDeviceReleaseHttp(),
    private val io: CoroutineDispatcher = Dispatchers.IO) {
    internal fun coreSource() = RouterCoreUpdateSource(cache, http, io)

    suspend fun downloadNewer(currentVersion: String, progress: (Int?) -> Unit): RouterDevicePackage? {
        val pending = AtomicReference<RouterDevicePackage?>()
        try {
            val result = withContext(io) {
                val metadata = http.read(ROUTER_DEVICE_RELEASE_METADATA.toHttpUrl(), 4194304)
                val candidate = parseRouterDeviceReleaseMetadata(metadata, currentVersion) ?: return@withContext null
                val base = "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/${candidate.tag}/"
                val index = http.read((base + "SHA256SUMS").toHttpUrl(), 262144)
                val entries = verifyRouterReleaseManifest(index, routerReleaseSha256(index))
                val release = RouterDeviceRelease(candidate.tag, candidate.version, candidate.name,
                    entries[candidate.name] ?: throw RouterUpdateFailure(RouterUpdateFailureReason.PACKAGE_VERIFICATION_FAILED), candidate.bytes)
                require(cache.isDirectory && !Files.isSymbolicLink(cache.toPath()))
                val directory = Files.createTempDirectory(cache.toPath(), "router-device-").toFile()
                val file = try { File.createTempFile("base-", ".apk", directory) }
                    catch (failure: Exception) { directory.delete(); throw failure }
                try {
                    require(file.setReadable(false, false) && file.setWritable(false, false) &&
                        file.setReadable(true, true) && file.setWritable(true, true))
                    http.download((base + release.fileName).toHttpUrl(), release.bytes, file) { completed, _ ->
                        progress((completed * 60 / release.bytes).toInt().coerceIn(0, 60))
                    }
                    verifyRouterDevicePackage(file, release)
                    RouterDevicePackage(release, file, directory).also { pending.set(it) }
                } catch (failure: Exception) { file.delete(); directory.delete(); throw failure }
            }
            pending.compareAndSet(result, null)
            return result
        } finally { pending.getAndSet(null)?.close() }
    }
}

internal data class RouterDeviceReleaseCandidate(val tag: String, val version: String, val name: String, val bytes: Long)

internal fun parseRouterDeviceReleaseMetadata(bytes: ByteArray, currentVersion: String): RouterDeviceReleaseCandidate? {
    require(bytes.size in 1..4194304)
    requireBoundedRouterJson(bytes, maxNodes = 500000)
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    val releases = Json.parseToJsonElement(text) as? JsonArray ?: invalidEngineResponse()
    require(releases.size in 1..10)
    val candidates = releases.mapNotNull { raw ->
        val release = raw as? JsonObject ?: invalidEngineResponse()
        if (release.engineBoolean("draft")) return@mapNotNull null
        release.engineBoolean("prerelease")
        val tag = release.engineText("tag_name")
        if (!tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) return@mapNotNull null
        val assets = release["assets"] as? JsonArray ?: invalidEngineResponse()
        require(assets.size <= 1024)
        val rows = assets.map { it as? JsonObject ?: invalidEngineResponse() }
        val base = rows.filter { it.engineText("name").matches(Regex("foxhole-openwrt-client-[0-9]+\\.[0-9]+\\.[0-9]+-r[1-9][0-9]*\\.apk")) }
        if (base.isEmpty()) return@mapNotNull null
        require(base.size == 1 && rows.count { it.engineText("name") == "SHA256SUMS" } == 1)
        val asset = base.single()
        val name = asset.engineText("name")
        val version = name.removePrefix("foxhole-openwrt-client-").removeSuffix(".apk")
        require(tag == "v" + version.substringBefore("-r"))
        for (entry in listOf(asset, rows.single { it.engineText("name") == "SHA256SUMS" })) {
            require(entry.engineText("browser_download_url", 512) ==
                "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/$tag/${entry.engineText("name")}")
        }
        val size = asset.engineNumber("size").also { require(it in 4L..8388608L) }
        RouterDeviceReleaseCandidate(tag, version, name, size)
    }
    require(candidates.map { it.tag }.distinct().size == candidates.size)
    val latest = candidates.maxWithOrNull { left, right -> compareRouterPackageVersions(left.version, right.version) }
        ?: throw RouterUpdateFailure(RouterUpdateFailureReason.RELEASE_UNAVAILABLE)
    return latest.takeIf { compareRouterPackageVersions(it.version, currentVersion) > 0 }
}

internal fun compareRouterPackageVersions(left: String, right: String): Int {
    val pattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-r([1-9][0-9]*))?")
    val a = pattern.matchEntire(left) ?: throw RouterUpdateFailure(RouterUpdateFailureReason.VERSION_IDENTITY_CONFLICT)
    val b = pattern.matchEntire(right) ?: throw RouterUpdateFailure(RouterUpdateFailureReason.VERSION_IDENTITY_CONFLICT)
    for (index in 1..3) {
        val av = a.groupValues[index].toLongOrNull() ?: invalidEngineResponse()
        val bv = b.groupValues[index].toLongOrNull() ?: invalidEngineResponse()
        if (av != bv) return av.compareTo(bv)
    }
    // A missing revision cannot prove that a same-version upload is newer.
    if (a.groupValues[4].isEmpty() || b.groupValues[4].isEmpty()) return 0
    val ar = a.groupValues[4].toLongOrNull() ?: invalidEngineResponse()
    val br = b.groupValues[4].toLongOrNull() ?: invalidEngineResponse()
    return ar.compareTo(br)
}

internal fun verifyRouterDevicePackage(file: File, release: RouterDeviceRelease) {
    require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == release.bytes)
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { source ->
        val header = ByteArray(4)
        require(source.read(header) == 4 && header.contentEquals(byteArrayOf(65, 68, 66, 100)))
        digest.update(header)
        val buffer = ByteArray(8192)
        try {
            while (true) { val count = source.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        } finally { buffer.fill(0) }
    }
    require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == release.sha256)
}
