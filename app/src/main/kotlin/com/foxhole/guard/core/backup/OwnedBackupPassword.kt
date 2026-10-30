package com.foxhole.guard.core.backup

internal class OwnedBackupPassword private constructor(
    private var password: CharArray?,
) : AutoCloseable {
    @Synchronized
    fun <T> usePassword(block: (CharArray) -> T): T =
        block(checkNotNull(password) { "backup password is unavailable" })

    @Synchronized
    override fun close() {
        password?.fill('\u0000')
        password = null
    }

    companion object {
        fun take(password: CharArray): OwnedBackupPassword =
            try {
                OwnedBackupPassword(password.copyOf())
            } finally {
                password.fill('\u0000')
            }
    }
}
