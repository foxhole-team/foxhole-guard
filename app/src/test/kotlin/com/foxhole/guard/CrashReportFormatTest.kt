package com.foxhole.guard

import com.foxhole.core.model.DiagnosticSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The uncaught-crash report is the only crash artefact this app produces (no Play Vitals, no
 * crash-reporting SDK), and it is written into the journal that redacts everything that looks
 * like a host, an address or a secret. These tests pin both halves of that contract.
 */
class CrashReportFormatTest {
    private val thread = Thread("main")

    @Test
    fun `report survives the journal sanitizer unchanged`() {
        val report = formatUncaughtCrashReport(thread, crashWithStack())

        // Dotted class names read as hostnames to the sanitizer: an unescaped stack trace would be
        // persisted as a row of "[host]", destroying the only part worth keeping.
        assertEquals(report, DiagnosticSanitizer.sanitizeForPersistence(report))
        assertFalse(report, report.contains("[host]"))
        assertFalse(report, report.contains("[redacted]"))
    }

    @Test
    fun `report never carries the exception message`() {
        val secret = "connect failed to vless://user:hunter2@example.com:443"
        val report = formatUncaughtCrashReport(thread, IllegalStateException(secret))

        assertFalse(report, report.contains("hunter2"))
        assertFalse(report, report.contains("example"))
        assertFalse(report, report.contains("vless"))
    }

    @Test
    fun `report identifies thread type causes and frames`() {
        val report = formatUncaughtCrashReport(thread, crashWithStack())

        assertTrue(report, report.startsWith("uncaught exception thread=main"))
        assertTrue(report, report.contains("type=java/lang/IllegalStateException"))
        assertTrue(report, report.contains("causes=java/io/IOException"))
        assertTrue(report, report.contains("at=com/foxhole/guard/CrashReportFormatTest"))
    }

    @Test
    fun `a cyclic cause chain cannot loop forever`() {
        val outer = IllegalStateException()
        val inner = IllegalArgumentException(outer)
        outer.initCause(inner)

        val report = formatUncaughtCrashReport(thread, outer)

        assertTrue(report, report.contains("causes="))
        assertTrue(report, report.substringAfter("causes=").split(" <- ").size <= 4)
    }

    @Test
    fun `native frames are rendered without a bogus line number`() {
        val error = IllegalStateException()
        error.stackTrace =
            arrayOf(StackTraceElement("com.foxhole.guard.Native", "invoke", null, -2))

        val report = formatUncaughtCrashReport(thread, error)

        assertTrue(report, report.contains("at=com/foxhole/guard/Native#invoke@native"))
    }

    @Test
    fun `hex-shaped frames are not mistaken for addresses by the sanitizer`() {
        // `Foo#add:42` used to persist as `Foo#[ip]`: "add" is valid hex and the sanitizer reads
        // "<hex>:<digits>" as IPv6. The regression the '@' separator exists for.
        val error = IllegalStateException()
        error.stackTrace = arrayOf(StackTraceElement("com.foxhole.guard.Cafe", "add", "Cafe.kt", 42))

        val report = formatUncaughtCrashReport(thread, error)

        assertEquals(report, DiagnosticSanitizer.sanitizeForPersistence(report))
        assertTrue(report, report.contains("com/foxhole/guard/Cafe#add@42"))
    }

    private fun crashWithStack(): Throwable =
        runCatching {
            runCatching { throw java.io.IOException("socket closed") }
                .getOrElse { cause -> throw IllegalStateException("runtime start failed", cause) }
        }.exceptionOrNull()!!
}
