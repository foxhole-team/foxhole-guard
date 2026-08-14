package com.foxhole.core.runtime.network

import com.foxhole.core.model.IpInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import kotlin.system.measureTimeMillis

internal class IpInfoRepositoryFallbackTest : IpInfoRepositoryTestSupport() {
    @Test
    fun `merge fills ipv4 from dedicated ipv4 result while keeping primary ipv6`() {
        val primary =
            IpInfo(
                ip = "2a00:7b80:452:2000::44",
                ipv4 = null,
                ipv6 = "2a00:7b80:452:2000::44",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Schiedam",
                isp = "Snel.com B.V.",
                fetchedAt = 1L,
            )
        val ipv4 =
            IpInfo(
                ip = "84.17.54.10",
                ipv4 = "84.17.54.10",
                ipv6 = null,
                countryCode = null,
                countryName = null,
                city = null,
                isp = null,
                fetchedAt = 2L,
            )

        val merged = mergeIpInfo(primary = primary, ipv4 = ipv4, ipv6 = null)

        assertEquals("84.17.54.10", merged.ip)
        assertEquals("84.17.54.10", merged.ipv4)
        assertEquals("2a00:7b80:452:2000::44", merged.ipv6)
        assertEquals(primary.countryCode, merged.countryCode)
        assertEquals(primary.countryName, merged.countryName)
    }

    @Test
    fun `merge takes the ipv4 probe geo with its address when countries conflict`() {
        // The primary fetch raced a network handover (cellular geo), the ipv4 probe answered from
        // the current network: the published identity must be self-consistent — probe's IP + probe's
        // geo, never probe's IP + the other network's city/country.
        val primary =
            IpInfo(
                ip = "2a00:7b80:452:2000::44",
                ipv4 = null,
                ipv6 = "2a00:7b80:452:2000::44",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Schiedam",
                isp = "Snel.com B.V.",
                fetchedAt = 1L,
            )
        val ipv4 =
            IpInfo(
                ip = "84.17.54.10",
                ipv4 = "84.17.54.10",
                ipv6 = null,
                countryCode = "DE",
                countryName = null,
                city = "Frankfurt",
                isp = "CDN77",
                fetchedAt = 2L,
            )

        val merged = mergeIpInfo(primary = primary, ipv4 = ipv4, ipv6 = null)

        assertEquals("84.17.54.10", merged.ip)
        assertEquals("DE", merged.countryCode)
        assertEquals("Germany", merged.countryName)
        assertEquals("Frankfurt", merged.city)
        assertEquals("CDN77", merged.isp)
        assertEquals("2a00:7b80:452:2000::44", merged.ipv6)
    }

    @Test
    fun `merge keeps primary geo when the ipv4 probe agrees on the country`() {
        val primary =
            IpInfo(
                ip = "2a00:7b80:452:2000::44",
                ipv4 = null,
                ipv6 = "2a00:7b80:452:2000::44",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Schiedam",
                isp = "Snel.com B.V.",
                fetchedAt = 1L,
            )
        val ipv4 =
            IpInfo(
                ip = "84.17.54.10",
                ipv4 = "84.17.54.10",
                ipv6 = null,
                countryCode = "nl",
                countryName = null,
                city = "Amsterdam",
                isp = null,
                fetchedAt = 2L,
            )

        val merged = mergeIpInfo(primary = primary, ipv4 = ipv4, ipv6 = null)

        assertEquals("84.17.54.10", merged.ip)
        assertEquals("NL", merged.countryCode)
        assertEquals("Schiedam", merged.city)
        assertEquals("Snel.com B.V.", merged.isp)
    }

    @Test
    fun `full fetch mode keeps primary plus fallback endpoints`() {
        val repository =
            IpInfoRepository(
                client = okhttp3.OkHttpClient(),
                json = json,
            )

        val candidates = repository.effectiveEndpointCandidates("", mode = IpInfoFetchMode.FULL)

        assertTrue(candidates.isNotEmpty())
        assertEquals("https://ipwho.is/", candidates.first())
        assertEquals("https://1.1.1.1/cdn-cgi/trace", candidates[1])
        assertEquals("https://1.0.0.1/cdn-cgi/trace", candidates[2])
        assertEquals("https://cloudflare.com/cdn-cgi/trace", candidates[3])
        assertEquals("https://ipwhois.app/json/", candidates[4])
        assertTrue(candidates.size > 1)
    }

    @Test
    fun `entry quick mode tries dns independent endpoint before primary and provider endpoints`() {
        val repository =
            IpInfoRepository(
                client = okhttp3.OkHttpClient(),
                json = json,
            )

        val candidates =
            repository.effectiveEndpointCandidates(
                "https://example.com/ip",
                mode = IpInfoFetchMode.ENTRY_QUICK,
            )

        assertEquals("https://1.1.1.1/cdn-cgi/trace", candidates[0])
        assertEquals("https://example.com/ip", candidates[1])
        assertEquals("https://1.0.0.1/cdn-cgi/trace", candidates[2])
        assertEquals("https://ipinfo.io/json", candidates[3])
        assertEquals(4, candidates.size)
    }

