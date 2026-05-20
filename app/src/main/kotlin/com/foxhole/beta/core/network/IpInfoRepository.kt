package com.foxhole.beta.core.network

import android.net.Network
import android.util.Log
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.model.IpInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Authenticator
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.system.measureTimeMillis

enum class ProxyAccessType {
    HTTP,
    SOCKS,
}

data class HttpProxyAccess(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val type: ProxyAccessType = ProxyAccessType.HTTP,
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
        resolverNetwork: Network? = null,
    ): IpInfo? =
        withContext(Dispatchers.IO) {
            fetchFamily(
                endpoint = endpoint,
                callTimeoutMs = callTimeoutMs,
                network = network,
                addressFamilyPreference = AddressFamilyPreference.IPV4,
                proxy = proxy,
                resolverNetwork = resolverNetwork,
            )
        }

    suspend fun probe(
        endpoint: String,
        callTimeoutMs: Long? = null,
        network: Network? = null,
        proxy: HttpProxyAccess? = null,
        resolverNetwork: Network? = null,
    ) {
        withContext(Dispatchers.IO) {
            if (proxy?.type == ProxyAccessType.HTTP) {
                executeHttpProxyTunnel(endpoint, callTimeoutMs, proxy, resolverNetwork = resolverNetwork).let { response ->
                    require(response.isSuccessful) { "connectivity probe failed: ${response.code}" }
                }
            } else {
                execute(endpoint, callTimeoutMs, network, proxy = proxy, resolverNetwork = resolverNetwork).use { response ->
                    require(response.isSuccessful) { "connectivity probe failed: ${response.code}" }
                }
            }
        }
    }

    suspend fun probeIpv4(
        endpoint: String,
        callTimeoutMs: Long? = null,
        network: Network? = null,
        proxy: HttpProxyAccess? = null,
        resolverNetwork: Network? = null,
    ) {
        withContext(Dispatchers.IO) {
            execute(
                endpoint = endpoint,
                callTimeoutMs = callTimeoutMs,
                network = network,
                addressFamilyPreference = AddressFamilyPreference.IPV4,
                proxy = proxy,
                resolverNetwork = resolverNetwork,
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
        resolverNetwork: Network? = null,
    ): Long =
        withContext(Dispatchers.IO) {
            if (proxy?.type == ProxyAccessType.HTTP) {
                executeHttpProxyTunnel(endpoint, callTimeoutMs, proxy, resolverNetwork = resolverNetwork).let { response ->
                    require(response.isSuccessful) { "connectivity probe failed: ${response.code}" }
                    response.elapsedMs
                }
            } else {
                measureTimeMillis {
                    execute(endpoint, callTimeoutMs, network, proxy = proxy, resolverNetwork = resolverNetwork).use { response ->
                        require(response.isSuccessful) { "connectivity probe failed: ${response.code}" }
                    }
                }.coerceAtLeast(1L)
            }
        }

    suspend fun fetch(
        endpoint: String,
        callTimeoutMs: Long? = null,
        network: Network? = null,
        proxy: HttpProxyAccess? = null,
        resolverNetwork: Network? = null,
        mode: IpInfoFetchMode = IpInfoFetchMode.FULL,
    ): IpInfo =
        withContext(Dispatchers.IO) {
            val strategy = resolveFetchStrategy(endpoint, callTimeoutMs, mode)
            var lastFailure: Throwable? = null
            strategy.endpointCandidates.forEach { candidate ->
                currentCoroutineContext().ensureActive()
                diagnosticLog(
                    "fetch candidate host=${candidate.ipInfoHostLabel()} mode=${mode.name.lowercase()} bound=${network != null} proxy=${proxy != null}",
                )
                val result =
                    runCatching {
                        if (strategy.includeFamilyProbes) {
                            fetchSingleWithFamilyFallbacks(candidate, strategy.callTimeoutMs, network, proxy, resolverNetwork)
                        } else {
                            fetchSingle(
                                endpoint = candidate,
                                callTimeoutMs = strategy.callTimeoutMs,
                                network = network,
                                addressFamilyPreference = AddressFamilyPreference.ANY,
                                proxy = proxy,
                                resolverNetwork = resolverNetwork,
                            )
                        }
                    }
                if (result.isSuccess) {
                    diagnosticLog("fetch candidate succeeded host=${candidate.ipInfoHostLabel()}")
                    return@withContext result.getOrThrow()
                }
                lastFailure = result.exceptionOrNull()
                diagnosticLog(
                    "fetch candidate failed host=${candidate.ipInfoHostLabel()} error=${lastFailure?.javaClass?.simpleName.orEmpty()}: ${lastFailure?.message.orEmpty()}",
                )
            }
            throw lastFailure ?: IllegalStateException("ip info request failed")
        }

    private suspend fun fetchSingleWithFamilyFallbacks(
        endpoint: String,
        callTimeoutMs: Long?,
        network: Network?,
        proxy: HttpProxyAccess?,
        resolverNetwork: Network? = null,
    ): IpInfo {
        val primary = fetchSingle(endpoint, callTimeoutMs, network, AddressFamilyPreference.ANY, proxy, resolverNetwork)
        val familyCallTimeoutMs = callTimeoutMs?.coerceAtMost(FAMILY_PROBE_CALL_TIMEOUT_MS) ?: FAMILY_PROBE_CALL_TIMEOUT_MS
        val ipv4 =
            if (primary.ipv4 != null) {
                Result.success(primary)
            } else {
                runCatching {
                    fetchFamily(endpoint, familyCallTimeoutMs, network, AddressFamilyPreference.IPV4, proxy, resolverNetwork)
                }
            }
        val ipv6 =
            if (primary.ipv6 != null) {
                Result.success(primary)
            } else {
                runCatching {
                    fetchFamily(endpoint, familyCallTimeoutMs, network, AddressFamilyPreference.IPV6, proxy, resolverNetwork)
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
                    callTimeoutMs = callTimeoutMs ?: FULL_CALL_TIMEOUT_MS,
                    includeFamilyProbes = true,
                )
            IpInfoFetchMode.ENTRY_QUICK ->
                EndpointFetchStrategy(
                    endpointCandidates = quickEndpoints(endpoint),
                    callTimeoutMs = callTimeoutMs ?: ENTRY_QUICK_CALL_TIMEOUT_MS,
                    includeFamilyProbes = false,
                )
        }

    private suspend fun fetchSingle(
        endpoint: String,
        callTimeoutMs: Long?,
        network: Network?,
        addressFamilyPreference: AddressFamilyPreference,
        proxy: HttpProxyAccess?,
        resolverNetwork: Network? = null,
    ): IpInfo {
        if (proxy?.type == ProxyAccessType.HTTP) {
            val response =
                executeHttpProxyTunnel(
                    endpoint = endpoint,
                    callTimeoutMs = callTimeoutMs,
                    proxy = proxy,
                    addressFamilyPreference = addressFamilyPreference,
                    resolverNetwork = resolverNetwork,
                )
            require(response.isSuccessful) { "ip info request failed: ${response.code}" }
            return parseIpInfoResponse(response.body, json)
        }
        return execute(endpoint, callTimeoutMs, network, addressFamilyPreference, proxy, resolverNetwork).use { response ->
            require(response.isSuccessful) { "ip info request failed: ${response.code}" }
            parseIpInfoResponse(response.body?.string().orEmpty(), json)
        }
    }

    private suspend fun fetchFamily(
        endpoint: String,
        callTimeoutMs: Long?,
        network: Network?,
        addressFamilyPreference: AddressFamilyPreference,
        proxy: HttpProxyAccess?,
        resolverNetwork: Network? = null,
    ): IpInfo? {
        familyEndpoints(endpoint, addressFamilyPreference).forEach { candidate ->
            currentCoroutineContext().ensureActive()
            val info =
                runCatching { fetchSingle(candidate, callTimeoutMs, network, addressFamilyPreference, proxy, resolverNetwork) }
                    .getOrNull()
                    ?.takeIf { value ->
                        when (addressFamilyPreference) {
                            AddressFamilyPreference.ANY -> true
                            AddressFamilyPreference.IPV4 -> value.ipv4 != null
                            AddressFamilyPreference.IPV6 -> value.ipv6 != null
                        }
                    }
            if (info != null) {
                return info
            }
        }
        return null
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private suspend fun execute(
        endpoint: String,
        callTimeoutMs: Long?,
        network: Network?,
        addressFamilyPreference: AddressFamilyPreference = AddressFamilyPreference.ANY,
        proxy: HttpProxyAccess? = null,
        resolverNetwork: Network? = null,
    ) = run {
        val url =
            endpoint
                .ensurePublicHttpsUrl()
                .requirePublicHttpsUrl(resolveHost = true) { hostname ->
                    resolveAddresses(hostname, network, AddressFamilyPreference.ANY, resolverNetwork)
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
                        proxy(
                            Proxy(
                                when (proxy.type) {
                                    ProxyAccessType.HTTP -> Proxy.Type.HTTP
                                    ProxyAccessType.SOCKS -> Proxy.Type.SOCKS
                                },
                                InetSocketAddress(proxy.host, proxy.port),
                            ),
                        )
                        if (proxy.type == ProxyAccessType.HTTP && !proxy.username.isNullOrBlank() && !proxy.password.isNullOrBlank()) {
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
                                val addresses = resolveAddresses(hostname, network, addressFamilyPreference, resolverNetwork)
                                prioritize(addresses, addressFamilyPreference)
                            },
                        )
                    }
                }.build()
            }
        if (proxy?.type == ProxyAccessType.SOCKS && !proxy.username.isNullOrBlank() && !proxy.password.isNullOrBlank()) {
            synchronized(SOCKS_AUTH_LOCK) {
                Authenticator.setDefault(
                    object : Authenticator() {
                        override fun getPasswordAuthentication(): PasswordAuthentication? =
                            if (requestingHost == proxy.host && requestingPort == proxy.port) {
                                PasswordAuthentication(proxy.username, proxy.password.toCharArray())
                            } else {
                                null
                            }
                    },
                )
                return@synchronized try {
                    effectiveClient.newCall(request).execute()
                } finally {
                    Authenticator.setDefault(null)
                }
            }
        } else {
            effectiveClient.newCall(request).awaitResponse()
        }
    }

    private suspend fun executeHttpProxyTunnel(
        endpoint: String,
        callTimeoutMs: Long?,
        proxy: HttpProxyAccess,
        addressFamilyPreference: AddressFamilyPreference = AddressFamilyPreference.ANY,
        resolverNetwork: Network? = null,
    ): HttpProxyTunnelResponse =
        withContext(Dispatchers.IO) {
            val url =
                endpoint
                    .ensurePublicHttpsUrl()
                    .requirePublicHttpsUrl(resolveHost = true) { hostname ->
                        resolveAddresses(hostname, network = null, preference = addressFamilyPreference, resolverNetwork = resolverNetwork)
                    }
            val target = HttpProxyTunnelTarget(url.host, url.port)
            val timeout = (callTimeoutMs ?: FULL_CALL_TIMEOUT_MS).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            val startedAt = System.nanoTime()
            Socket().use { rawSocket ->
                rawSocket.soTimeout = timeout
                rawSocket.connect(InetSocketAddress(proxy.host, proxy.port), timeout)
                val output = rawSocket.getOutputStream()
                output.write(proxyConnectRequest(target, proxy).toByteArray(Charsets.ISO_8859_1))
                output.flush()
                val connectHead = readHttpResponseHead(rawSocket.getInputStream())
                require(connectHead.code in 200..299) { "proxy CONNECT failed: ${connectHead.code}" }

                val sslSocket =
                    (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(rawSocket, url.host, url.port, true) as SSLSocket
                sslSocket.soTimeout = timeout
                sslSocket.use { tlsSocket ->
                    tlsSocket.startHandshake()
                    require(HttpsURLConnection.getDefaultHostnameVerifier().verify(url.host, tlsSocket.session)) {
                        "proxy tunnel TLS hostname verification failed"
                    }
                    val tlsOutput = tlsSocket.getOutputStream()
                    tlsOutput.write(proxyTunnelGetRequest(url.encodedPathWithQuery(), target).toByteArray(Charsets.ISO_8859_1))
                    tlsOutput.flush()
                    val input = tlsSocket.getInputStream()
                    val responseHead = readHttpResponseHead(input)
                    val body = String(readHttpResponseBody(input, responseHead), Charsets.UTF_8)
                    HttpProxyTunnelResponse(
                        code = responseHead.code,
                        body = body,
                        elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L),
                    )
                }
            }
        }

    private fun effectiveEndpoints(endpoint: String): List<String> {
        val primary = primaryEndpoint(endpoint)
        return buildList {
            add(primary)
            FALLBACK_ENDPOINTS.forEach { candidate ->
                if (!candidate.equals(primary, ignoreCase = true)) {
                    add(candidate)
                }
            }
        }
    }

    private fun quickEndpoints(endpoint: String): List<String> {
        val primary = primaryEndpoint(endpoint)
        val fallback = FALLBACK_ENDPOINTS.firstOrNull { candidate -> !candidate.equals(primary, ignoreCase = true) }
        return listOfNotNull(primary, fallback)
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
        val primary = primaryEndpoint(endpoint)
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
        resolverNetwork: Network? = null,
    ): List<InetAddress> {
        val publicAddresses =
            runCatching {
                resolverNetwork
                    ?.getAllByName(hostname)
                    ?.toList()
                    ?: client.dns.lookup(hostname)
            }
        val candidate =
            publicResolvedAddressesOrNetworkFallback(publicAddresses) {
                network?.getAllByName(hostname)?.toList().orEmpty()
            }
        return prioritize(candidate.preferIpv4(), preference)
    }

    private enum class AddressFamilyPreference {
        ANY,
        IPV4,
        IPV6,
    }

    private fun primaryEndpoint(endpoint: String): String = endpoint.trim().ifBlank { BuildConfig.DEFAULT_IP_INFO_ENDPOINT }

    private companion object {
        val SOCKS_AUTH_LOCK = Any()
        const val ENTRY_QUICK_CALL_TIMEOUT_MS = 1_500L
        const val FULL_CALL_TIMEOUT_MS = 4_000L
        const val FAMILY_PROBE_CALL_TIMEOUT_MS = 1_500L
        val FALLBACK_ENDPOINTS =
            listOf(
                BuildConfig.DEFAULT_IP_INFO_ENDPOINT,
                "https://ipinfo.io/json",
                "https://ifconfig.co/json",
                "https://api64.ipify.org?format=json",
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

private data class HttpProxyTunnelTarget(
    val host: String,
    val port: Int,
) {
    val authority: String =
        if (host.contains(':') && !host.startsWith('[')) {
            "[$host]:$port"
        } else {
            "$host:$port"
        }
}

private data class HttpProxyTunnelResponse(
    val code: Int,
    val body: String,
    val elapsedMs: Long,
) {
    val isSuccessful: Boolean
        get() = code in 200..299
}

private data class HttpResponseHead(
    val code: Int,
    val headers: Map<String, List<String>>,
) {
    fun firstHeader(name: String): String? = headers[name.lowercase()]?.firstOrNull()
}

private fun proxyConnectRequest(
    target: HttpProxyTunnelTarget,
    proxy: HttpProxyAccess,
): String {
    val authHeader =
        if (!proxy.username.isNullOrBlank() && !proxy.password.isNullOrBlank()) {
            "Proxy-Authorization: ${Credentials.basic(proxy.username, proxy.password)}\r\n"
        } else {
            ""
        }
    return "CONNECT ${target.authority} HTTP/1.1\r\n" +
        "Host: ${target.authority}\r\n" +
        authHeader +
        "Proxy-Connection: keep-alive\r\n" +
        "\r\n"
}

private fun proxyTunnelGetRequest(
    path: String,
    target: HttpProxyTunnelTarget,
): String =
    "GET $path HTTP/1.1\r\n" +
        "Host: ${target.authority}\r\n" +
        "User-Agent: FoxHole/${BuildConfig.VERSION_NAME}\r\n" +
        "Accept: application/json,*/*;q=0.1\r\n" +
        "Connection: close\r\n" +
        "\r\n"

private fun readHttpResponseHead(input: InputStream): HttpResponseHead {
    val buffer = ByteArrayOutputStream()
    var tail = 0
    while (buffer.size() < HTTP_TUNNEL_MAX_HEADER_BYTES) {
        val value = input.read()
        if (value == -1) {
            break
        }
        buffer.write(value)
        tail = (tail shl 8) or (value and 0xff)
        if (tail == HTTP_HEADER_TERMINATOR) {
            val text = buffer.toString(Charsets.ISO_8859_1.name())
            val lines = text.substringBefore("\r\n\r\n").split("\r\n")
            val statusCode =
                lines.firstOrNull()
                    ?.split(' ', limit = 3)
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: error("invalid http response status")
            val headers =
                lines.drop(1)
                    .mapNotNull { line ->
                        val separator = line.indexOf(':')
                        if (separator <= 0) {
                            null
                        } else {
                            line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
                        }
                    }.groupBy(keySelector = { it.first }, valueTransform = { it.second })
            return HttpResponseHead(statusCode, headers)
        }
    }
    error("http response headers incomplete")
}

private fun readHttpResponseBody(
    input: InputStream,
    head: HttpResponseHead,
): ByteArray {
    if (head.code == 204 || head.code == 304 || head.code in 100..199) {
        return ByteArray(0)
    }
    if (head.firstHeader("transfer-encoding")?.contains("chunked", ignoreCase = true) == true) {
        return readChunkedHttpBody(input)
    }
    val contentLength = head.firstHeader("content-length")?.toIntOrNull()
    if (contentLength != null) {
        require(contentLength <= HTTP_TUNNEL_MAX_BODY_BYTES) { "http response body too large" }
        return input.readExactBytesBounded(contentLength)
    }
    return input.readUntilEofBounded(HTTP_TUNNEL_MAX_BODY_BYTES)
}

private fun readChunkedHttpBody(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    while (true) {
        val sizeLine = input.readAsciiLine(HTTP_TUNNEL_MAX_LINE_BYTES)
        val chunkSize = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: error("invalid chunk size")
        if (chunkSize == 0) {
            while (input.readAsciiLine(HTTP_TUNNEL_MAX_LINE_BYTES).isNotEmpty()) {
                // Consume trailers.
            }
            return output.toByteArray()
        }
        require(output.size() + chunkSize <= HTTP_TUNNEL_MAX_BODY_BYTES) { "http response body too large" }
        output.write(input.readExactBytesBounded(chunkSize))
        input.expectCrlf()
    }
}

private fun InputStream.readExactBytesBounded(size: Int): ByteArray {
    val output = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(output, offset, size - offset)
        if (read == -1) {
            error("http response body ended early")
        }
        offset += read
    }
    return output
}

private fun InputStream.readUntilEofBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = read(buffer)
        if (read == -1) {
            return output.toByteArray()
        }
        require(output.size() + read <= maxBytes) { "http response body too large" }
        output.write(buffer, 0, read)
    }
}

