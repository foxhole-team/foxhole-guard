package com.foxhole.core.runtime

import com.foxhole.core.model.LanProxyPhase
import com.foxhole.core.model.LanProxyUnavailableReason
import com.foxhole.core.model.LanProxyUpstream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LAN proxy is the one surface where a wrong "it works" is a security bug: it publishes a
 * listener on the phone's Wi-Fi address that relays into the owner's VPN or Tor. These tests pin the
 * two halves of that contract — the request we send, and the fact that a refusal stays a refusal.
 */
class LanProxyRuntimeTest {
    private val binding =
        LanNetworkBinding(
            networkHandle = 42L,
            localAddress = "192.168.1.24",
            interfaceName = "wlan0",
            transport = "wifi",
        )

    private fun request(
        socksPort: Int = 10808,
        httpPort: Int = 0,
        upstream: LanProxyUpstream = LanProxyUpstream.VPN,
        password: String = "correct horse battery",
    ) = LanProxyRequest(
        upstream = upstream,
        socksPort = socksPort,
        httpPort = httpPort,
        username = "foxhole",
        password = password,
        binding = binding,
    )

    @Test
    fun `request json carries the binding the core needs to pin the listener`() {
        val json = Json.parseToJsonElement(request(httpPort = 10809).toConfigJson()).jsonObject
        assertEquals("vpn", json.getValue("preset").jsonPrimitive.content)
        assertEquals("10808", json.getValue("socks_port").jsonPrimitive.content)
        assertEquals("10809", json.getValue("http_port").jsonPrimitive.content)
        assertEquals("192.168.1.24", json.getValue("local_address").jsonPrimitive.content)
        assertEquals("wlan0", json.getValue("interface_name").jsonPrimitive.content)
        assertEquals("wifi", json.getValue("transport").jsonPrimitive.content)
        assertEquals("42", json.getValue("network_handle").jsonPrimitive.content)
    }

    @Test
    fun `a status document the core did not answer is a failure, never an idle proxy`() {
        val status = parseLanProxyStatusJson("", requested = LanProxyUpstream.VPN, now = 1L)
        assertEquals(LanProxyPhase.FAILED, status.phase)
        assertEquals(LanProxyUnavailableReason.UNKNOWN, status.reason)
    }

    @Test
    fun `ready carries the published addresses and no reason`() {
        val status =
            parseLanProxyStatusJson(
                """
                {"state":"ready","socks_address":"192.168.1.24:10808","http_address":null,
                 "preset":"mixed","last_error":null}
                """.trimIndent(),
                requested = LanProxyUpstream.VPN,
                now = 7L,
            )
        assertEquals(LanProxyPhase.READY, status.phase)
        assertEquals("192.168.1.24:10808", status.socksAddress)
        assertNull(status.httpAddress)
        // The core's own answer wins over what we asked for: it is the one that bound the sockets.
        assertEquals(LanProxyUpstream.MIXED, status.upstream)
        assertNull(status.reason)
    }

    @Test
    fun `every intermediate core state reads as arming rather than serving`() {
        listOf("checking_permission", "resolving_network", "acquiring_components", "binding_listeners")
            .forEach { state ->
                val status =
                    parseLanProxyStatusJson(
                        """{"state":"$state"}""",
                        requested = LanProxyUpstream.VPN,
                        now = 1L,
                    )
                assertEquals(state, LanProxyPhase.ARMING, status.phase)
            }
    }

    @Test
    fun `a refused network never reaches the start call`() {
        val native = RecordingNativeApi(confirmResult = FoxholeNativeEngine.LAN_NETWORK_REFUSED)
        val status = LanProxyController(native) { 1L }.sync(handle = 9L, request = request())
        assertEquals(LanProxyPhase.FAILED, status.phase)
        assertEquals(LanProxyUnavailableReason.NETWORK_REFUSED, status.reason)
        assertTrue("start must not run after a refused confirm", native.startCalls.isEmpty())
    }

    @Test
    fun `a core without the lan proxy abi reports unavailable instead of crashing`() {
        val native = RecordingNativeApi(confirmResult = LAN_PROXY_NOT_LINKED)
        val status = LanProxyController(native) { 1L }.sync(handle = 9L, request = request())
        assertEquals(LanProxyPhase.UNAVAILABLE, status.phase)
        assertEquals(LanProxyUnavailableReason.CORE_UNSUPPORTED, status.reason)
    }

    @Test
    fun `no session means armed, not serving, and nothing is sent to the core`() {
        val native = RecordingNativeApi()
        val status = LanProxyController(native) { 1L }.sync(handle = null, request = request())
        assertEquals(LanProxyPhase.ARMING, status.phase)
        assertEquals(LanProxyUnavailableReason.NO_SESSION, status.reason)
        assertTrue(native.startCalls.isEmpty())
    }

    @Test
    fun `a blocked request that the app already refused is never published`() {
        val native = RecordingNativeApi()
        val status =
            LanProxyController(native) { 1L }.sync(
                handle = 9L,
                request = request(),
                blocked = LanProxyUnavailableReason.PACKET_TUNNEL,
            )
        assertEquals(LanProxyPhase.UNAVAILABLE, status.phase)
        assertEquals(LanProxyUnavailableReason.PACKET_TUNNEL, status.reason)
        assertTrue(native.startCalls.isEmpty())
    }

    @Test
    fun `a request that offers no port at all is not a proxy`() {
        val native = RecordingNativeApi()
        val status =
            LanProxyController(native) { 1L }.sync(
                handle = 9L,
                request = request(socksPort = 0, httpPort = 0),
            )
        assertEquals(LanProxyPhase.OFF, status.phase)
        assertTrue(native.startCalls.isEmpty())
    }

    @Test
    fun `a started proxy is taken down when the switch goes off`() {
        val native = RecordingNativeApi(statusJson = """{"state":"ready","socks_address":"192.168.1.24:10808"}""")
        val controller = LanProxyController(native) { 1L }
        assertEquals(LanProxyPhase.READY, controller.sync(handle = 9L, request = request()).phase)
        val stopped = controller.sync(handle = 9L, request = null)
        assertEquals(LanProxyPhase.OFF, stopped.phase)
        assertEquals(listOf(9L), native.stopCalls)
    }

    private class RecordingNativeApi(
        private val confirmResult: Int = FoxholeNativeEngine.LAN_OK,
        private val startResult: Int = FoxholeNativeEngine.LAN_OK,
        private val statusJson: String = """{"state":"ready"}""",
    ) : FoxCoreNativeApi {
        val startCalls = mutableListOf<String>()
        val stopCalls = mutableListOf<Long>()

        override fun confirmLanNetwork(
            handle: Long,
            networkHandle: Long,
            localAddress: String,
            interfaceName: String,
            transport: String,
        ): Int = confirmResult

        override fun startLanProxy(
            handle: Long,
            configJson: String,
        ): Int {
            startCalls += configJson
            return startResult
        }

        override fun stopLanProxy(handle: Long): Int {
            stopCalls += handle
            return FoxholeNativeEngine.LAN_OK
        }

        override fun lanProxyStatus(handle: Long): String = statusJson

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
}
