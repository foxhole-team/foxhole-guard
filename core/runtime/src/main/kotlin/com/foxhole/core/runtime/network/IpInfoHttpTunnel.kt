package com.foxhole.core.runtime.network

import android.util.Log
import com.foxhole.core.model.DiagnosticSanitizer
import com.foxhole.core.runtime.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class HttpProxyTunnelTarget(
    val host: String,
    val port: Int,
) {
    val authority: String =
        if (host.contains(':') && !host.startsWith('[')) {
            "[$host]:$port"
        } else {
            "$host:$port"
        }
}

internal data class HttpProxyTunnelResponse(
    val code: Int,
    val body: String,
    val elapsedMs: Long,
) {
    val isSuccessful: Boolean
        get() = code in 200..299
}

internal data class HttpResponseHead(
    val code: Int,
    val headers: Map<String, List<String>>,
) {
    fun firstHeader(name: String): String? = headers[name.lowercase()]?.firstOrNull()
}

internal class HttpProxyTunnelTimeoutBudget(
    timeoutMs: Long,
    private val startedAtNanos: Long = System.nanoTime(),
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val totalTimeoutMs = timeoutMs.coerceAtLeast(1L)

    fun remainingMs(): Int {
        val elapsedMs = ((nowNanos() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        val remainingMs = totalTimeoutMs - elapsedMs
        if (remainingMs <= 0L) {
            throw InterruptedIOException("timeout")
        }
        return remainingMs
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .coerceAtLeast(1)
    }
}

internal class DeadlineInputStream(
    private val delegate: InputStream,
    private val timeoutBudget: HttpProxyTunnelTimeoutBudget,
    private val applyTimeout: (Int) -> Unit,
) : InputStream() {
    override fun read(): Int {
        applyTimeout(timeoutBudget.remainingMs())
        return delegate.read()
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        applyTimeout(timeoutBudget.remainingMs())
        return delegate.read(buffer, offset, length)
    }
}

internal inline fun <T> httpProxyTunnelStage(
    stage: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (error: IOException) {
        throw IOException("proxy tunnel $stage failed: ${error.message.orEmpty()}", error)
    } catch (error: IllegalStateException) {
        throw IllegalStateException("proxy tunnel $stage failed: ${error.message.orEmpty()}", error)
    }

internal fun proxyConnectRequest(
    target: HttpProxyTunnelTarget,
    proxy: HttpProxyAccess,
): String {
    val authHeader =
        if (!proxy.username.isNullOrBlank() && !proxy.password.isNullOrBlank()) {
            "Proxy-Authorization: ${Credentials.basic(proxy.username, proxy.password)}\r\n"
        } else {
            ""
        }
    return "CONNECT ${target.authority} HTTP/1.1\r\n" +
        "Host: ${target.authority}\r\n" +
        authHeader +
        "Proxy-Connection: keep-alive\r\n" +
        "\r\n"
}

internal fun proxyTunnelGetRequest(
    path: String,
    target: HttpProxyTunnelTarget,
): String =
    "GET $path HTTP/1.1\r\n" +
        "Host: ${target.authority}\r\n" +
        "User-Agent: FoxHole/${BuildConfig.VERSION_NAME}\r\n" +
        "Accept: application/json,*/*;q=0.1\r\n" +
        "Connection: close\r\n" +
        "\r\n"

internal fun readHttpResponseHead(input: InputStream): HttpResponseHead {
    val buffer = ByteArrayOutputStream()
    var tail = 0
    while (buffer.size() < HTTP_TUNNEL_MAX_HEADER_BYTES) {
        val value = input.read()
        if (value == -1) {
            break
        }
        buffer.write(value)
        tail = (tail shl 8) or (value and 0xff)
        if (tail == HTTP_HEADER_TERMINATOR) {
            val text = buffer.toString(Charsets.ISO_8859_1.name())
            val lines = text.substringBefore("\r\n\r\n").split("\r\n")
            val statusCode =
                lines.firstOrNull()
                    ?.split(' ', limit = 3)
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: error("invalid http response status")
            val headers =
                lines.drop(1)
                    .mapNotNull { line ->
                        val separator = line.indexOf(':')
                        if (separator <= 0) {
                            null
                        } else {
                            line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
                        }
                    }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
            return HttpResponseHead(statusCode, headers)
        }
    }
    error("http response headers incomplete")
}

internal fun readHttpResponseBody(
    input: InputStream,
    head: HttpResponseHead,
): ByteArray =
    when {
        head.code == 204 || head.code == 304 || head.code in 100..199 -> ByteArray(0)
        head.firstHeader("transfer-encoding")?.contains("chunked", ignoreCase = true) == true ->
            readChunkedHttpBody(input)
        else ->
            readHttpResponseBodyWithLength(input, head)
    }

private fun readHttpResponseBodyWithLength(
    input: InputStream,
    head: HttpResponseHead,
): ByteArray {
    val contentLength = head.firstHeader("content-length")?.toIntOrNull()
    return if (contentLength != null) {
        require(contentLength <= HTTP_TUNNEL_MAX_BODY_BYTES) { "http response body too large" }
        input.readExactBytesBounded(contentLength)
    } else {
        input.readUntilEofBounded(HTTP_TUNNEL_MAX_BODY_BYTES)
    }
}

private fun readChunkedHttpBody(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    while (true) {
        val sizeLine = input.readAsciiLine(HTTP_TUNNEL_MAX_LINE_BYTES)
        val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: error("invalid chunk size")
        if (chunkSize == 0) {
            while (input.readAsciiLine(HTTP_TUNNEL_MAX_LINE_BYTES).isNotEmpty()) {
                // Consume trailers.
            }
            return output.toByteArray()
        }
        require(output.size() + chunkSize <= HTTP_TUNNEL_MAX_BODY_BYTES) { "http response body too large" }
        output.write(input.readExactBytesBounded(chunkSize))
        input.expectCrlf()
    }
}

private fun InputStream.readExactBytesBounded(size: Int): ByteArray {
    val output = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(output, offset, size - offset)
        if (read == -1) {
            error("http response body ended early")
        }
        offset += read
    }
    return output
}

private fun InputStream.readUntilEofBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read == -1) {
            return output.toByteArray()
        }
        require(output.size() + read <= maxBytes) { "http response body too large" }
        output.write(buffer, 0, read)
    }
}

