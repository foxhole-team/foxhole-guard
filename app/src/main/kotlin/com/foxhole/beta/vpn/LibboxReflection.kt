package com.foxhole.beta.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Base64
import android.util.Log
import androidx.core.content.getSystemService
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.UnknownHostException
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal class LibboxReflection(
    private val context: Context,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val isNetworkActivityLoggingEnabled: () -> Boolean,
) {
    private val setupDone = AtomicBoolean(false)
    private val appContext = context.applicationContext
    private val connectivityManager by lazy { appContext.getSystemService<ConnectivityManager>()!! }
    private val packageManager by lazy { appContext.packageManager }

    private val libboxClass by lazy { classOrNull("io.nekohasekai.libbox.Libbox") }
    private val setupOptionsClass by lazy { requireClass("io.nekohasekai.libbox.SetupOptions") }
    private val commandServerHandlerClass by lazy { requireClass("io.nekohasekai.libbox.CommandServerHandler") }
    private val platformInterfaceClass by lazy { requireClass("io.nekohasekai.libbox.PlatformInterface") }
    private val overrideOptionsClass by lazy { requireClass("io.nekohasekai.libbox.OverrideOptions") }
    private val systemProxyStatusClass by lazy { requireClass("io.nekohasekai.libbox.SystemProxyStatus") }
    private val connectionOwnerClass by lazy { requireClass("io.nekohasekai.libbox.ConnectionOwner") }
    private val networkInterfaceClass by lazy { requireClass("io.nekohasekai.libbox.NetworkInterface") }
    private val networkInterfaceIteratorClass by lazy { requireClass("io.nekohasekai.libbox.NetworkInterfaceIterator") }
    private val stringIteratorClass by lazy { requireClass("io.nekohasekai.libbox.StringIterator") }
    private val localDnsTransportClass by lazy { requireClass("io.nekohasekai.libbox.LocalDNSTransport") }
    private val interfaceUpdateListenerClass by lazy { requireClass("io.nekohasekai.libbox.InterfaceUpdateListener") }
    private val funcClass by lazy { requireClass("io.nekohasekai.libbox.Func") }
    private val wifiStateClass by lazy { requireClass("io.nekohasekai.libbox.WIFIState") }
    private val routePrefixClass by lazy { requireClass("io.nekohasekai.libbox.RoutePrefix") }

    val interfaceTypeWifi: Int by lazy { staticField("InterfaceTypeWIFI") as Int }
    val interfaceTypeCellular: Int by lazy { staticField("InterfaceTypeCellular") as Int }
    val interfaceTypeEthernet: Int by lazy { staticField("InterfaceTypeEthernet") as Int }
    val interfaceTypeOther: Int by lazy { staticField("InterfaceTypeOther") as Int }

    fun isAvailable(): Boolean = libboxClass != null

    fun setupIfNeeded() {
        if (setupDone.get() || !isAvailable()) {
            return
        }
        synchronized(this) {
            if (setupDone.get()) {
                return
            }
            val baseDir = File(appContext.filesDir, "libbox").apply { mkdirs() }
            val workingDir = File(baseDir, "working").apply { mkdirs() }
            val tempDir = File(appContext.cacheDir, "libbox").apply { mkdirs() }
            val options = setupOptionsClass.getDeclaredConstructor().newInstance()
            call(options, "setBasePath", baseDir.absolutePath)
            call(options, "setWorkingPath", workingDir.absolutePath)
            call(options, "setTempPath", tempDir.absolutePath)
            call(options, "setFixAndroidStack", true)
            call(options, "setCommandServerListenPort", 0)
            call(options, "setDebug", BuildConfig.DEBUG)
            call(options, "setLogMaxLines", LIBBOX_LOG_MAX_LINES)
            callStatic("setLocale", Locale.getDefault().toLanguageTag().replace("-", "_"))
            callStatic("setup", options)
            setupDone.set(true)
        }
    }

    fun commandServerHandlerProxy(
        onReload: () -> Unit,
        onStop: () -> Unit,
        onDebug: (String) -> Unit,
    ): Any =
        Proxy.newProxyInstance(
            commandServerHandlerClass.classLoader,
            arrayOf(commandServerHandlerClass),
        ) { _, method, args ->
            when (method.name) {
                "getSystemProxyStatus" -> systemProxyStatus(false, false)
                "serviceReload" -> onReload()
                "serviceStop" -> onStop()
                "setSystemProxyEnabled" -> Unit
                "writeDebugMessage" -> onDebug(args?.get(0)?.toString().orEmpty())
                else -> defaultValue(method)
            }
        }

    fun platformProxy(
        host: RuntimeServiceHost,
        defaultNetworkMonitor: DefaultNetworkMonitor,
        openTun: (RuntimeServiceHost, Any) -> Int,
    ): Any =
        Proxy.newProxyInstance(
            platformInterfaceClass.classLoader,
            arrayOf(platformInterfaceClass),
        ) { _, method, args ->
            when (method.name) {
                "autoDetectInterfaceControl" -> {
                    host.protectSocket(args?.firstOrNull() as Int)
                    Unit
                }
                "clearDNSCache" -> Unit
                "closeDefaultInterfaceMonitor" -> {
                    defaultNetworkMonitor.setListener(null)
                    Unit
                }
                "findConnectionOwner" ->
                    connectionOwner(
                        protocol = args?.getOrNull(0) as? Int ?: 0,
                        sourceHost = args?.getOrNull(1)?.toString().orEmpty(),
                        sourcePort = args?.getOrNull(2) as? Int ?: 0,
                        destinationHost = args?.getOrNull(3)?.toString().orEmpty(),
                        destinationPort = args?.getOrNull(4) as? Int ?: 0,
                    )
                "getInterfaces" -> getInterfaces(host.runtimeContext)
                "includeAllNetworks" -> false
                "localDNSTransport" -> {
                    diagnosticsLogger.record("dns", "platform requested local dns transport")
                    if (BuildConfig.DEBUG) {
                        Log.d(LOG_TAG, "platform requested localDNSTransport")
                    }
                    localDnsTransport(defaultNetworkMonitor)
                }
                "openTun" -> openTun(host, args?.firstOrNull() ?: error("tun options missing"))
                "readWIFIState" -> readWifiState()
                "sendNotification" -> {
                    logNotification(args?.firstOrNull())
                    Unit
                }
                "startDefaultInterfaceMonitor" -> {
                    defaultNetworkMonitor.setListener(args?.firstOrNull())
                    Unit
                }
                "systemCertificates" -> systemCertificates()
                "underNetworkExtension" -> false
                "usePlatformAutoDetectInterfaceControl" -> true
                "useProcFS" -> Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                else -> defaultValue(method)
            }
        }

    fun newCommandServer(handler: Any, platform: Any): Any {
        val method = libboxClass!!.getMethod("newCommandServer", commandServerHandlerClass, platformInterfaceClass)
        return method.invoke(null, handler, platform) ?: error("libbox returned null command server")
    }

    fun startServer(commandServer: Any) {
        call(commandServer, "start")
    }

    fun closeServer(commandServer: Any) {
        call(commandServer, "close")
    }

    fun closeService(commandServer: Any) {
        call(commandServer, "closeService")
    }

    fun checkConfig(commandServer: Any, config: String) {
        call(commandServer, "checkConfig", config)
    }

    fun startOrReloadService(commandServer: Any, config: String) {
        val overrideOptions = overrideOptionsClass.getDeclaredConstructor().newInstance()
        call(overrideOptions, "setAutoRedirect", false)
        call(commandServer, "startOrReloadService", config, overrideOptions)
    }

    fun resetNetwork(commandServer: Any) {
        call(commandServer, "resetNetwork")
    }

    fun call(target: Any?, name: String, vararg args: Any?): Any? {
        require(target != null) { "reflection target is null for $name" }
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: error("missing method ${target.javaClass.name}#$name")
        return method.invoke(target, *args)
    }

    fun callBoolean(target: Any?, name: String): Boolean = call(target, name) as Boolean

    fun callInt(target: Any?, name: String): Int = call(target, name) as Int

    fun forEachString(
        iterator: Any?,
        block: (String) -> Unit,
    ) {
        if (iterator == null) {
            return
        }
        while (callBoolean(iterator, "hasNext")) {
            block(call(iterator, "next").toString())
        }
    }

    fun collectStrings(iterator: Any?): List<String> {
        val items = mutableListOf<String>()
        forEachString(iterator) { items += it }
        return items
    }

    fun forEachRoutePrefix(
        iterator: Any?,
        block: (ReflectedRoutePrefix) -> Unit,
    ) {
        if (iterator == null) {
            return
        }
        while (callBoolean(iterator, "hasNext")) {
            val prefix = call(iterator, "next") ?: continue
            block(
                ReflectedRoutePrefix(
                    address = call(prefix, "address").toString(),
                    prefix = callInt(prefix, "prefix"),
                    string = call(prefix, "string").toString(),
                ),
            )
        }
    }

    private fun getInterfaces(context: Context): Any {
        val connectivity = context.getSystemService<ConnectivityManager>() ?: return iteratorProxy(networkInterfaceIteratorClass, emptyList())
        val systemInterfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val interfaces =
            ConnectivityNetworkRegistry.snapshot(context).mapNotNull { network ->
                network
                    .takeIf { isNonVpnNetwork(connectivity, it) }
                    ?.let { networkInterface(it, connectivity, systemInterfaces) }
            }
        return iteratorProxy(networkInterfaceIteratorClass, interfaces)
    }

    private fun networkInterface(
        network: Network,
        connectivity: ConnectivityManager,
        systemInterfaces: List<NetworkInterface>,
    ): Any? {
        val linkProperties = connectivity.getLinkProperties(network) ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        val interfaceName = linkProperties.interfaceName ?: return null
        val systemInterface = systemInterfaces.firstOrNull { it.name == interfaceName } ?: return null
        val boxInterface = networkInterfaceClass.getDeclaredConstructor().newInstance()
        call(boxInterface, "setName", interfaceName)
        call(boxInterface, "setType", interfaceType(capabilities))
        call(boxInterface, "setIndex", systemInterface.index)
        runCatching { call(boxInterface, "setMTU", systemInterface.mtu) }
        call(boxInterface, "setAddresses", stringIterator(interfacePrefixes(systemInterface)))
        call(boxInterface, "setDNSServer", stringIterator(linkProperties.dnsServers.mapNotNull { it.hostAddress }))
        call(boxInterface, "setFlags", interfaceFlags(systemInterface, capabilities))
        call(
            boxInterface,
            "setMetered",
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
        return boxInterface
    }

    private fun readWifiState(): Any? {
        val wifi = appContext.getSystemService<WifiManager>() ?: return null
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo ?: return null
        var ssid = info.ssid.orEmpty()
        if (ssid == "<unknown ssid>") {
            ssid = ""
        }
        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        val ctor = wifiStateClass.getDeclaredConstructor(String::class.java, String::class.java)
        return ctor.newInstance(ssid, info.bssid.orEmpty())
    }

    private fun systemCertificates(): Any {
        val keyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        val values = mutableListOf<String>()
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val cert = keyStore.getCertificate(aliases.nextElement()) ?: continue
            val encoded = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
            values += "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----"
        }
        return stringIterator(values)
    }

    private fun localDnsTransport(defaultNetworkMonitor: DefaultNetworkMonitor): Any =
        Proxy.newProxyInstance(
            localDnsTransportClass.classLoader,
            arrayOf(localDnsTransportClass),
        ) { _, method, args ->
            when (method.name) {
                "exchange" -> {
                    diagnosticsLogger.record("dns", "local dns exchange callback invoked")
                    exchangeLocalDns(
                        defaultNetworkMonitor = defaultNetworkMonitor,
                        ctx = args?.getOrNull(0) ?: error("missing exchange context"),
                        message = args.getOrNull(1) as? ByteArray ?: error("missing dns packet"),
                    )
                    Unit
                }
                "lookup" -> {
                    diagnosticsLogger.record("dns", "local dns lookup callback invoked")
                    lookupLocalDns(
                        defaultNetworkMonitor = defaultNetworkMonitor,
                        ctx = args?.getOrNull(0) ?: error("missing exchange context"),
                        network = args.getOrNull(1)?.toString().orEmpty(),
                        domain = args.getOrNull(2)?.toString().orEmpty(),
                    )
                    Unit
                }
                "raw" -> {
                    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    diagnosticsLogger.record("dns", "local dns raw mode=$supported")
                    supported
                }
                else -> defaultValue(method)
            }
        }.also {
            diagnosticsLogger.record("dns", "local dns transport proxy created")
            if (BuildConfig.DEBUG) {
                Log.d(LOG_TAG, "localDNSTransport proxy created")
            }
        }

    private fun exchangeLocalDns(
        defaultNetworkMonitor: DefaultNetworkMonitor,
        ctx: Any,
        message: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            call(ctx, "errorCode", DNS_RCODE_SERVFAIL)
            return
        }
        if (BuildConfig.DEBUG) {
            Log.d("FoxholeDns", "exchange start bytes=${message.size}")
        }
        runBlocking {
            val defaultNetwork = defaultNetworkMonitor.requireNetwork()
            if (BuildConfig.DEBUG) {
                Log.d("FoxholeDns", "exchange using network=$defaultNetwork")
            }
            suspendCancellableCoroutine<Unit> { continuation ->
                val signal = CancellationSignal()
                registerCancellation(ctx, signal)
                val callback =
                    object : DnsResolver.Callback<ByteArray> {
                        override fun onAnswer(answer: ByteArray, rcode: Int) {
                            if (BuildConfig.DEBUG) {
                                Log.d("FoxholeDns", "exchange answer rcode=$rcode bytes=${answer.size}")
                            }
                            if (rcode == 0) {
                                call(ctx, "rawSuccess", answer)
                            } else {
                                call(ctx, "errorCode", rcode)
                            }
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            if (BuildConfig.DEBUG) {
                                Log.w("FoxholeDns", "exchange error", error)
                            }
                            reportDnsError(ctx, error)
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }
                    }
                DnsResolver.getInstance().rawQuery(
                    defaultNetwork,
                    message,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    callback,
                )
                continuation.invokeOnCancellation { signal.cancel() }
            }
        }
    }

    private fun lookupLocalDns(
        defaultNetworkMonitor: DefaultNetworkMonitor,
        ctx: Any,
        network: String,
        domain: String,
    ) {
        if (domain.isBlank()) {
            call(ctx, "errorCode", DNS_RCODE_SERVFAIL)
            return
        }
        if (BuildConfig.DEBUG) {
            Log.d("FoxholeDns", "lookup start network=$network domain=$domain")
        }
        runBlocking {
            val defaultNetwork = defaultNetworkMonitor.requireNetwork()
            if (BuildConfig.DEBUG) {
                Log.d("FoxholeDns", "lookup using network=$defaultNetwork domain=$domain")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val signal = CancellationSignal()
                    registerCancellation(ctx, signal)
                    val callback =
                        object : DnsResolver.Callback<Collection<InetAddress>> {
                            override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                                if (BuildConfig.DEBUG) {
                                    Log.d("FoxholeDns", "lookup answer domain=$domain rcode=$rcode count=${answer.size}")
                                }
                                if (rcode == 0) {
                                    call(
                                        ctx,
                                        "success",
                                        answer.mapNotNull { it.hostAddress }.joinToString("\n"),
                                    )
                                } else {
                                    call(ctx, "errorCode", rcode)
                                }
                                if (continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }

                            override fun onError(error: DnsResolver.DnsException) {
                                if (BuildConfig.DEBUG) {
                                    Log.w("FoxholeDns", "lookup error domain=$domain", error)
                                }
                                reportDnsError(ctx, error)
                                if (continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }
                        }
                    val type =
                        when {
                            network.endsWith("4") -> DnsResolver.TYPE_A
                            network.endsWith("6") -> DnsResolver.TYPE_AAAA
                            else -> null
                        }
                    if (type != null) {
                        DnsResolver.getInstance().query(
                            defaultNetwork,
                            domain,
                            type,
                            DnsResolver.FLAG_NO_RETRY,
                            Dispatchers.IO.asExecutor(),
                            signal,
                            callback,
                        )
                    } else {
                        DnsResolver.getInstance().query(
                            defaultNetwork,
                            domain,
                            DnsResolver.FLAG_NO_RETRY,
                            Dispatchers.IO.asExecutor(),
                            signal,
                            callback,
                        )
                    }
                    continuation.invokeOnCancellation { signal.cancel() }
                }
            } else {
                val answer =
                    try {
                        defaultNetwork.getAllByName(domain)
                    } catch (_: UnknownHostException) {
                        if (BuildConfig.DEBUG) {
                            Log.d("FoxholeDns", "lookup nxdomain domain=$domain")
                        }
                        call(ctx, "errorCode", DNS_RCODE_NXDOMAIN)
                        return@runBlocking
                    } catch (error: Exception) {
                        diagnosticsLogger.record("dns", "local lookup failed: ${error.javaClass.simpleName}")
                        if (BuildConfig.DEBUG) {
                            Log.w("FoxholeDns", "lookup exception domain=$domain", error)
                        }
                        call(ctx, "errorCode", DNS_RCODE_SERVFAIL)
                        return@runBlocking
                    }
                if (BuildConfig.DEBUG) {
                    Log.d("FoxholeDns", "lookup success domain=$domain count=${answer.size}")
                }
                call(ctx, "success", answer.mapNotNull { it.hostAddress }.joinToString("\n"))
            }
        }
    }

    private fun registerCancellation(
        ctx: Any,
        signal: CancellationSignal,
    ) {
        val callback =
            Proxy.newProxyInstance(
                funcClass.classLoader,
                arrayOf(funcClass),
            ) { _, method, _ ->
                when (method.name) {
                    "invoke" -> {
                        signal.cancel()
                        Unit
                    }
                    else -> defaultValue(method)
                }
            }
        call(ctx, "onCancel", callback)
    }

    private fun reportDnsError(
        ctx: Any,
        error: DnsResolver.DnsException,
    ) {
        when (val cause = error.cause) {
            is ErrnoException -> {
                call(ctx, "errnoCode", cause.errno)
                return
            }
        }
        diagnosticsLogger.record("dns", "local resolver error: ${error.javaClass.simpleName}")
        call(ctx, "errorCode", DNS_RCODE_SERVFAIL)
    }

    private fun stringIterator(values: List<String>): Any = iteratorProxy(stringIteratorClass, values)

    private fun iteratorProxy(
        iteratorClass: Class<*>,
        values: List<Any>,
    ): Any {
        val iterator = values.iterator()
        return Proxy.newProxyInstance(iteratorClass.classLoader, arrayOf(iteratorClass)) { _, method, _ ->
            when (method.name) {
                "hasNext" -> iterator.hasNext()
                "next" -> iterator.next()
                "len" -> values.size
                else -> defaultValue(method)
            }
        }
    }

    private fun connectionOwner(
        protocol: Int,
        sourceHost: String,
        sourcePort: Int,
        destinationHost: String,
        destinationPort: Int,
    ): Any {
        val owner = connectionOwnerClass.getDeclaredConstructor().newInstance()
        val resolved =
            resolveConnectionOwner(
                protocol = protocol,
                sourceHost = sourceHost,
                sourcePort = sourcePort,
                destinationHost = destinationHost,
                destinationPort = destinationPort,
            )
        call(owner, "setUserId", resolved?.uid ?: 0)
        call(owner, "setUserName", resolved?.userName.orEmpty())
        call(owner, "setProcessPath", "")
        call(owner, "setAndroidPackageNames", stringIterator(resolved?.packageNames.orEmpty()))
        logNetworkActivity(
            protocol = protocol,
            sourceHost = sourceHost,
            sourcePort = sourcePort,
            destinationHost = destinationHost,
            destinationPort = destinationPort,
            owner = resolved,
        )
        return owner
    }

    private fun resolveConnectionOwner(
        protocol: Int,
        sourceHost: String,
        sourcePort: Int,
        destinationHost: String,
        destinationPort: Int,
    ): ResolvedConnectionOwner? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }
        val sourceAddress = sourceHost.toInetSocketAddress(sourcePort) ?: return null
        val destinationAddress = destinationHost.toInetSocketAddress(destinationPort) ?: return null
        val uid =
            runCatching {
                connectivityManager.getConnectionOwnerUid(protocol, sourceAddress, destinationAddress)
            }.getOrElse {
                diagnosticsLogger.recordStructured(
                    "network",
                    "Connection owner lookup failed",
                    protocolLabel(protocol),
                    it.javaClass.simpleName,
                )
                return null
            }
        if (uid <= 0) {
            return null
        }
        val packageNames = packageManager.getPackagesForUid(uid).orEmpty().distinct()
        val userName = packageNames.firstNotNullOfOrNull(::labelForPackageName) ?: packageNames.firstOrNull().orEmpty()
        return ResolvedConnectionOwner(
            uid = uid,
            userName = userName,
            packageNames = packageNames,
        )
    }

    private fun logNetworkActivity(
        protocol: Int,
        sourceHost: String,
        sourcePort: Int,
        destinationHost: String,
        destinationPort: Int,
        owner: ResolvedConnectionOwner?,
    ) {
        if (!isNetworkActivityLoggingEnabled()) {
            return
        }
        val appLabel = owner?.userName?.takeIf(String::isNotBlank) ?: "Unknown app"
        val throttleKey =
            listOf(protocol, owner?.packageNames?.firstOrNull(), sourceHost, sourcePort, destinationHost, destinationPort)
                .joinToString("|")
        diagnosticsLogger.recordThrottled(
            tag = "activity",
            throttleKey = throttleKey,
            windowMs = NETWORK_ACTIVITY_THROTTLE_MS,
            message =
                buildString {
                    append("App connection")
                    append(": ")
                    append(
                        buildList {
                            add("app=$appLabel")
                            owner?.packageNames?.takeIf { it.isNotEmpty() }?.let { add("packages=${it.joinToString()}") }
                            owner?.let { add("uid=${it.uid}") }
                            add("protocol=${protocolLabel(protocol)}")
                            add("local=${sourceHost.ifBlank { "?" }}:$sourcePort")
                            add("remote=${destinationHost.ifBlank { "?" }}:$destinationPort")
                        }.joinToString(separator = " • "),
                    )
                },
        )
    }

    private fun labelForPackageName(packageName: String): String? =
        runCatching {
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager)?.toString()?.takeIf(String::isNotBlank)
        }.getOrNull()

    private fun String.toInetSocketAddress(port: Int): InetSocketAddress? {
        if (isBlank() || port !in 1..65535) {
            return null
        }
        return runCatching { InetSocketAddress(InetAddress.getByName(this), port) }.getOrNull()
    }

    private fun protocolLabel(protocol: Int): String =
        when (protocol) {
            OsConstants.IPPROTO_TCP -> "TCP"
            OsConstants.IPPROTO_UDP -> "UDP"
            else -> "Proto $protocol"
        }

    private data class ResolvedConnectionOwner(
        val uid: Int,
        val userName: String,
        val packageNames: List<String>,
    )

    private fun systemProxyStatus(
        available: Boolean,
        enabled: Boolean,
    ): Any {
        val status = systemProxyStatusClass.getDeclaredConstructor().newInstance()
        call(status, "setAvailable", available)
        call(status, "setEnabled", enabled)
        return status
    }

    private fun interfaceType(capabilities: NetworkCapabilities): Int =
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> interfaceTypeWifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> interfaceTypeCellular
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> interfaceTypeEthernet
            else -> interfaceTypeOther
        }

    private fun interfaceFlags(
        systemInterface: NetworkInterface,
        capabilities: NetworkCapabilities,
    ): Int {
        var flags = 0
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
        }
        if (runCatching { systemInterface.isLoopback }.getOrDefault(false)) {
            flags = flags or OsConstants.IFF_LOOPBACK
        }
        if (runCatching { systemInterface.isPointToPoint }.getOrDefault(false)) {
            flags = flags or OsConstants.IFF_POINTOPOINT
        }
        if (runCatching { systemInterface.supportsMulticast() }.getOrDefault(false)) {
            flags = flags or OsConstants.IFF_MULTICAST
        }
        return flags
    }

    private fun interfacePrefixes(networkInterface: NetworkInterface): List<String> =
        networkInterface.interfaceAddresses.mapNotNull { it.toPrefix() }

    private fun InterfaceAddress.toPrefix(): String? {
        val inetAddress = address ?: return null
        return if (inetAddress is Inet6Address) {
            "${Inet6Address.getByAddress(inetAddress.address).hostAddress}/$networkPrefixLength"
        } else {
            "${inetAddress.hostAddress}/$networkPrefixLength"
        }
    }

    private fun logNotification(notification: Any?) {
        if (notification == null) {
            return
        }
        val title = runCatching { call(notification, "getTitle")?.toString().orEmpty() }.getOrDefault("")
        val body = runCatching { call(notification, "getBody")?.toString().orEmpty() }.getOrDefault("")
        if (title.isNotBlank() || body.isNotBlank()) {
            diagnosticsLogger.record("libbox-notification", listOf(title, body).filter { it.isNotBlank() }.joinToString(" | "))
        }
    }

    private fun callStatic(
        name: String,
        vararg args: Any?,
    ): Any? {
        val clazz = libboxClass ?: error("libbox is unavailable")
        val method =
            clazz.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: error("missing libbox method $name")
        return method.invoke(null, *args)
    }

    private fun classOrNull(name: String): Class<*>? = runCatching { Class.forName(name) }.getOrNull()

    private fun requireClass(name: String): Class<*> = Class.forName(name)

    private fun staticField(name: String): Any? = libboxClass?.getField(name)?.get(null)

    private fun defaultValue(method: Method): Any? =
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Void.TYPE -> Unit
            else -> null
        }

    private companion object {
        const val LOG_TAG = "FoxholeLibbox"
        const val DNS_RCODE_NXDOMAIN = 3
        const val DNS_RCODE_SERVFAIL = 2
        const val NETWORK_ACTIVITY_THROTTLE_MS = 2_000L
        const val LIBBOX_LOG_MAX_LINES = 4_000L
    }
}

internal data class ReflectedRoutePrefix(
    val address: String,
    val prefix: Int,
    val string: String,
)
