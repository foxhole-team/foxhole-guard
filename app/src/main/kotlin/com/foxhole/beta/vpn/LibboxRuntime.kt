package com.foxhole.beta.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.anomaly.DnsRuntimeStats
import com.foxhole.beta.core.diagnostics.DiagnosticSanitizer
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.NetworkActivityEvent
import com.foxhole.beta.core.model.VpnSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal fun createVpnRuntime(
    context: Context,
    diagnosticsLogger: DiagnosticsLogger,
    isNetworkActivityLoggingEnabled: () -> Boolean,
    networkActivityContext: () -> NetworkActivityContext = { NetworkActivityContext() },
    onNetworkActivityEvent: (NetworkActivityEvent) -> Unit = {},
): VpnCoreRuntime =
    ReflectiveLibboxRuntime(
        context = context,
        diagnosticsLogger = diagnosticsLogger,
        isNetworkActivityLoggingEnabled = isNetworkActivityLoggingEnabled,
        networkActivityContext = networkActivityContext,
        onNetworkActivityEvent = onNetworkActivityEvent,
    )

internal data class NetworkActivityContext(
    val profileId: Long? = null,
    val sessionId: String? = null,
)

private const val ANDROID_ROUTE_EXCLUDE_LIMIT = 512
private const val RUNTIME_FORCE_CLOSE_TIMEOUT_MS = 700L
private val libboxRuntimeOperationMutex = Mutex()

private fun sanitizedConfigFingerprint(configJson: String): String {
    val dnsLocal = "\"dns-local\""
    val dnsRemote = "\"dns-remote\""
    val sniff = "\"action\":\"sniff\""
    val hijackDns = "\"action\":\"hijack-dns\""
    val autoDetect = "\"auto_detect_interface\":true"
    val normalized = configJson.replace(Regex("\\s+"), "")
    return buildString {
        append("config hash=")
        append(configJson.hashCode())
        append(" dnsLocal=")
        append(normalized.contains(dnsLocal))
        append(" dnsRemote=")
        append(normalized.contains(dnsRemote))
        append(" sniff=")
        append(normalized.contains(sniff))
        append(" hijackDns=")
        append(normalized.contains(hijackDns))
        append(" autoDetect=")
        append(normalized.contains(autoDetect))
    }
}

