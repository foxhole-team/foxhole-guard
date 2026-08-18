package com.foxhole.guard

import com.foxhole.core.model.RuntimeFailureCode
import com.foxhole.core.model.RuntimeFailureException
import com.foxhole.core.runtime.FoxCoreConfigRejection
import com.foxhole.core.runtime.FoxCoreConfigTranslationException
import com.foxhole.core.runtime.FoxCoreRuntimeException
import com.foxhole.core.runtime.FoxCoreRuntimeFailure
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
    fun `missing Android VPN network is reachability not DNS`() {
        val error =
            IllegalStateException(
                "validation failed",
                RuntimeFailureException(RuntimeFailureCode.VPN_NETWORK_MISSING, "vpn network unavailable"),
            )

        assertEquals(UserFacingErrorKind.SERVER_UNREACHABLE, classifyUserFacingError(error))
        assertEquals(
            R.string.vpn_error_server_timeout,
            userFacingErrorMessageRes(error, R.string.error_dns_probe_failed),
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
    fun `only a real link failure claims the native core is absent`() {
        assertEquals(
            UserFacingErrorKind.RUNTIME_LIBRARY_MISSING,
            classifyUserFacingError(UnsatisfiedLinkError("""dlopen failed: library "libfoxcore.so" not found""")),
        )
    }

    @Test
    fun `a fenced native start reads as a start timeout, not as an unreachable server`() {
        assertEquals(
            UserFacingErrorKind.RUNTIME_START_TIMEOUT,
            classifyUserFacingError(IllegalStateException(RUNTIME_START_TIMEOUT_MARKER)),
        )
    }

    @Test
    fun `an unexplained start failure keeps the caller's fallback instead of blaming the build`() {
        val fallback =
            userFacingErrorMessageRes(
                IllegalStateException("the vpn service stopped"),
                R.string.error_runtime_start_failed,
            )

        assertEquals(R.string.error_runtime_start_failed, fallback)
    }

    @Test
    fun `no runtime or ui sink falls back to the missing-core message`() {
        val source =
            listOf(
                sourceDirectory("runtime"),
                sourceDirectory("ui"),
            ).flatMap { directory ->
                directory.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList()
            }.joinToString(separator = "\n") { file -> file.readText() }

        assertFalse(
            "error_runtime_missing regressed into a fallback; classify the failure instead.",
            source.contains("error_runtime_missing"),
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
    fun `diagnostic label keeps a stable native failure code after release obfuscation`() {
        val constructor =
            FoxCoreRuntimeException::class.java
                .getDeclaredConstructor(FoxCoreRuntimeFailure::class.java)
                .apply { isAccessible = true }
        val error = constructor.newInstance(FoxCoreRuntimeFailure.NATIVE_START_FAILED)

        assertEquals(
            "FoxCoreRuntimeException(native_start_failed)",
            diagnosticFailureLabel(error),
        )
    }

    @Test
    fun `a native handover failure names FoxHole Core instead of an unknown error`() {
        val constructor =
            FoxCoreRuntimeException::class.java
                .getDeclaredConstructor(FoxCoreRuntimeFailure::class.java)
                .apply { isAccessible = true }
        val error = constructor.newInstance(FoxCoreRuntimeFailure.ALREADY_RUNNING)

        assertEquals(UserFacingErrorKind.FOXCORE_RUNTIME_FAILURE, classifyUserFacingError(error))
        assertEquals(
            R.string.error_foxcore_runtime_failed,
            userFacingErrorMessageRes(error, R.string.error_runtime_start_failed),
        )
    }

    @Test
    fun `diagnostic label keeps a stable config rejection and non-hostlike json path`() {
        val constructor =
            FoxCoreConfigTranslationException::class.java
                .getDeclaredConstructor(
                    FoxCoreConfigRejection::class.java,
                    String::class.java,
                    String::class.java,
                ).apply { isAccessible = true }
        val error =
            constructor.newInstance(
                FoxCoreConfigRejection.POLICY_UNREPRESENTABLE,
                "$.route.final",
                null,
            )

        assertEquals(
            "FoxCoreConfigTranslationException(policy_unrepresentable at $/route/final)",
            diagnosticFailureLabel(error),
        )
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
