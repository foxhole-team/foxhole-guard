package com.foxhole.beta.core.network

import com.foxhole.beta.core.model.IpInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import kotlin.system.measureTimeMillis

class IpInfoRepositoryTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun `parses ipwho schema`() {
        val parsed =
            parseIpInfoResponse(
                body =
                    """
                    {
                      "success": true,
                      "ip": "84.17.54.10",
                      "country": "Netherlands",
                      "country_code": "NL",
                      "city": "Amsterdam",
                      "connection": {
                        "isp": "Datacamp Limited",
                        "org": "CDN AMS DC1"
                      }
                    }
                    """.trimIndent(),
                json = json,
            )

        assertEquals("84.17.54.10", parsed.ip)
        assertEquals("84.17.54.10", parsed.ipv4)
        assertNull(parsed.ipv6)
        assertEquals("NL", parsed.countryCode)
        assertEquals("Netherlands", parsed.countryName)
        assertEquals("Amsterdam", parsed.city)
        assertEquals("Datacamp Limited", parsed.isp)
    }

    @Test
    fun `parses ifconfig schema`() {
        val parsed =
            parseIpInfoResponse(
                body =
                    """
                    {
                      "ip": "2a01:4f8:c0c:7e4f::1",
                      "country": "The Netherlands",
                      "country_iso": "NL",
                      "city": "Amsterdam",
                      "asn_org": "Datacamp Limited"
                    }
                    """.trimIndent(),
                json = json,
            )

        assertEquals("2a01:4f8:c0c:7e4f::1", parsed.ip)
        assertNull(parsed.ipv4)
        assertEquals("2a01:4f8:c0c:7e4f::1", parsed.ipv6)
        assertEquals("NL", parsed.countryCode)
        assertEquals("The Netherlands", parsed.countryName)
        assertEquals("Amsterdam", parsed.city)
        assertEquals("Datacamp Limited", parsed.isp)
    }

    @Test
    fun `parses ipify schema`() {
        val parsed =
            parseIpInfoResponse(
                body =
                    """
                    {
                      "ip": "84.17.54.10"
                    }
                    """.trimIndent(),
                json = json,
            )

        assertEquals("84.17.54.10", parsed.ip)
        assertEquals("84.17.54.10", parsed.ipv4)
        assertNull(parsed.ipv6)
        assertNull(parsed.countryCode)
        assertNull(parsed.countryName)
        assertNull(parsed.city)
        assertNull(parsed.isp)
    }

    @Test
    fun `parses ipinfo schema`() {
        val parsed =
            parseIpInfoResponse(
                body =
                    """
                    {
                      "ip": "84.17.54.10",
                      "city": "Amsterdam",
                      "country": "NL",
                      "org": "AS60068 Datacamp Limited"
                    }
                    """.trimIndent(),
                json = json,
            )

        assertEquals("84.17.54.10", parsed.ip)
        assertEquals("84.17.54.10", parsed.ipv4)
        assertNull(parsed.ipv6)
        assertEquals("NL", parsed.countryCode)
        assertEquals("Netherlands", parsed.countryName)
        assertEquals("Amsterdam", parsed.city)
        assertEquals("AS60068 Datacamp Limited", parsed.isp)
    }

    @Test
    fun `normalizes iso country name field to display country`() {
        val parsed =
            parseIpInfoResponse(
                body =
                    """
                    {
                      "ip": "84.17.54.10",
                      "country_name": "NL",
                      "city": "Amsterdam",
                      "provider": "Datacamp Limited"
                    }
                    """.trimIndent(),
                json = json,
            )

        assertEquals("NL", parsed.countryCode)
        assertEquals("Netherlands", parsed.countryName)
        assertEquals("Amsterdam", parsed.city)
        assertEquals("Datacamp Limited", parsed.isp)
    }

    @Test
    fun `drops invalid two character country name field`() {
        val parsed =
            parseIpInfoResponse(
                body =
                    """
                    {
                      "ip": "84.17.54.10",
                      "countryName": "T1"
                    }
                    """.trimIndent(),
                json = json,
            )

        assertNull(parsed.countryCode)
        assertNull(parsed.countryName)
    }

    @Test
    fun `parses country code and nested provider schema`() {
        val parsed =
            parseIpInfoResponse(
                body =
                    """
                    {
                      "ip": "84.17.54.10",
                      "country_code": "NL",
                      "city": "Amsterdam",
                      "as": {
                        "name": "Datacamp Limited"
                      },
                      "company": {
                        "name": "Fallback Company"
                      }
                    }
                    """.trimIndent(),
                json = json,
            )

        assertEquals("84.17.54.10", parsed.ip)
        assertEquals("NL", parsed.countryCode)
        assertEquals("Netherlands", parsed.countryName)
        assertEquals("Amsterdam", parsed.city)
        assertEquals("Datacamp Limited", parsed.isp)
    }

    @Test
    fun `parses ipwhois app schema with literal nested fields`() {
        val parsed =
            parseIpInfoResponse(
                body =
                    """
                    {
                      "ip": "79.120.30.76",
                      "success": true,
                      "country": "Russia",
                      "country_code": "RU",
                      "city": "Moscow",
                      "isp": "PJSC MegaFon",
                      "connection": "mobile"
                    }
                    """.trimIndent(),
                json = json,
            )

        assertEquals("79.120.30.76", parsed.ip)
        assertEquals("RU", parsed.countryCode)
        assertEquals("Russia", parsed.countryName)
        assertEquals("Moscow", parsed.city)
        assertEquals("PJSC MegaFon", parsed.isp)
    }

    @Test
    fun `parses cloudflare trace schema`() {
        val parsed =
            parseIpInfoResponse(
                body =
                    """
                    fl=1272f65
                    h=1.1.1.1
                    ip=84.17.54.10
                    ts=1779634208.000
                    loc=NL
                    colo=AMS
                    tls=TLSv1.3
                    """.trimIndent(),
                json = json,
            )

        assertEquals("84.17.54.10", parsed.ip)
        assertEquals("84.17.54.10", parsed.ipv4)
        assertNull(parsed.ipv6)
        assertEquals("NL", parsed.countryCode)
        assertEquals("Netherlands", parsed.countryName)
        assertNull(parsed.city)
        assertNull(parsed.isp)
    }

    @Test
    fun `parses cloudflare trace with special loc without failing candidate`() {
        val parsed =
            parseIpInfoResponse(
                body =
                    """
                    fl=1272f65
                    h=1.1.1.1
                    ip=84.17.54.10
                    ts=1779634208.000
                    loc=T1
                    colo=AMS
                    tls=TLSv1.3
                    """.trimIndent(),
                json = json,
            )

        assertEquals("84.17.54.10", parsed.ip)
        assertEquals("84.17.54.10", parsed.ipv4)
        assertNull(parsed.ipv6)
        assertNull(parsed.countryCode)
        assertNull(parsed.countryName)
        assertNull(parsed.city)
        assertNull(parsed.isp)
    }

    @Test
    fun `ignores invalid two character country code instead of exposing it as geo`() {
        val parsed =
            parseIpInfoResponse(
                body =
                    """
                    {
                      "ip": "84.17.54.10",
                      "country": "T1",
                      "org": "AS60068 Datacamp Limited"
                    }
                    """.trimIndent(),
                json = json,
            )

        assertEquals("84.17.54.10", parsed.ip)
        assertNull(parsed.countryCode)
        assertNull(parsed.countryName)
        assertNull(parsed.city)
        assertEquals("AS60068 Datacamp Limited", parsed.isp)
    }

    @Test
    fun `full fetch keeps scanning after incomplete geo candidate`() {
        val ipOnly =
            IpInfo(
                ip = "84.17.54.10",
                ipv4 = "84.17.54.10",
                countryCode = null,
                countryName = null,
                city = null,
                isp = null,
                fetchedAt = 1L,
            )
        val countryOnly = ipOnly.copy(countryCode = "NL")
        val fullGeo = countryOnly.copy(countryName = "Netherlands", city = "Amsterdam")
        val countryWithProvider = countryOnly.copy(countryName = "Netherlands", isp = "Datacamp Limited")
        val fullGeoWithProvider = fullGeo.copy(isp = "Datacamp Limited")

        assertFalse(shouldStopIpInfoCandidateScan(IpInfoFetchMode.FULL, ipOnly))
        assertFalse(shouldStopIpInfoCandidateScan(IpInfoFetchMode.FULL, countryOnly))
        assertFalse(shouldStopIpInfoCandidateScan(IpInfoFetchMode.FULL, fullGeo))
        assertFalse(shouldStopIpInfoCandidateScan(IpInfoFetchMode.FULL, countryWithProvider))
        assertTrue(shouldStopIpInfoCandidateScan(IpInfoFetchMode.FULL, fullGeoWithProvider))
        assertFalse(shouldStopIpInfoCandidateScan(IpInfoFetchMode.ENTRY_QUICK, ipOnly))
        assertFalse(shouldStopIpInfoCandidateScan(IpInfoFetchMode.GEO_ENRICHMENT, countryWithProvider))
        assertTrue(shouldStopIpInfoCandidateScan(IpInfoFetchMode.GEO_ENRICHMENT, fullGeoWithProvider))
    }

    @Test
    fun `best full fetch candidate prefers location detail over ip only`() {
        val ipOnly =
            IpInfo(
                ip = "84.17.54.10",
                ipv4 = "84.17.54.10",
                countryCode = null,
                countryName = null,
                city = null,
                isp = null,
                fetchedAt = 1L,
            )
        val cityCandidate =
            IpInfo(
                ip = "84.17.54.10",
                ipv4 = "84.17.54.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = null,
                fetchedAt = 2L,
            )

        assertEquals(cityCandidate, selectBetterFullIpInfoCandidate(ipOnly, cityCandidate))
        assertEquals(cityCandidate, selectBetterFullIpInfoCandidate(cityCandidate, ipOnly))
    }

    @Test
    fun `best full fetch candidate prefers provider detail over geo only`() {
        val geoOnly =
            IpInfo(
                ip = "84.17.54.10",
                ipv4 = "84.17.54.10",
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = null,
                fetchedAt = 1L,
            )
        val withProvider = geoOnly.copy(isp = "Datacamp Limited", fetchedAt = 2L)

        assertEquals(withProvider, selectBetterFullIpInfoCandidate(geoOnly, withProvider))
        assertEquals(withProvider, selectBetterFullIpInfoCandidate(withProvider, geoOnly))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unsuccessful schema responses`() {
        parseIpInfoResponse(
            body =
                """
                {
                  "success": false,
                  "message": "service unavailable"
                }
                """.trimIndent(),
            json = json,
        )
    }

    @Test
    fun `merge does not copy ipv6 into ipv4 slot`() {
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

        val merged = mergeIpInfo(primary = primary, ipv4 = null, ipv6 = primary)

        assertEquals("2a00:7b80:452:2000::44", merged.ip)
        assertNull(merged.ipv4)
        assertEquals("2a00:7b80:452:2000::44", merged.ipv6)
        assertTrue(merged.localDnsServers.isEmpty())
        assertTrue(merged.remoteDnsServers.isEmpty())
    }

    @Test
    fun `best effort merge keeps primary result when family probes fail`() {
        val primary =
            IpInfo(
                ip = "84.17.54.10",
                ipv4 = "84.17.54.10",
                ipv6 = null,
                countryCode = "NL",
                countryName = "Netherlands",
                city = "Amsterdam",
                isp = "Datacamp Limited",
                fetchedAt = 1L,
            )

        val merged =
            mergeBestEffortIpInfo(
                primary = primary,
                ipv4 = Result.success(primary),
                ipv6 = Result.failure(IllegalStateException("ipv6 probe timeout")),
            )

        assertEquals(primary.ip, merged.ip)
        assertEquals(primary.ipv4, merged.ipv4)
        assertNull(merged.ipv6)
        assertEquals(primary.countryCode, merged.countryCode)
        assertEquals(primary.countryName, merged.countryName)
        assertEquals(primary.city, merged.city)
        assertEquals(primary.isp, merged.isp)
    }

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

        assertEquals("https://ipwhois.app/json/", candidates[0])
        assertEquals("https://example.com/ip", candidates[1])
        assertEquals("https://1.1.1.1/cdn-cgi/trace", candidates[2])
        assertEquals("https://ipinfo.io/json", candidates[3])
        assertEquals("https://ifconfig.co/json", candidates[4])
        assertEquals(5, candidates.size)
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
    fun `full fetch strategy skips family probes without overriding caller timeout`() {
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
        assertFalse(strategy.includeFamilyProbes)
        assertFalse(strategy.parallelCandidates)
    }

    @Test
    fun `entry quick fetch strategy uses bounded timeout and skips family probes`() {
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
        assertEquals(1_500L, strategy.callTimeoutMs)
        assertFalse(strategy.includeFamilyProbes)
        assertFalse(strategy.parallelCandidates)
    }

    @Test
    fun `geo enrichment fetch strategy uses short bounded timeout and skips family probes`() {
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

        assertEquals("https://ipwhois.app/json/", strategy.endpointCandidates.first())
        assertEquals("https://example.com/ip", strategy.endpointCandidates[1])
        assertEquals(1_200L, strategy.callTimeoutMs)
        assertFalse(strategy.includeFamilyProbes)
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
