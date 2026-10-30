package com.foxhole.guard.core.diagnostics

import android.content.Context
import android.content.ContextWrapper
import com.foxhole.core.model.RetentionPolicy
import com.foxhole.core.model.Settings
import com.foxhole.guard.core.sentinel.AesGcmFileEnvelope
import com.foxhole.guard.core.sentinel.AndroidKeystoreFileCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class ReadOnlyAboutExportCryptoTest {
    @Test
    fun `existing synthetic key decrypts exact envelope without changing file`() {
        val root = Files.createTempDirectory("foxhole-readonly-cipher").toFile()
        try {
            val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
            val plaintext = "owned synthetic journal".toByteArray()
            val encryption = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val bytes = AesGcmFileEnvelope.encode(encryption.iv, encryption.doFinal(plaintext))
            val file = File(root, "session-1-test.jsonl.enc").apply { writeBytes(bytes) }
            val cipher = AndroidKeystoreFileCipher("unused-test-alias")
            assertArrayEquals(plaintext, cipher.readBytesExistingKey(file) { KeyStore.SecretKeyEntry(key) })
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(listOf(file.name), root.list().orEmpty().toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing or wrong type key fails before file read and never creates anything`() {
        val root = Files.createTempDirectory("foxhole-missing-cipher").toFile()
        try {
            val missing = File(root, "not-created")
            val cipher = AndroidKeystoreFileCipher("unused-test-alias")
            listOf<KeyStore.Entry?>(null, object : KeyStore.Entry {}).forEach { entry ->
                var reads = 0
                val failure = runCatching {
                    cipher.readBytesExistingKey(missing) {
                        reads += 1
                        entry
                    }
                }.exceptionOrNull()
                assertEquals(1, reads)
                assertTrue(failure is KeyStoreException)
                assertEquals("diagnostics journal key unavailable", failure?.message)
                assertFalse(missing.exists())
                assertTrue(root.list().orEmpty().isEmpty())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `actual manual export reads encrypted and legacy sessions without journal mutation`() = runBlocking {
        val root = Files.createTempDirectory("foxhole-about-readonly").toFile()
        try {
            val logger = DiagnosticsLogger.forManualExportTests(TestExportContext(root), nowProvider = { 200_000_000L })
            val directory = File(root, "owned-journal").apply { mkdirs() }
            val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
            val plaintext = "{\"timestamp\":199999000,\"tag\":\"t\",\"message\":\"owned encrypted\"}".toByteArray()
            val encryption = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val encrypted = File(directory, "session-1-test.jsonl.enc").apply {
                writeBytes(AesGcmFileEnvelope.encode(encryption.iv, encryption.doFinal(plaintext)))
            }
            val legacy = File(directory, "session-2-test.jsonl").apply {
                writeText("{\"timestamp\":199999001,\"tag\":\"t\",\"message\":\"owned legacy\"}")
            }
            val expired = File(directory, "session-3-expired.jsonl").apply {
                writeText("expired owned file")
                setLastModified(1)
            }
            val before = directory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
            val store = DiagnosticsSessionStore(
                journalDir = directory,
                aboutEncryptedReader = { source ->
                    AndroidKeystoreFileCipher("unused-test-alias").readBytesExistingKey(source) {
                        KeyStore.SecretKeyEntry(key)
                    }
                },
            )
            val archive = logger.createSanitizedAboutExportFile(parseNativeAggregateCounters("{}"), store)
            val document = java.util.zip.GZIPInputStream(archive.inputStream()).bufferedReader().use { it.readText() }
            assertTrue(document.contains("owned encrypted"))
            assertTrue(document.contains("owned legacy"))
            assertTrue(document.contains("Native Aggregate Read: unavailable"))
            assertEquals(before, directory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() })
            assertTrue(encrypted.exists() && legacy.exists() && expired.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `actual logger store readonly cipher and decoder preserve original fatal boundaries`() {
        listOf("cipher", "plaintext", "decoder", "retention", "settings").forEach { stage ->
            listOf<Throwable>(IdentityCancellation(stage), IdentityFatal(stage)).forEach { failure ->
                assertManualBoundary(stage, failure)
            }
        }
    }

    private fun assertManualBoundary(stage: String, failure: Throwable) {
        val root = Files.createTempDirectory("foxhole-about-boundary").toFile()
        try {
            var armed = false
            val logger = DiagnosticsLogger.forManualExportTests(
                context = TestExportContext(root),
                retentionProvider = {
                    if (armed && stage == "retention") throwBoundary(failure) else RetentionPolicy()
                },
                settingsSnapshotProvider = {
                    if (armed && stage == "settings") throwBoundary(failure) else Settings()
                },
            )
            armed = true
            val directory = File(root, "owned-journal").apply { mkdirs() }
            val name = if (stage == "cipher") "session-1-test.jsonl.enc" else "session-1-test.jsonl"
            val file = File(directory, name).apply {
                writeText("{\"timestamp\":1,\"tag\":\"t\",\"message\":\"owned\"}")
            }
            val before = file.readBytes()
            val store = DiagnosticsSessionStore(
                journalDir = directory,
                aboutEncryptedReader = { source ->
                    AndroidKeystoreFileCipher("unused-test-alias").readBytesExistingKey(source) {
                        throwBoundary(failure)
                    }
                },
                aboutPlaintextReader = { source ->
                    if (stage == "plaintext") throwBoundary(failure) else source.readText()
                },
                aboutLineDecoder = if (stage == "decoder") ({ throwBoundary(failure) }) else null,
            )
            val actual = runCatching {
                runBlocking(Dispatchers.IO) {
                    logger.createSanitizedAboutExportFile(parseNativeAggregateCounters("{}"), store)
                }
            }.exceptionOrNull()
            assertSame(failure, actual)
            if (failure is IdentityCancellation) assertSame(failure.identity, (actual as IdentityCancellation).identity)
            if (failure is IdentityFatal) assertSame(failure.identity, (actual as IdentityFatal).identity)
            assertArrayEquals(before, file.readBytes())
            assertEquals(listOf(file.name), directory.list().orEmpty().toList())
            assertTrue(File(root, "cache").walkTopDown().none { it.isFile && it.name.endsWith(".gz") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `prepared write deletes owned partial file and keeps original failure`() = runBlocking {
        listOf<Throwable>(CancellationException("write"), LinkageError("write")).forEach { failure ->
            val root = Files.createTempDirectory("foxhole-partial-export").toFile()
            try {
                val target = File(root, "owned-export.gz")
                val actual = runCatching {
                    writePreparedDiagnosticsArchive(
                        prepare = { "prepared" },
                        target = { target },
                        write = { file, _ ->
                            file.writeText("partial")
                            throw failure
                        },
                    )
                }.exceptionOrNull()
                assertSame(failure, actual)
                assertFalse(target.exists())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    private fun throwBoundary(failure: Throwable): Nothing = throw failure

    private class IdentityFatal(message: String) : LinkageError(message) {
        val identity = Any()
    }

    private class IdentityCancellation(message: String) : CancellationException(message) {
        val identity = Any()
    }

    private class TestExportContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getApplicationContext(): Context = this
    }
}
