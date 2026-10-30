package com.foxhole.core.runtime

import android.content.Context
import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.FoxCoreSignedDnsRuleSetUpdate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
            "nativeRetainDnsContinuation",
            "nativeReleaseDnsContinuation",
            "nativeStartOnNetworkWithDnsContinuity",
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
    fun `DNS continuity preserves empty trusted and signed bootstrap shapes`() {
        val native = RecordingNativeApi()
        val payloads = listOf(
            FoxCoreDnsStartPayload.None,
            FoxCoreDnsStartPayload.Trusted("dns-main", byteArrayOf(7)),
            FoxCoreDnsStartPayload.Signed("dns-main", byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)),
        )
        payloads.forEach { payload ->
            assertEquals(404L, invokeFoxCoreNativeStart(native, 11, "{}", 22L, payload, TestHost, 77L))
        }
        assertEquals(listOf("continuity", "continuity", "continuity"), native.startCalls)
        val none = native.continuityCalls[0]
        assertEquals(77L, none.token)
        assertNull(none.name)
        assertNull(none.manifest)
        assertNull(none.signature)
        assertNull(none.artifact)
        assertTrue(!none.trusted)
        val trusted = native.continuityCalls[1]
        assertEquals("dns-main", trusted.name)
        assertNull(trusted.manifest)
        assertNull(trusted.signature)
        assertArrayEquals(byteArrayOf(7), trusted.artifact)
        assertTrue(trusted.trusted)
        val signed = native.continuityCalls[2]
        assertEquals("dns-main", signed.name)
        assertArrayEquals(byteArrayOf(1), signed.manifest)
        assertArrayEquals(byteArrayOf(2), signed.signature)
        assertArrayEquals(byteArrayOf(3), signed.artifact)
        assertTrue(!signed.trusted)
    }

    @Test
    fun `continuity start facade retains the typed JNI parameter order`() {
        val method = FoxholeNativeEngine::class.java.getDeclaredMethod(
            "nativeStartOnNetworkWithDnsContinuity",
            Int::class.javaPrimitiveType, String::class.java,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            String::class.java, ByteArray::class.java, ByteArray::class.java, ByteArray::class.java,
            Boolean::class.javaPrimitiveType, Any::class.java,
        )
        assertTrue(Modifier.isNative(method.modifiers))
        assertTrue(Modifier.isStatic(method.modifiers))
        assertEquals(Long::class.javaPrimitiveType, method.returnType)
    }

    @Test
    fun `traffic map compatibility alias stays typed`() {
        val native = RecordingNativeApi()

        assertEquals("connections", native.trafficMap(9L))
        assertEquals(1, native.connectionCalls)
    }

    @Test
    fun bootstrapResolverPolicyChangesRequireNativeReplacement() {
        val starter = FoxCoreNativeSessionStarter(RecordingNativeApi(), NoOpBoundaryDiagnostics)
        val original = """{"schema_version":1,"outbound":{"type":"vless","server":"group.example"},"dns":{"mode":"real_ip"},"bootstrap_dns":{"domains":["group.example"],"upstreams":[{"type":"udp","address":"203.0.113.53:53"}]}}"""
        val changed = original.replace("203.0.113.53", "203.0.113.54")
        val first = requireNotNull(starter.immutableEngineFingerprint(original, null))
        assertNotEquals(first, starter.immutableEngineFingerprint(changed, null))
        assertNotEquals(
            first,
            starter.immutableEngineFingerprint(
                """{"schema_version":1,"outbound":{"type":"vless","server":"group.example"}}""",
                null,
            ),
        )
        assertEquals(first, starter.immutableEngineFingerprint(original.replace("real_ip", "fake_ip"), null))
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
    val continuityCalls = mutableListOf<ContinuityStartCall>()

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

    override fun retainDnsContinuation(handle: Long): Long = 77L

    override fun releaseDnsContinuation(token: Long) = Unit

    override fun startOnNetworkWithDnsContinuity(
        tunFd: Int,
        configJson: String,
        networkHandle: Long,
        token: Long,
        name: String?,
        manifest: ByteArray?,
        signature: ByteArray?,
        artifact: ByteArray?,
        trusted: Boolean,
        host: RuntimeServiceHost,
    ): Long {
        startCalls += "continuity"
        continuityCalls += ContinuityStartCall(token, name, manifest, signature, artifact, trusted)
        return 404L
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

private data class ContinuityStartCall(
    val token: Long,
    val name: String?,
    val manifest: ByteArray?,
    val signature: ByteArray?,
    val artifact: ByteArray?,
    val trusted: Boolean,
)
