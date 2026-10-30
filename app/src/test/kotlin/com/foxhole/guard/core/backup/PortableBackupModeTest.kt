package com.foxhole.guard.core.backup

import com.foxhole.guard.core.security.GuardCrypto
import com.foxhole.guard.core.security.GuardKdfParams
import com.foxhole.guard.core.security.GuardKeypair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PortableBackupModeTest {
    private val document = BackupDocument(
        appVersionName = "owned test",
        profiles = listOf(
            BackupProfilePayload(name = "Owned profile", sourceType = "RAW_CONFIG_JSON", rawInput = "owned-secret"),
        )
    )

    @Test
    fun defaultExportIsPlainAndPasswordlessWithoutDerivingAKey() {
        val crypto = CountingCrypto()
        val pending = PendingBackupExport.create(BackupExportSelection())
        val operation = pending.transfer()
        try {
            val payload = operation.encode(document, crypto)
            assertTrue(payload.contains(BACKUP_DOCUMENT_FORMAT))
            assertTrue(payload.contains("owned-secret"))
            assertEquals(0, crypto.calls)
            assertTrue(decodeBackupDocument(payload) is BackupParseResult.Success)
        } finally {
            operation.close()
        }
    }

    @Test
    fun checkedExportUsesEncryptedEnvelopeAndPassword() {
        val crypto = CountingCrypto()
        val input = "owned password".toCharArray()
        val pending = PendingBackupExport.create(BackupExportSelection(encrypted = true), input)
        assertTrue(input.all { it == '\u0000' })
        val operation = pending.transfer()
        try {
            val payload = operation.encode(document, crypto)
            assertTrue(payload.contains(ENCRYPTED_BACKUP_FORMAT))
            assertFalse(payload.contains("owned-secret"))
            assertEquals(1, crypto.calls)
            assertTrue(
                decryptBackupDocument(payload, "owned password".toCharArray(), crypto) is BackupParseResult.Success
            )
        } finally {
            operation.close()
        }
    }

    @Test
    fun pendingCloseCannotWipePasswordTransferredToOperation() {
        val pending = PendingBackupExport.create(
            BackupExportSelection(encrypted = true),
            "owned password".toCharArray()
        )
        val operation = pending.transfer()
        var owned: CharArray? = null
        operation.withPassword { owned = it }
        pending.close()
        assertEquals("owned password", String(checkNotNull(owned)))
        operation.close()
        assertTrue(checkNotNull(owned).all { it == '\u0000' })
    }

    @Test
    fun selectorChangesAfterRequestCreationCannotChangeExportModeOrScope() {
        var selection = BackupExportSelection(includeProfiles = true, includeSettings = false)
        val pending = PendingBackupExport.create(selection)
        selection = BackupExportSelection(includeProfiles = false, includeSettings = true, encrypted = true)
        val operation = pending.transfer()
        try {
            assertTrue(operation.selection.includeProfiles)
            assertFalse(operation.selection.includeSettings)
            assertFalse(operation.selection.encrypted)
            assertTrue(selection.encrypted)
        } finally {
            operation.close()
        }
    }

    @Test
    fun exportRequestCannotTransferPasswordTwice() {
        val pending = PendingBackupExport.create(
            BackupExportSelection(encrypted = true),
            "owned password".toCharArray()
        )
        val operation = pending.transfer()
        try {
            assertThrows(IllegalStateException::class.java) { pending.transfer() }
        } finally {
            operation.close()
        }
    }

    @Test
    fun cancelledScopeWipesOwnedPasswordEvenWhenBodyNeverStarts() = runBlocking {
        val operation = PendingBackupExport.create(
            BackupExportSelection(encrypted = true),
            "owned password".toCharArray()
        ).transfer()
        var owned: CharArray? = null
        operation.withPassword { owned = it }
        val scope = CoroutineScope(SupervisorJob())
        scope.cancel()
        var ran = false
        val job = scope.launchOwnedBackupExport(operation) { ran = true }
        job.join()
        assertFalse(ran)
        assertTrue(checkNotNull(owned).all { it == '\u0000' })
    }

    @Test
    fun cancellingRunningOperationWipesAfterItsOwnedBodyEnds() = runBlocking {
        val operation = PendingBackupExport.create(
            BackupExportSelection(encrypted = true),
            "owned password".toCharArray()
        ).transfer()
        var owned: CharArray? = null
        operation.withPassword { owned = it }
        val entered = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob())
        val job = scope.launchOwnedBackupExport(operation) {
            entered.complete(Unit)
            awaitCancellation()
        }
        try {
            withTimeout(5_000) { entered.await() }
            assertEquals("owned password", String(checkNotNull(owned)))
            job.cancelAndJoin()
            assertTrue(checkNotNull(owned).all { it == '\u0000' })
        } finally {
            scope.cancel()
            job.cancelAndJoin()
        }
    }

    @Test
    fun cancelledImportScopeWipesTransferredPasswordWithoutRunningDecoder() = runBlocking {
        val supplied = "owned password".toCharArray()
        val operation = OwnedBackupPassword.take(supplied)
        assertTrue(supplied.all { it == '\u0000' })
        lateinit var owned: CharArray
        operation.usePassword { owned = it }
        val scope = CoroutineScope(SupervisorJob())
        scope.cancel()
        var ran = false
        scope.launchOwnedBackupWork(operation) { ran = true }.join()
        assertFalse(ran)
        assertTrue(owned.all { it == '\u0000' })
    }

    @Test
    fun importOwnsPasswordUntilCipherOperationFinishesAndThenWipesIt() = runBlocking {
        val prepared = (preparePortableBackupPayload(envelope()) as BackupImportPreparation.Ready)
            .prepared as PreparedBackupImport.Encrypted
        val operation = OwnedBackupPassword.take("owned password".toCharArray())
        lateinit var owned: CharArray
        operation.usePassword { owned = it }
        val entered = CompletableDeferred<Unit>()
        val releaseKdf = CountDownLatch(1)
        val crypto = CountingCrypto().apply {
            beforeKdf = {
                entered.complete(Unit)
                assertTrue(releaseKdf.await(5, TimeUnit.SECONDS))
            }
        }
        val scope = CoroutineScope(SupervisorJob())
        val job = scope.launchOwnedBackupWork(operation) { password ->
            withContext(Dispatchers.Default) {
                password.usePassword { prepared.unlock(it, crypto) }
            }
        }
        try {
            withTimeout(5_000) { entered.await() }
            job.cancel()
            assertEquals("owned password", String(owned))
            releaseKdf.countDown()
            withTimeout(5_000) { job.join() }
            assertTrue(owned.all { it == '\u0000' })
            assertEquals(1, crypto.calls)
        } finally {
            releaseKdf.countDown()
            scope.cancel()
            job.cancelAndJoin()
        }
    }

    @Test
    fun plaintextImportIsPreparedWithoutPasswordOrKdf() {
        val result = preparePortableBackupPayload(encodeBackupDocument(document))
        assertTrue(result is BackupImportPreparation.Ready)
        assertTrue((result as BackupImportPreparation.Ready).prepared is PreparedBackupImport.Plain)
    }

    @Test
    fun encryptedImportRetainsOneReadSnapshotAcrossPasswordPrompt() = runBlocking {
        val crypto = CountingCrypto()
        val first = encryptBackupDocument(document, "owned password".toCharArray(), crypto)
        var supplied = first
        var reads = 0
        val result = preparePortableBackupImportOnce {
            reads++
            supplied
        }
        val prepared = (result as BackupImportPreparation.Ready).prepared as PreparedBackupImport.Encrypted
        supplied = encodeBackupDocument(BackupDocument(appVersionName = "replaced provider"))
        val parsed = prepared.unlock("owned password".toCharArray(), crypto) as BackupParseResult.Success
        assertEquals("owned test", parsed.document.appVersionName)
        assertEquals(1, reads)
        assertEquals(2, crypto.calls)
    }

    @Test
    fun malformedEncryptedPayloadDoesNotBecomePlaintext() {
        val payload = """{"format":"$ENCRYPTED_BACKUP_FORMAT","profiles":[],"settings":null}"""
        assertEquals(BackupParseResult.Failure.MALFORMED, failure(preparePortableBackupPayload(payload)))
    }

    @Test
    fun plaintextTagWithCipherFieldsIsRejectedAsAmbiguous() {
        val payload = """{"format":"$BACKUP_DOCUMENT_FORMAT","ciphertext":"AAAA","nonce":"AAAA","salt":"AAAA","profiles":[]}"""
        assertEquals(BackupParseResult.Failure.MALFORMED, failure(preparePortableBackupPayload(payload)))
    }

    @Test
    fun unknownBackupFormatDoesNotPromptForPassword() {
        assertEquals(
            BackupParseResult.Failure.NOT_A_BACKUP,
            failure(preparePortableBackupPayload("""{"format":"owned-unknown"}"""))
        )
    }

    @Test
    fun unsupportedEncryptedVersionIsRejectedBeforePassword() {
        val payload = envelope().replace("\"formatVersion\": 1", "\"formatVersion\": 999")
        assertEquals(
            BackupParseResult.Failure.UNSUPPORTED_FORMAT_VERSION,
            failure(preparePortableBackupPayload(payload))
        )
    }

    @Test
    fun unsupportedKdfIsRejectedBeforePassword() {
        val payload = envelope().replace("argon2id13", "owned-unsupported")
        assertEquals(BackupParseResult.Failure.UNSUPPORTED_ENCRYPTION, failure(preparePortableBackupPayload(payload)))
    }

    @Test
    fun unsupportedMemoryParameterIsRejectedBeforePassword() {
        val payload = envelope().replace("\"memKib\": 65536", "\"memKib\": 32768")
        assertEquals(BackupParseResult.Failure.UNSUPPORTED_ENCRYPTION, failure(preparePortableBackupPayload(payload)))
    }

    @Test
    fun unsupportedOperationsParameterIsRejectedBeforePassword() {
        val payload = envelope().replace("\"ops\": 3", "\"ops\": 4")
        assertEquals(BackupParseResult.Failure.UNSUPPORTED_ENCRYPTION, failure(preparePortableBackupPayload(payload)))
    }

    @Test
    fun unsupportedParallelismParameterIsRejectedBeforePassword() {
        val payload = envelope().replace("\"parallelism\": 1", "\"parallelism\": 2")
        assertEquals(BackupParseResult.Failure.UNSUPPORTED_ENCRYPTION, failure(preparePortableBackupPayload(payload)))
    }

    @Test
    fun malformedNonceIsRejectedBeforePassword() {
        val payload = envelope().replace(Regex("\"nonce\"\\s*:\\s*\"[^\"]*\""), "\"nonce\": \"!\"")
        assertEquals(BackupParseResult.Failure.MALFORMED, failure(preparePortableBackupPayload(payload)))
    }

    @Test
    fun wrongEncryptedPasswordIsAnEncryptedFailureWithoutPlaintextRetry() {
        val crypto = CountingCrypto()
        val prepared = (
            (
                preparePortableBackupPayload(
                    envelope()
                ) as BackupImportPreparation.Ready
                ).prepared as PreparedBackupImport.Encrypted
            )
        val result = prepared.unlock("owned wrong password".toCharArray(), crypto)
        assertEquals(BackupParseResult.Failure.WRONG_PASSWORD_OR_CORRUPT, result)
        assertEquals(1, crypto.calls)
    }

    @Test
    fun encryptedKdfCancellationPropagates() {
        val crypto = CountingCrypto()
        val cancelled = CancellationException("owned KDF cancellation")
        crypto.failure = cancelled
        val prepared = (
            (
                preparePortableBackupPayload(
                    envelope()
                ) as BackupImportPreparation.Ready
                ).prepared as PreparedBackupImport.Encrypted
            )
        assertSame(cancelled, thrownBy { prepared.unlock("owned password".toCharArray(), crypto) })
    }

    @Test
    fun encryptedKdfFatalErrorPropagates() {
        val crypto = CountingCrypto()
        val fatal = AssertionError("owned KDF fatal marker")
        crypto.failure = fatal
        val prepared = (
            (
                preparePortableBackupPayload(
                    envelope()
                ) as BackupImportPreparation.Ready
                ).prepared as PreparedBackupImport.Encrypted
            )
        assertSame(fatal, thrownBy { prepared.unlock("owned password".toCharArray(), crypto) })
    }

    @Test
    fun cancelledReaderDoesNotReturnAnOrdinaryPreparationFailure() = runBlocking {
        val cancellation = CancellationException("owned reader cancellation")
        val actual = try {
            preparePortableBackupImportOnce { throw cancellation }
            null
        } catch (e: Throwable) { e }
        assertSame(cancellation, actual)
    }

    private fun envelope() = encryptBackupDocument(document, "owned password".toCharArray(), CountingCrypto())
    private fun failure(result: BackupImportPreparation): BackupParseResult.Failure {
        assertTrue(
            "Expected pre-prompt failure: ${result.javaClass.simpleName}",
            result is BackupImportPreparation.Failure
        )
        return (result as BackupImportPreparation.Failure).reason
    }
    private fun thrownBy(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (e: Throwable) { e }
}

private class CountingCrypto : GuardCrypto {
    var calls = 0
    var failure: Throwable? = null
    var beforeKdf: () -> Unit = {}
    override fun argon2id(password: ByteArray, salt: ByteArray, params: GuardKdfParams, outputLength: Int): ByteArray {
        calls++
        beforeKdf()
        failure?.let { throw it }
        val digest = MessageDigest.getInstance("SHA-256").digest(password + salt)
        return ByteArray(outputLength) { digest[it % digest.size] }
    }
    override fun sealedBoxKeypair(): GuardKeypair = error("not used")
    override fun seal(payload: ByteArray, publicKey: ByteArray): ByteArray = error("not used")
    override fun openSealed(sealed: ByteArray, publicKey: ByteArray, privateKey: ByteArray): ByteArray = error(
        "not used"
    )
}
