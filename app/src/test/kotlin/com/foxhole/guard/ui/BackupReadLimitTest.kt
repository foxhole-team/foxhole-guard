package com.foxhole.guard.ui

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class BackupReadLimitTest {
    @Test
    fun `capped reader returns an exact-boundary payload`() {
        assertEquals("12345678", ByteArrayInputStream("12345678".encodeToByteArray()).readBackupUtf8Capped(8))
    }

    @Test
    fun `capped reader aborts at max plus one without consuming the provider`() {
        val source = CountingInfiniteInputStream()

        assertThrows(BackupFileTooLargeException::class.java) {
            source.readBackupUtf8Capped(8)
        }
        assertEquals(9, source.bytesRead)
    }

    @Test
    fun `backup read helper never swallows cancellation or size limit`() {
        assertThrows(CancellationException::class.java) {
            backupReadOrNull<String> { throw CancellationException("cancelled") }
        }
        assertThrows(BackupFileTooLargeException::class.java) {
            backupReadOrNull<String> { throw BackupFileTooLargeException() }
        }
    }

    @Test
    fun `backup read helper maps ordinary provider failure to null`() {
        assertNull(backupReadOrNull<String> { throw IOException("provider failed") })
    }
}

private class CountingInfiniteInputStream : InputStream() {
    var bytesRead: Int = 0
        private set

    override fun read(): Int {
        bytesRead++
        return 'x'.code
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        buffer.fill('x'.code.toByte(), offset, offset + length)
        bytesRead += length
        return length
    }
}
