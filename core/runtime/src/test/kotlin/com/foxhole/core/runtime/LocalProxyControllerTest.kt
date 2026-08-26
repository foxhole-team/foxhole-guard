package com.foxhole.core.runtime

import com.foxhole.core.model.LocalProxyPhase
import com.foxhole.core.model.LocalProxyUpstream
import com.foxhole.core.runtime.network.HttpProxyAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProxyControllerTest {
    private class FakeNative(
        private val startCode: Int = 0,
        private var document: String = "",
    ) : FoxCoreNativeApi {
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()

        fun publish(json: String) {
            document = json
        }

        override fun startLoopbackInbound(handle: Long, configJson: String): Int {
            started += configJson
            return startCode
        }

        override fun stopLoopbackInbound(handle: Long, name: String): Int {
            stopped += name
            return 0
        }

        override fun loopbackInbounds(handle: Long): String = document

        override fun version(): String = "test"

        override fun abiVersion(): Int = FoxholeNativeEngine.ABI_VERSION

        override fun capabilities(): String = "{}"

        override fun startWithNetwork(
            tunFd: Int,
            configJson: String,
            networkHandle: Long,
            host: RuntimeServiceHost,
        ): Long = 0L

        override fun startWithNetworkAndTrustedDnsRuleSet(
            tunFd: Int,
            configJson: String,
            networkHandle: Long,
            name: String,
            artifact: ByteArray,
            host: RuntimeServiceHost,
        ): Long = 0L

        override fun installDnsRuleSet(
            handle: Long,
            name: String,
            manifest: ByteArray,
            signature: ByteArray,
            artifact: ByteArray,
        ): Long = 0L

        override fun stop(handle: Long): Int = 0

        override fun forceKill(handle: Long): Int = 0

        override fun reloadPolicy(
            handle: Long,
            policyJson: String,
        ): Long = 0L

        override fun lastPolicyError(handle: Long): String = ""

        override fun stats(handle: Long): String = "{}"

        override fun connections(handle: Long): String = "{}"

        override fun drainTrafficEvents(
            handle: Long,
            max: Int,
        ): String = "{}"

        override fun drainEvents(
            handle: Long,
            max: Int,
        ): String = "{}"

        override fun networkChanged(handle: Long) = Unit

        override fun networkChangedWithHandle(
            handle: Long,
            networkHandle: Long,
        ) = Unit
    }

    private fun ready(port: Int = 47821) =
        """{"inbounds":[{"name":"$LOCAL_PROXY_INBOUND_NAME","upstream":"profile","state":"ready","http_address":"127.0.0.1:$port"}]}"""

    private fun torProbeReady(port: Int = 47822, host: String = "127.0.0.1") =
        """{"inbounds":[{"name":"$TOR_PROBE_INBOUND_NAME","upstream":"tor","state":"ready","http_address":"$host:$port"}]}"""

    private fun request(port: Int = 0, auth: Boolean = true) =
        LocalProxyRequest(
            port = port,
            username = "user".takeIf { auth },
            password = "secret".takeIf { auth },
            upstream = LocalProxyUpstream.PROFILE,
        )

    @Test
    fun `the address comes from the core, not from the request`() {
        val native = FakeNative(document = ready(port = 47821))
        val controller = LocalProxyController(native) { 7L }

        val status = controller.sync(handle = 1L, request = request(port = 0))

        assertEquals(LocalProxyPhase.SERVING, status.phase)
        assertEquals("127.0.0.1:47821", status.address)
        assertEquals(LocalProxyUpstream.PROFILE, status.upstream)
    }

    @Test
    fun `an anonymous request carries no credentials into the document`() {
        val native = FakeNative(document = ready())
        val controller = LocalProxyController(native) { 7L }

        controller.sync(handle = 1L, request = request(auth = false))

        val document = native.started.single()
        assertFalse("логин не должен появиться из ниоткуда", document.contains("username"))
        assertFalse("пароль не должен появиться из ниоткуда", document.contains("password"))
        assertTrue(document.contains("\"upstream\":\"profile\""))
    }

    @Test
    fun `an unchanged request does not rebind the listener`() {
        val native = FakeNative(document = ready())
        val controller = LocalProxyController(native) { 7L }

        controller.sync(handle = 1L, request = request())
        controller.sync(handle = 1L, request = request())

        assertEquals(1, native.started.size)
        assertTrue(native.stopped.isEmpty())
    }

    @Test
    fun `a changed request takes the old listener down first`() {
        val native = FakeNative(document = ready())
        val controller = LocalProxyController(native) { 7L }

        controller.sync(handle = 1L, request = request(auth = true))
        controller.sync(handle = 1L, request = request(auth = false))

        assertEquals(2, native.started.size)
        assertEquals(listOf(LOCAL_PROXY_INBOUND_NAME), native.stopped)
    }

    @Test
    fun `without a session the switch is armed rather than serving`() {
        val native = FakeNative()
        val controller = LocalProxyController(native) { 7L }

        val status = controller.sync(handle = null, request = request())

        assertEquals(LocalProxyPhase.ARMING, status.phase)
        assertNull("адреса нет, пока нет сессии", status.address)
        assertTrue(native.started.isEmpty())
    }

    @Test
    fun `a refused start reports unavailable`() {
        val native = FakeNative(startCode = -7, document = ready())
        val controller = LocalProxyController(native) { 7L }

        val status = controller.sync(handle = 1L, request = request())

        assertEquals(LocalProxyPhase.UNAVAILABLE, status.phase)
        assertNull(status.address)
    }

    @Test
    fun `taking the scenario away stops the listener`() {
        val native = FakeNative(document = ready())
        val controller = LocalProxyController(native) { 7L }
        controller.sync(handle = 1L, request = request())

        val status = controller.sync(handle = 1L, request = null)

        assertEquals(LocalProxyPhase.OFF, status.phase)
        assertEquals(listOf(LOCAL_PROXY_INBOUND_NAME), native.stopped)
    }

    @Test
    fun `an inbound with another name is not ours`() {
        val other =
            """{"inbounds":[{"name":"webapp.a","upstream":"tor","state":"ready","http_address":"127.0.0.1:5"}]}"""

        val status = parseLocalProxyStatusJson(other, now = 7L)

        assertEquals(LocalProxyPhase.OFF, status?.phase)
        assertNull(status?.address)
    }

    @Test
    fun `an unreadable document is not a state at all`() {
        assertNull(parseLocalProxyStatusJson("", now = 7L))
        assertNull(parseLocalProxyStatusJson("not json", now = 7L))
    }

    @Test
    fun `tor probe starts authenticated on loopback with tor upstream`() {
        val native = FakeNative(document = torProbeReady())
        val controller = TorProbeProxyController(native, credentialFactory = { "probe-user" to "probe-secret" })
        val owner = TorProbeProxyOwner("session-a", 11L)

        val lease = controller.sync(handle = 1L, requestedOwner = owner).getOrThrow()
        val config = native.started.single()

        assertEquals(owner, lease?.owner)
        assertEquals("127.0.0.1", lease?.access?.host)
        assertEquals(47822, lease?.access?.port)
        assertEquals("probe-user", lease?.access?.username)
        assertEquals("probe-secret", lease?.access?.password)
        assertTrue(config.contains("\"name\":\"$TOR_PROBE_INBOUND_NAME\""))
        assertTrue(config.contains("\"upstream\":\"tor\""))
        assertTrue(config.contains("\"username\":\"probe-user\""))
        assertTrue(config.contains("\"password\":\"probe-secret\""))
    }

    @Test
    fun `repeated tor probe sync reuses one inbound and teardown closes it`() {
        val native = FakeNative(document = torProbeReady())
        val controller = TorProbeProxyController(native, credentialFactory = { "u" to "p" })
        val owner = TorProbeProxyOwner("session-a", 11L)

        controller.sync(handle = 1L, requestedOwner = owner).getOrThrow()
        controller.sync(handle = 1L, requestedOwner = owner).getOrThrow()
        assertEquals(1, native.started.size)

        controller.sync(handle = 1L, requestedOwner = null).getOrThrow()
        assertEquals(listOf(TOR_PROBE_INBOUND_NAME), native.stopped)
        assertNull(controller.currentLease())
    }

    @Test
    fun `delayed teardown cannot close a successor generation`() {
        val native = FakeNative(document = torProbeReady())
        val controller = TorProbeProxyController(native, credentialFactory = { "u" to "p" })
        val oldOwner = TorProbeProxyOwner("session-a", 11L)
        val newOwner = TorProbeProxyOwner("session-b", 12L)

        controller.sync(handle = 1L, requestedOwner = oldOwner).getOrThrow()
        controller.sync(handle = 1L, requestedOwner = newOwner).getOrThrow()
        val stopsBeforeDelayedRelease = native.stopped.size

        assertFalse(controller.release(handle = 1L, expectedOwner = oldOwner).getOrThrow())
        assertEquals(newOwner, controller.currentLease()?.owner)
        assertEquals(stopsBeforeDelayedRelease, native.stopped.size)
    }

    @Test
    fun `tor probe rejects a non-loopback report and a refused start with typed causes`() {
        val exposedNative = FakeNative(document = torProbeReady(host = "0.0.0.0"))
        val exposedController = TorProbeProxyController(
            exposedNative,
            credentialFactory = { "u" to "p" },
        )
        val badAddress = exposedController
            .sync(handle = 1L, requestedOwner = TorProbeProxyOwner("session-a", 11L)).exceptionOrNull()
        val refused = TorProbeProxyController(
            FakeNative(startCode = -7, document = torProbeReady()),
            credentialFactory = { "u" to "p" },
        ).sync(handle = 1L, requestedOwner = TorProbeProxyOwner("session-a", 11L)).exceptionOrNull()

        assertEquals(
            TorProbeProxyFailure.INVALID_LOOPBACK_ADDRESS,
            (badAddress as TorProbeProxyUnavailableException).failure,
        )
        assertEquals(listOf(TOR_PROBE_INBOUND_NAME), exposedNative.stopped)
        assertEquals(TorProbeProxyFailure.INVALID_LOOPBACK_ADDRESS, exposedController.currentIssue()?.failure)
        assertEquals(TorProbeProxyFailure.START_REFUSED, (refused as TorProbeProxyUnavailableException).failure)
    }

    @Test
    fun `stale tor probe generation is rejected after fetch`() {
        val started = TorProbeProxyLease(
            owner = TorProbeProxyOwner("session-a", 11L),
            access = HttpProxyAccess("127.0.0.1", 47822, "u", "p"),
        )
        val successor = started.copy(owner = TorProbeProxyOwner("session-b", 12L))

        val error = runCatching { requireCurrentTorProbeLease(started, successor) }.exceptionOrNull()

        assertEquals(TorProbeProxyFailure.STALE_GENERATION, (error as TorProbeProxyUnavailableException).failure)
    }
}
