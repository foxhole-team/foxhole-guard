package com.foxhole.guard.router

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class GithubRouterReleaseAssets(baseUrl: String, private val calls: Call.Factory = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
) : RouterReleaseAssetSource {
    private val base = routerReleaseBaseUrl(baseUrl)

    override suspend fun read(name: String, maximumBytes: Int): ByteArray = read(name, maximumBytes) { _, _ -> }

    override suspend fun read(name: String, maximumBytes: Int, onProgress: (Long, Long?) -> Unit): ByteArray = try { withTimeout(45_000L) {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")) && maximumBytes in 1..1048576)
        var destination = base.newBuilder().apply {
            if (base.encodedPath.endsWith('/')) removePathSegment(base.pathSize - 1)
        }.addPathSegment(name).build()
        repeat(4) {
            val reply = fetch(destination, maximumBytes, onProgress)
            if (reply.status == 200) return@withTimeout reply.bytes
            if (reply.status !in setOf(301, 302, 303, 307, 308)) throw IOException("router_release_unavailable")
            val next = requireNotNull(destination.resolve(requireNotNull(reply.location)))
            require(next.isHttps && next.port == 443 && next.username.isEmpty() && next.password.isEmpty() &&
                next.fragment == null && next.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            destination = next
        }
        throw IOException("router_release_redirect_limit")
    } } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw IOException("router_release_timeout")
    }

    private suspend fun fetch(url: HttpUrl, maximum: Int, onProgress: (Long, Long?) -> Unit): RouterAssetReply = suspendCancellableCoroutine { continuation ->
        val call = calls.newCall(Request.Builder().url(url).header("Accept", "application/octet-stream")
            .header("Cache-Control", "no-cache").get().build())
        continuation.invokeOnCancellation {
            call.cancel()
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("router_release_unavailable"))
            }
            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) { response.close(); return }
                try {
                    val reply = response.use { value ->
                        if (value.code != 200) RouterAssetReply(value.code, value.header("Location"), byteArrayOf())
                        else {
                            require(value.body.contentLength() <= maximum)
                            val total = value.body.contentLength().takeIf { it > 0 }
                            onProgress(0, total)
                            val contents = ByteArrayOutputStream()
                            value.body.byteStream().use { source ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("router_release_cancelled")
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    if (!continuation.isActive) throw IOException("router_release_cancelled")
                                    require(contents.size() + count <= maximum)
                                    contents.write(buffer, 0, count)
                                    onProgress(contents.size().toLong(), total)
                                }
                            }
                            require(total == null || contents.size().toLong() == total)
                            RouterAssetReply(200, null, contents.toByteArray())
                        }
                    }
                    if (continuation.isActive) continuation.resume(reply)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("router_release_invalid"))
                }
            }
        })
    }
}

private data class RouterAssetReply(val status: Int, val location: String?, val bytes: ByteArray)
