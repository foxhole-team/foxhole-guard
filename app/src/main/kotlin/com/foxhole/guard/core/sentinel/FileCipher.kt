package com.foxhole.guard.core.sentinel

import java.io.File

internal interface FileCipher {
    fun readBytes(file: File): ByteArray

    fun writeBytesAtomic(
        file: File,
        plaintext: ByteArray,
    )
}
