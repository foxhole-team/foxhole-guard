package com.foxhole.guard.runtime

import com.foxhole.core.network.RemoteHostResolver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

class UpdateResolverConsistencyTest {
    @Test fun sentinelAdmissionAndActualConnectionUseTheSameInjectedResolver() = runBlocking {
        verifyConnection("sentinel", "8.8.8.8")
    }
    @Test fun dnsAdmissionAndActualConnectionUseTheSameInjectedResolver() = runBlocking {
        verifyConnection("dns", "8.8.8.8")
    }
    @Test fun syntheticDomainAnswersStillUseTheInjectedTunnelMapping() = runBlocking {
        for (kind in listOf("sentinel", "dns")) verifyConnection(kind, "198.18.0.8")
    }
    @Test fun privateAnswersAreRejectedBeforeAnySocketOrPlatformDns() = runBlocking {
        for (kind in listOf("sentinel", "dns")) {
            val probe = Probe("10.0.0.8")
            fetch(kind, probe, URL)
            assertTrue(probe.sockets.isEmpty())
            assertEquals(0, probe.platformCalls.get())
        }
    }
    @Test fun literalSyntheticAddressesAreStillRejectedByPublicUrlAdmission() = runBlocking {
        for (kind in listOf("sentinel", "dns")) {
            val probe = Probe("8.8.8.8")
            fetch(kind, probe, "https://198.18.0.8/manifest.json")
            assertTrue(probe.sockets.isEmpty())
            assertEquals(0, probe.customCalls.get())
        }
    }

    private suspend fun verifyConnection(kind: String, address: String) {
        val probe = Probe(address)
        fetch(kind, probe, URL)
        assertTrue("Admission and HTTP DNS must both use the selected resolver", probe.customCalls.get() >= 2)
        assertEquals("The platform DNS mapping must not replace the injected route", 0, probe.platformCalls.get())
        assertTrue("A real guarded connection attempt was not reached", probe.sockets.isNotEmpty())
        assertTrue(probe.sockets.all { it.address.hostAddress == address })
    }

    private suspend fun fetch(kind: String, probe: Probe, url: String) {
        val json = Json { ignoreUnknownKeys = true }
        if (kind == "dns") {
            val store = object : DnsFilterRuleSetStore {
                override suspend fun installVerifiedDnsRuleSet(ruleSet: VerifiedDnsRuleSet): String = error("No fixture data to install")
            }
            assertEquals(DnsFilterUpdateStatus.FAILED,
                DnsFilterUpdateClient(probe.client, json, resolver = probe.resolver).update(url, store).status)
        } else {
            val store = object : ThreatIntelStore {
                override suspend fun installVerifiedThreatIntel(documentBytes: ByteArray, manifest: ThreatIntelManifest): String = error("No fixture data to install")
            }
            assertEquals(ThreatIntelUpdateStatus.FAILED,
                ThreatIntelUpdateClient(probe.client, json, resolver = probe.resolver).update(url, store).status)
        }
    }

    private class Probe(address: String) {
        val customCalls = AtomicInteger()
        val platformCalls = AtomicInteger()
        val sockets: MutableList<InetSocketAddress> = Collections.synchronizedList(mutableListOf())
        val resolver: RemoteHostResolver = { customCalls.incrementAndGet(); listOf(InetAddress.getByName(address)) }
        private val factory = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    sockets.add(endpoint as InetSocketAddress)
                    throw IOException("Test socket stops before any network I/O")
                }
            }
            override fun createSocket(host: String, port: Int): Socket = error("unexpected factory overload")
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = error("unexpected factory overload")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("unexpected factory overload")
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = error("unexpected factory overload")
        }
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
            .socketFactory(factory).dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    platformCalls.incrementAndGet()
                    return listOf(InetAddress.getByName("198.18.0.9"))
                }
            }).build()
    }
    private companion object { const val URL = "https://updates.example.net/manifest.json" }
}
