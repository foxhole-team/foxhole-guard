package com.foxhole.core.runtime.network

import okhttp3.ResponseBody
import okio.Buffer

internal const val MAX_IP_INFO_BODY_BYTES: Long = 64L * 1024L

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
