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
import com.foxhole.beta.R
import com.foxhole.beta.core.anomaly.DnsRuntimeStats
import com.foxhole.beta.core.anomaly.isDnsRuntimeLogMessage
import com.foxhole.beta.core.diagnostics.DiagnosticSanitizer
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.VpnSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

internal fun createVpnRuntime(
    context: Context,
    diagnosticsLogger: DiagnosticsLogger,
    isNetworkActivityLoggingEnabled: () -> Boolean,
    networkActivityContext: () -> NetworkActivityContext = { NetworkActivityContext() },
): VpnCoreRuntime =
    ReflectiveLibboxRuntime(
        context = context,
        diagnosticsLogger = diagnosticsLogger,
        isNetworkActivityLoggingEnabled = isNetworkActivityLoggingEnabled,
        networkActivityContext = networkActivityContext,
    )

internal data class NetworkActivityContext(
    val profileId: Long? = null,
    val sessionId: String? = null,
    val trafficMode: TrafficMode? = null,
    val runtimeProxyPort: Int? = null,
)

private val libboxRuntimeOperationMutex = Mutex()

private data class LibboxRuntimeDependencies(
    val diagnosticsLogger: RuntimeDiagnosticsSink,
    val reflection: LibboxRuntimeNative,
    val defaultNetworkMonitor: RuntimeDefaultNetworkMonitor,
)

private fun buildLibboxRuntimeDependencies(
    context: Context,
    diagnosticsLogger: DiagnosticsLogger,
    isNetworkActivityLoggingEnabled: () -> Boolean,
    networkActivityContext: () -> NetworkActivityContext,
): LibboxRuntimeDependencies {
    val diagnosticsSink = DiagnosticsLoggerRuntimeDiagnosticsSink(diagnosticsLogger)
    val reflection =
        LibboxReflection(
            context = context,
            diagnosticsLogger = diagnosticsSink,
            isNetworkActivityLoggingEnabled = isNetworkActivityLoggingEnabled,
            networkActivityContext = networkActivityContext,
        )
    return LibboxRuntimeDependencies(
        diagnosticsLogger = diagnosticsSink,
        reflection = reflection,
        defaultNetworkMonitor = DefaultNetworkMonitor(context, reflection, diagnosticsSink),
    )
}

