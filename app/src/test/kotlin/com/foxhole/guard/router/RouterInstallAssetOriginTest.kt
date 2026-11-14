package com.foxhole.guard.router

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class RouterInstallAssetOriginTest {
    private val cacheFile = "apk-${"a".repeat(64)}.apk"
    private fun item(name: String = "ca-bundle", version: String = "20260816-r1") = json(
        """{"file":"$cacheFile","name":"$name","version":"$version","arch":"noarch"}""")
    private fun document(source: String, role: String = "upstream", name: String = "ca-bundle", version: String = "20260816-r1") = json(
        """{"supportedOpenWrt":["25.12.5"],"supportedTargets":["x86/64"],"packageAuthentications":[{"file":"$cacheFile","name":"$name","version":"$version","architecture":"noarch","indexFile":"index.adb"}],"repositoryIndexes":[{"file":"index.adb","role":"$role","sourceUrl":"$source"}]}""")

    @Test fun upstreamCacheNameResolvesThroughItsAuthenticatedIndex() {
        val source = "https://downloads.openwrt.org/releases/25.12.5/targets/x86/64/packages/packages.adb"
        val url = routerBundlePackageSource(document(source), item(), "x86_64")
        assertEquals(source.substringBeforeLast('/') + "/ca-bundle-20260816-r1.apk", url)
        assertFalse(url.contains(cacheFile))
        val asset = RouterBundleAsset(cacheFile, "a".repeat(64), 4, sourceUrl = url)
        assertEquals(cacheFile, asset.name)
        assertEquals("a".repeat(64), asset.sha256)
        assertEquals(url, asset.sourceUrl)
    }

    @Test fun ownBasePackageUsesTheAuthenticatedReleaseOrigin() {
        val source = "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.6/base.adb"
        assertEquals(source.substringBeforeLast('/') + "/foxhole-openwrt-client-1.0.6-r1.apk",
            routerBundlePackageSource(document(source, "foxhole", "foxhole-openwrt-client", "1.0.6-r1"),
                item("foxhole-openwrt-client", "1.0.6-r1"), "x86_64"))
    }

    @Test fun sourceIdentityAndRepositoryScopeCannotBeSubstituted() {
        val source = "https://downloads.openwrt.org/releases/25.12.5/targets/x86/64/packages/packages.adb"
        assertThrows(IllegalArgumentException::class.java) {
            routerBundlePackageSource(document(source, version = "20260817-r1"), item(), "x86_64")
        }
        for (changed in listOf(source.replace("25.12.5", "25.12.4"), source.replace("downloads.openwrt.org", "example.com"),
            source + "?key=value", source + "#fragment", source.replace("/x86/64/", "/mediatek/filogic/"))) {
            assertThrows(IllegalArgumentException::class.java) { routerBundlePackageSource(document(changed), item(), "x86_64") }
        }
        assertThrows(IllegalArgumentException::class.java) { routerBundlePackageSource(document(source, "unknown"), item(), "x86_64") }
    }

    @Test fun upstreamInstallUrlsHaveClosedVersionArchitectureAndTargetPaths() {
        val accepted = listOf(
            "https://downloads.openwrt.org/releases/25.12.5/targets/x86/64/packages/ca-bundle-20260816-r1.apk",
            "https://downloads.openwrt.org/releases/25.12.5/packages/aarch64_cortex-a53/base/libc-1.2.5-r4.apk",
            "https://downloads.openwrt.org/releases/25.12.5/targets/mediatek/filogic/kmods/6.12.94-1-abc/kmod-tun-6.12.94-r1.apk")
        accepted.forEach { requireRouterUpstreamInstallPackage(it.toHttpUrl()) }
        for (url in listOf(accepted[0].replace("https:", "http:"), accepted[0] + "?token=x", accepted[0] + "#x",
            accepted[0].replace("25.12.5", "snapshots"), accepted[0].replace("downloads.openwrt.org", "127.0.0.1"),
            accepted[0].replace("/x86/64/", "/unknown/board/"), accepted[0].replace(".apk", ".pem"))) {
            assertThrows(IllegalArgumentException::class.java) { requireRouterUpstreamInstallPackage(url.toHttpUrl()) }
        }
    }

    @Test fun upstreamAccessIsRestrictedToInstallDownloadsAndRetainsThePayloadHash() = runBlocking {
        val bytes = "TEST".toByteArray()
        val url = "https://downloads.openwrt.org/releases/25.12.5/targets/x86/64/packages/ca-bundle-20260816-r1.apk".toHttpUrl()
        var calls = 0
        val http = RouterDeviceReleaseHttp(Call.Factory { request ->
            calls++
            assertEquals(url, request.url)
            FixtureCall(request, bytes)
        })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { http.read(url, 1024) } }
        assertEquals(0, calls)
        val folder = Files.createTempDirectory("router-origin-unit-").toFile()
        try {
            val target = folder.resolve(cacheFile)
            http.downloadInstallAsset(url, bytes.size.toLong(), target) { _, _ -> }
            assertEquals(1, calls)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            verifyRouterBundleAsset(target, RouterBundleAsset(cacheFile, hash, bytes.size.toLong(), sourceUrl = url.toString()))
            assertEquals(cacheFile, target.name)
        } finally { folder.deleteRecursively() }
    }

    private class FixtureCall(private val value: Request, private val bytes: ByteArray) : Call by OkHttpClient().newCall(value) {
        private var cancelled = false
        override fun request() = value
        override fun execute(): Response = response()
        override fun enqueue(responseCallback: Callback) { responseCallback.onResponse(this, response()) }
        private fun response() = Response.Builder().request(value).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(bytes.toResponseBody()).build()
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
        override fun isExecuted() = true
        override fun timeout() = Timeout()
        override fun clone(): Call = FixtureCall(value, bytes)
    }

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
}