    @Test
    fun `geo enrichment mode keeps bounded city provider candidates`() {
        val repository =
            IpInfoRepository(
                client = okhttp3.OkHttpClient(),
                json = json,
            )

        val candidates =
            repository.effectiveEndpointCandidates(
                "https://example.com/ip",
                mode = IpInfoFetchMode.GEO_ENRICHMENT,
            )

        // Every geo-enrichment candidate must be city-capable: the country-only Cloudflare trace is
        // intentionally excluded so it can never win the parallel race and blank out the city.
        assertEquals("https://ipapi.co/json/", candidates[0])
        assertEquals("https://example.com/ip", candidates[1])
        assertEquals("https://ipinfo.io/json", candidates[2])
        assertEquals("https://ipwho.is/", candidates[3])
        assertEquals("https://ifconfig.co/json", candidates[4])
        assertEquals("https://get.geojs.io/v1/ip/geo.json", candidates[5])
        assertFalse(candidates.any { candidate -> candidate.contains("cdn-cgi/trace") })
        assertEquals(6, candidates.size)
    }

    @Test
    fun `entry quick scan stops at country details and leaves city provider enrichment to background`() {
        val sparseInfo =
            IpInfo(
                ip = "84.17.54.10",
                ipv4 = "84.17.54.10",
                countryCode = "NL",
                countryName = null,
                city = null,
                isp = null,
                fetchedAt = 1L,
            )
        val geoInfo =
            sparseInfo.copy(
                countryName = "Netherlands",
                city = "Amsterdam",
            )

        assertTrue(shouldStopIpInfoCandidateScan(IpInfoFetchMode.ENTRY_QUICK, sparseInfo))
        assertTrue(shouldStopIpInfoCandidateScan(IpInfoFetchMode.ENTRY_QUICK, geoInfo))
        assertTrue(shouldStopIpInfoCandidateScan(IpInfoFetchMode.ENTRY_QUICK, geoInfo.copy(isp = "Datacamp Limited")))
    }

    @Test
    fun `full fetch strategy keeps caller timeout`() {
        val repository =
            IpInfoRepository(
                client = okhttp3.OkHttpClient(),
                json = json,
            )

        val strategy =
            repository.resolveFetchStrategy(
                endpoint = "",
                callTimeoutMs = 4_000L,
                mode = IpInfoFetchMode.FULL,
            )

        assertEquals("https://ipwho.is/", strategy.endpointCandidates.first())
        assertEquals(4_000L, strategy.callTimeoutMs)
        assertFalse(strategy.parallelCandidates)
    }

    @Test
    fun `entry quick fetch strategy uses bounded timeout`() {
        val repository =
            IpInfoRepository(
                client = okhttp3.OkHttpClient(),
                json = json,
            )

        val strategy =
            repository.resolveFetchStrategy(
                endpoint = "https://example.com/ip",
                callTimeoutMs = null,
                mode = IpInfoFetchMode.ENTRY_QUICK,
            )

        assertEquals("https://1.1.1.1/cdn-cgi/trace", strategy.endpointCandidates.first())
        assertEquals("https://example.com/ip", strategy.endpointCandidates[1])
        assertEquals(2_500L, strategy.callTimeoutMs)
        assertFalse(strategy.parallelCandidates)
    }

    @Test
    fun `geo enrichment fetch strategy uses short bounded timeout`() {
        val repository =
            IpInfoRepository(
                client = okhttp3.OkHttpClient(),
                json = json,
            )

        val strategy =
            repository.resolveFetchStrategy(
                endpoint = "https://example.com/ip",
                callTimeoutMs = null,
                mode = IpInfoFetchMode.GEO_ENRICHMENT,
            )

        assertEquals("https://ipapi.co/json/", strategy.endpointCandidates.first())
        assertEquals("https://example.com/ip", strategy.endpointCandidates[1])
        assertEquals(8_000L, strategy.callTimeoutMs)
        assertTrue(strategy.parallelCandidates)
    }

    @Test
    fun `http proxy tunnel timeout budget counts elapsed time`() {
        val budget =
            HttpProxyTunnelTimeoutBudget(
                timeoutMs = 200L,
                startedAtNanos = 1_000_000L,
                nowNanos = { 151_000_000L },
            )

        assertEquals(50, budget.remainingMs())
    }

    @Test
    fun `http proxy tunnel latency uses one total timeout across stages`() =
        runBlocking {
            val repository =
                IpInfoRepository(
                    client = okhttp3.OkHttpClient(),
                    json = json,
                )

            SlowConnectHttpProxy(connectResponseDelayMs = 450L).use { proxy ->
                var failure: Exception? = null
                val elapsed =
                    measureTimeMillis {
                        try {
                            repository.probeLatency(
                                endpoint = "https://example.com/",
                                callTimeoutMs = 500L,
                                proxy = HttpProxyAccess(host = "127.0.0.1", port = proxy.port),
                            )
                        } catch (error: Exception) {
                            failure = error
                        }
                    }

                assertTrue(
                    "probe should fail against an idle TLS tunnel, failure=${failure?.javaClass?.simpleName}",
                    failure != null,
                )
                assertTrue("elapsed=$elapsed", elapsed < 850L)
            }
        }

