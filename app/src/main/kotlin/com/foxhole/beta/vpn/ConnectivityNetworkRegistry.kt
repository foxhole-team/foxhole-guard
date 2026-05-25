package com.foxhole.beta.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService

internal object ConnectivityNetworkRegistry {
    @Volatile
    private var tracker: Tracker? = null

    fun snapshot(context: Context): List<Network> = ensureTracker(context.applicationContext).snapshot()

    @Synchronized
    private fun ensureTracker(appContext: Context): Tracker =
        tracker ?: Tracker(appContext).also { tracker = it }

    private class Tracker(
        appContext: Context,
    ) {
        private val connectivity = appContext.getSystemService<ConnectivityManager>() ?: error("missing connectivity manager")
        private val mainHandler = Handler(Looper.getMainLooper())
        private val request =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build()
        private val lock = Any()
        private val trackedNetworks = LinkedHashSet<Network>()

        private val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    track(network)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        track(network)
                    } else {
                        untrack(network)
                    }
                }

                override fun onLost(network: Network) {
                    untrack(network)
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
        }

        private fun track(network: Network) {
            synchronized(lock) {
                trackedNetworks += network
            }
        }

        private fun untrack(network: Network) {
            synchronized(lock) {
                trackedNetworks -= network
            }
        }

        @Suppress("DEPRECATION")
        private fun ConnectivityManager.snapshotAllNetworks(): Array<Network> =
            // There is no public replacement for enumerating currently known networks.
            allNetworks
    }
}

internal fun ConnectivityManager.preferredNonVpnInternetNetwork(
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
        .maxByOrNull { network -> upstreamNetworkPreferenceScore(getNetworkCapabilities(network)) }
}

internal fun ConnectivityManager.isNonVpnInternetNetwork(network: Network): Boolean {
    val capabilities = getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

private fun upstreamNetworkPreferenceScore(capabilities: NetworkCapabilities?): Int {
    if (capabilities == null) {
        return Int.MIN_VALUE
    }
    return buildList {
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add(100)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(50)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(45)
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add(25)
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(10)
    }.sum()
}
