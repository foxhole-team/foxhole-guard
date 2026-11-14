package com.foxhole.guard.router

import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import javax.net.ssl.SSLHandshakeException

class RouterInstallMirrorFallbackTest {
    private val bytes = "TEST".toByteArray()
    private val base = "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v1.0.6"
    private val original = "https://downloads.openwrt.org/releases/25.12.5/packages/x86_64/base/ca-bundle-20260816-r1.apk"
    private val name = "apk-${sha(bytes)}.apk"

    @Test fun availableImmutableMirrorDoesNotRequestMutableUpstream() = withPlan { plan, asset, target ->
        val urls = mutableListOf<String>()
        val http = RouterDeviceReleaseHttp(Call.Factory { request ->
            urls += request.url.toString(); ReplyCall(request, 200, bytes)
        })
        downloadRouterInstallAsset(http, plan, asset, target) { _, _ -> }
        verifyRouterBundleAsset(target, asset)
        assertEquals(listOf("$base/$name"), urls)
        assertEquals(name, target.name)
    }

    @Test fun onlyMissingMirrorUsesTheAuthenticatedOriginalAndSameHash() = withPlan { plan, asset, target ->
        val urls = mutableListOf<String>()
        val http = RouterDeviceReleaseHttp(Call.Factory { request ->
            urls += request.url.toString()
            if (urls.size == 1) ReplyCall(request, 404, byteArrayOf()) else ReplyCall(request, 200, bytes)
        })
        downloadRouterInstallAsset(http, plan, asset, target) { _, _ -> }
        verifyRouterBundleAsset(target, asset)
        assertEquals(listOf("$base/$name", original), urls)
        assertEquals(sha(bytes), asset.sha256)
    }

    @Test fun serverErrorsAndTlsFailuresNeverTriggerAnAlternateDownload() = withPlan { plan, asset, target ->
        var count = 0
        var http = RouterDeviceReleaseHttp(Call.Factory { request -> count++; ReplyCall(request, 503, byteArrayOf()) })
        val unavailable = assertThrows(RouterReleaseUnavailable::class.java) {
            runBlocking { downloadRouterInstallAsset(http, plan, asset, target) { _, _ -> } }
        }
        assertEquals(503, unavailable.statusCode)
        assertEquals(1, count)
        count = 0
        http = RouterDeviceReleaseHttp(Call.Factory { request ->
            count++; ReplyCall(request, 200, byteArrayOf(), SSLHandshakeException("synthetic_tls_failure"))
        })
        assertThrows(IOException::class.java) {
            runBlocking { downloadRouterInstallAsset(http, plan, asset, target) { _, _ -> } }
        }
        assertEquals(1, count)
    }

    @Test fun payloadIntegrityFailureCannotBeReplacedByAnAlternateSource() = withPlan { plan, asset, target ->
        var count = 0
        val http = RouterDeviceReleaseHttp(Call.Factory { request -> count++; ReplyCall(request, 200, "BADS".toByteArray()) })
        downloadRouterInstallAsset(http, plan, asset, target) { _, _ -> }
        assertThrows(IllegalArgumentException::class.java) { verifyRouterBundleAsset(target, asset) }
        assertEquals(1, count)
        assertEquals(sha(bytes), asset.sha256)
    }

    private fun withPlan(block: suspend (RouterInstallBundlePlan, RouterBundleAsset, File) -> Unit) = runBlocking {
        val manifest = "synthetic-unit-manifest".toByteArray()
        val asset = RouterBundleAsset(name, sha(bytes), bytes.size.toLong(), sourceUrl = original)
        val plan = RouterInstallBundlePlan(base, sha(manifest), listOf(
            RouterBundleAsset("SHA256SUMS", sha(manifest), manifest.size.toLong(), manifest), asset))
        val folder = Files.createTempDirectory("router-mirror-unit-").toFile()
        try {
            val target = folder.resolve(name)
            assertTrue(target.createNewFile())
            block(plan, asset, target)
        } finally { folder.deleteRecursively() }
    }

    private class ReplyCall(private val value: Request, private val status: Int, private val body: ByteArray,
        private val failure: IOException? = null) : Call by OkHttpClient().newCall(value) {
        private var cancelled = false
        override fun request() = value
        override fun execute(): Response = response()
        override fun enqueue(responseCallback: Callback) {
            val error = failure
            if (error != null) responseCallback.onFailure(this, error) else responseCallback.onResponse(this, response())
        }
        private fun response() = Response.Builder().request(value).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
            .body(body.toResponseBody()).build()
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
        override fun isExecuted() = true
        override fun timeout() = Timeout()
        override fun clone(): Call = ReplyCall(value, status, body, failure)
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
