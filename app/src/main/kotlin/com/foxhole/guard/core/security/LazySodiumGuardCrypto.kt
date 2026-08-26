package com.foxhole.guard.core.security

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong

internal class LazySodiumGuardCrypto : GuardCrypto {
    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }

    override fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        params: GuardKdfParams,
        outputLength: Int,
    ): ByteArray {
        require(salt.size == GuardCrypto.SALT_BYTES) { "argon2id salt must be ${GuardCrypto.SALT_BYTES} bytes" }
        require(password.isNotEmpty()) { "argon2id password must not be empty" }
        val output = ByteArray(outputLength)

        val succeeded =
            sodium.cryptoPwHash(
                output,
                output.size,
                password,
                password.size,
                salt,
                params.ops.toLong(),
                NativeLong(params.memKib * BYTES_PER_KIB),
                PwHash.Alg.PWHASH_ALG_ARGON2ID13,
            )
        check(succeeded) { "argon2id derivation failed (memKib=${params.memKib}, ops=${params.ops})" }
        return output
    }

    override fun sealedBoxKeypair(): GuardKeypair {
        val publicKey = ByteArray(Box.PUBLICKEYBYTES)
        val privateKey = ByteArray(Box.SECRETKEYBYTES)
        check(sodium.cryptoBoxKeypair(publicKey, privateKey)) { "x25519 keypair generation failed" }
        return GuardKeypair(publicKey = publicKey, privateKey = privateKey)
    }

    override fun seal(
        payload: ByteArray,
        publicKey: ByteArray,
    ): ByteArray {
        val sealed = ByteArray(payload.size + Box.SEALBYTES)
        check(sodium.cryptoBoxSeal(sealed, payload, payload.size.toLong(), publicKey)) { "sealed-box seal failed" }
        return sealed
    }

    override fun openSealed(
        sealed: ByteArray,
        publicKey: ByteArray,
        privateKey: ByteArray,
    ): ByteArray {
        require(sealed.size >= Box.SEALBYTES) { "sealed payload is truncated" }
        val payload = ByteArray(sealed.size - Box.SEALBYTES)
        val opened = sodium.cryptoBoxSealOpen(payload, sealed, sealed.size.toLong(), publicKey, privateKey)
        check(opened) { "sealed-box open failed" }
        return payload
    }

    private companion object {
        const val BYTES_PER_KIB = 1024L
    }
}
