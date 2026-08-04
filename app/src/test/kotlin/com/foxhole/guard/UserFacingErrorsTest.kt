package com.foxhole.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

class UserFacingErrorsTest {
    @Test
    fun `certificate cause wins over generic tls wrapper`() {
        val error =
            SSLHandshakeException("handshake failed for private.example")
                .apply { initCause(CertificateException("CertPath secret-host.example")) }

        assertEquals(
            UserFacingErrorKind.CERTIFICATE_VERIFICATION,
            classifyUserFacingError(error),
        )
    }

    @Test
    fun `timeouts map to stable reachability category`() {
        assertEquals(
            UserFacingErrorKind.SERVER_UNREACHABLE,
            classifyUserFacingError(SocketTimeoutException("api.private.example timed out")),
        )
    }

    @Test
    fun `unknown raw message never becomes a display category`() {
        assertEquals(
            UserFacingErrorKind.UNKNOWN,
            classifyUserFacingError(
                IllegalStateException("""java internals host=secret.example uuid=private-token"""),
            ),
        )
    }

    @Test
    fun `tor bootstrap without verified egress has a stable category`() {
        assertEquals(
            UserFacingErrorKind.TOR_VALIDATION,
            classifyUserFacingError(IllegalStateException("tor-only tunnel did not prove public egress through tor")),
        )
    }

    @Test
    fun `known user error sinks cannot regress to raw exception messages`() {
        val source =
            listOf(
                sourceDirectory("runtime"),
                sourceDirectory("ui"),
            ).flatMap { directory ->
                directory.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList()
            }.joinToString(separator = "\n") { file -> file.readText() }

        listOf(
            "emitError(error.message",
            "emitError(throwable.message",
            "fail(error.message",
            "showBanner(it.message",
            "state.reject(result.reason)",
            "PasswordSetupResult.Failed(",
        ).forEach { forbidden ->
            assertFalse("raw user-facing sink regressed: $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun `diagnostic label names every layer of the cause chain`() {
        val error =
            IllegalStateException("outer")
                .apply { initCause(SocketTimeoutException("inner")) }

        assertEquals(
            "IllegalStateException <- SocketTimeoutException",
            diagnosticFailureLabel(error),
        )
    }

    @Test
    fun `diagnostic label never carries the text of an exception this repository did not write`() {
        val label =
            diagnosticFailureLabel(
                SSLHandshakeException("handshake failed for private.example:443 uuid=secret-token"),
            )

        assertEquals("SSLHandshakeException", label)
        listOf("private.example", "secret-token", "443").forEach { value ->
            assertFalse("diagnostic label leaked $value into the journal", label.contains(value))
        }
    }

    @Test
    fun `diagnostic label of no error is still a word`() {
        assertEquals("unknown", diagnosticFailureLabel(null))
    }

    private fun sourceDirectory(relativePath: String): File =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/$relativePath"),
            File("app/src/main/kotlin/com/foxhole/guard/$relativePath"),
            File("../app/src/main/kotlin/com/foxhole/guard/$relativePath"),
        ).first(File::isDirectory)
}
