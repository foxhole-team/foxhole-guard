package com.foxhole.guard.router

import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Suppress("DEPRECATION")
internal suspend fun discoverRouterNsdEndpoints(manager: NsdManager, network: Network,
    requireReachable: (RouterEndpoint) -> Unit): List<RouterEndpoint> {
    val found = CopyOnWriteArrayList<RouterEndpoint>()
    val completed = withTimeoutOrNull(1500) {
        suspendCancellableCoroutine<List<RouterEndpoint>> { continuation ->
            val finished = AtomicBoolean(false)
            val closed = AtomicBoolean(false)
            val discovery = AtomicReference<NsdManager.DiscoveryListener?>()
            val resolutions = CopyOnWriteArrayList<NsdManager.ResolveListener>()
            val services = ConcurrentHashMap.newKeySet<String>()
            fun close() {
                if (!closed.compareAndSet(false, true)) return
                discovery.get()?.let { listener ->
                    try { manager.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) { } catch (_: SecurityException) { }
                }
                if (Build.VERSION.SDK_INT >= 34) resolutions.forEach { listener ->
                    try { manager.stopServiceResolution(listener) } catch (_: IllegalArgumentException) { } catch (_: SecurityException) { }
                }
                resolutions.clear()
            }
            fun finish(endpoints: List<RouterEndpoint>, failure: Exception? = null) {
                if (!finished.compareAndSet(false, true)) return
                close()
                if (continuation.isActive) {
                    if (failure == null) continuation.resume(endpoints) else continuation.resumeWithException(failure)
                }
            }
            continuation.invokeOnCancellation { finished.set(true); close() }
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    val failure = if (Build.VERSION.SDK_INT >= 37 && errorCode == NsdManager.FAILURE_PERMISSION_DENIED)
                        RouterLocalNetworkPermissionRequired() else null
                    finish(emptyList(), failure)
                }
                override fun onServiceLost(service: NsdServiceInfo) = Unit
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (finished.get() || service.serviceType.trimEnd('.') != "_foxhole._tcp" ||
                        service.serviceName.length !in 1..128) return
                    val accepted = synchronized(services) { services.size < 4 && services.add(service.serviceName) }
                    if (!accepted) return
                    if (Build.VERSION.SDK_INT >= 33 && service.network != null && service.network != network) return
                    val resolver = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { resolutions.remove(this) }
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            resolutions.remove(this)
                            if (finished.get() || Build.VERSION.SDK_INT >= 33 && info.network != null && info.network != network) return
                            val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.filterIsInstance<Inet4Address>()
                                else listOfNotNull(info.host as? Inet4Address)
                            for (host in addresses.take(4)) {
                                val address = host.hostAddress ?: continue
                                val endpoint = try { RouterEndpoint(address, info.port) } catch (_: IllegalArgumentException) { continue }
                                try {
                                    requireReachable(endpoint)
                                    synchronized(found) { if (found.size < 4 && !found.contains(endpoint)) found.add(endpoint) }
                                    if (found.size >= 4) finish(found.toList())
                                } catch (failure: RouterLocalNetworkPermissionRequired) { finish(emptyList(), failure) }
                                catch (_: RouterWifiRequired) { }
                                catch (_: SecurityException) { finish(emptyList(), RouterLocalNetworkPermissionRequired()) }
                            }
                        }
                    }
                    resolutions += resolver
                    try { manager.resolveService(service, resolver) }
                    catch (_: IllegalArgumentException) { resolutions.remove(resolver) }
                    catch (_: SecurityException) { finish(emptyList(), RouterLocalNetworkPermissionRequired()) }
                }
            }
            discovery.set(listener)
            if (finished.get()) return@suspendCancellableCoroutine
            try {
                if (Build.VERSION.SDK_INT >= 33) manager.discoverServices("_foxhole._tcp.", NsdManager.PROTOCOL_DNS_SD,
                    network, Executor { it.run() }, listener)
                else manager.discoverServices("_foxhole._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (_: SecurityException) { finish(emptyList(), RouterLocalNetworkPermissionRequired()) }
            catch (_: IllegalArgumentException) { finish(emptyList()) }
        }
    }
    return completed ?: found.toList()
}
