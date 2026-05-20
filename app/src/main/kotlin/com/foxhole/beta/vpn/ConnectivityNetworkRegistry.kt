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
