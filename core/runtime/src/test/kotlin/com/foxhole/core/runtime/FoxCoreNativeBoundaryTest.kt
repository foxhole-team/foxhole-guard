package com.foxhole.core.runtime

import android.content.Context
import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.FoxCoreSignedDnsRuleSetUpdate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class FoxCoreNativeBoundaryTest {
    @Test
    fun `java facade declares production and compatibility boundary entries`() {
        val methods = FoxholeNativeEngine::class.java.declaredMethods.associateBy { it.name }

        listOf(
            "nativeImportLink",
            "nativeImportSubscription",
            "nativeTrafficMap",
            "nativeStartWithNetworkAndDnsRuleSet",
            "nativeConfirmContinuity",
        ).forEach { name ->
            val method = requireNotNull(methods[name])
            assertTrue("$name must remain a JNI declaration", Modifier.isNative(method.modifiers))
        }
    }

    @Test
    fun `a signed DNS bundle reaches the atomic signed start entry`() {
        val native = RecordingNativeApi()
        val payload =
            FoxCoreDnsStartPayload.Signed(
                name = "dns-main",
                manifest = byteArrayOf(1, 2),
                signature = byteArrayOf(3, 4),
                artifact = byteArrayOf(5, 6),
            )

        val handle = invokeFoxCoreNativeStart(native, 11, "{}", 22L, payload, TestHost)

        assertEquals(303L, handle)
        assertEquals(listOf("signed"), native.startCalls)
        assertArrayEquals(payload.manifest, native.manifest)
        assertArrayEquals(payload.signature, native.signature)
        assertArrayEquals(payload.artifact, native.artifact)
    }

    @Test
    fun `APK trusted and empty bootstrap keep their distinct start entries`() {
        val native = RecordingNativeApi()

        assertEquals(
            202L,
            invokeFoxCoreNativeStart(
                native,
                11,
                "{}",
                22L,
                FoxCoreDnsStartPayload.Trusted("dns-main", byteArrayOf(7)),
                TestHost,
            ),
        )
        assertEquals(
            101L,
            invokeFoxCoreNativeStart(
                native,
                11,
                "{}",
                22L,
                FoxCoreDnsStartPayload.None,
                TestHost,
            ),
        )
        assertEquals(listOf("trusted", "plain"), native.startCalls)
    }

    @Test
    fun `traffic map compatibility alias stays typed`() {
        val native = RecordingNativeApi()

        assertEquals("connections", native.trafficMap(9L))
        assertEquals(1, native.connectionCalls)
    }

    @Test
    fun `DNS trust changes replace the engine while data revisions stay live installable`() {
        val starter = FoxCoreNativeSessionStarter(RecordingNativeApi(), NoOpBoundaryDiagnostics)
        val first =
            FoxCoreDnsRuleSetBootstrap(
                name = "dns-main",
                artifactPath = "/data/one.fhds",
                artifactSha256 = "one",
                publicKeyBase64 = "key-a",
                minimumSequence = 1L,
                signedUpdate = FoxCoreSignedDnsRuleSetUpdate(
                    manifestPath = "/data/one.json",
                    signaturePath = "/data/one.sig",
                    artifactPath = "/data/one.fhds",
                ),
            )
        val updatedData =
            first.copy(
                artifactPath = "/data/two.fhds",
                artifactSha256 = "two",
                minimumSequence = 2L,
                signedUpdate = FoxCoreSignedDnsRuleSetUpdate(
                    manifestPath = "/data/two.json",
                    signaturePath = "/data/two.sig",
                    artifactPath = "/data/two.fhds",
                ),
            )
        val engine = """{"schema_version":1,"outbound":{"type":"direct"}}"""

        val withoutDns = starter.immutableEngineFingerprint(engine, null)
        val firstFingerprint = starter.immutableEngineFingerprint(engine, first)
        assertEquals(firstFingerprint, starter.immutableEngineFingerprint(engine, updatedData))
        assertNotEquals(withoutDns, firstFingerprint)
        assertNotEquals(firstFingerprint, starter.immutableEngineFingerprint(engine, first.copy(name = "dns-alt")))
        assertNotEquals(
            firstFingerprint,
            starter.immutableEngineFingerprint(
                engine,
                first.copy(publicKeyBase64 = "key-b"),
            ),
        )
    }
}

private class RecordingNativeApi : FoxCoreNativeApi {
    val startCalls = mutableListOf<String>()
    var manifest = byteArrayOf()
    var signature = byteArrayOf()
    var artifact = byteArrayOf()
    var connectionCalls = 0

    override fun version(): String = "test"

    override fun abiVersion(): Int = FoxholeNativeEngine.ABI_VERSION

    override fun capabilities(): String = "{}"

    override fun startWithNetwork(
        tunFd: Int,
        configJson: String,
        networkHandle: Long,
        host: RuntimeServiceHost,
    ): Long {
        startCalls += "plain"
        return 101L
    }

    override fun startWithNetworkAndDnsRuleSet(
        tunFd: Int,
        configJson: String,
        networkHandle: Long,
        name: String,
        manifest: ByteArray,
        signature: ByteArray,
        artifact: ByteArray,
        host: RuntimeServiceHost,
    ): Long {
        startCalls += "signed"
        this.manifest = manifest
        this.signature = signature
        this.artifact = artifact
        return 303L
    }

    override fun startWithNetworkAndTrustedDnsRuleSet(
        tunFd: Int,
        configJson: String,
        networkHandle: Long,
        name: String,
        artifact: ByteArray,
        host: RuntimeServiceHost,
    ): Long {
        startCalls += "trusted"
        this.artifact = artifact
        return 202L
    }

    override fun installDnsRuleSet(
        handle: Long,
        name: String,
        manifest: ByteArray,
        signature: ByteArray,
        artifact: ByteArray,
    ): Long = 1L

    override fun stop(handle: Long): Int = 0

    override fun forceKill(handle: Long): Int = 0

    override fun reloadPolicy(handle: Long, policyJson: String): Long = 1L

    override fun lastPolicyError(handle: Long): String = ""

    override fun stats(handle: Long): String = "{}"

    override fun connections(handle: Long): String {
        connectionCalls += 1
        return "connections"
    }

    override fun drainTrafficEvents(handle: Long, max: Int): String = "{}"

    override fun drainEvents(handle: Long, max: Int): String = "{}"

    override fun networkChanged(handle: Long) = Unit

    override fun networkChangedWithHandle(handle: Long, networkHandle: Long) = Unit
}

private object TestHost : RuntimeServiceHost {
    override val runtimeContext: Context
        get() = error("unused")

    override fun stopRuntimeService() = Unit

    override fun protectSocket(socket: Int): Boolean = true
}

private object NoOpBoundaryDiagnostics : RuntimeDiagnosticsSink {
    override fun record(tag: String, message: String) = Unit

    override fun recordStructured(tag: String, headline: String, vararg details: String?) = Unit
}
