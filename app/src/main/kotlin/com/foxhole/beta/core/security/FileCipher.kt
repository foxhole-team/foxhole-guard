package com.foxhole.beta.core.security

import java.io.File

internal interface FileCipher {
    fun readBytes(file: File): ByteArray

    fun writeBytesAtomic(
        file: File,
        plaintext: ByteArray,
    )
}