    @Test
    fun `address resolver prefers public resolver before bound network dns`() {
        val publicAddress = InetAddress.getByName("93.184.216.34")
        var networkLookupCount = 0
        val selected =
            publicResolvedAddressesOrNetworkFallback(
                publicAddresses = Result.success(listOf(publicAddress)),
                networkFallback = {
                    networkLookupCount += 1
                    throw UnknownHostException("vpn dns unavailable")
                },
            )

        assertEquals(listOf(publicAddress), selected)
        assertEquals(0, networkLookupCount)
    }

    @Test
    fun `address resolver falls back when public resolver is unavailable`() {
        val networkAddress = InetAddress.getByName("93.184.216.34")
        val selected =
            publicResolvedAddressesOrNetworkFallback(
                publicAddresses = Result.failure(UnknownHostException("public dns unavailable")),
                networkFallback = { listOf(networkAddress) },
            )

        assertEquals(listOf(networkAddress), selected)
    }

    @Test
    fun `public dns ordering prefers ipv4 before ipv6`() {
        val ipv6Address = InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")
        val ipv4Address = InetAddress.getByName("93.184.216.34")

        val ordered = listOf(ipv6Address, ipv4Address).preferIpv4()

        assertEquals(listOf(ipv4Address, ipv6Address), ordered)
    }

    @Test
    fun `public remote dns falls back to doh resolver when platform dns fails`() {
        val fallbackAddress = InetAddress.getByName("104.26.12.205")
        val dns =
            PublicRemoteDns(
                delegate = { throw UnknownHostException("platform dns unavailable") },
                fallback = PublicDnsFallback { hostname ->
                    assertEquals("api.ipify.org", hostname)
                    listOf(fallbackAddress)
                },
            )

        assertEquals(listOf(fallbackAddress), dns.lookup("api.ipify.org"))
    }

    @Test
    fun `public remote dns rejects private fallback answers`() {
        val dns =
            PublicRemoteDns(
                delegate = { throw UnknownHostException("platform dns unavailable") },
                fallback = PublicDnsFallback { listOf(InetAddress.getByName("192.168.1.10")) },
            )

        var failure: UnknownHostException? = null
        try {
            dns.lookup("api.ipify.org")
        } catch (error: UnknownHostException) {
            failure = error
        }

        assertTrue(failure != null)
    }

    @Test
    fun `doh fallback parser extracts matching public answers`() {
        val addresses =
            PublicDohDnsFallback.parseDohAddresses(
                body =
                """
                    {
                      "Status": 0,
                      "Answer": [
                        { "name": "api.ipify.org", "type": 1, "data": "104.26.12.205" },
                        { "name": "api.ipify.org", "type": 28, "data": "2606:4700:20::681a:ccd" }
                      ]
                    }
                """.trimIndent(),
                type = 1,
            )

        assertEquals(listOf(InetAddress.getByName("104.26.12.205")), addresses)
    }

    @Test
    fun `doh fallback parser extracts txt records`() {
        val records =
            PublicDohDnsFallback.parseDohTxtRecords(
                body =
                """
                    {
                      "Status": 0,
                      "Answer": [
                        {
                          "name": "AS12714.asn.cymru.com",
                          "type": 16,
                          "data": "\"12714 | RU | ripencc | 1999-10-08 | MEGAFON-AS - PJSC MegaFon, RU\""
                        }
                      ]
                    }
                """.trimIndent(),
            )

        assertEquals(listOf("12714 | RU | ripencc | 1999-10-08 | MEGAFON-AS - PJSC MegaFon, RU"), records)
    }
}

private class SlowConnectHttpProxy(
    private val connectResponseDelayMs: Long,
) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private var acceptedSocket: Socket? = null
    val port: Int = server.localPort

    private val worker =
        Thread {
            runCatching {
                server.accept().use { socket ->
                    acceptedSocket = socket
                    socket.soTimeout = 2_000
                    readProxyRequestHead(socket)
                    Thread.sleep(connectResponseDelayMs)
                    socket
                        .getOutputStream()
                        .write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    socket.getOutputStream().flush()
                    Thread.sleep(2_000)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

    override fun close() {
        runCatching { acceptedSocket?.close() }
        runCatching { server.close() }
        worker.join(500)
    }
}

private fun readProxyRequestHead(socket: Socket) {
    val input = socket.getInputStream()
    var tail = 0
    repeat(16 * 1024) {
        val value = input.read()
        if (value == -1) {
            return
        }
        tail = (tail shl 8) or (value and 0xff)
        if (tail == 0x0D0A0D0A) {
            return
        }
    }
}
