package com.foxhole.guard.router

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI

internal data class RouterRepositoryBytes(val indexes: Long, val keys: Long)

private val REPOSITORY_FILE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
private val REPOSITORY_HASH = Regex("[a-f0-9]{64}")
private const val PUBLIC_SIGNING_FINGERPRINT = "fa220a28fd8662fc5f617f46594bafe2415b4f605136570dc3c446caeb7d1ef1"
private const val CANONICAL_REPOSITORY_KEY_FILE = "foxhole-openwrt-apk.pem"
private val OWN_PACKAGES = setOf("foxcore-cli", "foxcore-cli-ram", "foxhole-openwrt-client", "hysteria", "hysteria-server-ram")

internal fun verifyRouterRepositoryAuthentication(value: JsonObject, packageValues: List<JsonObject>,
    manifest: Map<String, String>, architecture: String): RouterRepositoryBytes =
    verifyRouterRepositoryAuthenticationFor(value, packageValues, manifest, architecture, OWN_PACKAGES, 256)

internal fun verifyRouterCompactRepositoryAuthentication(value: JsonObject, packageValues: List<JsonObject>,
    manifest: Map<String, String>, architecture: String, exactOwnPackages: Set<String>): RouterRepositoryBytes {
    require(value.compactLong("schema") == 2L && value.compactText("kind") == "compact-selected-budget-v1" &&
        value.compactStrings("rootPackages", 2..14).toSet() == exactOwnPackages && "foxhole-openwrt-client" in exactOwnPackages)
    return verifyRouterRepositoryAuthenticationFor(value, packageValues, manifest, architecture, exactOwnPackages, 512)
}

private fun verifyRouterRepositoryAuthenticationFor(value: JsonObject, packageValues: List<JsonObject>,
    manifest: Map<String, String>, architecture: String, ownPackages: Set<String>, maximumPackages: Int): RouterRepositoryBytes {
    val verification = value["verification"] as? JsonObject ?: repositoryInvalid()
    require(verification.repositoryText("signatureModel") == "canonical-apk-and-upstream-index-v1" &&
        verification.repositoryText("factorySourceSha256").matches(REPOSITORY_HASH))
    require(architecture in setOf("x86_64", "aarch64_generic", "aarch64_cortex-a53"))
    require(packageValues.size in 1..maximumPackages)
    val packages = packageValues.associateBy { it.repositoryText("file").also { name -> require(name.matches(REPOSITORY_FILE) && name.endsWith(".apk")) } }
    require(packages.size == packageValues.size)
    packageValues.forEach { packageValue ->
        require(packageValue.repositoryText("arch") in setOf(architecture, "noarch") &&
            packageValue.repositoryText("name").matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,127}")) &&
            packageValue.repositoryText("version").matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_~-]{0,159}")) &&
            packageValue.repositoryText("sha256").matches(REPOSITORY_HASH) &&
            manifest[packageValue.repositoryText("file")] == packageValue.repositoryText("sha256"))
    }
    val keyValues = value.repositoryObjects("trustKeys", 1..32)
    val keys = keyValues.associateBy { key ->
        require(key.keys == setOf("file", "sha256", "bytes"))
        key.repositoryText("file").also { require(it.matches(REPOSITORY_FILE) && it !in packages) }
    }
    require(keys.size == keyValues.size && keys.values.map { it.repositoryText("sha256") }.distinct().size == keys.size)
    keys.forEach { (file, key) ->
        require(key.repositoryText("sha256").matches(REPOSITORY_HASH) && manifest[file] == key.repositoryText("sha256"))
        key.repositoryBytes("bytes", 16384)
    }
    val indexValues = value.repositoryObjects("repositoryIndexes", 1..16)
    val roles = mutableMapOf<String, String>()
    val indexes = indexValues.associateBy { index ->
        require(index.keys == setOf("file", "sha256", "bytes", "role", "sourceUrl", "trustKeyFiles"))
        val file = index.repositoryText("file")
        require(file.matches(REPOSITORY_FILE) && file !in packages && file !in keys)
        val role = index.repositoryText("role")
        require(role in setOf("foxhole", "upstream"))
        repositorySource(index.repositoryText("sourceUrl"), role, value, architecture)
        val trusted = index.repositoryStrings("trustKeyFiles", 1..16)
        require(trusted.toSet().size == trusted.size)
        if (role == "foxhole") require(trusted == listOf(CANONICAL_REPOSITORY_KEY_FILE))
        trusted.forEach { keyFile ->
            val key = keys[keyFile] ?: repositoryInvalid()
            require(roles.putIfAbsent(keyFile, role).let { it == null || it == role })
            require((key.repositoryText("sha256") == PUBLIC_SIGNING_FINGERPRINT) == (role == "foxhole"))
        }
        require(index.repositoryText("sha256").matches(REPOSITORY_HASH) && manifest[file] == index.repositoryText("sha256"))
        index.repositoryBytes("bytes", 8L * 1024 * 1024)
        file
    }
    require(indexes.size == indexValues.size && roles.keys == keys.keys)
    val authentications = value.repositoryObjects("packageAuthentications", 1..maximumPackages)
    val authenticated = authentications.associateBy { authentication ->
        require(authentication.keys == setOf("file", "indexFile", "name", "version", "architecture", "packageIdentity"))
        val file = authentication.repositoryText("file")
        val packageValue = packages[file] ?: repositoryInvalid()
        val index = indexes[authentication.repositoryText("indexFile")] ?: repositoryInvalid()
        require(authentication.repositoryText("name") == packageValue.repositoryText("name") &&
            authentication.repositoryText("version") == packageValue.repositoryText("version") &&
            authentication.repositoryText("architecture") == packageValue.repositoryText("arch") &&
            authentication.repositoryText("packageIdentity").matches(REPOSITORY_HASH))
        val expectedRole = if (packageValue.repositoryText("name") in ownPackages) "foxhole" else "upstream"
        require(index.repositoryText("role") == expectedRole)
        file
    }
    require(authenticated.size == authentications.size && authenticated.keys == packages.keys)
    val expectedFiles = packages.mapValues { it.value.repositoryText("sha256") } +
        indexes.mapValues { it.value.repositoryText("sha256") } + keys.mapValues { it.value.repositoryText("sha256") }
    val declared = value["filesSha256"] as? JsonObject ?: repositoryInvalid()
    require(declared.keys == expectedFiles.keys && declared.all { (file, hash) ->
        (hash as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull == expectedFiles[file] && manifest[file] == expectedFiles[file]
    })
    val indexBytes = indexes.values.sumOf { it.repositoryBytes("bytes", 8L * 1024 * 1024) }
    val keyBytes = keys.values.sumOf { it.repositoryBytes("bytes", 16384) }
    require(value.repositoryBytes("repositoryIndexBytes", 16L * 8 * 1024 * 1024) == indexBytes &&
        value.repositoryBytes("trustKeyBytes", 32L * 16384) == keyBytes)
    // The native builder authenticates signed entries; this binds its metadata to the pinned release assets.
    return RouterRepositoryBytes(indexBytes, keyBytes)
}

