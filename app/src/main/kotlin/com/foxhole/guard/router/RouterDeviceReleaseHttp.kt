package com.foxhole.guard.router

import com.foxhole.core.network.isPrivateOrLocalAddress
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun routerPublicReleaseClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).proxy(Proxy.NO_PROXY)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
    .dns(Dns { host ->
        Dns.SYSTEM.lookup(host).also { addresses ->
            if (addresses.isEmpty() || addresses.any { it.isPrivateOrLocalAddress() }) throw UnknownHostException("router_release_destination")
        }
    }).build()

internal class RouterDeviceReleaseHttp(private val calls: Call.Factory = routerPublicReleaseClient()) {
    suspend fun read(url: HttpUrl, maximum: Int): ByteArray {
        require(maximum in 1..4194304)
        return fetch(url, maximum, null) { _, _ -> }.bytes
    }

    suspend fun downloadInstallAsset(url: HttpUrl, expectedBytes: Long, target: File, progress: (Long, Long?) -> Unit) {
        require(expectedBytes in 1L..536870912L)
        val reply = fetch(url, expectedBytes.toInt(), target, progress)
        require(reply.count == expectedBytes && target.length() == expectedBytes)
    }

    suspend fun download(url: HttpUrl, expectedBytes: Long, target: File, progress: (Long, Long?) -> Unit) {
        require(expectedBytes in 4L..8388608L)
        val reply = fetch(url, expectedBytes.toInt(), target, progress)
        if (reply.count != expectedBytes || target.length() != expectedBytes) throw IOException("router_release_size")
    }

    private suspend fun fetch(original: HttpUrl, maximum: Int, target: File?, progress: (Long, Long?) -> Unit): Reply {
        require(maximum in 1..536870912 && (target != null || maximum <= 4194304))
        requireSource(original)
        var destination = original
        repeat(4) {
            val reply = request(destination, maximum, target, progress)
            if (reply.code == 200) return reply
            if (original.host == "api.github.com" || reply.code !in setOf(301, 302, 303, 307, 308)) throw IOException("router_release_unavailable")
            val next = destination.resolve(reply.location ?: throw IOException("router_release_redirect"))
                ?: throw IOException("router_release_redirect")
            require(next.isHttps && next.port == 443 && next.username.isEmpty() && next.password.isEmpty() && next.fragment == null)
            require(next == original || next.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            destination = next
        }
        throw IOException("router_release_redirect_limit")
    }

    private fun requireSource(url: HttpUrl) {
        require(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
        require(if (url.host == "api.github.com") url.toString() == ROUTER_DEVICE_RELEASE_METADATA else
            url.host == "github.com" && url.query == null && url.encodedPath.matches(Regex(
                "/foxhole-team/foxhole-openwrt-client/releases/download/v[0-9]+\\.[0-9]+\\.[0-9]+/[A-Za-z0-9][A-Za-z0-9._-]{0,159}")))
    }

    private suspend fun request(url: HttpUrl, maximum: Int, target: File?, progress: (Long, Long?) -> Unit): Reply =
        suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(Request.Builder().url(url).header("Accept", if (url.host == "api.github.com")
                "application/vnd.github+json" else "application/octet-stream").header("Cache-Control", "no-cache").get().build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("router_release_unavailable"))
                }
                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) { response.close(); return }
                    try {
                        val reply = response.use { value ->
                            if (value.code != 200) Reply(value.code, value.header("Location"), byteArrayOf(), 0)
                            else {
                                val length = value.body.contentLength()
                                require(length <= maximum)
                                val memory = if (target == null) ByteArrayOutputStream() else null
                                val output = memory ?: FileOutputStream(requireNotNull(target), false)
                                var count = 0L
                                output.use { destination ->
                                    value.body.byteStream().use { source ->
                                        val buffer = ByteArray(8192)
                                        try {
                                            while (true) {
                                                if (!continuation.isActive) throw IOException("router_release_cancelled")
                                                val read = source.read(buffer)
                                                if (read < 0) break
                                                require(read > 0 && count + read <= maximum)
                                                destination.write(buffer, 0, read)
                                                count += read
                                                progress(count, length.takeIf { it > 0 })
                                            }
                                        } finally { buffer.fill(0) }
                                    }
                                }
                                require(count > 0 && (length < 0 || count == length))
                                Reply(200, null, memory?.toByteArray() ?: byteArrayOf(), count)
                            }
                        }
                        if (continuation.isActive) continuation.resume(reply)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("router_release_invalid"))
                    }
                }
            })
        }

    private data class Reply(val code: Int, val location: String?, val bytes: ByteArray, val count: Long)
}

internal const val ROUTER_DEVICE_RELEASE_METADATA = "https://api.github.com/repos/foxhole-team/foxhole-openwrt-client/releases?per_page=10"
