package com.foxhole.guard.core.security

import com.foxhole.guard.core.sentinel.FileCipher
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Deterministic JVM stand-in for libsodium: SHA-256-based KDF and a toy sealed box
 * that still enforces the keypair relationship, so wrong-key opens fail like the
 * real primitive. Never used in production code.
 */
internal class FakeGuardCrypto : GuardCrypto {
    var argon2Invocations = 0
        private set

    override fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        params: GuardKdfParams,
        outputLength: Int,
    ): ByteArray {
        argon2Invocations += 1
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("fake-argon2id|${params.memKib}|${params.ops}|${params.parallelism}|".encodeToByteArray())
        digest.update(salt)
        digest.update(password)
        var block = digest.digest()
        val output = ByteArray(outputLength)
        var offset = 0
        while (offset < outputLength) {
            val take = minOf(block.size, outputLength - offset)
            block.copyInto(output, offset, 0, take)
            offset += take
            block = MessageDigest.getInstance("SHA-256").digest(block)
        }
        return output
    }

    override fun sealedBoxKeypair(): GuardKeypair {
        val privateKey = ByteArray(GuardCrypto.X25519_KEY_BYTES).also(SecureRandom()::nextBytes)
        return GuardKeypair(publicKey = derivePublic(privateKey), privateKey = privateKey)
    }

    override fun seal(
        payload: ByteArray,
        publicKey: ByteArray,
    ): ByteArray {
        val nonce = ByteArray(SEAL_NONCE_BYTES).also(SecureRandom()::nextBytes)
        val tag = MessageDigest.getInstance("SHA-256").digest(publicKey + nonce)
        return nonce + tag.copyOfRange(0, SEAL_TAG_BYTES) + xorStream(payload, publicKey + nonce)
    }

    override fun openSealed(
        sealed: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray {
        check(derivePublic(privateKey).contentEquals(publicKey)) { "sealed-box open failed" }
        check(sealed.size >= SEAL_NONCE_BYTES + SEAL_TAG_BYTES) { "sealed payload is truncated" }
        val nonce = sealed.copyOfRange(0, SEAL_NONCE_BYTES)
        val tag = sealed.copyOfRange(SEAL_NONCE_BYTES, SEAL_NONCE_BYTES + SEAL_TAG_BYTES)
        val expectedTag = MessageDigest.getInstance("SHA-256").digest(publicKey + nonce).copyOfRange(0, SEAL_TAG_BYTES)
        check(tag.contentEquals(expectedTag)) { "sealed-box open failed" }
        return xorStream(sealed.copyOfRange(SEAL_NONCE_BYTES + SEAL_TAG_BYTES, sealed.size), publicKey + nonce)
    }

    private fun derivePublic(privateKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("fake-x25519-pub".encodeToByteArray() + privateKey)

    private fun xorStream(
        input: ByteArray,
        keyMaterial: ByteArray,
    ): ByteArray {
        val output = ByteArray(input.size)
        var block = MessageDigest.getInstance("SHA-256").digest(keyMaterial)
        for (index in input.indices) {
            if (index % block.size == 0 && index > 0) {
                block = MessageDigest.getInstance("SHA-256").digest(block)
            }
            output[index] = (input[index].toInt() xor block[index % block.size].toInt()).toByte()
        }
        return output
    }

    private companion object {
        const val SEAL_NONCE_BYTES = 16
        const val SEAL_TAG_BYTES = 16
    }
}

/**
 * Obfuscating FileCipher stand-in whose output never starts with '{' (a version-byte
 * prefix plus XOR), so the keybox legacy-JSON detection sees a "wrapped" file exactly
 * like with the real Keystore cipher.
 */
internal class XorFileCipher : FileCipher {
    override fun readBytes(file: File): ByteArray {
        val raw = file.readBytes()
        check(raw.isNotEmpty() && raw[0] == PREFIX) { "not an XorFileCipher payload" }
        return ByteArray(raw.size - 1) { index -> (raw[index + 1].toInt() xor MASK).toByte() }
    }

    override fun writeBytesAtomic(
        file: File,
        plaintext: ByteArray,
    ) {
        file.parentFile?.mkdirs()
        val payload = ByteArray(plaintext.size + 1)
        payload[0] = PREFIX
        for (index in plaintext.indices) {
            payload[index + 1] = (plaintext[index].toInt() xor MASK).toByte()
        }
        file.writeBytes(payload)
    }

    private companion object {
        const val PREFIX: Byte = 0x01
        const val MASK = 0x5A
    }
}

/** Plaintext FileCipher stand-in for the Keystore-backed cipher (JVM tests only). */
internal class FakeFileCipher : FileCipher {
    override fun readBytes(file: File): ByteArray = file.readBytes()

    override fun writeBytesAtomic(
        file: File,
        plaintext: ByteArray,
    ) {
        file.parentFile?.mkdirs()
        file.writeBytes(plaintext)
    }
}