private fun InputStream.readAsciiLine(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    while (output.size() < maxBytes) {
        val value = read()
        if (value == -1) {
            error("http response ended before line")
        }
        if (value == '\n'.code) {
            return output.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
        }
        output.write(value)
    }
    error("http response line too long")
}

private fun InputStream.expectCrlf() {
    val cr = read()
    val lf = read()
    require(cr == '\r'.code && lf == '\n'.code) { "invalid chunk delimiter" }
}

internal fun okhttp3.HttpUrl.encodedPathWithQuery(): String =
    encodedPath + encodedQuery?.let { query -> "?$query" }.orEmpty()

private const val HTTP_TUNNEL_MAX_HEADER_BYTES = 16 * 1024
private const val HTTP_TUNNEL_MAX_BODY_BYTES = 128 * 1024
private const val HTTP_TUNNEL_MAX_LINE_BYTES = 8 * 1024
private const val HTTP_HEADER_TERMINATOR = 0x0D0A0D0A

internal suspend fun Call.awaitResponse(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (!continuation.isActive) {
                        return
                    }
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    continuation.resume(response)
                }
            },
        )
    }

internal fun diagnosticLog(message: String) {
    if (BuildConfig.ENABLE_DIAGNOSTIC_LOGCAT) {
        Log.d("FoxholeDiag", "[ip] ${DiagnosticSanitizer.sanitizeForExport(message)}")
    }
}

internal fun String.ipInfoHostLabel(): String = toHttpUrlOrNull()?.host ?: "invalid"

internal data class HttpClientKey(
    val callTimeoutMs: Long?,
    val networkHandle: Long?,
    val resolverNetworkHandle: Long?,
    val addressFamilyPreference: AddressFamilyPreference,
    val proxy: HttpProxyAccess?,
)

internal data class CachedHttpClient(
    val client: OkHttpClient,
    val createdAtNanos: Long,
)
