package com.foxhole.guard.router

import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl

/** Source URLs are derived only from the already authenticated package/index relationship. */
internal fun routerBundlePackageSource(document: JsonObject, item: JsonObject, architecture: String): String {
    val file = item.compactFile("file")
    val auth = document.compactObjects("packageAuthentications", 1..512).single { it.compactFile("file") == file }
    require(auth.compactText("name") == item.compactText("name") &&
        auth.compactText("version") == item.compactText("version") && auth.compactText("architecture") == item.compactText("arch"))
    val index = document.compactObjects("repositoryIndexes", 1..16).single { it.compactFile("file") == auth.compactFile("indexFile") }
    val source = index.compactText("sourceUrl")
    val role = index.compactText("role")
    requireRouterRepositorySource(source, role, document, architecture)
    val name = item.compactText("name").also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_-]{0,127}"))) }
    val version = item.compactText("version").also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_~-]{0,159}"))) }
    if (role == "foxhole" && source.startsWith("https://github.com/foxhole-team/foxhole-core/"))
        requireRouterCoreModuleOrigin(source, name, version)
    return source.substringBeforeLast('/') + "/" + name + "-" + version + ".apk"
}

internal fun requireRouterUpstreamInstallPackage(url: HttpUrl) {
    require(url.isHttps && url.host == "downloads.openwrt.org" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
    val path = url.encodedPath
    require('%' !in path && path.split('/').none { it == "." || it == ".." })
    val parts = path.removePrefix("/").split('/')
    require(parts.size in 6..8 && parts[0] == "releases" && parts[1].matches(Regex("25\\.12\\.[0-9]+")))
    require(parts.last().matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_~-]{0,287}\\.apk")))
    val repository = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    val architectureRepository = parts.size == 6 && parts[2] == "packages" &&
        parts[3] in setOf("x86_64", "aarch64_generic", "aarch64_cortex-a53") && parts[4].matches(repository)
    val target = parts.getOrNull(3) + "/" + parts.getOrNull(4)
    val knownTarget = target in setOf("x86/64", "armsr/armv8", "mediatek/filogic")
    val targetRepository = parts.size == 7 && parts[2] == "targets" && knownTarget && parts[5] == "packages"
    val kernelRepository = parts.size == 8 && parts[2] == "targets" && knownTarget && parts[5] == "kmods" &&
        parts[6].matches(Regex("[A-Za-z0-9][A-Za-z0-9.+_~-]{0,159}"))
    require(architectureRepository || targetRepository || kernelRepository)
}
