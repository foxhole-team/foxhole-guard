package com.foxhole.core.runtime.network

import okhttp3.ResponseBody
import okio.Buffer

/**
 * IP-info JSON is a few hundred bytes; 64 KiB is a generous ceiling. These probes are relayed
 * through the user's (untrusted) tunnel/proxy, so an unbounded `ResponseBody.string()` would let a
 * hostile endpoint stream an arbitrarily large body straight into the heap. Every other remote fetch
 * in the app caps its read; the direct IP-info path is brought in line here.
 */
internal const val MAX_IP_INFO_BODY_BYTES: Long = 64L * 1024L

/**
 * Reads at most [maxBytes] of the response as UTF-8, erroring past the cap instead of buffering the
 * whole (possibly hostile) body. Mirrors the app-module `readUtf8Capped`, kept here because
 * `core:runtime` cannot depend on `app`.
 */
internal fun ResponseBody.readIpInfoBodyCapped(maxBytes: Long = MAX_IP_INFO_BODY_BYTES): String {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    val declaredLength = contentLength()
    require(declaredLength <= maxBytes || declaredLength == -1L) {
        "ip info response body too large: $declaredLength > $maxBytes"
    }
    val source = source()
    val buffer = Buffer()
    while (true) {
        val remaining = maxBytes + 1L - buffer.size
        if (remaining <= 0L) {
            error("ip info response body exceeded limit: $maxBytes bytes")
        }
        val read = source.read(buffer, minOf(READ_CHUNK_BYTES, remaining))
        if (read == -1L) {
            break
        }
        if (buffer.size > maxBytes) {
            error("ip info response body exceeded limit: $maxBytes bytes")
        }
    }
    return buffer.readString(Charsets.UTF_8)
}

private const val READ_CHUNK_BYTES: Long = 8_192L
