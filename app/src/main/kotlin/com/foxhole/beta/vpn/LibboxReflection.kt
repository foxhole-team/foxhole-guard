package com.foxhole.beta.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.system.OsConstants
import android.util.Base64
import android.util.Log
import androidx.core.content.getSystemService
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.model.NetworkActivityEvent
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.traffic.TorGeoIpCountryResolver
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.UnknownHostException
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("LargeClass", "TooManyFunctions")
internal class LibboxReflection(
    private val context: Context,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
    private val isNetworkActivityLoggingEnabled: () -> Boolean,
    private val networkActivityContext: () -> NetworkActivityContext = { NetworkActivityContext() },
    private val onNetworkActivityEvent: (NetworkActivityEvent) -> Unit = {},
) : LibboxRuntimeNative {
    private val setupDone = AtomicBoolean(false)
    private val appContext = context.applicationContext
    private val connectivityManager by lazy { appContext.getSystemService<ConnectivityManager>()!! }
    private val packageManager by lazy { appContext.packageManager }
    private val countryResolver by lazy { TorGeoIpCountryResolver(appContext) }

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
    private val interfaceUpdateListenerClass by lazy { requireClass("io.nekohasekai.libbox.InterfaceUpdateListener") }
    private val localDnsTransportClass by lazy { classOrNull("io.nekohasekai.libbox.LocalDNSTransport") }
    private val wifiStateClass by lazy { requireClass("io.nekohasekai.libbox.WIFIState") }
    private val routePrefixClass by lazy { requireClass("io.nekohasekai.libbox.RoutePrefix") }

    val interfaceTypeWifi: Int by lazy { staticField("InterfaceTypeWIFI") as Int }
    val interfaceTypeCellular: Int by lazy { staticField("InterfaceTypeCellular") as Int }
    val interfaceTypeEthernet: Int by lazy { staticField("InterfaceTypeEthernet") as Int }
    val interfaceTypeOther: Int by lazy { staticField("InterfaceTypeOther") as Int }

    override fun isAvailable(): Boolean = libboxClass != null

    override fun setupIfNeeded() {
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

    override fun commandServerHandlerProxy(
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

    @Suppress("CyclomaticComplexMethod")
    override fun platformProxy(
        host: RuntimeServiceHost,
        defaultNetworkMonitor: RuntimeDefaultNetworkMonitor,
        openTun: (RuntimeServiceHost, Any) -> Int,
    ): Any =
        Proxy.newProxyInstance(
            platformInterfaceClass.classLoader,
            arrayOf(platformInterfaceClass),
        ) { _, method, args ->
            recordPlatformCall(method, args)
            when (method.name) {
                "autoDetectInterfaceControl" -> {
                    val fd = platformFileDescriptor(args?.firstOrNull())
                    if (!host.protectSocket(fd)) {
                        diagnosticsLogger.record("libbox", "protect upstream socket failed")
                        error("android: protect upstream socket failed")
                    }
                    if (BuildConfig.DEBUG) {
                        diagnosticsLogger.recordThrottled(
                            tag = "libbox",
                            throttleKey = "auto_detect_interface_control",
                            windowMs = 5_000L,
                            message = "auto-detect protected upstream socket",
                        )
                    }
                    defaultNetworkMonitor.bindSocketToDefaultNetwork(fd)
                    Unit
                }
                "clearDNSCache" -> Unit
                "closeNeighborMonitor" -> Unit
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
                    localDnsTransport(defaultNetworkMonitor)
                }
                "openTun" -> openTun(host, args?.firstOrNull() ?: error("tun options missing"))
                "readWIFIState" -> readWifiState()
                "registerMyInterface" -> Unit
                "sendNotification" -> {
                    logNotification(args?.firstOrNull())
                    Unit
                }
                "startNeighborMonitor" -> Unit
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

    private fun recordPlatformCall(
        method: Method,
        args: Array<Any?>?,
    ) {
        if (!BuildConfig.DEBUG) {
            return
        }
        val argTypes =
            args
                ?.map { arg -> arg?.javaClass?.simpleName ?: "null" }
                ?.joinToString(",")
                ?.takeIf(String::isNotBlank)
                ?: "none"
        diagnosticsLogger.recordThrottled(
            tag = "libbox",
            throttleKey = "platform_call_${method.name}",
            windowMs = 5_000L,
            message = "platform call ${method.name} args=$argTypes",
        )
    }

    private fun platformFileDescriptor(raw: Any?): Int =
        when (raw) {
            is Int -> raw
            is Number -> raw.toInt()
            else -> error("android: invalid auto-detect fd argument")
        }

    override fun newCommandServer(handler: Any, platform: Any): Any {
        val method = libboxClass!!.getMethod("newCommandServer", commandServerHandlerClass, platformInterfaceClass)
        return method.invoke(null, handler, platform) ?: error("libbox returned null command server")
    }

    override fun startServer(commandServer: Any) {
        call(commandServer, "start")
    }

    override fun closeServer(commandServer: Any) {
        call(commandServer, "close")
    }

    override fun closeService(commandServer: Any) {
        call(commandServer, "closeService")
    }

    override fun checkConfig(commandServer: Any, config: String) {
        call(commandServer, "checkConfig", config)
    }

    override fun startOrReloadService(commandServer: Any, config: String) {
        val overrideOptions = overrideOptionsClass.getDeclaredConstructor().newInstance()
        call(overrideOptions, "setAutoRedirect", false)
        call(commandServer, "startOrReloadService", config, overrideOptions)
    }

    override fun resetNetwork(commandServer: Any) {
        call(commandServer, "resetNetwork")
    }

    private fun localDnsTransport(defaultNetworkMonitor: RuntimeDefaultNetworkMonitor): Any? {
        val clazz = localDnsTransportClass ?: return null
        return Proxy.newProxyInstance(
            clazz.classLoader,
            arrayOf(clazz),
        ) { _, method, args ->
            when (method.name) {
                "raw" -> false
                "lookup" -> {
                    val ctx = args?.getOrNull(0) ?: return@newProxyInstance Unit
                    val networkHint = args.getOrNull(1)?.toString().orEmpty()
                    val domain = args.getOrNull(2)?.toString().orEmpty()
                    resolveLocalDns(ctx, defaultNetworkMonitor, networkHint, domain)
                    Unit
                }
                "exchange" -> {
                    args?.getOrNull(0)?.let { ctx -> call(ctx, "errorCode", DNS_RCODE_NOT_IMPLEMENTED) }
                    Unit
                }
                else -> defaultValue(method)
            }
        }
    }

    private fun resolveLocalDns(
        ctx: Any,
        defaultNetworkMonitor: RuntimeDefaultNetworkMonitor,
        networkHint: String,
        domain: String,
    ) {
        runCatching {
            val addresses =
                defaultNetworkMonitor.requireNetwork()
                    .getAllByName(domain)
                    .toList()
                    .filterForDnsNetworkHint(networkHint)
            if (addresses.isEmpty()) {
                throw UnknownHostException(domain)
            }
            call(ctx, "success", addresses.mapNotNull(InetAddress::getHostAddress).joinToString("\n"))
        }.onFailure { error ->
            val rcode = if (error is UnknownHostException) DNS_RCODE_NXDOMAIN else DNS_RCODE_SERVFAIL
            call(ctx, "errorCode", rcode)
        }
    }

    private fun List<InetAddress>.filterForDnsNetworkHint(networkHint: String): List<InetAddress> =
        when {
            networkHint.endsWith("4") -> filterIsInstance<Inet4Address>()
            networkHint.endsWith("6") -> filterIsInstance<Inet6Address>()
            else -> this
        }

    override fun call(target: Any?, name: String, vararg args: Any?): Any? {
        require(target != null) { "reflection target is null for $name" }
        val method =
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: error("missing method ${target.javaClass.name}#$name")
        return method.invoke(target, *args)
    }

    override fun callBoolean(target: Any?, name: String): Boolean = call(target, name) as Boolean

    override fun callInt(target: Any?, name: String): Int = call(target, name) as Int

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

    override fun collectStrings(iterator: Any?): List<String> {
        val items = mutableListOf<String>()
        forEachString(iterator) { items += it }
        return items
    }

    @Suppress("ReturnCount")
    override fun collectStringBoxOrIterator(value: Any?): List<String> {
        if (value == null) {
            return emptyList()
        }
        val methodNames = value.javaClass.methods.map { it.name }.toSet()
        if ("hasNext" in methodNames && "next" in methodNames) {
            return collectStrings(value)
        }
        return listOfNotNull(
            runCatching { call(value, "getValue")?.toString() }
                .getOrNull()
                ?: value.toString(),
        ).filter(String::isNotBlank)
    }

    override fun forEachRoutePrefix(
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
        val info =
            runCatching { wifi.connectionInfo }
                .getOrNull()
                ?: return null
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
        call(owner, "setUserId", resolved?.uid ?: UNKNOWN_CONNECTION_OWNER_UID)
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
        if (shouldSkipRuntimeProxyOwnerLookup(sourceHost, destinationHost, destinationPort)) {
            diagnosticsLogger.recordThrottled(
                tag = "network",
                throttleKey = "runtime_proxy_owner_lookup_skip",
                windowMs = 5_000L,
                message = "runtime proxy owner lookup skipped",
            )
            return null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return resolveConnectionOwnerFromProcNet(
                protocol = protocol,
                sourceHost = sourceHost,
                sourcePort = sourcePort,
                destinationHost = destinationHost,
                destinationPort = destinationPort,
            )
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

    private fun shouldSkipRuntimeProxyOwnerLookup(
        sourceHost: String,
        destinationHost: String,
        destinationPort: Int,
    ): Boolean {
        val context = networkActivityContext()
        val sourceAddress = sourceHost.toInetAddressOrNull()
        val destinationAddress = destinationHost.toInetAddressOrNull()
        return context.trafficMode == TrafficMode.TUNNEL &&
            context.runtimeProxyPort == destinationPort &&
            sourceAddress?.isLoopbackAddress == true &&
            destinationAddress?.isLoopbackAddress == true
    }

    private fun resolveConnectionOwnerFromProcNet(
        protocol: Int,
        sourceHost: String,
        sourcePort: Int,
        destinationHost: String,
        destinationPort: Int,
    ): ResolvedConnectionOwner? {
        val sourceAddress = sourceHost.toInetAddressOrNull() ?: return null
        val destinationAddress = destinationHost.toInetAddressOrNull() ?: return null
        val uid =
            procNetFiles(protocol).firstNotNullOfOrNull { file ->
                findProcNetUid(
                    file = file,
                    sourceAddress = sourceAddress,
                    sourcePort = sourcePort,
                    destinationAddress = destinationAddress,
                    destinationPort = destinationPort,
                )
            } ?: return null
        val packageNames = packageManager.getPackagesForUid(uid).orEmpty().distinct()
        val userName = packageNames.firstNotNullOfOrNull(::labelForPackageName) ?: packageNames.firstOrNull().orEmpty()
        return ResolvedConnectionOwner(
            uid = uid,
            userName = userName,
            packageNames = packageNames,
        )
    }

    private fun procNetFiles(protocol: Int): List<File> =
        when (protocol) {
            OsConstants.IPPROTO_UDP -> listOf(File("/proc/net/udp"), File("/proc/net/udp6"))
            else -> listOf(File("/proc/net/tcp"), File("/proc/net/tcp6"))
        }

    private fun findProcNetUid(
        file: File,
        sourceAddress: InetAddress,
        sourcePort: Int,
        destinationAddress: InetAddress,
        destinationPort: Int,
    ): Int? =
        runCatching {
            if (!file.isFile || !file.canRead()) {
                return null
            }
            file.useLines { lines ->
                lines.drop(1).firstNotNullOfOrNull { line ->
                    val columns = line.trim().split(Regex("\\s+"))
                    val local = columns.getOrNull(1)?.let(::parseProcNetEndpoint) ?: return@firstNotNullOfOrNull null
                    val remote = columns.getOrNull(2)?.let(::parseProcNetEndpoint) ?: return@firstNotNullOfOrNull null
                    val uid = columns.getOrNull(7)?.toIntOrNull() ?: return@firstNotNullOfOrNull null
                    if (local.matches(sourceAddress, sourcePort) && remote.matches(destinationAddress, destinationPort)) {
                        uid
                    } else {
                        null
                    }
                }
            }
        }.getOrNull()

    private fun parseProcNetEndpoint(value: String): ProcNetEndpoint? {
        val addressHex = value.substringBefore(':').takeIf { it.isNotBlank() } ?: return null
        val port = value.substringAfter(':', "").toIntOrNull(radix = 16) ?: return null
        val addresses =
            when (addressHex.length) {
                8 -> procNetIpv4Candidates(addressHex)
                32 -> procNetIpv6Candidates(addressHex)
                else -> emptyList()
            }
        return ProcNetEndpoint(addresses = addresses, port = port)
    }

    private fun procNetIpv4Candidates(hex: String): List<InetAddress> {
        val bytes = hexToBytes(hex).takeIf { it.size == 4 } ?: return emptyList()
        return listOf(bytes.reversedArray(), bytes)
            .distinctBy { it.contentToString() }
            .mapNotNull { runCatching { InetAddress.getByAddress(it) }.getOrNull() }
    }

    private fun procNetIpv6Candidates(hex: String): List<InetAddress> {
        val bytes = hexToBytes(hex).takeIf { it.size == 16 } ?: return emptyList()
        val wordReversed =
            bytes.toMutableList()
                .chunked(4)
                .flatMap { word -> word.reversed() }
                .toByteArray()
        return listOf(bytes, wordReversed)
            .distinctBy { it.contentToString() }
            .mapNotNull { runCatching { InetAddress.getByAddress(it) }.getOrNull() }
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2)
            .mapNotNull { chunk -> chunk.toIntOrNull(radix = 16)?.toByte() }
            .toByteArray()

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
        val packageNames = owner?.packageNames.orEmpty().distinct().sorted()
        val countryCode =
            destinationHost
                .takeIf(String::isNotBlank)
                ?.let(countryResolver::countryCodeForDestination)
        if (packageNames.isNotEmpty() && destinationHost.isNotBlank()) {
            val context = networkActivityContext()
            onNetworkActivityEvent(
                NetworkActivityEvent(
                    timestampMs = System.currentTimeMillis(),
                    packageNames = packageNames,
                    protocol = protocolLabel(protocol),
                    remoteHost = destinationHost,
                    remotePort = destinationPort.takeIf { port -> port in 1..65535 },
                    countryCode = countryCode,
                    bytesRx = 0L,
                    bytesTx = 0L,
                    profileId = context.profileId,
                    sessionId = context.sessionId,
                ),
            )
        }
        val throttleKey =
            listOf(protocol, packageNames.joinToString(","), destinationHost, destinationPort)
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
                            packageNames.takeIf { it.isNotEmpty() }?.let { add("packages=${it.joinToString()}") }
                            owner?.let { add("uid=${it.uid}") }
                            add("protocol=${protocolLabel(protocol)}")
                            add("local=${sourceHost.ifBlank { "?" }}:$sourcePort")
                            add("remote=${destinationHost.ifBlank { "?" }}:$destinationPort")
                            countryCode?.let { add("country=$it") }
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

    private fun String.toInetAddressOrNull(): InetAddress? =
        takeIf { it.isNotBlank() }?.let { value -> runCatching { InetAddress.getByName(value) }.getOrNull() }

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

    private data class ProcNetEndpoint(
        val addresses: List<InetAddress>,
        val port: Int,
    ) {
        fun matches(
            address: InetAddress,
            port: Int,
        ): Boolean =
            this.port == port && addresses.any { candidate -> candidate == address }
    }

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
        const val NETWORK_ACTIVITY_THROTTLE_MS = 10_000L
        const val LIBBOX_LOG_MAX_LINES = 4_000L
        const val DNS_RCODE_SERVFAIL = 2
        const val DNS_RCODE_NXDOMAIN = 3
        const val DNS_RCODE_NOT_IMPLEMENTED = 4
        const val UNKNOWN_CONNECTION_OWNER_UID = -1
    }
}

internal data class ReflectedRoutePrefix(
    val address: String,
    val prefix: Int,
    val string: String,
)