private class ReflectiveLibboxRuntime(
    private val context: Context,
    private val diagnosticsLogger: DiagnosticsLogger,
    isNetworkActivityLoggingEnabled: () -> Boolean,
    networkActivityContext: () -> NetworkActivityContext,
    onNetworkActivityEvent: (NetworkActivityEvent) -> Unit,
) : VpnCoreRuntime {
    private val reflection =
        LibboxReflection(
            context = context,
            diagnosticsLogger = diagnosticsLogger,
            isNetworkActivityLoggingEnabled = isNetworkActivityLoggingEnabled,
            networkActivityContext = networkActivityContext,
            onNetworkActivityEvent = onNetworkActivityEvent,
        )
    private val defaultNetworkMonitor by lazy { DefaultNetworkMonitor(context, reflection, diagnosticsLogger) }
    private val commandServerRef = AtomicReference<Any?>(null)
    private val fileDescriptorRef = AtomicReference<ParcelFileDescriptor?>(null)
    private val runtimeGeneration = AtomicLong(0L)

    @Volatile
    private var currentConfig: String? = null

    @Volatile
    private var currentHost: RuntimeServiceHost? = null

    @Volatile
    private var currentDnsServerAddress: String? = null

    override suspend fun start(session: VpnSession, host: RuntimeServiceHost): Result<Unit> =
        libboxRuntimeOperationMutex.withLock {
            startLocked(
                session = session,
                host = host,
                generation = nextRuntimeGeneration("start"),
            )
        }

    private suspend fun startLocked(
        session: VpnSession,
        host: RuntimeServiceHost,
        generation: Long,
    ): Result<Unit> {
        var newServer: Any? = null
        return try {
            if (!reflection.isAvailable()) {
                error(host.runtimeContext.getString(com.foxhole.beta.R.string.error_runtime_missing))
            }
            withContext(Dispatchers.IO) {
                stopLocked(
                    RuntimeStopPolicy(
                        closeServiceTimeoutMs = 500L,
                        closeServerTimeoutMs = 500L,
                        totalGracefulTimeoutMs = 1_000L,
                        forceKillAfterTimeout = true,
                    ),
                )
                ensureRuntimeGenerationCurrent(generation)
                reflection.setupIfNeeded()
                ensureRuntimeGenerationCurrent(generation)

                defaultNetworkMonitor.start()
                currentHost = host
                currentConfig = session.configJson
                diagnosticsLogger.record("runtime", sanitizedConfigFingerprint(session.configJson))
                val platform = reflection.platformProxy(host, defaultNetworkMonitor, ::openTun)
                val handler =
                    reflection.commandServerHandlerProxy(
                        onReload = {
                            val config = currentConfig ?: return@commandServerHandlerProxy
                            val server = commandServerRef.get() ?: return@commandServerHandlerProxy
                            reflection.startOrReloadService(server, config)
                        },
                        onStop = {
                            diagnosticsLogger.record("libbox", "service stop requested")
                            currentHost?.stopRuntimeService()
                        },
                        onDebug = { message ->
                            message
                                .trim()
                                .takeIf(String::isNotBlank)
                                ?.let {
                                    DnsRuntimeStats.recordLogMessage(it)
                                    diagnosticsLogger.record("libbox", it)
                                }
                        },
                    )
                newServer = reflection.newCommandServer(handler, platform)
                ensureRuntimeGenerationCurrent(generation)
                reflection.startServer(newServer)
                ensureRuntimeGenerationCurrent(generation)
                reflection.checkConfig(newServer, session.configJson)
                ensureRuntimeGenerationCurrent(generation)
                reflection.startOrReloadService(newServer, session.configJson)
                ensureRuntimeGenerationCurrent(generation)
                commandServerRef.set(newServer)
                newServer = null
                diagnosticsLogger.record("libbox", "runtime started")
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            cleanupFailedStart(newServer)
            diagnosticsLogger.record("runtime", "start cancelled: ${cancelled.message.orEmpty()}")
            throw cancelled
        } catch (error: Throwable) {
            cleanupFailedStart(newServer)
            val normalized = unwrapVpnRuntimeFailure(error)
            diagnosticsLogger.record("runtime", "start failed: ${describeVpnRuntimeFailure(normalized)}")
            logRuntimeFailure("libbox start failed", normalized)
            Result.failure(normalized)
        }
    }

    override suspend fun reload(session: VpnSession, host: RuntimeServiceHost): Result<Unit> =
        libboxRuntimeOperationMutex.withLock {
            reloadLocked(
                session = session,
                host = host,
                generation = nextRuntimeGeneration("reload"),
            )
        }

    private suspend fun reloadLocked(
        session: VpnSession,
        host: RuntimeServiceHost,
        generation: Long,
    ): Result<Unit> =
        try {
            if (!reflection.isAvailable()) {
                error(host.runtimeContext.getString(com.foxhole.beta.R.string.error_runtime_missing))
            }
            val server = commandServerRef.get() ?: error("android: runtime is not running")
            withContext(Dispatchers.IO) {
                currentHost = host
                currentConfig = session.configJson
                diagnosticsLogger.record("runtime", "reload ${sanitizedConfigFingerprint(session.configJson)}")
                reflection.checkConfig(server, session.configJson)
                ensureRuntimeGenerationCurrent(generation)
                reflection.startOrReloadService(server, session.configJson)
                ensureRuntimeGenerationCurrent(generation)
            }
            diagnosticsLogger.record("libbox", "runtime reloaded")
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            diagnosticsLogger.record("runtime", "reload cancelled: ${cancelled.message.orEmpty()}")
            throw cancelled
        } catch (error: Throwable) {
            val normalized = unwrapVpnRuntimeFailure(error)
            diagnosticsLogger.record("runtime", "reload failed: ${describeVpnRuntimeFailure(normalized)}")
            logRuntimeFailure("libbox reload failed", normalized)
            Result.failure(normalized)
        }

    override suspend fun stop(policy: RuntimeStopPolicy): RuntimeStopResult =
        libboxRuntimeOperationMutex.withLock {
            nextRuntimeGeneration("stop")
            stopLocked(policy)
        }

    private suspend fun stopLocked(policy: RuntimeStopPolicy): RuntimeStopResult =
        withContext(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            diagnosticsLogger.recordStructured(
                "runtime",
                "stop requested",
                "close_tun_first=${policy.closeTunFdImmediately}",
                "total_timeout_ms=${policy.totalGracefulTimeoutMs}",
            )
            val server = commandServerRef.getAndSet(null)
            currentConfig = null
            currentHost = null
            currentDnsServerAddress = null
            val tunClosed =
                if (policy.closeTunFdImmediately) {
                    closeTunFdNow()
                } else {
                    fileDescriptorRef.get() == null
                }
            defaultNetworkMonitor.stop()

            val closeServiceOk =
                if (server == null) {
                    true
                } else {
                    closeNativeServerPart(
                        server = server,
                        label = "close_service",
                        timeoutMs = policy.closeServiceTimeoutMs,
                    ) { target ->
                        reflection.closeService(target)
                    }
                }
            val closeServerOk =
                if (server == null) {
                    true
                } else {
                    closeNativeServerPart(
                        server = server,
                        label = "close_server",
                        timeoutMs = policy.closeServerTimeoutMs,
                    ) { target ->
                        reflection.closeServer(target)
                    }
                }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val shouldKill =
                policy.forceKillAfterTimeout &&
                    (!closeServiceOk || !closeServerOk || elapsedMs > policy.totalGracefulTimeoutMs)
            if (shouldKill) {
                diagnosticsLogger.recordStructured(
                    "runtime",
                    "force_kill_start",
                    "reason=stop_timeout",
                    "elapsed_ms=$elapsedMs",
                )
                killLocked("stop_timeout")
                diagnosticsLogger.record("runtime", "force_kill_end")
            }
            RuntimeStopResult(
                closeServiceOk = closeServiceOk,
                closeServerOk = closeServerOk,
                tunClosed = tunClosed,
                escalatedToKill = shouldKill,
                elapsedMs = elapsedMs,
            )
        }

    override suspend fun forceKill(reason: String): RuntimeKillResult {
        nextRuntimeGeneration("kill:$reason")
        return libboxRuntimeOperationMutex.withLock {
            killLocked(reason)
        }
    }

    private suspend fun killLocked(reason: String): RuntimeKillResult =
        withContext(Dispatchers.IO) {
            val server = commandServerRef.getAndSet(null)
            currentConfig = null
            currentHost = null
            currentDnsServerAddress = null
            defaultNetworkMonitor.stop()
            val tunClosed = closeTunFdNow()
            var closeDetached = false
            if (server != null) {
                val serviceClosed =
                    closeNativeServerPart(
                        server = server,
                        label = "force_close_service",
                        timeoutMs = RUNTIME_FORCE_CLOSE_TIMEOUT_MS,
                    ) { target ->
                        reflection.closeService(target)
                    }
                val serverClosed =
                    closeNativeServerPart(
                        server = server,
                        label = "force_close_server",
                        timeoutMs = RUNTIME_FORCE_CLOSE_TIMEOUT_MS,
                    ) { target ->
                        reflection.closeServer(target)
                    }
                closeDetached = !serviceClosed || !serverClosed
            }
            diagnosticsLogger.recordStructured(
                "runtime",
                "runtime force kill",
                "reason=$reason",
                "server_detached=${server != null}",
                "tun_closed=$tunClosed",
                "close_detached=$closeDetached",
            )
            RuntimeKillResult(
                reason = reason,
                tunClosed = tunClosed,
                serverDetached = server != null,
                closeDetached = closeDetached,
            )
        }

    private fun nextRuntimeGeneration(reason: String): Long =
        runtimeGeneration.incrementAndGet().also { generation ->
            diagnosticsLogger.recordStructured(
                "runtime",
                "runtime generation advanced",
                "reason=$reason",
                "generation=$generation",
            )
        }

    private suspend fun ensureRuntimeGenerationCurrent(generation: Long) {
        currentCoroutineContext().ensureActive()
        if (runtimeGeneration.get() != generation) {
            throw CancellationException("runtime generation superseded")
        }
    }

    private suspend fun closeNativeServerPart(
        server: Any,
        label: String,
        timeoutMs: Long,
        close: (Any) -> Unit,
    ): Boolean {
        diagnosticsLogger.record("runtime", "${label}_start")
        val closed = runBlockingRuntimeClose(timeoutMs) { close(server) }
        diagnosticsLogger.recordStructured(
            "runtime",
            if (closed) "${label}_end" else "${label}_timeout",
            "close_detached=${!closed}",
        )
        return closed
    }

    override fun nativeSnapshot(): NativeRuntimeSnapshot =
        NativeRuntimeSnapshot(
            hasCommandServer = commandServerRef.get() != null,
            hasTunFileDescriptor = fileDescriptorRef.get() != null,
            hasHost = currentHost != null,
            hasConfig = currentConfig != null,
            dnsServerAddress = currentDnsServerAddress,
        )

    override fun onDefaultNetworkAvailable() {
        defaultNetworkMonitor.dispatchListenerUpdate()
        commandServerRef.get()?.let {
            runCatching { reflection.resetNetwork(it) }
                .onFailure { diagnosticsLogger.record("libbox", "reset network failed") }
        }
    }

    override fun onDefaultNetworkLost() {
        defaultNetworkMonitor.dispatchListenerUpdate()
    }

    override fun currentDnsServerAddress(): String? = currentDnsServerAddress

    private fun openTun(host: RuntimeServiceHost, tunOptions: Any): Int {
        if (!host.hasVpnPermission()) {
            error("android: missing vpn permission")
        }

        val builder =
            requireNotNull(host.createTunBuilder()) { "android: tun requested without vpn host" }
                .setSession("foxhole")
                .setMtu(reflection.callInt(tunOptions, "getMTU"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(defaultNetworkMonitor.isCurrentNetworkMetered())
        }
        runCatching {
            builder.setUnderlyingNetworks(arrayOf(defaultNetworkMonitor.requireNetwork()))
        }.onFailure {
            diagnosticsLogger.record("libbox", "vpn underlying network unavailable before establish")
        }

        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet4Address")) { prefix ->
            builder.addAddress(prefix.address, prefix.prefix)
        }
        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet6Address")) { prefix ->
            builder.addAddress(prefix.address, prefix.prefix)
        }

        if (reflection.callBoolean(tunOptions, "getAutoRoute")) {
            val fallbackDnsServerAddresses =
                reflection.collectStringBoxOrIterator(
                    reflection.call(tunOptions, "getDNSServerAddress"),
                )
            val fallbackDnsServerAddress = fallbackDnsServerAddresses.firstOrNull()
            val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(currentConfig)
            val advertisedDnsServers =
                VpnDnsServerSelector.advertisedDnsServerAddresses(
                    configJson = currentConfig,
                    fallbackServerAddress = fallbackDnsServerAddress,
                )
            currentDnsServerAddress = advertisedDnsServers.firstOrNull()
            diagnosticsLogger.recordStructured(
                "dns",
                "VPN DNS selection",
                "fallback=${fallbackDnsServerAddresses.joinToString()}",
                "advertised=${advertisedDnsServers.joinToString()}",
                "remote=${remoteDnsServers.joinToString()}",
            )
            if (advertisedDnsServers.isEmpty()) {
                error("android: vpn dns server unavailable")
            }
            advertisedDnsServers.forEach(builder::addDnsServer)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                addRoutesApi33(builder, tunOptions)
            } else {
                addRoutesLegacy(builder, tunOptions)
            }

            val includePackages = reflection.collectStrings(reflection.call(tunOptions, "getIncludePackage"))
            if (includePackages.isNotEmpty()) {
                recordPackageSplitDiagnostics(
                    mode = "include",
                    includePackages = includePackages,
                    excludePackages = emptyList(),
                )
                addPackages(
                    packages = includePackages,
                    onPackage = { builder.addAllowedApplication(it) },
                )
            } else {
                val excludePackages = reflection.collectStrings(reflection.call(tunOptions, "getExcludePackage"))
                recordPackageSplitDiagnostics(
                    mode = if (excludePackages.isEmpty()) "full" else "exclude",
                    includePackages = emptyList(),
                    excludePackages = excludePackages,
                )
                addPackages(
                    packages = excludePackages,
                    onPackage = { builder.addDisallowedApplication(it) },
                )
            }
        }

        addHttpProxy(builder, tunOptions)

        val pfd = builder.establish() ?: error("android: vpn establish failed")
        runCatching { fileDescriptorRef.getAndSet(pfd)?.close() }
            .onFailure { diagnosticsLogger.record("runtime", "previous tun fd close failed") }
        return pfd.fd
    }

    private fun addHttpProxy(
        builder: VpnService.Builder,
        tunOptions: Any,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val enabled =
            runCatching { reflection.callBoolean(tunOptions, "isHTTPProxyEnabled") }
                .getOrElse { return }
        if (!enabled) {
            return
        }
        val server =
            runCatching { reflection.call(tunOptions, "getHTTPProxyServer")?.toString()?.trim() }
                .getOrNull()
                ?.takeIf(String::isNotBlank)
        val port =
            runCatching { reflection.callInt(tunOptions, "getHTTPProxyServerPort") }
                .getOrDefault(0)
        if (server == null || port !in 1..65535) {
            diagnosticsLogger.record("libbox", "vpn http proxy skipped: invalid endpoint")
            return
        }
        val bypassDomains =
            runCatching { reflection.collectStrings(reflection.call(tunOptions, "getHTTPProxyBypassDomain")) }
                .getOrDefault(emptyList())
        builder.setHttpProxy(ProxyInfo.buildDirectProxy(server, port, bypassDomains))
        diagnosticsLogger.recordStructured(
            "libbox",
            "VPN HTTP proxy configured",
            "server=${DiagnosticSanitizer.sanitize(server)}",
            "port=$port",
            "bypass=${bypassDomains.size}",
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun addRoutesApi33(
        builder: VpnService.Builder,
        tunOptions: Any,
    ) {
        var hasIpv4Route = false
        var includeRouteCount = 0
        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet4RouteAddress")) { prefix ->
            hasIpv4Route = true
            includeRouteCount += 1
            builder.addRoute(IpPrefix(InetAddress.getByName(prefix.address), prefix.prefix))
        }
        if (!hasIpv4Route) {
            reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet4Address")) {
                hasIpv4Route = true
            }
            if (hasIpv4Route) {
                includeRouteCount += 1
                builder.addRoute("0.0.0.0", 0)
            }
        }

        var hasIpv6Route = false
        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet6RouteAddress")) { prefix ->
            hasIpv6Route = true
            includeRouteCount += 1
            builder.addRoute(IpPrefix(InetAddress.getByName(prefix.address), prefix.prefix))
        }
        if (!hasIpv6Route) {
            reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet6Address")) {
                hasIpv6Route = true
            }
            if (hasIpv6Route) {
                includeRouteCount += 1
                builder.addRoute("::", 0)
            }
        }

        val excludeRoutes = mutableListOf<ReflectedRoutePrefix>()
        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet4RouteExcludeAddress")) { prefix ->
            excludeRoutes += prefix
        }
        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet6RouteExcludeAddress")) { prefix ->
            excludeRoutes += prefix
        }
        val acceptedExcludeRoutes =
            if (excludeRoutes.size <= ANDROID_ROUTE_EXCLUDE_LIMIT) {
                excludeRoutes
            } else {
                diagnosticsLogger.recordStructured(
                    "split",
                    "VPN route split rejected",
                    "api=${Build.VERSION.SDK_INT}",
                    "include_routes=$includeRouteCount",
                    "exclude_routes=${excludeRoutes.size}",
                    "reason=exclude_route_limit",
                )
                emptyList()
            }
        acceptedExcludeRoutes.forEach { prefix ->
            builder.excludeRoute(IpPrefix(InetAddress.getByName(prefix.address), prefix.prefix))
        }
        recordRoutePlanDiagnostics(includeRouteCount, acceptedExcludeRoutes.size, legacyMode = false)
    }

    private fun addRoutesLegacy(
        builder: VpnService.Builder,
        tunOptions: Any,
    ) {
        var includeRouteCount = 0
        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet4RouteRange")) { prefix ->
            includeRouteCount += 1
            builder.addRoute(prefix.address, prefix.prefix)
        }
        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet6RouteRange")) { prefix ->
            includeRouteCount += 1
            builder.addRoute(prefix.address, prefix.prefix)
        }
        recordRoutePlanDiagnostics(includeRouteCount, excludeRouteCount = 0, legacyMode = true)
    }

    private fun addPackages(
        packages: Iterable<String>,
        onPackage: (String) -> Unit,
    ) {
        packages.forEach { packageName ->
            runCatching { onPackage(packageName) }
                .onFailure {
                    if (it !is PackageManager.NameNotFoundException) {
                        throw it
                    }
                    diagnosticsLogger.recordStructured(
                        "split",
                        "VPN package skipped",
                        "package=$packageName",
                        "reason=not_installed",
                    )
                }
        }
    }

    private fun recordPackageSplitDiagnostics(
        mode: String,
        includePackages: List<String>,
        excludePackages: List<String>,
    ) {
        diagnosticsLogger.recordStructured(
            "split",
            "VPN app split applied",
            "mode=$mode",
            "include_count=${includePackages.size}",
            "exclude_count=${excludePackages.size}",
            "include_hash=${includePackages.stablePackageHash()}",
            "exclude_hash=${excludePackages.stablePackageHash()}",
        )
    }

    private fun recordRoutePlanDiagnostics(
        includeRouteCount: Int,
        excludeRouteCount: Int,
        legacyMode: Boolean,
    ) {
        diagnosticsLogger.recordStructured(
            "split",
            "VPN route split applied",
            "api=${Build.VERSION.SDK_INT}",
            "include_routes=$includeRouteCount",
            "exclude_routes=$excludeRouteCount",
            "legacy_route_mode=$legacyMode",
        )
    }

    private suspend fun cleanupFailedStart(newServer: Any?) {
        runCatching {
            newServer?.let { server ->
                runBlockingRuntimeClose(500L) { reflection.closeService(server) }
                runBlockingRuntimeClose(500L) { reflection.closeServer(server) }
            }
        }.onFailure {
            diagnosticsLogger.record("libbox", "failed-start server cleanup failed")
        }
        closeTunFdNow()
        currentConfig = null
        currentHost = null
        currentDnsServerAddress = null
        defaultNetworkMonitor.stop()
    }

    private fun closeTunFdNow(): Boolean {
        val descriptor = fileDescriptorRef.getAndSet(null) ?: return true
        return runCatching {
            descriptor.close()
            diagnosticsLogger.record("runtime", "close_fd")
            true
        }.getOrElse {
            diagnosticsLogger.record("runtime", "close_fd_failed")
            false
        }
    }

}