private fun repositorySource(raw: String, role: String, value: JsonObject, architecture: String) {
    require(raw.length in 1..4096 && raw.none(Char::isISOControl))
    val uri = try { URI(raw) } catch (_: Exception) { repositoryInvalid() }
    val path = uri.rawPath ?: repositoryInvalid()
    require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null &&
        !uri.isOpaque && '%' !in path && path.split('/').none { it == "." || it == ".." })
    if (role == "foxhole") {
        require(uri.host == "github.com" && uri.rawPath.matches(Regex("/foxhole-team/foxhole-openwrt-client/releases/download/v[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?/[A-Za-z0-9][A-Za-z0-9._-]{0,159}\\.adb")))
    } else {
        require(uri.host == "downloads.openwrt.org")
        val parts = uri.rawPath.removePrefix("/").split('/')
        val versions = value.repositoryStrings("supportedOpenWrt", 1..32)
        val targets = value.repositoryStrings("supportedTargets", 1..16)
        require(versions.all { it.matches(Regex("25\\.12\\.[0-9]+")) } &&
            targets.all { it in setOf("x86/64", "armsr/armv8", "mediatek/filogic") })
        require(parts.size in 6..8 && parts[0] == "releases" && parts[1] in versions &&
            parts.last() == "packages.adb")
        val architectureRepo = parts.size == 6 && parts[2] == "packages" && parts[3] == architecture && parts[4].matches(REPOSITORY_FILE)
        val target = parts.getOrNull(3) + "/" + parts.getOrNull(4)
        val targetKnown = target in targets
        val targetRepo = parts.size == 7 && parts[2] == "targets" && targetKnown && parts[5] == "packages"
        val kernelRepo = parts.size == 8 && parts[2] == "targets" && targetKnown && parts[5] == "kmods" && parts[6].matches(REPOSITORY_FILE)
        require(architectureRepo || targetRepo || kernelRepo)
    }
}

private fun JsonObject.repositoryText(field: String): String =
    (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: repositoryInvalid()

private fun JsonObject.repositoryBytes(field: String, maximum: Long): Long {
    val number = (this[field] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: repositoryInvalid()
    require(number in 1..maximum)
    return number
}

private fun JsonObject.repositoryObjects(field: String, bounds: IntRange): List<JsonObject> {
    val array = this[field] as? JsonArray ?: repositoryInvalid()
    require(array.size in bounds)
    return array.map { it as? JsonObject ?: repositoryInvalid() }
}

private fun JsonObject.repositoryStrings(field: String, bounds: IntRange): List<String> {
    val array = this[field] as? JsonArray ?: repositoryInvalid()
    require(array.size in bounds)
    return array.map { (it as? JsonPrimitive)?.takeIf { text -> text.isString }?.contentOrNull ?: repositoryInvalid() }
}

private fun repositoryInvalid(): Nothing = throw IllegalArgumentException("router_repository_authentication_invalid")
