package com.foxhole.guard.core.security

import java.security.SecureRandom

internal object KdfCalibration {
    const val TARGET_MAX_MS = 800L
    const val DEFAULT_OPS = 3
    val CANDIDATE_MEM_KIB = listOf(65_536, 49_152, 32_768)

    fun calibrate(
        crypto: GuardCrypto,
        nowNanos: () -> Long = System::nanoTime,
    ): GuardKdfParams {
        val salt = ByteArray(GuardCrypto.SALT_BYTES).also(SecureRandom()::nextBytes)
        val probePassword = "foxhole-kdf-probe".encodeToByteArray()
        var chosen = GuardKdfParams(memKib = CANDIDATE_MEM_KIB.last(), ops = DEFAULT_OPS)
        for (memKib in CANDIDATE_MEM_KIB) {
            val candidate = GuardKdfParams(memKib = memKib, ops = DEFAULT_OPS)
            val startedAt = nowNanos()
            crypto.argon2id(probePassword, salt, candidate).zeroize()
            val elapsedMs = (nowNanos() - startedAt) / NANOS_PER_MILLI
            if (elapsedMs <= TARGET_MAX_MS) {
                chosen = candidate
                break
            }
        }
        return chosen
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
