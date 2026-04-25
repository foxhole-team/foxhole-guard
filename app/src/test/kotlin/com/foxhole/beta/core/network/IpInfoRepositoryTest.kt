package com.foxhole.beta.core.network

import com.foxhole.beta.core.model.IpInfo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue(candidates.size > 1)
    }

    @Test
    fun `entry quick mode keeps primary plus fallback endpoints`() {
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

        assertEquals("https://example.com/ip", candidates.first())
        assertTrue(candidates.size > 1)
    }

    @Test
    fun `full fetch strategy probes address families without overriding caller timeout`() {
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
        assertTrue(strategy.includeFamilyProbes)
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

        assertEquals("https://example.com/ip", strategy.endpointCandidates.first())
        assertEquals(2_500L, strategy.callTimeoutMs)
        assertFalse(strategy.includeFamilyProbes)
    }
}
