package com.foxhole.beta.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.VpnSession
import java.net.InetAddress

internal fun createVpnRuntime(
    context: Context,
    diagnosticsLogger: DiagnosticsLogger,
    isNetworkActivityLoggingEnabled: () -> Boolean,
): VpnCoreRuntime = ReflectiveLibboxRuntime(context, diagnosticsLogger, isNetworkActivityLoggingEnabled)

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
) : VpnCoreRuntime {
    private val reflection = LibboxReflection(context, diagnosticsLogger, isNetworkActivityLoggingEnabled)
    private val defaultNetworkMonitor by lazy { DefaultNetworkMonitor(context, reflection, diagnosticsLogger) }
    private var commandServer: Any? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var currentConfig: String? = null
    private var currentHost: RuntimeServiceHost? = null
    private var currentDnsServerAddress: String? = null

    override suspend fun start(session: VpnSession, host: RuntimeServiceHost): Result<Unit> =
        try {
            if (!reflection.isAvailable()) {
                error(host.runtimeContext.getString(com.foxhole.beta.R.string.error_runtime_missing))
            }
            stop()
            reflection.setupIfNeeded()
            defaultNetworkMonitor.start()
            currentHost = host
            currentConfig = session.configJson
            diagnosticsLogger.record("runtime", sanitizedConfigFingerprint(session.configJson))
            val platform = reflection.platformProxy(host, defaultNetworkMonitor, ::openTun)
            val handler =
                reflection.commandServerHandlerProxy(
                    onReload = {
                        val config = currentConfig ?: return@commandServerHandlerProxy
                        val server = commandServer ?: return@commandServerHandlerProxy
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
                            ?.let { diagnosticsLogger.record("libbox", it) }
                    },
                )
            val server = reflection.newCommandServer(handler, platform)
            reflection.startServer(server)
            reflection.checkConfig(server, session.configJson)
            reflection.startOrReloadService(server, session.configJson)
            commandServer = server
            diagnosticsLogger.record("libbox", "runtime started")
            Result.success(Unit)
        } catch (error: Throwable) {
            val normalized = unwrapVpnRuntimeFailure(error)
            diagnosticsLogger.record("runtime", "start failed: ${describeVpnRuntimeFailure(normalized)}")
            logRuntimeFailure("libbox start failed", normalized)
            Result.failure(normalized)
        }

    override suspend fun reload(session: VpnSession, host: RuntimeServiceHost): Result<Unit> =
        try {
            if (!reflection.isAvailable()) {
                error(host.runtimeContext.getString(com.foxhole.beta.R.string.error_runtime_missing))
            }
            val server = commandServer ?: error("android: runtime is not running")
            currentHost = host
            currentConfig = session.configJson
            diagnosticsLogger.record("runtime", "reload ${sanitizedConfigFingerprint(session.configJson)}")
            reflection.checkConfig(server, session.configJson)
            reflection.startOrReloadService(server, session.configJson)
            diagnosticsLogger.record("libbox", "runtime reloaded")
            Result.success(Unit)
        } catch (error: Throwable) {
            val normalized = unwrapVpnRuntimeFailure(error)
            diagnosticsLogger.record("runtime", "reload failed: ${describeVpnRuntimeFailure(normalized)}")
            logRuntimeFailure("libbox reload failed", normalized)
            Result.failure(normalized)
        }

    override suspend fun stop() {
        val server = commandServer
        commandServer = null
        currentConfig = null
        currentHost = null
        currentDnsServerAddress = null
        defaultNetworkMonitor.stop()
        runCatching {
            if (server != null) {
                reflection.closeService(server)
                reflection.closeServer(server)
            }
        }.onFailure {
            diagnosticsLogger.record("libbox", "close failed")
        }
        runCatching {
            fileDescriptor?.close()
        }
        fileDescriptor = null
    }

    override fun onDefaultNetworkAvailable() {
        defaultNetworkMonitor.dispatchListenerUpdate()
        commandServer?.let {
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
            builder.setMetered(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                builder.setUnderlyingNetworks(arrayOf(defaultNetworkMonitor.requireNetwork()))
            }.onFailure {
                diagnosticsLogger.record("libbox", "vpn underlying network unavailable before establish")
            }
        }

        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet4Address")) { prefix ->
            builder.addAddress(prefix.address, prefix.prefix)
        }
        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet6Address")) { prefix ->
            builder.addAddress(prefix.address, prefix.prefix)
        }

        if (reflection.callBoolean(tunOptions, "getAutoRoute")) {
            val fallbackDnsServerAddress =
                reflection.call(reflection.call(tunOptions, "getDNSServerAddress"), "getValue")
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
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
                "fallback=${fallbackDnsServerAddress.orEmpty()}",
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
                addPackages(
                    packages = includePackages,
                    onPackage = { builder.addAllowedApplication(it) },
                )
            } else {
                val excludePackages = reflection.collectStrings(reflection.call(tunOptions, "getExcludePackage"))
                addPackages(
                    packages = excludePackages,
                    onPackage = { builder.addDisallowedApplication(it) },
                )
            }
        }

        val pfd = builder.establish() ?: error("android: vpn establish failed")
        fileDescriptor?.close()
        fileDescriptor = pfd
        return pfd.fd
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun addRoutesApi33(
        builder: VpnService.Builder,
        tunOptions: Any,
    ) {
        var hasIpv4Route = false
        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet4RouteAddress")) { prefix ->
            hasIpv4Route = true
            builder.addRoute(IpPrefix(InetAddress.getByName(prefix.address), prefix.prefix))
        }
        if (!hasIpv4Route) {
            reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet4Address")) {
                hasIpv4Route = true
            }
            if (hasIpv4Route) {
                builder.addRoute("0.0.0.0", 0)
            }
        }

        var hasIpv6Route = false
        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet6RouteAddress")) { prefix ->
            hasIpv6Route = true
            builder.addRoute(IpPrefix(InetAddress.getByName(prefix.address), prefix.prefix))
        }
        if (!hasIpv6Route) {
            reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet6Address")) {
                hasIpv6Route = true
            }
            if (hasIpv6Route) {
                builder.addRoute("::", 0)
            }
        }

        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet4RouteExcludeAddress")) { prefix ->
            builder.excludeRoute(IpPrefix(InetAddress.getByName(prefix.address), prefix.prefix))
        }
        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet6RouteExcludeAddress")) { prefix ->
            builder.excludeRoute(IpPrefix(InetAddress.getByName(prefix.address), prefix.prefix))
        }
    }

    private fun addRoutesLegacy(
        builder: VpnService.Builder,
        tunOptions: Any,
    ) {
        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet4RouteRange")) { prefix ->
            builder.addRoute(prefix.address, prefix.prefix)
        }
        reflection.forEachRoutePrefix(reflection.call(tunOptions, "getInet6RouteRange")) { prefix ->
            builder.addRoute(prefix.address, prefix.prefix)
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
                }
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

    fun dispatchListenerUpdate() {
        val listener = listener ?: return
        val network = currentNetwork ?: preferredNetwork()
        if (network == null) {
            runCatching {
                reflection.call(
                    listener,
                    "updateDefaultInterface",
                    "",
                    reflection.interfaceTypeOther,
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
        val type =
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> reflection.interfaceTypeWifi
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> reflection.interfaceTypeCellular
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> reflection.interfaceTypeEthernet
                else -> reflection.interfaceTypeOther
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
                linkProperties.interfaceName.orEmpty(),
                type,
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
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> connectivity.requestNetwork(request, callback, mainHandler)
                else -> connectivity.registerDefaultNetworkCallback(callback, mainHandler)
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
    if (BuildConfig.DEBUG || BuildConfig.ENABLE_DIAGNOSTIC_LOGCAT) {
        Log.e("FoxholeLibbox", message, error)
    } else {
        Log.e("FoxholeLibbox", "$message error=${error.javaClass.simpleName}")
    }
}
