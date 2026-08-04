package com.foxhole.guard.core.sentinel

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
        require(iv.size == AES_GCM_IV_SIZE_BYTES) { "iv must be 12 bytes" }
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
        require(ivSize == AES_GCM_IV_SIZE_BYTES) { "iv must be 12 bytes" }
        require(payload.size > HEADER_SIZE_BYTES + ivSize) { "payload is truncated" }
        val iv = ByteArray(ivSize)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        require(ciphertext.isNotEmpty()) { "ciphertext must not be empty" }
        return DecodedPayload(iv = iv, ciphertext = ciphertext)
    }

    private const val AES_GCM_IV_SIZE_BYTES = 12
}
