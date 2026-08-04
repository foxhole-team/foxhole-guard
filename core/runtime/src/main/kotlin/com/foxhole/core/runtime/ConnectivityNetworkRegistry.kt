package com.foxhole.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

object ConnectivityNetworkRegistry {
    @Volatile
    private var tracker: Tracker? = null

    fun snapshot(context: Context): List<Network> = ensureTracker(context.applicationContext).snapshot()

    /**
     * Bumps whenever the set of known networks changes — including the VPN networks the plain
     * [snapshot] can only be polled for. Flows that decide something from a snapshot (say "is a
     * foxhole VPN network up?") must fold this in, or they only re-read on whatever unrelated
     * signal happens to emit next.
     */
    fun revision(context: Context): StateFlow<Long> = ensureTracker(context.applicationContext).revision

    @Synchronized
    private fun ensureTracker(appContext: Context): Tracker =
        tracker ?: Tracker(appContext).also { tracker = it }

    private class Tracker(
        appContext: Context,
    ) {
        private val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: error("missing connectivity manager")
        private val mainHandler = Handler(Looper.getMainLooper())
        private val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()

        // NetworkRequest.Builder() implies NET_CAPABILITY_NOT_VPN, so the request above never
        // matches our own tunnel. Watching it explicitly is what turns "a VPN network appeared"
        // into an event instead of something callers can only discover by polling.
        private val vpnRequest =
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
        private val lock = Any()
        private val trackedNetworks = LinkedHashSet<Network>()
        private val revisionMutable = MutableStateFlow(0L)
        val revision: StateFlow<Long> = revisionMutable

        private val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    track(network)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    recordValidationSighting(network.networkHandle, networkCapabilities)
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        track(network)
                    } else {
                        untrack(network)
                    }
                }

                override fun onLost(network: Network) {
                    forgetValidationSighting(network.networkHandle)
                    untrack(network)
                }
            }

        // VPN networks are never added to trackedNetworks (the upstream selection filters them out
        // anyway and snapshot() enumerates them): this callback exists purely to publish the edge.
        private val vpnCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    bumpRevision()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    bumpRevision()
                }

                override fun onLost(network: Network) {
                    bumpRevision()
                }
            }

        init {
            connectivity.activeNetwork?.let(::track)
            register()
        }

        fun snapshot(): List<Network> =
            synchronized(lock) {
                linkedSetOf<Network>().apply {
                    connectivity.activeNetwork?.let(::add)
                    connectivity.snapshotAllNetworks().forEach(::add)
                    addAll(trackedNetworks)
                }.toList()
            }

        private fun register() {
            runCatching {
                connectivity.registerNetworkCallback(request, callback, mainHandler)
            }.recoverCatching {
                connectivity.registerNetworkCallback(request, callback)
            }
            runCatching {
                connectivity.registerNetworkCallback(vpnRequest, vpnCallback, mainHandler)
            }.recoverCatching {
                connectivity.registerNetworkCallback(vpnRequest, vpnCallback)
            }
        }

        private fun track(network: Network) {
            val changed =
                synchronized(lock) {
                    trackedNetworks.add(network)
                }
            if (changed) {
                bumpRevision()
            }
        }

        private fun untrack(network: Network) {
            val changed =
                synchronized(lock) {
                    trackedNetworks.remove(network)
                }
            if (changed) {
                bumpRevision()
            }
        }

        private fun bumpRevision() {
            revisionMutable.update { current -> current + 1 }
        }

        @Suppress("DEPRECATION")
        private fun ConnectivityManager.snapshotAllNetworks(): Array<Network> =
            // There is no public replacement for enumerating currently known networks.
            allNetworks
    }
}

fun ConnectivityManager.preferredNonVpnInternetNetwork(
    candidates: Iterable<Network>,
    excludedHandle: Long? = null,
): Network? {
    activeNetwork
        ?.takeUnless { network -> network.networkHandle == excludedHandle }
        ?.takeIf(::isNonVpnInternetNetwork)
        ?.let { return it }
    return candidates
        .asSequence()
        .distinctBy { network -> network.networkHandle }
        .filter { network -> network.networkHandle != excludedHandle && isNonVpnInternetNetwork(network) }
        .maxByOrNull { network ->
            upstreamNetworkPreferenceScore(network.networkHandle, getNetworkCapabilities(network))
        }
}

fun ConnectivityManager.isNonVpnInternetNetwork(network: Network): Boolean {
    val capabilities = getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

// An AP hop / DHCP renew / captive re-check strips VALIDATED from Wi-Fi for a moment; without a
// grace window the score flips the upstream to cellular (and the device identity fetch follows),
// then flips back — the classic "Wi-Fi shows cellular geo" report. A network keeps its validation
// credit for this long after the flag last seen; a genuinely captive network loses it for real.
private const val VALIDATION_GRACE_MS = 12_000L
private const val VALIDATION_SIGHTING_MAX_ENTRIES = 32
private val validationSightingsByHandle = ConcurrentHashMap<Long, Long>()

internal fun recordValidationSighting(
    handle: Long,
    capabilities: NetworkCapabilities?,
) {
    if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true) {
        return
    }
    val now = SystemClock.elapsedRealtime()
    validationSightingsByHandle[handle] = now
    if (validationSightingsByHandle.size > VALIDATION_SIGHTING_MAX_ENTRIES) {
        validationSightingsByHandle.entries.removeAll { entry -> now - entry.value > VALIDATION_GRACE_MS }
    }
}

internal fun forgetValidationSighting(handle: Long) {
    validationSightingsByHandle.remove(handle)
}

private fun validatedWithGrace(
    handle: Long,
    capabilities: NetworkCapabilities,
): Boolean {
    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
        recordValidationSighting(handle, capabilities)
        return true
    }
    val lastSeenAt = validationSightingsByHandle[handle] ?: return false
    return SystemClock.elapsedRealtime() - lastSeenAt <= VALIDATION_GRACE_MS
}

private fun upstreamNetworkPreferenceScore(
    handle: Long,
    capabilities: NetworkCapabilities?,
): Int {
    if (capabilities == null) {
        return Int.MIN_VALUE
    }
    return buildList {
        if (validatedWithGrace(handle, capabilities)) add(100)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(50)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(45)
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add(25)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(10)
    }.sum()
}
