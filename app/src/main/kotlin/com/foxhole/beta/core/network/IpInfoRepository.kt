package com.foxhole.beta.core.network

import android.net.Network
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.model.IpInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

data class HttpProxyAccess(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
)

enum class IpInfoFetchMode {
    FULL,
    ENTRY_QUICK,
}

class IpInfoRepository(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetchIpv4(
        endpoint: String,
        callTimeoutMs: Long? = null,
        network: Network? = null,
        proxy: HttpProxyAccess? = null,
    ): IpInfo? =
        withContext(Dispatchers.IO) {
            fetchFamily(
                endpoint = endpoint,
                callTimeoutMs = callTimeoutMs,
                network = network,
                addressFamilyPreference = AddressFamilyPreference.IPV4,
                proxy = proxy,
            )
        }

    suspend fun probe(
        endpoint: String,
        callTimeoutMs: Long? = null,
        network: Network? = null,
        proxy: HttpProxyAccess? = null,
    ) {
        withContext(Dispatchers.IO) {
            execute(endpoint, callTimeoutMs, network, proxy = proxy).use { response ->
                require(response.isSuccessful) { "connectivity probe failed: ${response.code}" }
            }
        }
    }

    suspend fun probeIpv4(
        endpoint: String,
        callTimeoutMs: Long? = null,
        network: Network? = null,
        proxy: HttpProxyAccess? = null,
    ) {
        withContext(Dispatchers.IO) {
            execute(
                endpoint = endpoint,
                callTimeoutMs = callTimeoutMs,
                network = network,
                addressFamilyPreference = AddressFamilyPreference.IPV4,
                proxy = proxy,
            ).use { response ->
                require(response.isSuccessful) { "connectivity probe failed: ${response.code}" }
            }
        }
    }

    suspend fun probeLatency(
        endpoint: String,
        callTimeoutMs: Long? = null,
        network: Network? = null,
        proxy: HttpProxyAccess? = null,
    ): Long =
        withContext(Dispatchers.IO) {
            measureTimeMillis {
                execute(endpoint, callTimeoutMs, network, proxy = proxy).use { response ->
                    require(response.isSuccessful) { "connectivity probe failed: ${response.code}" }
                }
            }.coerceAtLeast(1L)
        }

    suspend fun fetch(
        endpoint: String,
        callTimeoutMs: Long? = null,
        network: Network? = null,
        proxy: HttpProxyAccess? = null,
        mode: IpInfoFetchMode = IpInfoFetchMode.FULL,
    ): IpInfo =
        withContext(Dispatchers.IO) {
            val strategy = resolveFetchStrategy(endpoint, callTimeoutMs, mode)
            var lastFailure: Throwable? = null
            strategy.endpointCandidates.forEach { candidate ->
                val result =
                    runCatching {
                        if (strategy.includeFamilyProbes) {
                            fetchSingleWithFamilyFallbacks(candidate, strategy.callTimeoutMs, network, proxy)
                        } else {
                            fetchSingle(
                                endpoint = candidate,
                                callTimeoutMs = strategy.callTimeoutMs,
                                network = network,
                                addressFamilyPreference = AddressFamilyPreference.ANY,
                                proxy = proxy,
                            )
                        }
                    }
                if (result.isSuccess) {
                    return@withContext result.getOrThrow()
                }
                lastFailure = result.exceptionOrNull()
            }
            throw lastFailure ?: IllegalStateException("ip info request failed")
        }

    private fun fetchSingleWithFamilyFallbacks(
        endpoint: String,
        callTimeoutMs: Long?,
        network: Network?,
        proxy: HttpProxyAccess?,
    ): IpInfo {
        val primary = fetchSingle(endpoint, callTimeoutMs, network, AddressFamilyPreference.ANY, proxy)
        val familyCallTimeoutMs = callTimeoutMs?.coerceAtMost(FAMILY_PROBE_CALL_TIMEOUT_MS) ?: FAMILY_PROBE_CALL_TIMEOUT_MS
        val ipv4 =
            if (primary.ipv4 != null) {
                Result.success(primary)
            } else {
                runCatching {
                    fetchFamily(endpoint, familyCallTimeoutMs, network, AddressFamilyPreference.IPV4, proxy)
                }
            }
        val ipv6 =
            if (primary.ipv6 != null) {
                Result.success(primary)
            } else {
                runCatching {
                    fetchFamily(endpoint, familyCallTimeoutMs, network, AddressFamilyPreference.IPV6, proxy)
                }
            }
        return mergeBestEffortIpInfo(primary = primary, ipv4 = ipv4, ipv6 = ipv6)
    }

    internal data class EndpointFetchStrategy(
        val endpointCandidates: List<String>,
        val callTimeoutMs: Long?,
        val includeFamilyProbes: Boolean,
    )

    internal fun resolveFetchStrategy(
        endpoint: String,
        callTimeoutMs: Long?,
        mode: IpInfoFetchMode,
    ): EndpointFetchStrategy =
        when (mode) {
            IpInfoFetchMode.FULL ->
                EndpointFetchStrategy(
                    endpointCandidates = effectiveEndpoints(endpoint),
                    callTimeoutMs = callTimeoutMs,
                    includeFamilyProbes = true,
                )
            IpInfoFetchMode.ENTRY_QUICK ->
                EndpointFetchStrategy(
                    endpointCandidates = effectiveEndpoints(endpoint),
                    callTimeoutMs = callTimeoutMs ?: ENTRY_QUICK_CALL_TIMEOUT_MS,
                    includeFamilyProbes = false,
                )
        }

    private fun fetchSingle(
        endpoint: String,
        callTimeoutMs: Long?,
        network: Network?,
        addressFamilyPreference: AddressFamilyPreference,
        proxy: HttpProxyAccess?,
    ): IpInfo =
        execute(endpoint, callTimeoutMs, network, addressFamilyPreference, proxy).use { response ->
            require(response.isSuccessful) { "ip info request failed: ${response.code}" }
            parseIpInfoResponse(response.body?.string().orEmpty(), json)
        }

    private fun fetchFamily(
        endpoint: String,
        callTimeoutMs: Long?,
        network: Network?,
        addressFamilyPreference: AddressFamilyPreference,
        proxy: HttpProxyAccess?,
    ): IpInfo? =
        familyEndpoints(endpoint, addressFamilyPreference).firstNotNullOfOrNull { candidate ->
            runCatching { fetchSingle(candidate, callTimeoutMs, network, addressFamilyPreference, proxy) }
                .getOrNull()
                ?.takeIf { info ->
                    when (addressFamilyPreference) {
                        AddressFamilyPreference.ANY -> true
                        AddressFamilyPreference.IPV4 -> info.ipv4 != null
                        AddressFamilyPreference.IPV6 -> info.ipv6 != null
                    }
                }
        }

    private fun execute(
        endpoint: String,
        callTimeoutMs: Long?,
        network: Network?,
        addressFamilyPreference: AddressFamilyPreference = AddressFamilyPreference.ANY,
        proxy: HttpProxyAccess? = null,
    ) = run {
        val url =
            endpoint
                .ensurePublicHttpsUrl()
                .requirePublicHttpsUrl(resolveHost = true) { hostname ->
                    resolveAddresses(hostname, network, AddressFamilyPreference.ANY)
                }
        val request = Request.Builder().url(url).get().build()
        val effectiveClient =
            if (callTimeoutMs == null && network == null && addressFamilyPreference == AddressFamilyPreference.ANY && proxy == null) {
                client
                    .newBuilder()
                    .dns(PublicRemoteDns(client.dns::lookup))
                    .build()
            } else {
                client.newBuilder().apply {
                    callTimeoutMs?.let { timeout -> callTimeout(timeout, TimeUnit.MILLISECONDS) }
                    if (proxy != null) {
                        proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.host, proxy.port)))
                        if (!proxy.username.isNullOrBlank() && !proxy.password.isNullOrBlank()) {
                            proxyAuthenticator { _, response ->
                                if (response.request.header("Proxy-Authorization") != null) {
                                    null
                                } else {
                                    response.request
                                        .newBuilder()
                                        .header(
                                            "Proxy-Authorization",
                                            Credentials.basic(proxy.username, proxy.password),
                                        ).build()
                                }
                            }
                        }
                    } else {
                        network?.let { socketFactory(it.socketFactory) }
                    }
                    if (proxy == null) {
                        dns(
                            PublicRemoteDns { hostname ->
                                val addresses = resolveAddresses(hostname, network, addressFamilyPreference)
                                prioritize(addresses, addressFamilyPreference)
                            },
                        )
                    }
                }.build()
            }
        effectiveClient.newCall(request).execute()
    }

    private fun effectiveEndpoints(endpoint: String): List<String> {
        val primary = endpoint.trim().ifBlank { BuildConfig.DEFAULT_IP_INFO_ENDPOINT }
        return buildList {
            add(primary)
            FALLBACK_ENDPOINTS.forEach { candidate ->
                if (!candidate.equals(primary, ignoreCase = true)) {
                    add(candidate)
                }
            }
        }
    }

    internal fun effectiveEndpointCandidates(
        endpoint: String,
        mode: IpInfoFetchMode,
    ): List<String> =
        resolveFetchStrategy(endpoint = endpoint, callTimeoutMs = null, mode = mode).endpointCandidates

    private fun familyEndpoints(
        endpoint: String,
        addressFamilyPreference: AddressFamilyPreference,
    ): List<String> {
        val primary = endpoint.trim().ifBlank { BuildConfig.DEFAULT_IP_INFO_ENDPOINT }
        val familyFallbacks =
            when (addressFamilyPreference) {
                AddressFamilyPreference.ANY -> emptyList()
                AddressFamilyPreference.IPV4 -> IPV4_FALLBACK_ENDPOINTS
                AddressFamilyPreference.IPV6 -> IPV6_FALLBACK_ENDPOINTS
            }
        return buildList {
            add(primary)
            familyFallbacks.forEach { candidate ->
                if (!candidate.equals(primary, ignoreCase = true)) {
                    add(candidate)
                }
            }
        }
    }

    private fun prioritize(
        addresses: List<InetAddress>,
        preference: AddressFamilyPreference,
    ): List<InetAddress> =
        when (preference) {
            AddressFamilyPreference.ANY -> addresses
            AddressFamilyPreference.IPV4 -> addresses.filterIsInstance<Inet4Address>()
            AddressFamilyPreference.IPV6 -> addresses.filterIsInstance<Inet6Address>()
        }

    private fun resolveAddresses(
        hostname: String,
        network: Network?,
        preference: AddressFamilyPreference,
    ): List<InetAddress> {
        val candidate =
            if (network != null) {
                network.getAllByName(hostname).toList()
            } else {
                client.dns.lookup(hostname)
            }
        return prioritize(candidate, preference)
    }

    private enum class AddressFamilyPreference {
        ANY,
        IPV4,
        IPV6,
    }

    private companion object {
        const val ENTRY_QUICK_CALL_TIMEOUT_MS = 2_500L
        const val FAMILY_PROBE_CALL_TIMEOUT_MS = 1_500L
        val FALLBACK_ENDPOINTS =
            listOf(
                BuildConfig.DEFAULT_IP_INFO_ENDPOINT,
                "https://ifconfig.co/json",
                "https://api.ipify.org?format=json",
            )
        val IPV4_FALLBACK_ENDPOINTS =
            listOf(
                "https://api.ipify.org?format=json",
            )
        val IPV6_FALLBACK_ENDPOINTS =
            listOf(
                "https://api6.ipify.org?format=json",
            )
    }
}

