package com.foxhole.beta.core.security

import java.nio.ByteBuffer

internal object AesGcmFileEnvelope {
    private const val VERSION: Byte = 1
    private const val HEADER_SIZE_BYTES = 1 + Int.SIZE_BYTES

    data class DecodedPayload(
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    fun encode(
        iv: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        require(iv.isNotEmpty()) { "iv must not be empty" }
        require(ciphertext.isNotEmpty()) { "ciphertext must not be empty" }
        return ByteBuffer
            .allocate(HEADER_SIZE_BYTES + iv.size + ciphertext.size)
            .put(VERSION)
            .putInt(iv.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    fun decode(payload: ByteArray): DecodedPayload {
        require(payload.size >= HEADER_SIZE_BYTES + 1) { "payload is truncated" }
        val buffer = ByteBuffer.wrap(payload)
        val version = buffer.get()
        require(version == VERSION) { "unsupported payload version: $version" }
        val ivSize = buffer.int
        require(ivSize > 0) { "invalid iv length" }
        require(payload.size > HEADER_SIZE_BYTES + ivSize) { "payload is truncated" }
        val iv = ByteArray(ivSize)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        require(ciphertext.isNotEmpty()) { "ciphertext must not be empty" }
        return DecodedPayload(iv = iv, ciphertext = ciphertext)
    }
}
