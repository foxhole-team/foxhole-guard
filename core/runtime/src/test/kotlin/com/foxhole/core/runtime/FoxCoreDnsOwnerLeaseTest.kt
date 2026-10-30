package com.foxhole.core.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.FoxCoreTunPlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class FoxCoreDnsOwnerLeaseTest {
    @Test fun capturedOwnerInstallsOnIoAndReportsCurrentRevision() = runBlocking {
        val fixture = Fixture()
        val caller = Thread.currentThread()
        fixture.onNative = { assertFalse(Thread.currentThread() === caller) }
        val lease = requireNotNull(fixture.runtime.captureDnsRuleSetInstallLease())
        assertEquals(
            RuntimeDnsRuleSetInstallOutcome.Installed(9),
            lease.install("owned", byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        )
        assertEquals(listOf(41L), fixture.calls)
        assertEquals(9L, fixture.active().policyRevision)
        assertTrue(lease.isCurrent())
    }

    @Test fun retiredOwnerNeverRedirectsLeaseToReplacementSession() = runBlocking {
        for (replace in listOf<(Fixture) -> Unit>(
            { it.set("generation", 8L) },
            { it.set("active", it.active().copy(sessionIdentity = Any())) },
            { it.set("active", it.active().copy(handle = 99L)) },
            { it.set("state", RuntimeState.IDLE) },
            { it.set("cleanupDraining", true) },
            { it.set("handoverQuiesced", true) }
        )) {
            val fixture = Fixture()
            val lease = requireNotNull(fixture.runtime.captureDnsRuleSetInstallLease())
            replace(fixture)
            assertFalse(lease.isCurrent())
            assertEquals(
                RuntimeDnsRuleSetInstallOutcome.Superseded,
                lease.install("owned", byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
            )
            assertTrue(fixture.calls.isEmpty())
        }
    }

    @Test fun revokedServiceCannotCaptureOrApply() = runBlocking {
        val fixture = Fixture()
        assertNull(fixture.runtime.captureDnsRuleSetInstallLease { false })
        var current = true
        val lease = requireNotNull(fixture.runtime.captureDnsRuleSetInstallLease { current })
        current = false
        assertFalse(lease.isCurrent())
        assertEquals(
            RuntimeDnsRuleSetInstallOutcome.Superseded,
            lease.install("owned", byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        )
        assertTrue(fixture.calls.isEmpty())
    }

    @Test fun replacementDuringNativeCallCannotCommitOldRevision() = runBlocking {
        val fixture = Fixture()
        val lease = requireNotNull(fixture.runtime.captureDnsRuleSetInstallLease())
        fixture.onNative = { fixture.set("active", fixture.active().copy(sessionIdentity = Any(), handle = 99L)) }
        assertEquals(
            RuntimeDnsRuleSetInstallOutcome.Superseded,
            lease.install("owned", byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        )
        assertEquals(1L, fixture.active().policyRevision)
        assertFalse(lease.isCurrent())
    }

    @Test fun serviceRevokedDuringNativeCallCannotCommitOldRevision() = runBlocking {
        val fixture = Fixture()
        var current = true
        val lease = requireNotNull(fixture.runtime.captureDnsRuleSetInstallLease { current })
        fixture.onNative = { current = false }
        assertEquals(
            RuntimeDnsRuleSetInstallOutcome.Superseded,
            lease.install("owned", byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        )
        assertEquals(1L, fixture.active().policyRevision)
        assertFalse(lease.isCurrent())
    }

    @Test fun mutableCallerInputCannotChangeCapturedNativeBytes() = runBlocking {
        val fixture = Fixture()
        val manifest = byteArrayOf(1)
        fixture.onNative = { manifest[0] = 9 }
        val lease = requireNotNull(fixture.runtime.captureDnsRuleSetInstallLease())
        assertEquals(
            RuntimeDnsRuleSetInstallOutcome.Installed(9),
            lease.install("owned", manifest, byteArrayOf(2), byteArrayOf(3))
        )
        assertArrayEquals(byteArrayOf(1), fixture.manifestAtNative)
    }

    private class Fixture {
        val calls = mutableListOf<Long>()
        var manifestAtNative: ByteArray? = null
        var onNative: () -> Unit = {}
        private val native = Proxy.newProxyInstance(
            FoxCoreNativeApi::class.java.classLoader,
            arrayOf(FoxCoreNativeApi::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "installDnsRuleSet" -> {
                    onNative()
                    calls += arguments!![0] as Long
                    manifestAtNative = (arguments[2] as ByteArray).copyOf()
                    9L
                }
                else -> when (method.returnType) {
                    java.lang.Long.TYPE -> 0L
                    java.lang.Integer.TYPE -> 0
                    java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }
        } as FoxCoreNativeApi
        private val host = object : RuntimeServiceHost {
            override val runtimeContext: Context get() = error("not used")
            override fun stopRuntimeService() = Unit
            override fun protectSocket(socket: Int): Boolean = true
            override fun currentUnderlyingNetwork() = error("typed target must not reselect ambient host")
        }
        private val diagnostics = object : RuntimeDiagnosticsSink {
            override fun record(tag: String, message: String) = Unit
            override fun recordStructured(tag: String, headline: String, vararg details: String?) = Unit
        }
        val runtime = FoxCoreRuntime(diagnostics, native = native).also {
            set(
                it,
                "active",
                ActiveFoxCoreSession(
                    handle = 41L,
                    tun = ParcelFileDescriptor(null as ParcelFileDescriptor?),
                    masterTunFd = -1,
                    nativeTunFd = -1,
                    host = host,
                    translated = FoxCoreSessionConfig(
                        "{}",
                        "{}",
                        FoxCoreTunPlan(1500, "10.0.0.1", 30, null, null, emptyList(), emptyList()),
                    ),
                    immutableFingerprint = "network-lease",
                    policyRevision = 1L,
                    networkHandle = 1L,
                    dnsServers = emptyList(),
                ),
            )
            set(it, "state", RuntimeState.RUNNING)
            set(it, "generation", 7L)
        }

        fun active() = field("active") as ActiveFoxCoreSession

        fun field(name: String): Any? = FoxCoreRuntime::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(runtime)

        fun set(name: String, value: Any?) = set(runtime, name, value)

        private fun set(runtime: FoxCoreRuntime, name: String, value: Any?) {
            FoxCoreRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }.set(runtime, value)
        }
    }
}
