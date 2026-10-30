package com.foxhole.guard.core.backup

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BackupPickerBoundaryTest {
    @Test
    fun exportLaunchFailureClosesRequestClearsPendingAndReportsOrdinaryFailure() {
        val request = encryptedRequest()
        val failure = IOException("owned picker failure")
        var cleared = false
        var reported: Exception? = null
        launchPendingBackupExportPicker(
            request = request,
            clearPending = { cleared = true },
            onFailure = { reported = it },
            launch = { throw failure },
        )
        assertTrue(cleared)
        assertSame(failure, reported)
        assertThrows(IllegalStateException::class.java) { request.transfer() }
    }

    @Test
    fun importLaunchFailureClearsBusyAndReportsOrdinaryFailure() {
        var busy = true
        var reports = 0
        launchBackupPicker(
            cleanup = { busy = false },
            onFailure = { reports++ },
            launch = { throw IOException("owned picker failure") },
        )
        assertFalse(busy)
        assertEquals(1, reports)
    }

    @Test
    fun pickerCancellationPropagatesAfterRequestCleanupWithoutFailureBanner() {
        val request = encryptedRequest()
        val cancellation = CancellationException("owned picker cancellation")
        var cleared = false
        var reported = false
        val thrown = assertThrows(CancellationException::class.java) {
            launchPendingBackupExportPicker(request, { cleared = true }, { reported = true }) {
                throw cancellation
            }
        }
        assertSame(cancellation, thrown)
        assertTrue(cleared)
        assertFalse(reported)
        assertThrows(IllegalStateException::class.java) { request.transfer() }
    }

    @Test
    fun pickerFatalErrorPropagatesAfterRequestCleanupWithoutFailureBanner() {
        val request = encryptedRequest()
        val fatal = AssertionError("owned picker fatal marker")
        var cleared = false
        var reported = false
        val thrown = assertThrows(AssertionError::class.java) {
            launchPendingBackupExportPicker(request, { cleared = true }, { reported = true }) {
                throw fatal
            }
        }
        assertSame(fatal, thrown)
        assertTrue(cleared)
        assertFalse(reported)
        assertThrows(IllegalStateException::class.java) { request.transfer() }
    }

    @Test
    fun successfulPickerKeepsOwnedRequestAndUsesCapturedScopeFilename() {
        val request = encryptedRequest()
        var cleared = false
        var filename = ""
        launchPendingBackupExportPicker(request, { cleared = true }, { throw AssertionError(it) }) {
            filename = it
        }
        assertFalse(cleared)
        assertTrue(filename.contains("_profiles_"))
        val operation = request.transfer()
        try {
            assertTrue(operation.selection.encrypted)
            assertFalse(operation.selection.includeSettings)
        } finally {
            operation.close()
        }
    }

    @Test
    fun cancelledPickerClosesRequestAndReportsCancellationWithoutExport() {
        val request = encryptedRequest()
        var exports = 0
        var cancellations = 0
        completeBackupExportPicker<String>(null, request, { _, _ -> exports++ }, { cancellations++ })
        assertEquals(0, exports)
        assertEquals(1, cancellations)
        assertThrows(IllegalStateException::class.java) { request.transfer() }
    }

    @Test
    fun returnedUriWithoutEphemeralRequestReportsCancellationAndNeverCreatesPlainExport() {
        var exports = 0
        var cancellations = 0
        completeBackupExportPicker("owned-returned-uri", null, { _, _ -> exports++ }, { cancellations++ })
        assertEquals(0, exports)
        assertEquals(1, cancellations)
    }

    @Test
    fun pickerResultReleasesUiRequestWithoutWipingTransferredOperationPassword() {
        val request = encryptedRequest()
        lateinit var operation: OwnedBackupExportRequest
        completeBackupExportPicker("owned-uri", request, { _, pending -> operation = pending.transfer() }, {})
        lateinit var password: CharArray
        operation.withPassword { password = checkNotNull(it) }
        assertEquals("owned password", String(password))
        operation.close()
        assertTrue(password.all { it == '\u0000' })
    }

    private fun encryptedRequest() = PendingBackupExport.create(
        BackupExportSelection(includeSettings = false, encrypted = true),
        "owned password".toCharArray(),
    )
}
