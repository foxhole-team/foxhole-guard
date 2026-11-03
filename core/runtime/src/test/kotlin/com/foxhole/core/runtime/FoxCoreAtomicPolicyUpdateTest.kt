package com.foxhole.core.runtime

import android.content.Context
import android.net.Network
import android.os.ParcelFileDescriptor
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.FoxCoreTunPlan
import com.foxhole.core.model.FoxCoreTunRoute
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import com.foxhole.core.model.Settings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.PerAppRoutingMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class FoxCoreAtomicPolicyUpdateTest {
    @Test
    fun `colliding fingerprints cannot skip atomic admission for different application rules`() {
        val assembler = RuntimeConfigAssembler(Json)
        val before = Settings(expert = ExpertSettings(
            perAppRoutingMode = PerAppRoutingMode.INCLUDE_SELECTED_APPS,
            appAssignments = mapOf("org.example.Aa" to AppTunnelLane.VPN),
        ))
        val after = before.copy(expert = before.expert.copy(appAssignments = mapOf("org.example.BB" to AppTunnelLane.VPN)))
        assertEquals(assembler.runtimeFingerprint(before, null), assembler.runtimeFingerprint(after, null))
        assertFalse(assembler.runtimeSettingsEquivalent(before, after))
        assertTrue(assembler.runtimeSettingsEquivalent(before, before.copy(connection = before.connection.copy(atomicConnection = false))))
    }

    @Test
    fun `block acknowledgement precedes persistence and candidate acknowledgement precedes release`() = runBlocking {
        val f = AtomicFixture()
        val update = f.runtime.prepareAtomicPolicyUpdate(f.session)
        assertEquals(listOf("block"), f.events)
        f.events += "persist"
        update.commit().getOrThrow()
        assertEquals(listOf("block", "persist", "block", "release"), f.events)
        assertFalse(f.runtime.atomicPolicyUpdateActive)
        assertEquals(listOf(1L, 2L, 3L), f.expectedRevisions)
        assertEquals(4L, f.active().policyRevision)
    }

    @Test
    fun `ordinary concurrent reload cannot clear an admitted gate`() = runBlocking {
        val f = AtomicFixture()
        val update = f.runtime.prepareAtomicPolicyUpdate(f.session)
        val refused = f.runtime.reload(f.session, f.host)
        assertTrue(refused.exceptionOrNull() is RuntimePolicyUpdateException)
        assertEquals(listOf("block"), f.events)
        assertTrue(f.runtime.atomicPolicyUpdateActive)
        update.commit().getOrThrow()
        assertFalse(f.runtime.atomicPolicyUpdateActive)
    }

    @Test
    fun `candidate refusal retains the block and never sends release`() = runBlocking {
        val f = AtomicFixture(failPolicyCall = 2)
        val update = f.runtime.prepareAtomicPolicyUpdate(f.session)
        assertTrue(update.commit().isFailure)
        assertEquals(listOf("block", "block", "block"), f.events)
        assertTrue(f.runtime.atomicPolicyUpdateActive)
        assertTrue(f.active().translated.policyConfigJson.killSwitch())
    }

    @Test
    fun `release refusal rearms the gate without a physical network event`() = runBlocking {
        val f = AtomicFixture(failPolicyCall = 3, protocol = ProtocolHint.WIREGUARD)
        val update = f.runtime.prepareAtomicPolicyUpdate(f.session)
        assertTrue(update.commit().isFailure)
        assertEquals(listOf("block", "block", "release", "block"), f.events)
        assertTrue(f.runtime.atomicPolicyUpdateActive)
        assertTrue(f.active().translated.policyConfigJson.killSwitch())
    }

    @Test
    fun `ordinary policy engines do not receive a network epoch reset`() = runBlocking {
        val f = AtomicFixture(protocol = ProtocolHint.VLESS)
        f.runtime.prepareAtomicPolicyUpdate(f.session).commit().getOrThrow()
        assertEquals(listOf("block", "block", "release"), f.events)
    }

    @Test
    fun `staged transaction candidate is admitted before commit and replaces only policy`() = runBlocking {
        val f = AtomicFixture()
        val update = f.runtime.prepareAtomicPolicyUpdate(f.session)
        val staged = atomicConfig().copy(policyConfigJson = """{"dns":{},"traffic":{"default_action":"direct","kill_switch":false}}""")
        update.stage(f.session.copy(foxCoreConfig = staged))
        assertEquals(listOf("block"), f.events)
        f.events += "database-commit"
        update.commit().getOrThrow()
        assertEquals("direct", Json.parseToJsonElement(f.active().translated.policyConfigJson).jsonObject
            .getValue("traffic").jsonObject.getValue("default_action").jsonPrimitive.content)
        assertEquals(listOf("block", "database-commit", "block", "release"), f.events)
    }

    @Test
    fun `unsupported staged candidate rolls back without releasing the established block`() = runBlocking {
        val f = AtomicFixture()
        val update = f.runtime.prepareAtomicPolicyUpdate(f.session)
        var committed = false
        runCatching {
            update.stage(f.session.copy(foxCoreConfig = atomicConfig().copy(tunPlan = atomicConfig().tunPlan.copy(mtu = 1400))))
            committed = true
        }
        assertFalse(committed)
        assertTrue(f.runtime.atomicPolicyUpdateActive)
        assertEquals(listOf("block"), f.events)
    }

    @Test
    fun `retired owner cannot release a successor session`() = runBlocking {
        val f = AtomicFixture()
        val update = f.runtime.prepareAtomicPolicyUpdate(f.session)
        f.set("active", f.active().copy(handle = 2L, sessionIdentity = Any()))
        assertTrue(update.commit().isFailure)
        assertEquals(listOf("block"), f.events)
        assertEquals(2L, f.active().handle)
    }

    @Test
    fun `Stop invalidates a pending commit even when Android descriptor closure is incomplete`() = runBlocking {
        val f = AtomicFixture(allowStop = true)
        val update = f.runtime.prepareAtomicPolicyUpdate(f.session)
        f.runtime.stop()
        assertFalse(f.runtime.atomicPolicyUpdateActive)
        assertTrue(update.commit().exceptionOrNull() is RuntimePolicyUpdateException)
        assertEquals(listOf("block", "stop"), f.events)
    }

    @Test
    fun `partial capture and changed tun plans are refused before persistence or native mutation`() = runBlocking {
        val base = atomicConfig()
        listOf(
            base.tunPlan.copy(allowedApplications = listOf("org.example.app")),
            base.tunPlan.copy(disallowedApplications = listOf("org.example.app")),
            base.tunPlan.copy(ipv6Address = null, ipv6PrefixLength = null),
        ).forEach { plan ->
            val f = AtomicFixture(config = base.copy(tunPlan = plan))
            assertTrue(runCatching { f.runtime.prepareAtomicPolicyUpdate(f.session) }.exceptionOrNull() is RuntimePolicyUpdateException)
            assertTrue(f.events.isEmpty())
        }
        val f = AtomicFixture()
        assertTrue(runCatching {
            f.runtime.prepareAtomicPolicyUpdate(f.session.copy(foxCoreConfig = base.copy(tunPlan = base.tunPlan.copy(mtu = 1400))))
        }.isFailure)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `temporary gate preserves application rules and an existing kill switch`() {
        val config = atomicConfig().copy(policyConfigJson = """{"traffic":{"kill_switch":true,"default_action":"direct","applications":[{"package":"org.example.app","action":"vpn"}]},"dns":{}}""")
        val released = config.withPolicyGate(false, 7L)
        assertTrue(released.policyConfigJson.killSwitch())
        val traffic = Json.parseToJsonElement(released.policyConfigJson).jsonObject.getValue("traffic").jsonObject
        assertEquals("direct", traffic.getValue("default_action").jsonPrimitive.content)
        assertTrue(traffic.containsKey("applications"))
    }
}

private fun String.killSwitch(): Boolean =
    Json.parseToJsonElement(this).jsonObject.getValue("traffic").jsonObject.getValue("kill_switch").jsonPrimitive.boolean

private fun atomicConfig() = FoxCoreSessionConfig(
    engineConfigJson = "{}",
    policyConfigJson = """{"dns":{},"traffic":{"default_action":"vpn","kill_switch":false}}""",
    tunPlan = FoxCoreTunPlan(
        1500, "10.0.0.1", 30, "fd00::1", 126,
        listOf(FoxCoreTunRoute("0.0.0.0", 0), FoxCoreTunRoute("::", 0)), listOf("10.0.0.2"),
    ),
)

private class AtomicFixture(
    failPolicyCall: Int = 0,
    config: FoxCoreSessionConfig = atomicConfig(),
    protocol: ProtocolHint = ProtocolHint.WIREGUARD,
    allowStop: Boolean = false,
) {
    val events = mutableListOf<String>()
    val expectedRevisions = mutableListOf<Long>()
    private var calls = 0
    private var revision = 1L
    val native = Proxy.newProxyInstance(FoxCoreNativeApi::class.java.classLoader, arrayOf(FoxCoreNativeApi::class.java)) { _, method, args ->
        when (method.name) {
            "reloadPolicy" -> {
                val policy = args!![1] as String
                events += if (policy.killSwitch()) "block" else "release"
                Json.parseToJsonElement(policy).jsonObject["expected_revision"]?.jsonPrimitive?.content?.toLong()?.let(expectedRevisions::add)
                if (++calls == failPolicyCall) -7L else ++revision
            }
            "networkChanged" -> {
                error("Core1.0.3 policy ACK resets packet decisions; atomic apply must not signal a physical network event")
            }
            "stats", "connections", "drainTrafficEvents", "drainEvents" -> "{}"
            "version", "lastPolicyError" -> ""
            "stop" -> if (allowStop) { events += "stop"; 0 } else error("policy-only update must not restart native")
            "forceKill" -> if (allowStop) 0 else error("policy-only update must not restart native")
            "startWithNetwork" -> error("policy-only update must not restart native")
            else -> when (method.returnType) {
                java.lang.Long.TYPE -> 0L
                java.lang.Integer.TYPE -> 0
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        }
    } as FoxCoreNativeApi
    val host = object : RuntimeServiceHost {
        override val runtimeContext: Context get() = error("not used")
        override fun stopRuntimeService() = Unit
        override fun protectSocket(socket: Int) = true
        override fun currentUnderlyingNetwork(): Network? = null
    }
    val runtime = FoxCoreRuntime(NoOpRuntimeDiagnosticsSink, native = native)
    val session = VpnSession(
        profileId = 1L,
        profileName = "Atomic policy",
        protocolHint = protocol,
        configJson = "{}",
        correlationId = "atomic-policy-test",
        foxCoreConfig = config,
    )

    init {
        val fingerprint = FoxCoreNativeEngineOperations(native, NoOpRuntimeDiagnosticsSink, FoxCoreConfigTranslator())
            .immutableFingerprint(config)!!
        set("active", ActiveFoxCoreSession(
            handle = 1L,
            tun = ParcelFileDescriptor(null as ParcelFileDescriptor?),
            masterTunFd = -1,
            nativeTunFd = -1,
            host = host,
            translated = config,
            immutableFingerprint = fingerprint,
            policyRevision = 1L,
            networkHandle = 1L,
            dnsServers = listOf("10.0.0.2"),
        ))
        set("state", RuntimeState.RUNNING)
    }

    fun active() = FoxCoreRuntime::class.java.getDeclaredField("active").apply { isAccessible = true }.get(runtime) as ActiveFoxCoreSession
    fun set(name: String, value: Any?) = FoxCoreRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.set(runtime, value)
}