internal class DefaultNetworkMonitor(
    context: Context,
    private val reflection: LibboxReflection,
    private val diagnosticsLogger: DiagnosticsLogger,
) {
    private val appContext = context.applicationContext
    private val connectivity = context.getSystemService<ConnectivityManager>() ?: error("missing connectivity manager")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val request =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    @Volatile
    private var currentNetwork: Network? = null
    @Volatile
    private var listener: Any? = null
    @Volatile
    private var started = false

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isUpstreamNetwork(network)) {
                    return
                }
                currentNetwork = network
                dispatchListenerUpdate()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                if (currentNetwork == network) {
                    currentNetwork = network.takeIf { isUpstreamNetwork(it) } ?: preferredNetwork()
                    dispatchListenerUpdate()
                } else if (currentNetwork == null && isUpstreamNetwork(network)) {
                    currentNetwork = network
                    dispatchListenerUpdate()
                }
            }

            override fun onLost(network: Network) {
                if (currentNetwork == network) {
                    currentNetwork = preferredNetwork()
                    dispatchListenerUpdate()
                }
            }
        }

    fun start() {
        if (started) {
            return
        }
        started = true
        register()
        if (currentNetwork == null) {
            currentNetwork = preferredNetwork()
        }
        dispatchListenerUpdate()
    }

    fun stop() {
        if (!started) {
            return
        }
        started = false
        listener = null
        currentNetwork = null
        runCatching { connectivity.unregisterNetworkCallback(callback) }
            .onFailure { diagnosticsLogger.record("libbox", "default network monitor unregister failed") }
    }

    fun setListener(listener: Any?) {
        this.listener = listener
        dispatchListenerUpdate()
    }

    fun requireNetwork(): Network {
        currentNetwork?.let { return it }
        return preferredNetwork() ?: error("android: missing default network")
    }

    fun isCurrentNetworkMetered(): Boolean {
        val network = currentNetwork ?: preferredNetwork()
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
    }

    fun bindSocketToDefaultNetwork(fd: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        val network =
            runCatching { requireNetwork() }
                .getOrElse {
                    diagnosticsLogger.record("libbox", "default network unavailable for socket bind")
                    return
                }
        runCatching {
            ParcelFileDescriptor.fromFd(fd).use { parcel ->
                network.bindSocket(parcel.fileDescriptor)
            }
        }.onFailure { error ->
            diagnosticsLogger.record(
                "libbox",
                "default network socket bind failed: ${error.javaClass.simpleName}",
            )
        }
    }

    fun dispatchListenerUpdate() {
        val listener = listener ?: return
        val network = currentNetwork ?: preferredNetwork()
        if (network == null) {
            runCatching {
                reflection.call(
                    listener,
                    "updateDefaultInterface",
                    "",
                    -1,
                    false,
                    false,
                )
            }.onFailure {
                diagnosticsLogger.record("libbox", "default interface callback failed")
            }
            return
        }
        val linkProperties = connectivity.getLinkProperties(network) ?: return
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return
        val interfaceName = linkProperties.interfaceName.orEmpty()
        val interfaceIndex =
            runCatching { NetworkInterface.getByName(interfaceName)?.index }
                .getOrNull()
        if (interfaceIndex == null) {
            diagnosticsLogger.record("libbox", "default interface unavailable: $interfaceName")
            return
        }
        val isConstrained =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED)
            } else {
                false
            }
        runCatching {
            reflection.call(
                    listener,
                    "updateDefaultInterface",
                    interfaceName,
                    interfaceIndex,
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    isConstrained,
                )
        }.onFailure {
            diagnosticsLogger.record("libbox", "default interface callback failed")
        }
    }

    private fun preferredNetwork(): Network? {
        connectivity.activeNetwork?.takeIf(::isUpstreamNetwork)?.let { return it }
        return ConnectivityNetworkRegistry.snapshot(appContext).firstOrNull(::isUpstreamNetwork)
    }

    private fun isUpstreamNetwork(network: Network): Boolean = isNonVpnNetwork(connectivity, network)

    private fun register() {
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> connectivity.registerBestMatchingNetworkCallback(request, callback, mainHandler)
                else -> connectivity.registerNetworkCallback(request, callback, mainHandler)
            }
        }.onFailure { error ->
            diagnosticsLogger.record("libbox", "default network monitor registration failed: ${error.javaClass.simpleName}")
            runCatching {
                connectivity.registerDefaultNetworkCallback(callback, mainHandler)
            }.onFailure {
                diagnosticsLogger.record("libbox", "default network monitor fallback failed")
            }
        }
    }
}

internal fun isNonVpnNetwork(
    connectivity: ConnectivityManager,
    network: Network,
): Boolean {
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

private fun logRuntimeFailure(
    message: String,
    error: Throwable,
) {
    if (BuildConfig.DEBUG) {
        Log.e("FoxholeLibbox", message, error)
    } else {
        Log.e("FoxholeLibbox", DiagnosticSanitizer.sanitizeForExport("$message error=${error.javaClass.simpleName}"))
    }
}

private fun Iterable<String>.stablePackageHash(): Int =
    map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
        .joinToString(separator = "|")
        .hashCode()