private fun sanitizedConfigFingerprint(configJson: String): String {
    val dnsLocal = "\"dns-local\""
    val dnsRemote = "\"dns-remote\""
    val sniff = "\"action\":\"sniff\""
    val hijackDns = "\"action\":\"hijack-dns\""
    val autoDetect = "\"auto_detect_interface\":true"
    val normalized = configJson.replace(Regex("\\s+"), "")
    return buildString {
        append("config dnsLocal=")
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

@Suppress("ReturnCount")
internal inline fun withRunningServerIfIdle(
    reason: String,
    operationMutex: Mutex,
    commandServerRef: AtomicReference<Any?>,
    diagnosticsLogger: RuntimeDiagnosticsSink,
    noinline beforeTryLock: (() -> Unit)? = null,
    block: (Any) -> Unit,
) {
    val server = commandServerRef.get() ?: return
    beforeTryLock?.invoke()
    if (!operationMutex.tryLock()) {
        diagnosticsLogger.recordStructured(
            "runtime",
            "native_callback_skipped_operation_busy",
            "reason=$reason",
        )
        return
    }
    try {
        val currentServer = commandServerRef.get() ?: return
        if (currentServer !== server) {
            diagnosticsLogger.recordStructured(
                "runtime",
                "native_callback_skipped_stale_server",
                "reason=$reason",
            )
            return
        }
        block(currentServer)
    } finally {
        operationMutex.unlock()
    }
}

@Suppress("LargeClass")
internal class ReflectiveLibboxRuntime(
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
    private val reflection: LibboxRuntimeNative,
    private val defaultNetworkMonitor: RuntimeDefaultNetworkMonitor,
    private val operationMutex: Mutex = libboxRuntimeOperationMutex,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) : VpnCoreRuntime {
    constructor(
        context: Context,
        diagnosticsLogger: DiagnosticsLogger,
        isNetworkActivityLoggingEnabled: () -> Boolean,
        networkActivityContext: () -> NetworkActivityContext,
    ) : this(
        buildLibboxRuntimeDependencies(
            context = context,
            diagnosticsLogger = diagnosticsLogger,
            isNetworkActivityLoggingEnabled = isNetworkActivityLoggingEnabled,
            networkActivityContext = networkActivityContext,
        ),
    )

    private constructor(dependencies: LibboxRuntimeDependencies) : this(
        diagnosticsLogger = dependencies.diagnosticsLogger,
        reflection = dependencies.reflection,
        defaultNetworkMonitor = dependencies.defaultNetworkMonitor,
    )

    private val commandServerRef = AtomicReference<Any?>(null)
    private val fileDescriptorRef = AtomicReference<ParcelFileDescriptor?>(null)
    private val runtimeGenerationGuard = RuntimeGenerationGuard(diagnosticsLogger)

    @Volatile
    private var currentConfig: String? = null

    @Volatile
    private var currentHost: RuntimeServiceHost? = null

    @Volatile
    private var currentDnsServerAddress: String? = null

    override suspend fun start(session: VpnSession, host: RuntimeServiceHost): Result<Unit> =
        operationMutex.withLock {
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
                            withRunningServerIfIdle("command_server_reload") { server ->
                                currentConfig?.let { config ->
                                    reflection.startOrReloadService(server, config)
                                }
                            }
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
                                    if (shouldRecordDnsRuntimeStats(it)) {
                                        DnsRuntimeStats.recordLogMessage(it)
                                    }
                                    recordLibboxDebugMessage(diagnosticsLogger, it)
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
        operationMutex.withLock {
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

    override suspend fun stop(policy: RuntimeStopPolicy): RuntimeStopResult {
        val startedAt = elapsedRealtime()
        nextRuntimeGeneration("stop")
        val preclosedTun =
            if (policy.closeTunFdImmediately) {
                closeTunFdNow()
            } else {
                null
            }
        return if (policy.forceKillAfterTimeout) {
            if (operationMutex.tryLock()) {
                try {
                    stopLocked(policy, preclosedTun)
                } finally {
                    operationMutex.unlock()
                }
            } else {
                diagnosticsLogger.recordStructured(
                    "runtime",
                    "stop_lock_busy",
                    "total_timeout_ms=${policy.totalGracefulTimeoutMs}",
                )
                val killResult =
                    killRuntimeState(
                        reason = "stop_lock_busy",
                        operationLockAcquired = false,
                        preclosedTun = preclosedTun,
                    )
                RuntimeStopResult(
                    closeServiceOk = false,
                    closeServerOk = false,
                    tunClosed = killResult.tunClosed,
                    escalatedToKill = true,
                    elapsedMs = elapsedRealtime() - startedAt,
                )
            }
        } else {
            operationMutex.withLock {
                stopLocked(policy, preclosedTun)
            }
        }
    }

    private suspend fun stopLocked(
        policy: RuntimeStopPolicy,
        preclosedTun: Boolean? = null,
    ): RuntimeStopResult =
        withContext(Dispatchers.IO) {
            val startedAt = elapsedRealtime()
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
                preclosedTun ?: if (policy.closeTunFdImmediately) {
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
            val elapsedMs = elapsedRealtime() - startedAt
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
        val preclosedTun = closeTunFdNow()
        if (operationMutex.tryLock()) {
            return try {
                killRuntimeState(
                    reason = reason,
                    operationLockAcquired = true,
                    preclosedTun = preclosedTun,
                )
            } finally {
                operationMutex.unlock()
            }
        }
        diagnosticsLogger.recordStructured(
            "runtime",
            "force_kill_lock_busy",
            "reason=$reason",
        )
        return killRuntimeState(
            reason = reason,
            operationLockAcquired = false,
            preclosedTun = preclosedTun,
        )
    }

    private suspend fun killLocked(reason: String): RuntimeKillResult =
        killRuntimeState(reason = reason, operationLockAcquired = true)

    private suspend fun killRuntimeState(
        reason: String,
        operationLockAcquired: Boolean,
        preclosedTun: Boolean? = null,
    ): RuntimeKillResult =
        withContext(Dispatchers.IO) {
            val server = commandServerRef.getAndSet(null)
            currentConfig = null
            currentHost = null
            currentDnsServerAddress = null
            val tunClosed = preclosedTun ?: closeTunFdNow()
            if (!operationLockAcquired) {
                runCatching { defaultNetworkMonitor.stop() }
                    .onFailure {
                        diagnosticsLogger.record("libbox", "default network monitor detached stop failed")
                    }
                diagnosticsLogger.recordStructured(
                    "runtime",
                    "runtime force kill detached native close skipped",
                    "reason=$reason",
                    "server_detached=${server != null}",
                    "tun_closed=$tunClosed",
                )
                return@withContext RuntimeKillResult(
                    reason = reason,
                    tunClosed = tunClosed,
                    serverDetached = server != null,
                    closeDetached = server != null,
                )
            }

            defaultNetworkMonitor.stop()
            var closeDetached = false
            if (server != null) {
                val serviceClosed =
                    closeNativeServerPart(
                        server = server,
                        label = "force_close_service",
                        timeoutMs = RuntimeNativeClosePolicy.FORCE_CLOSE_TIMEOUT_MS,
                    ) { target ->
                        reflection.closeService(target)
                    }
                val serverClosed =
                    closeNativeServerPart(
                        server = server,
                        label = "force_close_server",
                        timeoutMs = RuntimeNativeClosePolicy.FORCE_CLOSE_TIMEOUT_MS,
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
                "operation_lock_acquired=$operationLockAcquired",
            )
            RuntimeKillResult(
                reason = reason,
                tunClosed = tunClosed,
                serverDetached = server != null,
                closeDetached = closeDetached,
            )
        }

    private fun nextRuntimeGeneration(reason: String): Long =
        runtimeGenerationGuard.next(reason)

    private suspend fun ensureRuntimeGenerationCurrent(generation: Long) =
        runtimeGenerationGuard.ensureCurrent(generation)

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
        withRunningServerIfIdle("default_network_available") { server ->
            runCatching { reflection.resetNetwork(server) }
                .onFailure { diagnosticsLogger.record("libbox", "reset network failed") }
        }
    }

    override fun onDefaultNetworkLost() {
        defaultNetworkMonitor.dispatchListenerUpdate()
    }

    override fun currentDnsServerAddress(): String? = currentDnsServerAddress

    private fun openTun(host: RuntimeServiceHost, tunOptions: Any): Int {
        val generation = runtimeGenerationGuard.current()
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
                addRoutesApi33(builder, tunOptions, host.runtimeContext)
            } else {
                addRoutesLegacy(builder, tunOptions, host.runtimeContext)
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
        if (!runtimeGenerationGuard.isCurrent(generation)) {
            runCatching { pfd.close() }
                .onFailure { diagnosticsLogger.record("runtime", "superseded tun fd close failed") }
            throw CancellationException("runtime generation superseded during tun establish")
        }
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
        context: Context,
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

        val excludeRoutes = collectExcludeRoutes(tunOptions)
        when (routeExcludeCompatibility(Build.VERSION.SDK_INT, excludeRoutes.size)) {
            RouteExcludeCompatibility.SUPPORTED -> Unit
            RouteExcludeCompatibility.UNSUPPORTED_ANDROID_VERSION -> {
                diagnosticsLogger.recordStructured(
                    "split",
                    "VPN route split rejected",
                    "api=${Build.VERSION.SDK_INT}",
                    "include_routes=$includeRouteCount",
                    "exclude_routes=${excludeRoutes.size}",
                    "reason=exclude_route_android_version",
                )
                error(context.getString(R.string.error_route_excludes_android_version))
            }

            RouteExcludeCompatibility.EXCEEDS_ANDROID_LIMIT -> {
                diagnosticsLogger.recordStructured(
                    "split",
                    "VPN route split rejected",
                    "api=${Build.VERSION.SDK_INT}",
                    "include_routes=$includeRouteCount",
                    "exclude_routes=${excludeRoutes.size}",
                    "reason=exclude_route_limit",
                )
                error(
                    context.getString(
                        R.string.error_route_excludes_too_many,
                        excludeRoutes.size,
                        ANDROID_ROUTE_EXCLUDE_LIMIT,
                    ),
                )
            }
        }
        excludeRoutes.forEach { prefix ->
            builder.excludeRoute(IpPrefix(InetAddress.getByName(prefix.address), prefix.prefix))
        }
        recordRoutePlanDiagnostics(includeRouteCount, excludeRoutes.size, legacyMode = false)
    }

    private fun addRoutesLegacy(
        builder: VpnService.Builder,
        tunOptions: Any,
        context: Context,
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
        val excludeRoutes = collectExcludeRoutes(tunOptions)
        if (routeExcludeCompatibility(Build.VERSION.SDK_INT, excludeRoutes.size) != RouteExcludeCompatibility.SUPPORTED) {
            diagnosticsLogger.recordStructured(
                "split",
                "VPN route split rejected",
                "api=${Build.VERSION.SDK_INT}",
                "include_routes=$includeRouteCount",
                "exclude_routes=${excludeRoutes.size}",
                "reason=exclude_route_android_version",
            )
            error(context.getString(R.string.error_route_excludes_android_version))
        }
        recordRoutePlanDiagnostics(includeRouteCount, excludeRouteCount = 0, legacyMode = true)
    }

    private fun collectExcludeRoutes(tunOptions: Any): List<ReflectedRoutePrefix> =
        buildList {
            reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet4RouteExcludeAddress")) { prefix ->
                add(prefix)
            }
            reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet6RouteExcludeAddress")) { prefix ->
                add(prefix)
            }
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
                        "package_hash=${listOf(packageName).stablePackageHash()}",
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

    private inline fun withRunningServerIfIdle(
        reason: String,
        block: (Any) -> Unit,
    ) {
        withRunningServerIfIdle(
            reason = reason,
            operationMutex = operationMutex,
            commandServerRef = commandServerRef,
            diagnosticsLogger = diagnosticsLogger,
            block = block,
        )
    }

}

internal class DefaultNetworkMonitor(
    context: Context,
    private val reflection: LibboxRuntimeNative,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
) : RuntimeDefaultNetworkMonitor {
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

    override fun start() {
        if (started) {
            return
        }
        if (!register()) {
            return
        }
        started = true
        if (currentNetwork == null) {
            currentNetwork = preferredNetwork()
        }
        dispatchListenerUpdate()
    }

    override fun stop() {
        if (!started) {
            return
        }
        started = false
        listener = null
        currentNetwork = null
        runCatching { connectivity.unregisterNetworkCallback(callback) }
            .onFailure { diagnosticsLogger.record("libbox", "default network monitor unregister failed") }
    }

    override fun setListener(listener: Any?) {
        this.listener = listener
        dispatchListenerUpdate()
    }

    override fun requireNetwork(): Network {
        currentNetwork?.let { return it }
        return preferredNetwork() ?: error("android: missing default network")
    }

    override fun isCurrentNetworkMetered(): Boolean {
        val network = currentNetwork ?: preferredNetwork()
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
    }

    override fun bindSocketToDefaultNetwork(fd: Int) {
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
            if (BuildConfig.DEBUG) {
                diagnosticsLogger.recordThrottled(
                    tag = "libbox",
                    throttleKey = "default_network_socket_bind_success",
                    windowMs = 5_000L,
                    message = "default network socket bind ok",
                )
            }
        }.onFailure { error ->
            diagnosticsLogger.record(
                "libbox",
                "default network socket bind failed: ${error.javaClass.simpleName}",
            )
        }
    }

    @Suppress("ReturnCount")
    override fun dispatchListenerUpdate() {
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

    private fun preferredNetwork(): Network? =
        connectivity.preferredNonVpnInternetNetwork(ConnectivityNetworkRegistry.snapshot(appContext))

    private fun isUpstreamNetwork(network: Network): Boolean = isNonVpnNetwork(connectivity, network)

    private fun register(): Boolean =
        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> connectivity.registerBestMatchingNetworkCallback(request, callback, mainHandler)
                else -> connectivity.registerNetworkCallback(request, callback, mainHandler)
            }
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                diagnosticsLogger.record("libbox", "default network monitor registration failed: ${error.javaClass.simpleName}")
                runCatching {
                    connectivity.registerDefaultNetworkCallback(callback, mainHandler)
                }.fold(
                    onSuccess = { true },
                    onFailure = {
                        diagnosticsLogger.record("libbox", "default network monitor fallback failed")
                        false
                    },
                )
            }
        )
}

internal fun isNonVpnNetwork(
    connectivity: ConnectivityManager,
    network: Network,
): Boolean = connectivity.isNonVpnInternetNetwork(network)

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

private fun recordLibboxDebugMessage(
    diagnosticsLogger: RuntimeDiagnosticsSink,
    message: String,
) {
    if (!shouldDropRoutineLibboxVerboseMessage(message)) {
        val outputMessage = libboxDiagnosticOutputMessage(message)
        if (outputMessage != null) {
            logLibboxDebugMessage(outputMessage)
            diagnosticsLogger.record("libbox", outputMessage)
        }
    }
}

private fun libboxDiagnosticOutputMessage(message: String): String? {
    val throttleKey = libboxHighVolumeThrottleKey(message)
    var outputMessage: String? = message.takeIf { throttleKey == null }
    val summarize = shouldSummarizeLibboxDiagnostic(message)
    val windowMs =
        if (summarize) {
            LIBBOX_SUMMARIZED_DIAGNOSTICS_WINDOW_MS
        } else {
            LIBBOX_HIGH_VOLUME_DIAGNOSTICS_WINDOW_MS
        }
    if (throttleKey != null && shouldEmitLibboxDiagnostic(throttleKey.outputKey(summarize), windowMs)) {
        outputMessage =
            if (summarize) {
                "high-volume libbox logs suppressed category=${throttleKey.removePrefix("libbox:")}; raw trace omitted"
            } else {
                message
            }
    }
    return outputMessage
}

private fun String.outputKey(summarize: Boolean): String =
    if (summarize) {
        "summary:$this"
    } else {
        this
    }

private fun logLibboxDebugMessage(message: String) {
    if (BuildConfig.ENABLE_DIAGNOSTIC_LOGCAT) {
        Log.d("FoxholeLibbox", DiagnosticSanitizer.sanitizeForExport(message))
    }
}

private fun shouldEmitLibboxDiagnostic(
    key: String,
    windowMs: Long,
): Boolean {
    val now = SystemClock.elapsedRealtime()
    synchronized(libboxDiagnosticThrottleLock) {
        pruneLibboxDiagnosticThrottle(now)
        val previousAt = libboxDiagnosticThrottleAt[key]
        if (previousAt != null && now - previousAt < windowMs) {
            return false
        }
        libboxDiagnosticThrottleAt[key] = now
        return true
    }
}

private fun pruneLibboxDiagnosticThrottle(now: Long) {
    val cutoff = now - LIBBOX_DIAGNOSTIC_THROTTLE_TTL_MS
    val iterator = libboxDiagnosticThrottleAt.entries.iterator()
    while (iterator.hasNext()) {
        if (iterator.next().value < cutoff) {
            iterator.remove()
        }
    }
}

private fun shouldRecordDnsRuntimeStats(message: String): Boolean {
    return isDnsRuntimeLogMessage(message)
}

private fun shouldSummarizeLibboxDiagnostic(message: String): Boolean {
    val lower = message.lowercase(Locale.ROOT)
    return lower.startsWith("trace[") ||
        "outbound/tor[" in lower ||
        "connection_edge_process_relay_cell" in lower ||
        "sendme_circuit_data_received" in lower ||
        "channel_process_cell" in lower ||
        "connection_or_process_cells_from_inbuf" in lower ||
        "conn_read_callback" in lower ||
        "tor_tls_" in lower
}

private fun shouldDropRoutineLibboxVerboseMessage(message: String): Boolean {
    val lower = message.lowercase(Locale.ROOT)
    if (lower.containsAny(LIBBOX_HIGH_VOLUME_SEVERITY_TERMS)) {
        return false
    }
    return lower.startsWith("debug[") || lower.startsWith("trace[")
}

private fun libboxHighVolumeThrottleKey(message: String): String? {
    val lower = message.lowercase(Locale.ROOT)
    return lower
        .takeUnless { it.containsAny(LIBBOX_HIGH_VOLUME_SEVERITY_TERMS) }
        ?.let(::matchingLibboxHighVolumeThrottleKey)
}

private fun matchingLibboxHighVolumeThrottleKey(lowercaseMessage: String): String? =
    LIBBOX_HIGH_VOLUME_THROTTLE_RULES
        .firstOrNull { rule -> rule.matches(lowercaseMessage) }
        ?.key

private fun String.containsAny(tokens: Iterable<String>): Boolean =
    tokens.any { token -> token in this }

private data class LibboxHighVolumeThrottleRule(
    val key: String,
    val anyTerms: List<String> = emptyList(),
    val allTerms: List<String> = emptyList(),
    val startsWith: String? = null,
) {
    fun matches(message: String): Boolean {
        val matchesAnyTerm = anyTerms.isNotEmpty() && message.containsAny(anyTerms)
        val matchesAllTerms = allTerms.isNotEmpty() && allTerms.all { term -> term in message }
        val matchesPrefix = startsWith?.let(message::startsWith) == true
        return matchesAnyTerm || matchesAllTerms || matchesPrefix
    }
}

private val LIBBOX_HIGH_VOLUME_SEVERITY_TERMS = listOf("error", "warn", "panic")

private val LIBBOX_HIGH_VOLUME_THROTTLE_RULES =
    listOf(
        LibboxHighVolumeThrottleRule(
            key = "libbox:dns",
            anyTerms = listOf("dns: exchange", "dns: exchanged", "dns: cached"),
        ),
        LibboxHighVolumeThrottleRule(
            key = "libbox:inbound",
            anyTerms = listOf("inbound packet connection", "inbound connection"),
        ),
        LibboxHighVolumeThrottleRule(
            key = "libbox:router-identity",
            anyTerms = listOf("router: found package name", "router: found user id"),
        ),
        LibboxHighVolumeThrottleRule(
            key = "libbox:router-match",
            anyTerms = listOf("router: match", "router: sniffed"),
        ),
        LibboxHighVolumeThrottleRule(
            key = "libbox:outbound",
            allTerms = listOf("outbound/", "connection to"),
        ),
        LibboxHighVolumeThrottleRule(
            key = "libbox:connection-close",
            anyTerms = listOf("connection upload", "connection download"),
        ),
        LibboxHighVolumeThrottleRule(
            key = "libbox:trace",
            startsWith = "trace[",
        ),
    )

private fun Iterable<String>.stablePackageHash(): Int =
    map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
        .joinToString(separator = "|")
        .hashCode()

private val libboxDiagnosticThrottleLock = Any()
private val libboxDiagnosticThrottleAt = LinkedHashMap<String, Long>()

private const val LIBBOX_HIGH_VOLUME_DIAGNOSTICS_WINDOW_MS = 1_000L
private const val LIBBOX_SUMMARIZED_DIAGNOSTICS_WINDOW_MS = 10_000L
private const val LIBBOX_DIAGNOSTIC_THROTTLE_TTL_MS = 30 * 60 * 1000L
