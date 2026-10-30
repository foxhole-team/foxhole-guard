package com.foxhole.guard.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal interface CategorizedDnsStoreIo {
    fun replace(parent: File, name: String, bytes: ByteArray)
    fun read(file: File): ByteArray = file.readBytes()
    fun remove(file: File): Boolean
}

internal object FileCategorizedDnsStoreIo : CategorizedDnsStoreIo {
    override fun replace(parent: File, name: String, bytes: ByteArray) {
        val temporary = File.createTempFile("$name-", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                File(parent, name).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            temporary.delete()
        }
    }

    override fun remove(file: File): Boolean = !file.exists() || file.delete()
}
