package com.foxhole.core.runtime

import android.net.Network
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.FoxCoreTunPlan
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class FoxCoreRuntimeHandoffAndroidTest {
    @Test
    fun handoffWhilePolicyJniIsPendingDoesNotSupersedeItsSession() = runBlocking {
        withRuntime { fixture ->
            val before = requireNotNull(fixture.active())
            val barrier = NativeCallBarrier()
            fixture.native.policyBarrier = barrier
            val reload = async(Dispatchers.Default) { fixture.runtime.reload(TEST_SESSION, fixture.host) }
            barrier.awaitEntry()

            fixture.host.network = TEST_NETWORK_B
            fixture.runtime.onDefaultNetworkAvailable()
            assertNotSame(before, fixture.active())
            assertSame(before.sessionIdentity, fixture.active()?.sessionIdentity)
            assertEquals(TEST_NETWORK_B.networkHandle, fixture.active()?.networkHandle)

            barrier.release()
            assertTrue(withTimeout(5_000) { reload.await() }.isSuccess)
            assertEquals(RuntimeState.RUNNING, fixture.runtime.nativeSnapshot().nativeState)
            assertEquals(2L, fixture.active()?.policyRevision)
            assertEquals(TEST_NETWORK_B.networkHandle, fixture.active()?.networkHandle)

            fixture.runtime.onDefaultNetworkAvailable()
            assertEquals(listOf(TEST_NETWORK_B.networkHandle), fixture.native.networkCalls.toList())
            assertTrue(fixture.runtime.reload(TEST_SESSION, fixture.host).isSuccess)
            assertEquals(listOf(1L, 2L), fixture.native.expectedRevisions.toList())
        }
    }

    @Test
    fun policyCompletionWhileHandoffJniIsPendingSurvivesTheHandoffPublication() = runBlocking {
        withRuntime { fixture ->
            fixture.host.network = TEST_NETWORK_B
            val barrier = NativeCallBarrier()
            fixture.native.networkBarrier = barrier
            val handoff = async(Dispatchers.Default) { fixture.runtime.onDefaultNetworkAvailable() }
            barrier.awaitEntry()

            assertTrue(fixture.runtime.reload(TEST_SESSION, fixture.host).isSuccess)
            assertEquals(2L, fixture.active()?.policyRevision)
            barrier.release()
            withTimeout(5_000) { handoff.await() }

            assertEquals(TEST_NETWORK_B.networkHandle, fixture.active()?.networkHandle)
            assertEquals(2L, fixture.active()?.policyRevision)
            assertTrue(fixture.runtime.reload(TEST_SESSION, fixture.host).isSuccess)
            assertEquals(listOf(1L, 2L), fixture.native.expectedRevisions.toList())
        }
    }

    @Test
    fun stopFencesAPendingPolicyCompletionAfterAHandoff() = runBlocking {
        withRuntime { fixture ->
            val barrier = NativeCallBarrier()
            fixture.native.policyBarrier = barrier
            val reload = async(Dispatchers.Default) { fixture.runtime.reload(TEST_SESSION, fixture.host) }
            barrier.awaitEntry()
            fixture.host.network = TEST_NETWORK_B
            fixture.runtime.onDefaultNetworkAvailable()

            assertTrue(withTimeout(5_000) { fixture.runtime.stop() }.graceful)
            assertNull(fixture.active())
            barrier.release()
            assertTrue(withTimeout(5_000) { reload.await() }.isFailure)
            assertNull(fixture.active())
            assertEquals(RuntimeState.IDLE, fixture.runtime.nativeSnapshot().nativeState)
            assertFalse(fixture.runtime.nativeSnapshot().hasEngineHandle)
            assertEquals(listOf(INITIAL_HANDLE), fixture.native.stoppedHandles.toList())
        }
    }

    @Test
    fun aLateHandoffCannotOverwriteTheReplacementSessionsNetwork() = runBlocking {
        withRuntime { fixture ->
            val before = requireNotNull(fixture.active())
            fixture.host.network = TEST_NETWORK_B
            val barrier = NativeCallBarrier()
            fixture.native.networkBarrier = barrier
            val handoff = async(Dispatchers.Default) { fixture.runtime.onDefaultNetworkAvailable() }
            barrier.awaitEntry()

            fixture.host.network = TEST_NETWORK_C
            val replacement = TEST_SESSION.copy(
                foxCoreConfig = TEST_CONFIG.copy(engineConfigJson = """{"schema_version":1,"outbound":{"kind":"direct"}}"""),
            )
            assertTrue(fixture.runtime.reload(replacement, fixture.host).isSuccess)
            val next = requireNotNull(fixture.active())
            assertNotSame(before.sessionIdentity, next.sessionIdentity)
            assertEquals(REPLACEMENT_HANDLE, next.handle)
            assertEquals(TEST_NETWORK_C.networkHandle, next.networkHandle)

            barrier.release()
            withTimeout(5_000) { handoff.await() }
            assertSame(next.sessionIdentity, fixture.active()?.sessionIdentity)
            assertEquals(TEST_NETWORK_C.networkHandle, fixture.active()?.networkHandle)
            assertEquals(listOf(INITIAL_HANDLE), fixture.native.stoppedHandles.toList())
        }
    }
}

private suspend fun withRuntime(block: suspend CoroutineScope.(RuntimeFixture) -> Unit) = coroutineScope {
    val fixture = RuntimeFixture()
    try {
        block(fixture)
    } finally {
        fixture.native.releaseCalls()
        fixture.runtime.stop()
        fixture.closeDescriptors()
    }
}