internal fun parseIpInfoResponse(
    body: String,
    json: Json,
): IpInfo {
    val objectValue = json.parseToJsonElement(body).jsonObject
    require(objectValue.boolean("success") != false) {
        objectValue.string("message") ?: "ip info request failed"
    }
    val ip = objectValue.string("ip")?.takeIf(String::isNotBlank) ?: error("ip info response missing ip")
    val connection = objectValue["connection"]?.jsonObject
    return IpInfo(
        ip = ip,
        ipv4 = ip.takeIf(::isIpv4Address),
        ipv6 = ip.takeIf(::isIpv6Address),
        countryCode = objectValue.string("country_code") ?: objectValue.string("country_iso"),
        countryName = objectValue.string("country"),
        city = objectValue.string("city"),
        isp =
            objectValue.string("isp")
                ?: objectValue.string("organization")
                ?: objectValue.string("asn_org")
                ?: connection?.string("isp")
                ?: connection?.string("org"),
        fetchedAt = System.currentTimeMillis(),
    )
}

internal fun mergeIpInfo(
    primary: IpInfo,
    ipv4: IpInfo?,
    ipv6: IpInfo?,
): IpInfo =
    primary.copy(
        ip = ipv4?.ipv4 ?: primary.ip,
        ipv4 = ipv4?.ipv4 ?: primary.ipv4,
        ipv6 = ipv6?.ipv6 ?: primary.ipv6,
        countryCode = primary.countryCode ?: ipv4?.countryCode ?: ipv6?.countryCode,
        countryName = primary.countryName ?: ipv4?.countryName ?: ipv6?.countryName,
        city = primary.city ?: ipv4?.city ?: ipv6?.city,
        isp = primary.isp ?: ipv4?.isp ?: ipv6?.isp,
    )

internal fun mergeBestEffortIpInfo(
    primary: IpInfo,
    ipv4: Result<IpInfo?>,
    ipv6: Result<IpInfo?>,
): IpInfo =
    mergeIpInfo(
        primary = primary,
        ipv4 = ipv4.getOrNull(),
        ipv6 = ipv6.getOrNull(),
    )

private fun Map<String, JsonElement>.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun Map<String, JsonElement>.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

private fun isIpv4Address(value: String): Boolean = value.contains('.') && !value.contains(':')

private fun isIpv6Address(value: String): Boolean = value.contains(':')