private fun InputStream.readAsciiLine(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    while (output.size() < maxBytes) {
        val value = read()
        if (value == -1) {
            error("http response ended before line")
        }
        if (value == '\n'.code) {
            return output.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
        }
        output.write(value)
    }
    error("http response line too long")
}

private fun InputStream.expectCrlf() {
    val cr = read()
    val lf = read()
    require(cr == '\r'.code && lf == '\n'.code) { "invalid chunk delimiter" }
}

private fun okhttp3.HttpUrl.encodedPathWithQuery(): String =
    encodedPath + encodedQuery?.let { query -> "?$query" }.orEmpty()

private const val HTTP_TUNNEL_MAX_HEADER_BYTES = 16 * 1024
private const val HTTP_TUNNEL_MAX_BODY_BYTES = 128 * 1024
private const val HTTP_TUNNEL_MAX_LINE_BYTES = 8 * 1024
private const val HTTP_HEADER_TERMINATOR = 0x0D0A0D0A

private suspend fun Call.awaitResponse(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (!continuation.isActive) {
                        return
                    }
                    continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    continuation.resume(response)
                }
            },
        )
    }

private fun diagnosticLog(message: String) {
    if (BuildConfig.ENABLE_DIAGNOSTIC_LOGCAT) {
        Log.d("FoxholeDiag", "[ip] $message")
    }
}