private class RuntimeFixture {
    val native = ControlledNativeCalls()
    val host = HandoffRuntimeHost()
    val runtime = FoxCoreRuntime(diagnosticsLogger = HandoffDiagnostics, native = native.api)
    private val pipe = ParcelFileDescriptor.createPipe()
    private val activeField = FoxCoreRuntime::class.java.getDeclaredField("active").apply { isAccessible = true }

    init {
        val fingerprint = requireNotNull(
            FoxCoreNativeSessionStarter(native.api, HandoffDiagnostics)
                .immutableEngineFingerprint(TEST_CONFIG.engineConfigJson, null),
        )
        // Seed an established session without requesting a VPN grant or modifying device routing.
        activeField.set(
            runtime,
            ActiveFoxCoreSession(
                handle = INITIAL_HANDLE,
                tun = pipe[0],
                masterTunFd = pipe[0].fd,
                nativeTunFd = -1,
                host = host,
                translated = TEST_CONFIG,
                immutableFingerprint = fingerprint,
                policyRevision = 1L,
                networkHandle = TEST_NETWORK_A.networkHandle,
                dnsServers = TEST_CONFIG.tunPlan.advertisedDnsServers,
            ),
        )
    }

    fun active(): ActiveFoxCoreSession? = activeField.get(runtime) as? ActiveFoxCoreSession

    fun closeDescriptors() {
        pipe.forEach { runCatching { it.close() } }
    }
}

private class HandoffRuntimeHost : RuntimeServiceHost {
    override val runtimeContext = InstrumentationRegistry.getInstrumentation().targetContext

    @Volatile
    var network: Network = TEST_NETWORK_A

    override fun currentUnderlyingNetwork(): Network = network

    override fun protectSocket(socket: Int): Boolean = true

    override fun stopRuntimeService() = Unit
}

private class NativeCallBarrier {
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun enter() {
        entered.countDown()
        check(released.await(10, TimeUnit.SECONDS)) { "Native test call was never released" }
    }

    fun awaitEntry() {
        assertTrue("Native call did not reach its barrier", entered.await(5, TimeUnit.SECONDS))
    }

    fun release() = released.countDown()
}

private class ControlledNativeCalls {
    @Volatile
    var policyBarrier: NativeCallBarrier? = null

    @Volatile
    var networkBarrier: NativeCallBarrier? = null

    val expectedRevisions = Collections.synchronizedList(mutableListOf<Long>())
    val networkCalls = Collections.synchronizedList(mutableListOf<Long>())
    val stoppedHandles = Collections.synchronizedList(mutableListOf<Long>())
    private val revision = AtomicLong(1L)

    val api = Proxy.newProxyInstance(
        FoxCoreNativeApi::class.java.classLoader,
        arrayOf(FoxCoreNativeApi::class.java),
    ) { _, method, arguments ->
        val args = arguments.orEmpty()
        when (method.name) {
            "reloadPolicy" -> {
                expectedRevisions += Json.parseToJsonElement(args[1] as String)
                    .jsonObject.getValue("expected_revision").jsonPrimitive.long
                policyBarrier?.enter()
                revision.incrementAndGet()
            }
            "networkChangedWithHandle" -> {
                networkCalls += args[1] as Long
                networkBarrier?.enter()
                null
            }
            "stats" -> "{}"
            "stop" -> {
                stoppedHandles += args[0] as Long
                FoxholeNativeEngine.STOPPED
            }
            "startWithNetwork" -> {
                ParcelFileDescriptor.adoptFd(args[0] as Int).close()
                REPLACEMENT_HANDLE
            }
            else -> error("Unexpected native call: ${method.name}")
        }
    } as FoxCoreNativeApi

    fun releaseCalls() {
        policyBarrier?.release()
        networkBarrier?.release()
    }
}

private object HandoffDiagnostics : RuntimeDiagnosticsSink {
    override fun record(tag: String, message: String) = Unit

    override fun recordStructured(tag: String, headline: String, vararg details: String?) = Unit
}

private const val INITIAL_HANDLE = 71L
private const val REPLACEMENT_HANDLE = 72L
private val TEST_NETWORK_A = testNetwork(101)
private val TEST_NETWORK_B = testNetwork(102)
private val TEST_NETWORK_C = testNetwork(103)

private fun testNetwork(netId: Int): Network {
    // The public Parcelable boundary is available on API 26; fromNetworkHandle requires API 28.
    val parcel = Parcel.obtain()
    return try {
        parcel.writeInt(netId)
        parcel.setDataPosition(0)
        Network.CREATOR.createFromParcel(parcel)
    } finally {
        parcel.recycle()
    }
}
private val TEST_CONFIG = FoxCoreSessionConfig(
    engineConfigJson = """{"schema_version":1}""",
    policyConfigJson = "{}",
    tunPlan = FoxCoreTunPlan(
        mtu = 1_500,
        ipv4Address = "172.19.0.1",
        ipv4PrefixLength = 30,
        ipv6Address = null,
        ipv6PrefixLength = null,
        routes = emptyList(),
        advertisedDnsServers = listOf("172.19.0.2"),
    ),
)
private val TEST_SESSION = VpnSession(
    profileId = 1L,
    profileName = "runtime-handoff-test",
    protocolHint = ProtocolHint.LOCAL_GUARD,
    configJson = "{}",
    correlationId = "runtime-handoff-test",
    foxCoreConfig = TEST_CONFIG,
)
