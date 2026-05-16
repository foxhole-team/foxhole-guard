package com.foxhole.beta.core.data

import com.foxhole.beta.core.network.RemoteHostResolver
import com.foxhole.beta.core.network.PublicRemoteDns
import com.foxhole.beta.core.network.requirePublicUrl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import java.util.concurrent.TimeUnit

internal const val REMOTE_FETCH_MAX_REDIRECTS = 5
internal const val MAX_SUBSCRIPTION_BYTES = 2L * 1024L * 1024L
internal const val MAX_ROUTING_CATALOG_BYTES = 512L * 1024L

internal data class BoundedPublicHttpResponse(
    val code: Int,
    val headers: Headers,
    val body: String?,
    val finalUrl: HttpUrl,
) {
    val isSuccessful: Boolean
        get() = code in 200..299
}

internal fun OkHttpClient.withBoundedRemoteFetchTimeouts(
    connectTimeoutMs: Long = DEFAULT_REMOTE_FETCH_CONNECT_TIMEOUT_MS,
    readTimeoutMs: Long = DEFAULT_REMOTE_FETCH_READ_TIMEOUT_MS,
    callTimeoutMs: Long = DEFAULT_REMOTE_FETCH_CALL_TIMEOUT_MS,
): OkHttpClient =
    newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(connectTimeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        .callTimeout(callTimeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        .build()

internal fun executeBoundedPublicGet(
    client: OkHttpClient,
    initialUrl: HttpUrl,
    allowHttp: Boolean,
    maxBytes: Long,
    maxRedirects: Int = REMOTE_FETCH_MAX_REDIRECTS,
    resolver: RemoteHostResolver? = null,
    requestFactory: (HttpUrl) -> Request,
): BoundedPublicHttpResponse {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    require(maxRedirects >= 0) { "maxRedirects must not be negative" }
    var url = initialUrl
    repeat(maxRedirects + 1) {
        url.requirePublicUrl(
            allowHttp = allowHttp,
            resolveHost = true,
            resolver = resolver,
        )
        val guardedClient = client.withPublicRemoteDns()
        guardedClient.newCall(requestFactory(url)).execute().use { response ->
            if (response.isRedirect) {
                val location = response.header("Location") ?: error("redirect without Location")
                val nextUrl = url.resolve(location) ?: error("invalid redirect Location")
                nextUrl.requirePublicUrl(
                    allowHttp = allowHttp,
                    resolveHost = true,
                    resolver = resolver,
                )
                url = nextUrl
                return@repeat
            }
            return BoundedPublicHttpResponse(
                code = response.code,
                headers = response.headers,
                body = if (response.code == 304) null else response.body?.readUtf8Capped(maxBytes).orEmpty(),
                finalUrl = url,
            )
        }
    }
    error("too many redirects")
}

private fun OkHttpClient.withPublicRemoteDns(): OkHttpClient =
    newBuilder()
        .dns(PublicRemoteDns(dns::lookup))
        .build()

private const val DEFAULT_REMOTE_FETCH_CONNECT_TIMEOUT_MS = 10_000L
private const val DEFAULT_REMOTE_FETCH_READ_TIMEOUT_MS = 20_000L
private const val DEFAULT_REMOTE_FETCH_CALL_TIMEOUT_MS = 30_000L

internal fun ResponseBody.readUtf8Capped(maxBytes: Long): String {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    val declaredLength = contentLength()
    require(declaredLength <= maxBytes || declaredLength == -1L) {
        "response body too large: $declaredLength > $maxBytes"
    }
    val source = source()
    val buffer = Buffer()
    while (true) {
        val remaining = maxBytes + 1L - buffer.size
        if (remaining <= 0L) {
            error("response body exceeded limit: $maxBytes bytes")
        }
        val read = source.read(buffer, minOf(8_192L, remaining))
        if (read == -1L) {
            break
        }
        if (buffer.size > maxBytes) {
            error("response body exceeded limit: $maxBytes bytes")
        }
    }
    return buffer.readString(Charsets.UTF_8)
}
