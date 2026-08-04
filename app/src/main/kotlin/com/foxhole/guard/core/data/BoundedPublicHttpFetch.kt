package com.foxhole.guard.core.data

import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.requirePublicUrl
import com.foxhole.core.runtime.network.PublicRemoteDns
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
    // For "read the start of the document" (a web app's <head>): an oversized body is truncated
    // rather than failing the request. Subscriptions keep false, where overflow is a refusal.
    truncateOversizedBody: Boolean = false,
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
            val body =
                when {
                    response.code == 304 -> null
                    truncateOversizedBody -> response.body?.readUtf8Truncated(maxBytes).orEmpty()
                    else -> response.body?.readUtf8Capped(maxBytes).orEmpty()
                }
            return BoundedPublicHttpResponse(
                code = response.code,
                headers = response.headers,
                body = body,
                finalUrl = url,
            )
        }
    }
    error("too many redirects")
}

// The binary twin of executeBoundedPublicGet, for web-app icons: same SSRF guard and body cap, but
// the body comes back as bytes, since readUtf8Capped would corrupt a binary. The redirect loop is
// duplicated deliberately — generalising the text path would touch audited security code.
internal fun executeBoundedPublicGetBytes(
    client: OkHttpClient,
    initialUrl: HttpUrl,
    allowHttp: Boolean,
    maxBytes: Long,
    maxRedirects: Int = REMOTE_FETCH_MAX_REDIRECTS,
    resolver: RemoteHostResolver? = null,
    requestFactory: (HttpUrl) -> Request,
): ByteArray? {
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
            if (response.code !in 200..299) {
                return null
            }
            return response.body?.readBytesCapped(maxBytes)
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

internal fun ResponseBody.readBytesCapped(maxBytes: Long): ByteArray {
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
    return buffer.readByteArray()
}

// Reads at most maxBytes and stops silently on overflow, unlike readUtf8Capped which throws. For
// web-app previews only <head> is needed, and a full page can be enormous.
internal fun ResponseBody.readUtf8Truncated(maxBytes: Long): String {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    val source = source()
    val buffer = Buffer()
    while (buffer.size < maxBytes) {
        val read = source.read(buffer, minOf(8_192L, maxBytes - buffer.size))
        if (read == -1L) {
            break
        }
    }
    return buffer.readString(Charsets.UTF_8)
}

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
