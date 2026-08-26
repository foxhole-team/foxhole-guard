package com.foxhole.core.runtime.network

import android.net.Network
import com.foxhole.core.model.IpInfo
import com.foxhole.core.network.ensurePublicHttpsUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.Socket
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.system.measureTimeMillis

@Suppress("LargeClass")
class IpInfoRepository(
    private val client: OkHttpClient,
    private val json: Json,

    private val localCountryCode: ((String) -> String?)? = null,
) {
    private val httpClientCacheLock = Any()
    private val httpClientCache =
        LinkedHashMap<HttpClientKey, CachedHttpClient>(
            HTTP_CLIENT_CACHE_MAX_SIZE,
            0.75f,
            true,
        )
    private val defaultNetworkEpoch = AtomicLong(0L)

    fun markDefaultNetworkChanged(): Long = defaultNetworkEpoch.incrementAndGet()

    /** Clears sockets and clients bound to the previous default network. Call off the main thread. */
    fun evictStaleConnections() {
        synchronized(httpClientCacheLock) {
            httpClientCache.clear()
        }
        runCatching { client.connectionPool.evictAll() }
    }

    private suspend fun <T> withCurrentDefaultNetworkEpoch(block: suspend () -> T): T {
        val startedAtEpoch = defaultNetworkEpoch.get()
        val result = block()
        if (defaultNetworkEpoch.get() != startedAtEpoch) {
            throw IOException("default network changed during IP info request")
        }
        return result
    }

    suspend fun fetchIpv4(
        endpoint: String,
        callTimeoutMs: Long? = null,
        network: Network? = null,
        proxy: HttpProxyAccess? = null,
        resolverNetwork: Network? = null,
        torExit: Boolean = false,
    ): IpInfo? =
        withCurrentDefaultNetworkEpoch {
            withContext(Dispatchers.IO) {
                fetchFamily(
                    endpoint = endpoint,
                    callTimeoutMs = callTimeoutMs,
                    network = network,
                    addressFamilyPreference = AddressFamilyPreference.IPV4,
                    proxy = proxy,
                    resolverNetwork = resolverNetwork,
                    torExit = torExit,
                )
            }
        }

    suspend fun probe(
        endpoint: String,
        callTimeoutMs: Long? = null,
        network: Network? = null,
        proxy: HttpProxyAccess? = null,
        resolverNetwork: Network? = null,
    ) {
        withCurrentDefaultNetworkEpoch {
            withContext(Dispatchers.IO) {
                if (proxy?.type == ProxyAccessType.HTTP) {
                    executeHttpProxyTunnel(
                        endpoint = endpoint,
                        callTimeoutMs = callTimeoutMs,
                        proxy = proxy,
                    ).let { response ->
                        require(response.isSuccessful) { "connectivity probe failed: ${response.code}" }
                    }
                } else {
                    execute(
                        endpoint,
                        callTimeoutMs,
                        network,
                        proxy = proxy,
                        resolverNetwork = resolverNetwork
                    ).use { response ->
                        require(response.isSuccessful) { "connectivity probe failed: ${response.code}" }
                    }
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
        withCurrentDefaultNetworkEpoch {
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
    }

    suspend fun probeLatency(
        endpoint: String,
        callTimeoutMs: Long? = null,
        network: Network? = null,
        proxy: HttpProxyAccess? = null,
        resolverNetwork: Network? = null,
    ): Long =
        withCurrentDefaultNetworkEpoch {
            withContext(Dispatchers.IO) {
                if (proxy?.type == ProxyAccessType.HTTP) {
                    executeHttpProxyTunnel(
                        endpoint = endpoint,
                        callTimeoutMs = callTimeoutMs,
                        proxy = proxy,
                    ).let { response ->
                        require(response.isSuccessful) { "connectivity probe failed: ${response.code}" }
                        response.elapsedMs
                    }
                } else {
                    measureTimeMillis {
                        withBoundedCallTimeout(callTimeoutMs) {
                            execute(
                                endpoint,
                                callTimeoutMs,
                                network,
                                proxy = proxy,
                                resolverNetwork = resolverNetwork
                            ).use { response ->
                                require(response.isSuccessful) { "connectivity probe failed: ${response.code}" }
                            }
                        }
                    }.coerceAtLeast(1L)
                }
            }
        }

    suspend fun fetch(
        endpoint: String,
        callTimeoutMs: Long? = null,
        network: Network? = null,
        proxy: HttpProxyAccess? = null,
        resolverNetwork: Network? = null,
        mode: IpInfoFetchMode = IpInfoFetchMode.FULL,
        torExit: Boolean = false,
    ): IpInfo =
        withCurrentDefaultNetworkEpoch {
            withContext(Dispatchers.IO) {
                val strategy = resolveFetchStrategy(endpoint, callTimeoutMs, mode, torExit)
                var lastFailure: Throwable? = null
                var bestFullCandidate: IpInfo? = null
                val asnProviderCache = mutableMapOf<String, Result<String?>>()
                if (strategy.parallelCandidates) {
                    return@withContext fetchCandidatesInParallel(
                        strategy = strategy,
                        mode = mode,
                        network = network,
                        proxy = proxy,
                        resolverNetwork = resolverNetwork,
                        asnProviderCache = asnProviderCache,
                    )
                }
                strategy.endpointCandidates.forEach { candidate ->
                    currentCoroutineContext().ensureActive()
                    diagnosticLog(
                        "fetch candidate host=${candidate.ipInfoHostLabel()} mode=${mode.name.lowercase()} bound=${network != null} proxy=${proxy != null}",
                    )
                    val result =
                        runCatching {
                            withBoundedCallTimeout(strategy.callTimeoutMs) {
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
                        val info =
                            enrichWithAsnProviderIfNeeded(
                                info = result.getOrThrow(),
                                cache = asnProviderCache,
                                mode = mode,
                            )
                        bestFullCandidate = selectBetterFullIpInfoCandidate(bestFullCandidate, info)
                        if (shouldStopIpInfoCandidateScan(mode, info)) {
                            diagnosticLog("fetch candidate succeeded host=${candidate.ipInfoHostLabel()}")
                            return@withContext info
                        }
                        diagnosticLog(
                            "fetch candidate incomplete host=${candidate.ipInfoHostLabel()} quality=${info.fullIpInfoQualityScore()}",
                        )
                    }
                    val failure = result.exceptionOrNull()
                    if (failure is CancellationException) {
                        throw failure
                    }
                    lastFailure = failure
                    if (failure != null) {
                        diagnosticLog(
                            "fetch candidate failed host=${candidate.ipInfoHostLabel()} error=${failure.javaClass.simpleName}: ${failure.message.orEmpty()}",
                        )
                    }
                }
                bestFullCandidate?.let { candidate ->
                    diagnosticLog("fetch candidate best-effort quality=${candidate.fullIpInfoQualityScore()}")
                    return@withContext candidate
                }
                throw lastFailure ?: error("ip info request failed")
            }
        }

    suspend fun fetchVerifiedTorExit(
        callTimeoutMs: Long? = null,
        proxy: HttpProxyAccess,
        resolverNetwork: Network? = null,
    ): IpInfo =
        withCurrentDefaultNetworkEpoch {
            withContext(Dispatchers.IO) {
                withBoundedCallTimeout(callTimeoutMs) {
                    fetchSingle(
                        endpoint = TOR_CHECK_IP_INFO_ENDPOINT,
                        callTimeoutMs = callTimeoutMs,
                        network = null,
                        addressFamilyPreference = AddressFamilyPreference.ANY,
                        proxy = proxy,
                        resolverNetwork = resolverNetwork,
                        requireTorExitProof = true,
                    )
                }
            }
        }

    private suspend fun fetchCandidatesInParallel(
        strategy: EndpointFetchStrategy,
        mode: IpInfoFetchMode,
        network: Network?,
        proxy: HttpProxyAccess?,
        resolverNetwork: Network?,
        asnProviderCache: MutableMap<String, Result<String?>>,
    ): IpInfo =
        coroutineScope {
            val results = Channel<Pair<String, Result<IpInfo>>>(capacity = strategy.endpointCandidates.size)
            val candidateJobs =
                strategy.endpointCandidates.map { candidate ->
                    diagnosticLog(
                        "fetch candidate host=${candidate.ipInfoHostLabel()} mode=${mode.name.lowercase()} bound=${network != null} proxy=${proxy != null}",
                    )
                    launch {
                        results.send(
                            candidate to
                                runCatching {
                                    withBoundedCallTimeout(strategy.callTimeoutMs) {
                                        fetchSingle(
                                            endpoint = candidate,
                                            callTimeoutMs = strategy.callTimeoutMs,
                                            network = network,
                                            addressFamilyPreference = AddressFamilyPreference.ANY,
                                            proxy = proxy,
                                            resolverNetwork = resolverNetwork,
                                        )
                                    }
                                },
                        )
                    }
                }
            var lastFailure: Throwable? = null
            var bestFullCandidate: IpInfo? = null
            try {
                repeat(strategy.endpointCandidates.size) {
                    val (candidate, result) = results.receive()
                    if (result.isSuccess) {
                        val info =
                            enrichWithAsnProviderIfNeeded(
                                info = result.getOrThrow(),
                                cache = asnProviderCache,
                                mode = mode,
                            )
                        bestFullCandidate = selectBetterFullIpInfoCandidate(bestFullCandidate, info)
                        if (shouldStopIpInfoCandidateScan(mode, info)) {
                            diagnosticLog("fetch candidate succeeded host=${candidate.ipInfoHostLabel()}")
                            return@coroutineScope info
                        }
                        diagnosticLog(
                            "fetch candidate incomplete host=${candidate.ipInfoHostLabel()} quality=${info.fullIpInfoQualityScore()}",
                        )
                    }
                    val failure = result.exceptionOrNull()
                    if (failure is CancellationException) {
                        throw failure
                    }
                    lastFailure = failure
                    if (failure != null) {
                        diagnosticLog(
                            "fetch candidate failed host=${candidate.ipInfoHostLabel()} error=${failure.javaClass.simpleName}: ${failure.message.orEmpty()}",
                        )
                    }
                }
            } finally {
                candidateJobs.forEach(Job::cancel)
            }
            bestFullCandidate?.let { candidate ->
                diagnosticLog("fetch candidate best-effort quality=${candidate.fullIpInfoQualityScore()}")
                return@coroutineScope candidate
            }
            throw lastFailure ?: error("ip info request failed")
        }

    data class EndpointFetchStrategy(
        val endpointCandidates: List<String>,
        val callTimeoutMs: Long?,
        val parallelCandidates: Boolean = false,
    )

    fun resolveFetchStrategy(
        endpoint: String,
        callTimeoutMs: Long?,
        mode: IpInfoFetchMode,
        torExit: Boolean = false,
    ): EndpointFetchStrategy =
        resolveIpInfoFetchStrategy(
            endpoint = endpoint,
            callTimeoutMs = callTimeoutMs,
            mode = mode,
            torExit = torExit,
        )

    private suspend fun fetchSingle(
        endpoint: String,
        callTimeoutMs: Long?,
        network: Network?,
        addressFamilyPreference: AddressFamilyPreference,
        proxy: HttpProxyAccess?,
        resolverNetwork: Network? = null,
        requireTorExitProof: Boolean = false,
    ): IpInfo {
        if (proxy?.type == ProxyAccessType.HTTP) {
            val response =
                executeHttpProxyTunnel(
                    endpoint = endpoint,
                    callTimeoutMs = callTimeoutMs,
                    proxy = proxy,
                )
            require(response.isSuccessful) { "ip info request failed: ${response.code}" }
            return parseIpInfoResponse(
                body = response.body,
                json = json,
                requireTorExitProof = requireTorExitProof,
            ).withLocalGeoCountry()
        }
        return execute(
            endpoint = endpoint,
            callTimeoutMs = callTimeoutMs,
            network = network,
            addressFamilyPreference = addressFamilyPreference,
            proxy = proxy,
            resolverNetwork = resolverNetwork,
        ).use { response ->
            require(response.isSuccessful) { "ip info request failed: ${response.code}" }
            parseIpInfoResponse(
                body = response.body.readIpInfoBodyCapped(),
                json = json,
                requireTorExitProof = requireTorExitProof,
            ).withLocalGeoCountry()
        }
    }

    private fun IpInfo.withLocalGeoCountry(): IpInfo {
        if (countryCode?.isNotBlank() == true) {
            return this
        }
        val resolve = localCountryCode ?: return this
        val resolvedCode =
            listOfNotNull(ipv4, ip, ipv6)
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .firstNotNullOfOrNull { candidate -> runCatching { resolve(candidate) }.getOrNull() }
                .toIsoCountryCodeOrNull()
                ?: return this
        diagnosticLog("country resolved from local geoip database code=$resolvedCode")
        return copy(
            countryCode = resolvedCode,
            countryName = countryName?.takeIf(String::isNotBlank) ?: countryDisplayName(resolvedCode),
        )
    }

    private suspend fun fetchFamily(
        endpoint: String,
        callTimeoutMs: Long?,
        network: Network?,
        addressFamilyPreference: AddressFamilyPreference,
        proxy: HttpProxyAccess?,
        resolverNetwork: Network? = null,
        torExit: Boolean = false,
    ): IpInfo? {
        withTorExitEndpoint(familyEndpoints(endpoint, addressFamilyPreference), torExit).forEach { candidate ->
            currentCoroutineContext().ensureActive()
            val result =
                runCatching {
                    withBoundedCallTimeout(callTimeoutMs) {
                        fetchSingle(candidate, callTimeoutMs, network, addressFamilyPreference, proxy, resolverNetwork)
                    }
                }
            val failure = result.exceptionOrNull()
            if (failure is CancellationException) {
                throw failure
            }
            val info =
                result
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

    private suspend fun <T> withBoundedCallTimeout(
        callTimeoutMs: Long?,
        block: suspend () -> T,
    ): T {
        val timeoutMs = callTimeoutMs?.coerceAtLeast(1L) ?: return block()
        return withTimeoutOrNull(timeoutMs) { block() } ?: throw InterruptedIOException("timeout")
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
        val url = endpoint.ensurePublicHttpsUrl()
        val request = Request.Builder().url(url).get().build()
        val effectiveClient =
            cachedHttpClient(
                HttpClientKey(
                    callTimeoutMs = callTimeoutMs,
                    networkHandle = network?.networkHandle,
                    resolverNetworkHandle = resolverNetwork?.networkHandle,
                    addressFamilyPreference = addressFamilyPreference,
                    proxy = proxy,
                ),
            ) {
                client.newBuilder().apply {
                    callTimeoutMs?.let { timeout -> callTimeout(timeout, TimeUnit.MILLISECONDS) }
                    applyProxyOrNetwork(proxy, network)
                    if (proxy == null) {
                        dns(
                            PublicRemoteDns(delegate = { hostname ->
                                val addresses = resolveAddresses(
                                    hostname,
                                    network,
                                    addressFamilyPreference,
                                    resolverNetwork
                                )
                                prioritize(addresses, addressFamilyPreference)
                            }),
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

    private fun OkHttpClient.Builder.applyProxyOrNetwork(
        proxyAccess: HttpProxyAccess?,
        network: Network?,
    ) {
        if (proxyAccess != null) {
            proxy(
                Proxy(
                    when (proxyAccess.type) {
                        ProxyAccessType.HTTP -> Proxy.Type.HTTP
                        ProxyAccessType.SOCKS -> Proxy.Type.SOCKS
                    },
                    InetSocketAddress(proxyAccess.host, proxyAccess.port),
                ),
            )
            if (
                proxyAccess.type == ProxyAccessType.HTTP &&
                !proxyAccess.username.isNullOrBlank() &&
                !proxyAccess.password.isNullOrBlank()
            ) {
                proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) {
                        null
                    } else {
                        response.request
                            .newBuilder()
                            .header(
                                "Proxy-Authorization",
                                Credentials.basic(proxyAccess.username, proxyAccess.password),
                            ).build()
                    }
                }
            }
        } else {
            network?.let {
                proxy(Proxy.NO_PROXY)
                socketFactory(it.socketFactory)
            }
        }
    }

    private inline fun cachedHttpClient(
        key: HttpClientKey,
        factory: () -> OkHttpClient,
    ): OkHttpClient {
        val now = System.nanoTime()
        synchronized(httpClientCacheLock) {
            pruneExpiredHttpClients(now)
            httpClientCache[key]?.takeIf { cached -> now - cached.createdAtNanos <= HTTP_CLIENT_CACHE_TTL_NANOS }?.let { cached ->
                return cached.client
            }
        }
        val built = factory()
        synchronized(httpClientCacheLock) {
            pruneExpiredHttpClients(now)
            httpClientCache[key] = CachedHttpClient(built, now)
            while (httpClientCache.size > HTTP_CLIENT_CACHE_MAX_SIZE) {
                val eldestKey = httpClientCache.entries.firstOrNull()?.key ?: break
                httpClientCache.remove(eldestKey)
            }
        }
        return built
    }

    private fun pruneExpiredHttpClients(nowNanos: Long) {
        val iterator = httpClientCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowNanos - entry.value.createdAtNanos > HTTP_CLIENT_CACHE_TTL_NANOS) {
                iterator.remove()
            }
        }
    }

    private suspend fun executeHttpProxyTunnel(
        endpoint: String,
        callTimeoutMs: Long?,
        proxy: HttpProxyAccess,
    ): HttpProxyTunnelResponse =
        withContext(Dispatchers.IO) {
            val url = endpoint.ensurePublicHttpsUrl()
            val target = HttpProxyTunnelTarget(url.host, url.port)
            val startedAt = System.nanoTime()
            val timeoutBudget =
                HttpProxyTunnelTimeoutBudget(
                    timeoutMs = callTimeoutMs ?: IP_INFO_FULL_CALL_TIMEOUT_MS,
                    startedAtNanos = startedAt,
                )
            Socket().use { rawSocket ->
                httpProxyTunnelStage("local_connect") {
                    rawSocket.connect(InetSocketAddress(proxy.host, proxy.port), timeoutBudget.remainingMs())
                }
                val rawInput =
                    DeadlineInputStream(rawSocket.getInputStream(), timeoutBudget) { timeout ->
                        rawSocket.soTimeout = timeout
                    }
                val output = rawSocket.getOutputStream()
                httpProxyTunnelStage("connect_write") {
                    output.write(proxyConnectRequest(target, proxy).toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                }
                val connectHead =
                    httpProxyTunnelStage("connect_response") {
                        readHttpResponseHead(rawInput)
                    }
                require(connectHead.code in 200..299) { "proxy CONNECT failed: ${connectHead.code}" }

                val sslSocket =
                    (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(rawSocket, url.host, url.port, true) as SSLSocket
                sslSocket.use { tlsSocket ->
                    httpProxyTunnelStage("tls_handshake") {
                        tlsSocket.soTimeout = timeoutBudget.remainingMs()
                        tlsSocket.startHandshake()
                    }
                    require(HttpsURLConnection.getDefaultHostnameVerifier().verify(url.host, tlsSocket.session)) {
                        "proxy tunnel TLS hostname verification failed"
                    }
                    val input =
                        DeadlineInputStream(tlsSocket.getInputStream(), timeoutBudget) { timeout ->
                            tlsSocket.soTimeout = timeout
                        }
                    val tlsOutput = tlsSocket.getOutputStream()
                    httpProxyTunnelStage("request_write") {
                        tlsOutput.write(
                            proxyTunnelGetRequest(
                                path = url.encodedPathWithQuery(),
                                target = target,
                            ).toByteArray(Charsets.ISO_8859_1),
                        )
                        tlsOutput.flush()
                    }
                    val responseHead =
                        httpProxyTunnelStage("response_head") {
                            readHttpResponseHead(input)
                        }
                    val body =
                        httpProxyTunnelStage("response_body") {
                            String(readHttpResponseBody(input, responseHead), Charsets.UTF_8)
                        }
                    HttpProxyTunnelResponse(
                        code = responseHead.code,
                        body = body,
                        elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L),
                    )
                }
            }
        }

    fun effectiveEndpointCandidates(
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

    private fun enrichWithAsnProviderIfNeeded(
        info: IpInfo,
        cache: MutableMap<String, Result<String?>>,
        mode: IpInfoFetchMode,
    ): IpInfo {
        if (mode == IpInfoFetchMode.ENTRY_QUICK || info.isp?.isNotBlank() == true || info.ip.isBlank()) {
            return info
        }
        val provider =
            cache
                .getOrPut(info.ip) {
                    runCatching { lookupAsnProvider(info.ip) }
                }.getOrNull()
                ?.takeIf(String::isNotBlank)
                ?: return info
        diagnosticLog("asn provider fallback resolved ip=${info.ip} provider=$provider")
        return info.copy(isp = provider)
    }

    private companion object {
        val SOCKS_AUTH_LOCK = Any()
        const val HTTP_CLIENT_CACHE_MAX_SIZE = 24
        val HTTP_CLIENT_CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(5)
    }
}