private fun String.ipInfoHostLabel(): String = toHttpUrlOrNull()?.host ?: "invalid"

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
    val country = objectValue.string("country")
    val countryCode =
        objectValue.string("country_code")
            ?: objectValue.string("country_iso")
            ?: objectValue.string("cc")
            ?: country?.takeIf { it.length == ISO_COUNTRY_CODE_LENGTH }
    val countryName =
        objectValue.string("country_name")
            ?: objectValue.string("countryName")
            ?: country?.takeUnless { it.length == ISO_COUNTRY_CODE_LENGTH }
    return IpInfo(
        ip = ip,
        ipv4 = ip.takeIf(::isIpv4Address),
        ipv6 = ip.takeIf(::isIpv6Address),
        countryCode = countryCode,
        countryName = countryName,
        city = objectValue.string("city"),
        isp =
            objectValue.string("isp")
                ?: objectValue.string("organization")
                ?: objectValue.string("asn_org")
                ?: objectValue.string("org")
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

private const val ISO_COUNTRY_CODE_LENGTH = 2

internal fun publicResolvedAddressesOrNetworkFallback(
    publicAddresses: Result<List<InetAddress>>,
    networkFallback: () -> List<InetAddress>,
): List<InetAddress> =
    publicAddresses.getOrNull()?.takeIf { it.isNotEmpty() }
        ?: runCatching(networkFallback).getOrNull()?.takeIf { it.isNotEmpty() }
        ?: publicAddresses.getOrThrow()
