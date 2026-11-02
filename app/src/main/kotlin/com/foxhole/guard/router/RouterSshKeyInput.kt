package com.foxhole.guard.router

import java.io.IOException
import java.io.InputStream
import java.nio.CharBuffer

internal const val ROUTER_SSH_KEY_MAX_BYTES = 65536

internal fun readRouterSshPrivateKey(source: InputStream): ByteArray {
    val buffer = ByteArray(ROUTER_SSH_KEY_MAX_BYTES + 1)
    try {
        var count = 0
        while (count < buffer.size) {
            val read = source.read(buffer, count, buffer.size - count)
            if (read < 0) break
            if (read == 0) throw IOException("router_key_read_failed")
            count += read
        }
        if (count !in 1..ROUTER_SSH_KEY_MAX_BYTES) throw IOException("router_key_size")
        return checkedRouterSshPrivateKey(buffer.copyOf(count))
    } finally { buffer.fill(0) }
}

internal fun routerSshPrivateKeyBytes(text: CharSequence): ByteArray {
    if (text.length !in 1..ROUTER_SSH_KEY_MAX_BYTES) throw IOException("router_key_size")
    val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text))
    try {
        if (encoded.remaining() > ROUTER_SSH_KEY_MAX_BYTES) throw IOException("router_key_size")
        return checkedRouterSshPrivateKey(ByteArray(encoded.remaining()).also { encoded.get(it) })
    } finally { if (encoded.hasArray()) encoded.array().fill(0) }
}

private fun checkedRouterSshPrivateKey(bytes: ByteArray): ByteArray {
    val headers = listOf("OPENSSH PRIVATE KEY", "RSA PRIVATE KEY", "DSA PRIVATE KEY", "EC PRIVATE KEY",
        "PRIVATE KEY", "ENCRYPTED PRIVATE KEY")
    val offset = bytes.indexOfFirst { it !in listOf(9.toByte(), 10.toByte(), 13.toByte(), 32.toByte()) }
    if (offset < 0 || headers.none { type ->
            val header = "-----BEGIN $type-----".toByteArray(Charsets.US_ASCII)
            bytes.size - offset >= header.size && header.indices.all { bytes[offset + it] == header[it] }
        }) {
        bytes.fill(0)
        throw IOException("router_key_format")
    }
    return bytes
}
