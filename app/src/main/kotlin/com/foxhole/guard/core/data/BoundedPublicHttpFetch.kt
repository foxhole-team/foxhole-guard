package com.foxhole.guard.core.data

import com.foxhole.core.network.RemoteHostResolver
import com.foxhole.core.network.requirePublicUrl
import com.foxhole.core.runtime.network.PublicRemoteDns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
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

// Revalidate and resolve every redirect so a public entry URL can never pivot to a private host.
internal fun executeBoundedPublicGet(
    client: OkHttpClient,
    initialUrl: HttpUrl,
    allowHttp: Boolean,
    maxBytes: Long,
    maxRedirects: Int = REMOTE_FETCH_MAX_REDIRECTS,
    resolver: RemoteHostResolver? = null,

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
                    truncateOversizedBody -> response.body.readUtf8Truncated(maxBytes)
                    else -> response.body.readUtf8Capped(maxBytes)
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

internal suspend fun executeBoundedPublicGetCancellable(
    client: OkHttpClient,
    initialUrl: HttpUrl,
    allowHttp: Boolean,
    maxBytes: Long,
    maxRedirects: Int = REMOTE_FETCH_MAX_REDIRECTS,
    resolver: RemoteHostResolver? = null,
    truncateOversizedBody: Boolean = false,
    requestFactory: (HttpUrl) -> Request,
): BoundedPublicHttpResponse = executeBoundedPublicGetCancellable(
    client, initialUrl, allowHttp, maxBytes, maxRedirects, resolver, truncateOversizedBody, { it }, requestFactory,
)

internal suspend fun executeBoundedPublicGetCancellable(
    client: OkHttpClient,
    initialUrl: HttpUrl,
    allowHttp: Boolean,
    maxBytes: Long,
    maxRedirects: Int = REMOTE_FETCH_MAX_REDIRECTS,
    resolver: RemoteHostResolver? = null,
    truncateOversizedBody: Boolean = false,
    transportFailureMapper: (IOException) -> Throwable,
    requestFactory: (HttpUrl) -> Request,
): BoundedPublicHttpResponse {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    require(maxRedirects >= 0) { "maxRedirects must not be negative" }
    var url = initialUrl
    repeat(maxRedirects + 1) {
        currentCoroutineContext().ensureActive()
        url.requirePublicUrl(
            allowHttp = allowHttp,
            resolveHost = true,
            resolver = resolver,
        )
        val response =
            client
                .withPublicRemoteDns()
                .executeCancellable(requestFactory(url), url, maxBytes, truncateOversizedBody, transportFailureMapper)
        if (response.code in HTTP_REDIRECT_CODES) {
            val location = response.headers["Location"] ?: error("redirect without Location")
            val nextUrl = url.resolve(location) ?: error("invalid redirect Location")
            nextUrl.requirePublicUrl(
                allowHttp = allowHttp,
                resolveHost = true,
                resolver = resolver,
            )
            url = nextUrl
            return@repeat
        }
        return response
    }
    error("too many redirects")
}

private suspend fun OkHttpClient.executeCancellable(
    request: Request,
    finalUrl: HttpUrl,
    maxBytes: Long,
    truncateOversizedBody: Boolean,
    transportFailureMapper: (IOException) -> Throwable,
): BoundedPublicHttpResponse =
    executeCancellableResponse(request, transportFailureMapper) { opened ->
        BoundedPublicHttpResponse(
            code = opened.code,
            headers = opened.headers,
            body = when {
                opened.code == 304 || opened.code in HTTP_REDIRECT_CODES -> null
                truncateOversizedBody -> opened.body.readUtf8Truncated(maxBytes)
                else -> opened.body.readUtf8Capped(maxBytes)
            },
            finalUrl = finalUrl,
        )
    }

internal suspend fun <T> OkHttpClient.executeCancellableResponse(
    request: Request,
    readResponse: (Response) -> T,
): T = executeCancellableResponse(request, { it }, readResponse)

internal suspend fun <T> OkHttpClient.executeCancellableResponse(
    request: Request,
    transportFailureMapper: (IOException) -> Throwable,
    readResponse: (Response) -> T,
): T =
    newCall(request).let { call ->
        awaitCancellableHttpCall(
            cancelCall = call::cancel,
            startCall = { complete ->
                call.enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            val failure = try { transportFailureMapper(e) } catch (error: Throwable) { error }
                            complete(Result.failure(failure))
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            val result =
                                runCatching {
                                    response.use(readResponse)
                                }
                            result.fold(
                                onSuccess = { value ->
                                    complete(Result.success(value))
                                },
                                onFailure = { error ->
                                    complete(Result.failure(error))
                                },
                            )
                        }
                    },
                )
            },
        )
    }

internal suspend fun <T> awaitCancellableHttpCall(
    cancelCall: () -> Unit,
    startCall: (((Result<T>) -> Unit) -> Unit),
): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancelCall() }
        startCall(continuation::resumeWith)
    }

internal suspend fun executeBoundedPublicGetBytesCancellable(
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
        currentCoroutineContext().ensureActive()
        url.requirePublicUrl(allowHttp = allowHttp, resolveHost = true, resolver = resolver)
        val (code, headers, body) = client.withPublicRemoteDns().executeCancellableResponse(
            requestFactory(url)
        ) { response ->
            Triple(
                response.code,
                response.headers,
                if (response.isSuccessful) response.body.readBytesCapped(maxBytes) else null
            )
        }
        if (code in HTTP_REDIRECT_CODES) {
            val location = headers["Location"] ?: error("redirect without Location")
            val nextUrl = url.resolve(location) ?: error("invalid redirect Location")
            nextUrl.requirePublicUrl(allowHttp = allowHttp, resolveHost = true, resolver = resolver)
            url = nextUrl
            return@repeat
        }
        return body
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
private val HTTP_REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)

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
