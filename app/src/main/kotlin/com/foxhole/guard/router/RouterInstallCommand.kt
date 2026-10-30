package com.foxhole.guard.router

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal class RouterVerifiedInstallScript(bytes: ByteArray, expectedSha256: String) {
    private val contents = bytes.copyOf()
    init {
        require(expectedSha256.matches(Regex("[a-f0-9]{64}")) && contents.size in 1..1048576)
        val actual = MessageDigest.getInstance("SHA-256").digest(contents)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(actual == expectedSha256)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(contents)).toString()
        require('\u0000' !in text)
    }
    fun bytes(): ByteArray = contents.copyOf()
}

internal sealed class RouterInstallCommandSource {
    internal abstract fun arguments(): List<String>

    private class Github(baseUrl: String) : RouterInstallCommandSource() {
        private val url = routerReleaseBaseUrl(baseUrl).toString().trimEnd('/')
        override fun arguments(): List<String> = listOf("--base-url", url)
    }
    companion object {
        fun github(baseUrl: String): RouterInstallCommandSource = Github(baseUrl)
    }
}

internal class RouterInstallCommand(
    source: RouterInstallCommandSource,
    manifestSha256: String,
    mode: RouterStorageMode,
    protocols: Set<RouterInstallProtocol>,
    includeServer: Boolean = false,
    licenseAccepted: Boolean = false,
) {
    constructor(baseUrl: String, manifestSha256: String, mode: RouterStorageMode, protocols: Set<RouterInstallProtocol>,
        includeServer: Boolean = false, licenseAccepted: Boolean = false) :
        this(RouterInstallCommandSource.github(baseUrl), manifestSha256, mode, protocols, includeServer, licenseAccepted)

    val command: String
    init {
        requireRouterSetupLicense(licenseAccepted)
        require(manifestSha256.matches(Regex("[a-f0-9]{64}")) && protocols.isNotEmpty())
        val origin = source.arguments()
        require(origin.size == 2 && origin[0] in setOf("--base-url", "--bundle") && origin[1].isNotEmpty() && origin[1].none(Char::isISOControl))
        val arguments = origin + listOf("--manifest-sha256", manifestSha256,
            if (mode == RouterStorageMode.FLASH) "--flash" else "--ram") +
            RouterInstallProtocol.entries.filter { it in protocols }.map {
                when (it) {
                    RouterInstallProtocol.HYSTERIA2 -> "--hy2"
                    RouterInstallProtocol.WIREGUARD -> "--wg"
                    else -> "--${it.canonical}"
                }
            } + (if (includeServer) listOf("--server") else emptyList()) + listOf("--apply", "--accept-license")
        command = "sh -s -- " + arguments.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
    }
}

internal fun routerReleaseBaseUrl(value: String): HttpUrl {
    val url = requireNotNull(value.toHttpUrlOrNull())
    require(url.isHttps && url.host == "github.com" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
    require(url.encodedPath.matches(Regex("/foxhole-team/foxhole-openwrt-client/releases/download/[A-Za-z0-9._-]+/?")))
    return url
}
