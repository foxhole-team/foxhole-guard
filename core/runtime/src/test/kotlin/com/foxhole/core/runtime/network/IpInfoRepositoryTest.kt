package com.foxhole.core.runtime.network

import com.foxhole.core.model.IpInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class IpInfoRepositoryTest : IpInfoRepositoryTestSupport() {
    @Test
    fun `response started on the previous default network is rejected`() =
        runBlocking {
            supervisorScope {
                val requestStarted = CountDownLatch(1)
                val releaseResponse = CountDownLatch(1)
                val client =
                    OkHttpClient.Builder()
                        .addInterceptor(
                            Interceptor { chain ->
                                requestStarted.countDown()
                                check(releaseResponse.await(5, TimeUnit.SECONDS))
                                Response.Builder()
                                    .request(chain.request())
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(200)
                                    .message("OK")
                                    .body(
                                        """{"ip":"198.51.100.10","country_code":"US","city":"old-city"}"""
                                            .toResponseBody("application/json".toMediaType()),
                                    ).build()
                            },
                        ).build()
                val repository = IpInfoRepository(client = client, json = json)
                val fetch =
                    async(Dispatchers.IO) {
                        repository.fetch(
                            endpoint = "https://example.com/ip",
                            mode = IpInfoFetchMode.ENTRY_QUICK,
                        )
                    }

                assertTrue(requestStarted.await(5, TimeUnit.SECONDS))
                repository.markDefaultNetworkChanged()
                repository.evictStaleConnections()
                releaseResponse.countDown()

                val failure = runCatching { fetch.await() }.exceptionOrNull()
                assertTrue(failure is IOException)
            }
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
    fun `parses tor project check schema and takes the exit ip`() {
        val parsed =
            parseIpInfoResponse(
                body =
                """
                    {
                      "IsTor": true,
                      "IP": "171.25.193.25"
                    }
                """.trimIndent(),
                json = json,
            )

        assertEquals("171.25.193.25", parsed.ip)
        assertEquals("171.25.193.25", parsed.ipv4)
    }

    @Test
    fun `rejects tor project check response that did not egress through tor`() {
        val error =
            runCatching {
                parseIpInfoResponse(
                    body =
                    """
                        {
                          "IsTor": false,
                          "IP": "203.0.113.7"
                        }
                    """.trimIndent(),
                    json = json,
                )
            }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `strict tor parser rejects a generic ip response without explicit proof`() {
        val error =
            runCatching {
                parseIpInfoResponse(
                    body = """{"ip":"203.0.113.7"}""",
                    json = json,
                    requireTorExitProof = true,
                )
            }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `verified tor exit fetch never falls back after negative tor proof`() =
        runBlocking {
            val requestedUrls = mutableListOf<String>()
            val client =
                OkHttpClient.Builder()
                    .addInterceptor(
                        Interceptor { chain ->
                            requestedUrls += chain.request().url.toString()
                            Response.Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(
                                    """{"IsTor":false,"IP":"203.0.113.7"}"""
                                        .toResponseBody("application/json".toMediaType()),
                                ).build()
                        },
                    ).build()
            val repository = IpInfoRepository(client = client, json = json)

            val result =
                runCatching {
                    repository.fetchVerifiedTorExit(
                        callTimeoutMs = 1_000L,
                        proxy = HttpProxyAccess(
                            host = "127.0.0.1",
                            port = 9,
                            type = ProxyAccessType.SOCKS,
                        ),
                    )
                }

            assertTrue(result.isFailure)
            assertEquals(listOf(TOR_CHECK_IP_INFO_ENDPOINT), requestedUrls)
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
    fun `merge keeps primary identity when a geo-less ipv4 probe answers another network`() {
        // Гонка WiFi<->cell: primary-фетч ушёл по одной сети (его адрес и гео из одного ответа),
        // адресная ipv4-проба — по другой и БЕЗ своей геолокации. Чужой адрес не должен
        // приклеиваться к гео primary («айпи wifi, гео с моб сети» на дашборде).
        val primary =
            IpInfo(
                ip = "203.0.113.7",
                ipv4 = "203.0.113.7",
                ipv6 = null,
                countryCode = "US",
                countryName = "United States",
                city = "Atlanta",
                isp = "WiFi ISP",
                fetchedAt = 1L,
            )
        val racedIpv4Probe =
            IpInfo(
                ip = "198.51.100.9",
                ipv4 = "198.51.100.9",
                ipv6 = null,
                countryCode = null,
                countryName = null,
                city = null,
                isp = null,
                fetchedAt = 2L,
            )

        val merged = mergeIpInfo(primary = primary, ipv4 = racedIpv4Probe, ipv6 = null)

        assertEquals("203.0.113.7", merged.ip)
        assertEquals("203.0.113.7", merged.ipv4)
        assertEquals("US", merged.countryCode)
        assertEquals("Atlanta", merged.city)
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
}
